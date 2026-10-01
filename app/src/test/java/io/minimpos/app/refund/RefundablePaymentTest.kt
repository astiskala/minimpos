package io.minimpos.app.refund

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.RefundedLine
import io.minimpos.core.codec.RefundQrPayload
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset

/** Plain JUnit: the refund rules need no database or Android. */
class RefundablePaymentTest {
    private val now = Instant.parse("2026-09-30T14:58:11Z")
    private val sale =
        SaleEntity(
            id = "s1",
            createdAt = 1_790_679_835_000,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 909,
            taxMinor = 91,
            totalMinor = 1_000,
            status = SaleStatus.APPROVED,
            merchantReference = "MP-1",
            poiTransactionId = "BV0q001643892070000.PSP1",
            poiTimestamp = "2026-09-29T11:04:00.000Z",
        )
    private val lines =
        listOf(
            SaleLineEntity(2, "s1", 1, null, "Water", null, 100, 1, "Free", 0, 100, 0, 100),
            // Three coffees for 9.00: a third of a line does not come out in whole cents.
            SaleLineEntity(1, "s1", 0, 1, "Latte", null, 300, 3, "GST", 10_000, 818, 82, 900),
        )
    private val record = SaleWithLines(sale, lines)

    private fun refundable(record: SaleWithLines = this.record) = (RefundablePayment.check(record) as Refundability.Refundable).payment

    private fun reason(record: SaleWithLines) = (RefundablePayment.check(record) as Refundability.NotRefundable).reason

    @Test
    fun `approved sales with the terminal's transaction details can be refunded`() {
        val payment = refundable()
        assertThat(payment.transactionId).isEqualTo("BV0q001643892070000.PSP1")
        assertThat(payment.timestamp).isEqualTo("2026-09-29T11:04:00.000Z")
        assertThat(payment.createdAt).isEqualTo(Instant.ofEpochMilli(sale.createdAt))
        assertThat(payment.reference).isEqualTo("MP-1")
        assertThat(payment.itemsKnown).isTrue()
        assertThat(payment.remainingMinor).isEqualTo(1_000)
        // In cart order.
        assertThat(payment.lines.map { it.lineId }).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun `one rule decides what cannot be refunded`() {
        assertThat(reason(record.copy(sale = sale.copy(status = SaleStatus.DECLINED)))).isEqualTo(RefundInvalidReason.NOT_REFUNDABLE)
        assertThat(reason(record.copy(sale = sale.copy(poiTransactionId = null)))).isEqualTo(RefundInvalidReason.NOT_REFUNDABLE)
        assertThat(reason(record.copy(sale = sale.copy(poiTimestamp = null)))).isEqualTo(RefundInvalidReason.NOT_REFUNDABLE)
        // A reversal must send the time stamp back, so one the terminal client cannot read (or without a time zone) rules
        // the sale out, and it gets no refund QR code either.
        listOf("not a time", "2026-09-29T11:04:00").forEach { timestamp ->
            val odd = record.copy(sale = sale.copy(poiTimestamp = timestamp))
            assertThat(reason(odd)).isEqualTo(RefundInvalidReason.NOT_REFUNDABLE)
            assertThat(RefundablePayment.qrCode(odd)).isNull()
        }
        assertThat(RefundablePayment.check(record.copy(sale = sale.copy(poiTimestamp = "2026-09-29T13:04:00+02:00"))))
            .isInstanceOf(Refundability.Refundable::class.java)
        assertThat(reason(record.copy(sale = sale.copy(refundedMinor = 1_000)))).isEqualTo(RefundInvalidReason.FULLY_REFUNDED)
    }

    @Test
    fun `a scanned code is linked to the local sale, else describes a payment from another terminal`() {
        val qr = RefundQrPayload("TENDER.PSPX", Instant.parse("2026-01-01T00:00:00Z"), 1_000, "AUD", "MP-9")
        assertThat((RefundablePayment.find(record, qr) as Refundability.Refundable).payment.local).isEqualTo(record)

        val foreign = (RefundablePayment.find(null, qr) as Refundability.Refundable).payment
        assertThat(foreign.itemsKnown).isFalse()
        assertThat(foreign.timestamp).isEqualTo("2026-01-01T00:00:00.000Z")
        assertThat(foreign.reference).isEqualTo("MP-9")
        assertThat(foreign.lines).isEmpty()
        assertThat(foreign.refundedMinor).isEqualTo(0)

        val nothing = RefundQrPayload("T.P", Instant.EPOCH, 0, "AUD")
        assertThat(
            (RefundablePayment.find(null, nothing) as Refundability.NotRefundable).reason,
        ).isEqualTo(RefundInvalidReason.FULLY_REFUNDED)
        assertThat((RefundablePayment.find(null, null) as Refundability.NotRefundable).reason).isEqualTo(RefundInvalidReason.NOT_A_RECEIPT)
    }

    @Test
    fun `choices are priced and capped at what is left`() {
        val payment = refundable(record.copy(sale = sale.copy(refundedMinor = 300), lines = lines.map { it.copy(refundedQuantity = 1) }))
        assertThat(payment.remainingMinor).isEqualTo(700)
        assertThat(payment.amountFor(RefundChoice.Everything)).isEqualTo(700)
        // The second of three coffees: 600 - 300.
        assertThat(payment.amountFor(RefundChoice.Items(mapOf(1L to 1)))).isEqualTo(300)
        assertThat(payment.isValid(RefundChoice.Items(emptyMap()))).isFalse()
        assertThat(payment.isValid(RefundChoice.Amount(700))).isTrue()
        assertThat(payment.isValid(RefundChoice.Amount(701))).isFalse()
        assertThat(payment.isValid(RefundChoice.Amount(0))).isFalse()
        assertThat(payment.cappedQuantity(1, 5)).isEqualTo(2)
        assertThat(payment.cappedQuantity(1, -1)).isEqualTo(0)
        assertThat(payment.cappedQuantity(99, 1)).isNull()
    }

    @Test
    fun `requests are full reversals only when nothing was refunded before`() {
        val full = refundable().request(RefundChoice.Everything, "", now, ZoneOffset.UTC)!!
        assertThat(full.full).isTrue()
        assertThat(full.amountMinor).isEqualTo(1_000)
        assertThat(full.saleId).isEqualTo("s1")
        assertThat(full.originalTransactionId).isEqualTo(sale.poiTransactionId)
        assertThat(full.originalTimestamp).isEqualTo(sale.poiTimestamp)
        assertThat(full.originalReference).isEqualTo("MP-1")
        assertThat(full.merchantReference).matches("R-260930-145811-[0-9A-Z]{4}")
        assertThat(full.lines).isEmpty()
        assertThat(full.params().amount).isNull()
        assertThat(full.params().currency).isNull()

        val rest =
            refundable(
                record.copy(sale = sale.copy(refundedMinor = 100)),
            ).request(RefundChoice.Everything, " MP ", now, ZoneOffset.UTC)!!
        assertThat(rest.full).isFalse()
        assertThat(rest.amountMinor).isEqualTo(900)
        assertThat(rest.merchantReference).startsWith("MP-R-")
        assertThat(rest.params().amount).isEqualTo(BigDecimal("9.00"))
        assertThat(rest.params().currency).isEqualTo("AUD")

        assertThat(refundable().request(RefundChoice.Amount(0), "", now, ZoneOffset.UTC)).isNull()
    }

    @Test
    fun `item refunds carry each line's apportioned share`() {
        val request = refundable().request(RefundChoice.Items(mapOf(1L to 1, 2L to 0, 99L to 1)), "", now, ZoneOffset.UTC)!!
        assertThat(request.full).isFalse()
        assertThat(request.amountMinor).isEqualTo(300)
        assertThat(request.lines).containsExactly(RefundedLine(1, "Latte", 1, 300, 300))

        // Refunding everything item by item returns exactly what was paid.
        val all = refundable().request(RefundChoice.Items(mapOf(1L to 3, 2L to 1)), "", now, ZoneOffset.UTC)!!
        assertThat(all.amountMinor).isEqualTo(1_000)
        assertThat(all.lines.map { it.lineId to it.grossMinor }).containsExactly(1L to 900L, 2L to 100L).inOrder()
    }

    @Test
    fun `refund QR codes carry the payment, also once it has been refunded`() {
        val code = RefundablePayment.qrCode(record)!!
        val decoded = RefundQrPayload.decode(code)!!
        assertThat(decoded.transactionId).isEqualTo(sale.poiTransactionId)
        assertThat(decoded.timestamp).isEqualTo(Instant.parse("2026-09-29T11:04:00Z"))
        assertThat(decoded.amountMinor).isEqualTo(1_000)
        assertThat(decoded.reference).isEqualTo("MP-1")
        assertThat(RefundablePayment.qrCode(record.copy(sale = sale.copy(refundedMinor = 1_000)))).isEqualTo(code)
        assertThat(RefundablePayment.qrCode(record.copy(sale = sale.copy(status = SaleStatus.UNKNOWN)))).isNull()
        // Transaction IDs the code cannot carry.
        assertThat(RefundablePayment.qrCode(record.copy(sale = sale.copy(poiTransactionId = "A B")))).isNull()
    }

    @Test
    fun `pre-authorisations are never refunded, only cancelled in full, once`() {
        val preAuth = record.copy(sale = sale.copy(kind = SaleKind.PRE_AUTHORISATION))
        assertThat(reason(preAuth)).isEqualTo(RefundInvalidReason.PRE_AUTHORISATION)
        val qr = RefundQrPayload("BV0q001643892070000.PSP1", Instant.parse("2026-09-29T11:04:00Z"), 1_000, "AUD", "MP-1")
        assertThat((RefundablePayment.find(preAuth, qr) as Refundability.NotRefundable).reason)
            .isEqualTo(RefundInvalidReason.PRE_AUTHORISATION)
        assertThat(RefundablePayment.qrCode(preAuth)).isNull()

        val cancel = RefundablePayment.cancellation(preAuth, " MP ", now, ZoneOffset.UTC)!!
        assertThat(cancel.cancellation).isTrue()
        assertThat(cancel.full).isTrue()
        assertThat(cancel.saleId).isEqualTo("s1")
        assertThat(cancel.amountMinor).isEqualTo(1_000)
        assertThat(cancel.originalTransactionId).isEqualTo(sale.poiTransactionId)
        assertThat(cancel.originalTimestamp).isEqualTo(sale.poiTimestamp)
        assertThat(cancel.merchantReference).matches("MP-C-260930-145811-[0-9A-Z]{4}")
        assertThat(cancel.params().amount).isNull()
        assertThat(RefundablePayment.canCancel(preAuth)).isTrue()

        // Sales are refunded instead; unapproved pre-authorisations, ones the terminal gave no transaction details for
        // and ones already cancelled cannot be cancelled.
        listOf(
            record,
            preAuth.copy(sale = preAuth.sale.copy(status = SaleStatus.DECLINED)),
            preAuth.copy(sale = preAuth.sale.copy(poiTransactionId = null)),
            preAuth.copy(sale = preAuth.sale.copy(refundedMinor = 1_000)),
        ).forEach {
            assertThat(RefundablePayment.cancellation(it, "", now, ZoneOffset.UTC)).isNull()
            assertThat(RefundablePayment.canCancel(it)).isFalse()
        }
    }

    @Test
    fun `refund requests must refund something`() {
        assertThrows(IllegalArgumentException::class.java) { RefundStart(null, "T", "t", null, "AUD", 0, false, "R-1") }
    }
}
