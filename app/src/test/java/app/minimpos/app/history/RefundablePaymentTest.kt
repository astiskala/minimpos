package app.minimpos.app.history

import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.TenderKind
import app.minimpos.app.data.repo.AllocatedLine
import app.minimpos.app.data.repo.RefundedLine
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.repo.TenderAllocationJson
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.payment.PaymentContext
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
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
    fun `a split sale proposes returns across every original tender and reserves unknown legs`() {
        val first =
            SaleTenderEntity(
                "s1-1",
                "s1",
                1,
                500,
                550,
                poiTransactionId = "FIRST.PSP1",
                poiTimestamp = sale.poiTimestamp,
                surchargeMinor = 50,
                tipMinor = 0,
            )
        val second = first.copy(id = "s1-2", sequence = 2, authorisedMinor = 600, poiTransactionId = "SECOND.PSP2", tipMinor = 50)
        val split =
            record.copy(
                sale = sale.copy(terminalPaidMinor = 1150, surchargeMinor = 100, tipMinor = 50),
                tenders = listOf(first, second),
            )
        val payment = refundable(split)
        assertThat(payment.amountMinor).isEqualTo(1150)
        assertThat(payment.legs.map { it.tender?.id }).containsExactly("s1-1", "s1-2").inOrder()
        assertThat(payment.itemsKnown).isTrue()
        assertThat(payment.isValid(RefundChoice.Amount(1151))).isFalse()
        // Never the whole return to the latest card: every original tender gets its own leg.
        val everything = payment.proposal(RefundChoice.Everything)
        assertThat(everything).containsExactly("s1-1", 550L, "s1-2", 600L)
        val requests = checkNotNull(payment.requests(RefundChoice.Everything, everything, "", now, ZoneOffset.UTC))
        assertThat(requests.map { it.originalTransactionId }).containsExactly("FIRST.PSP1", "SECOND.PSP2").inOrder()
        assertThat(requests.map { it.tenderId }).containsExactly("s1-1", "s1-2").inOrder()
        assertThat(requests.all { it.full }).isTrue()
        assertThat(requests.first().surchargeMinor).isEqualTo(50)
        assertThat(requests.last().tipMinor).isEqualTo(50)
        assertThat(payment.surchargeFor(everything)).isEqualTo(100)
        assertThat(payment.tipFor(everything)).isEqualTo(50)
    }

    @Test
    fun `partial and reserved split returns stay within each original tender`() {
        val first =
            SaleTenderEntity(
                "s1-1",
                "s1",
                1,
                500,
                550,
                poiTransactionId = "FIRST.PSP1",
                poiTimestamp = sale.poiTimestamp,
                surchargeMinor = 50,
                tipMinor = 0,
            )
        val second = first.copy(id = "s1-2", sequence = 2, authorisedMinor = 600, poiTransactionId = "SECOND.PSP2", tipMinor = 50)
        val split =
            record.copy(
                sale = sale.copy(terminalPaidMinor = 1150, surchargeMinor = 100, tipMinor = 50),
                tenders = listOf(first, second),
            )
        val payment = refundable(split)
        // Partial returns are shared in proportion to what each tender can still return.
        val partial = payment.proposal(RefundChoice.Amount(115))
        assertThat(partial).containsExactly("s1-1", 55L, "s1-2", 60L)
        assertThat(payment.allocationValid(RefundChoice.Amount(115), mapOf("s1-1" to 115L))).isTrue()
        assertThat(payment.allocationValid(RefundChoice.Amount(115), mapOf("s1-1" to 100L))).isFalse()
        assertThat(payment.allocationValid(RefundChoice.Amount(700), mapOf("s1-1" to 700L))).isFalse()
        assertThat(payment.allocationValid(RefundChoice.Amount(115), mapOf("other" to 115L))).isFalse()
        assertThat(payment.requests(RefundChoice.Amount(115), mapOf("s1-1" to 1L), "", now, ZoneOffset.UTC)).isNull()
        val pending =
            RefundEntity(
                "r",
                "s1",
                0,
                "R",
                "FIRST.PSP1",
                sale.poiTimestamp!!,
                originalReference = null,
                currency = "AUD",
                amountMinor = 550,
                full = true,
                status = RefundStatus.UNKNOWN,
                tenderId = first.id,
                surchargeMinor = 50,
                tipMinor = 0,
            )
        val reserved = refundable(split.copy(refunds = listOf(pending)))
        assertThat(reserved.remainingMinor).isEqualTo(600)
        assertThat(reserved.legs.first { it.tender?.id == first.id }.remainingMinor).isEqualTo(0)
        assertThat(reserved.proposal(RefundChoice.Everything)).containsExactly("s1-1", 0L, "s1-2", 600L)
        val rest = checkNotNull(reserved.requests(RefundChoice.Amount(510), mapOf("s1-2" to 510L), "", now, ZoneOffset.UTC)).single()
        assertThat(rest.full).isFalse()
        assertThat(rest.tipMinor).isEqualTo(10)
        assertThat(rest.surchargeMinor).isEqualTo(0)
        assertThat(rest.reviewedRefunds).containsExactly("r", RefundStatus.UNKNOWN)
        val cashBack =
            refundable(split.copy(sale = split.sale.copy(cashBackMinor = 100), tenders = listOf(first.copy(cashBackMinor = 100))))
        assertThat(cashBack.amountMinor).isEqualTo(1050)
        assertThat(cashBack.legs.single().amountMinor).isEqualTo(450)
        assertThat(cashBack.requests(RefundChoice.Everything, mapOf("s1-1" to 450L), "", now, ZoneOffset.UTC)!!.single().full).isFalse()
    }

    @Test
    fun `cash legs are handed back last locally and item returns go first to the tenders that paid for them`() {
        val items = TenderAllocationJson.encode(TenderAllocation.Items(listOf(AllocatedLine(1, 1, 100))))
        val card =
            SaleTenderEntity(
                "s1-1",
                "s1",
                1,
                900,
                900,
                poiTransactionId = "CARD.PSP",
                poiTimestamp = sale.poiTimestamp,
                surchargeMinor = 0,
                tipMinor = 0,
            )
        val cash =
            SaleTenderEntity(
                "s1-2",
                "s1",
                2,
                100,
                100,
                createdAt = 1_790_679_900_000,
                tipMinor = 0,
                surchargeMinor = 0,
                cashBackMinor = 0,
                kind = TenderKind.CASH,
                allocationJson = items,
                cashReceivedMinor = 200,
                changeMinor = 100,
            )
        val split =
            record.copy(
                sale = sale.copy(terminalPaidMinor = 1000, surchargeMinor = 0, paidMinor = 1000),
                tenders = listOf(card, cash),
            )
        val payment = refundable(split)
        val water = RefundChoice.Items(mapOf(2L to 1))
        assertThat(payment.amountFor(water)).isEqualTo(100)
        assertThat(payment.proposal(water)).containsExactly("s1-1", 0L, "s1-2", 100L)
        val leg = checkNotNull(payment.requests(water, payment.proposal(water), "", now, ZoneOffset.UTC)).single()
        assertThat(leg.cash).isTrue()
        assertThat(leg.originalTransactionId).isEqualTo("s1-2")
        assertThat(leg.originalTimestamp).isEqualTo(Instant.ofEpochMilli(1_790_679_900_000).toString())
        assertThat(leg.expectedContext).isNull()
        assertThat(leg.full).isFalse()
        assertThat(leg.lines.single().lineId).isEqualTo(2)
        // The refund QR code identifies the card, never cash.
        assertThat(RefundQrPayload.decode(RefundablePayment.qrCode(split)!!)!!.transactionId).isEqualTo("CARD.PSP")
        val cashOnly =
            record.copy(
                sale = sale.copy(terminalPaidMinor = 1000, surchargeMinor = 0),
                tenders = listOf(cash.copy(authorisedMinor = 1000)),
            )
        assertThat(RefundablePayment.qrCode(cashOnly)).isNull()
        assertThat(refundable(cashOnly).legs.single().cash).isTrue()
    }

    @Test
    fun `refund indexes are reused and a fresh record rebuilds remaining quantities`() {
        val payment = refundable()
        assertThat(record.sortedLines).isSameInstanceAs(record.sortedLines)
        assertThat(payment.lines).isSameInstanceAs(payment.lines)
        val updated = record.copy(lines = record.lines.map { it.copy(refundedQuantity = it.quantity) })
        val changed = refundable(updated)
        assertThat(changed.lines).isNotSameInstanceAs(payment.lines)
        assertThat(changed.cappedQuantity(1, 3)).isEqualTo(0)
        assertThat(payment.cappedQuantity(1, 3)).isEqualTo(3)
    }

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
        assertThat(full.params().amountMinor).isNull()
        assertThat(full.params().currency).isNull()

        val rest =
            refundable(
                record.copy(sale = sale.copy(refundedMinor = 100)),
            ).request(RefundChoice.Everything, " MP ", now, ZoneOffset.UTC)!!
        assertThat(rest.full).isFalse()
        assertThat(rest.amountMinor).isEqualTo(900)
        assertThat(rest.merchantReference).startsWith("MP-R-")
        assertThat(rest.params().amountMinor).isEqualTo(900)
        assertThat(rest.params().currency).isEqualTo("AUD")

        assertThat(refundable().request(RefundChoice.Amount(0), "", now, ZoneOffset.UTC)).isNull()
    }

    @Test
    fun `item refunds carry each line's apportioned share`() {
        val request = refundable().request(RefundChoice.Items(mapOf(1L to 1, 2L to 0, 99L to 1)), "", now, ZoneOffset.UTC)!!
        assertThat(request.full).isFalse()
        assertThat(request.amountMinor).isEqualTo(300)
        assertThat(request.lines).containsExactly(RefundedLine(1, "Latte", 1, 300, 300, 10_000))

        // Refunding everything item by item returns exactly what was paid.
        val all = refundable().request(RefundChoice.Items(mapOf(1L to 3, 2L to 1)), "", now, ZoneOffset.UTC)!!
        assertThat(all.amountMinor).isEqualTo(1_000)
        assertThat(all.lines.map { it.lineId to it.grossMinor }).containsExactly(1L to 900L, 2L to 100L).inOrder()
        assertThat(all.lines.map { it.taxRateMilliPercent }).containsExactly(10_000, 0).inOrder()
        val reduced = refundable(record.copy(lines = lines.map { it.copy(taxRateMilliPercent = 8_000) }))
        assertThat(
            reduced
                .request(RefundChoice.Items(mapOf(1L to 1)), "", now, ZoneOffset.UTC)!!
                .lines
                .single()
                .taxRateMilliPercent,
        ).isEqualTo(8_000)
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
    fun `both kinds of hold cancellation retain their original payment context`() {
        val context = PaymentContext("CLOUD", "S1F2-123", "POS1", "Merchant", "LIVE")
        listOf(
            sale.copy(kind = SaleKind.PRE_AUTHORISATION, context = context),
            sale.copy(tipOnReceipt = true, context = context),
        ).forEach { held ->
            val cancellation = RefundablePayment.cancellation(record.copy(sale = held), "", now, ZoneOffset.UTC)!!
            assertThat(cancellation.expectedContext).isEqualTo(context)
        }
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
        assertThat(cancel.params().amountMinor).isNull()

        // Sales are refunded instead; unapproved pre-authorisations, ones the terminal gave no transaction details for
        // and ones already cancelled cannot be cancelled.
        listOf(
            record,
            preAuth.copy(sale = preAuth.sale.copy(status = SaleStatus.DECLINED)),
            preAuth.copy(sale = preAuth.sale.copy(poiTransactionId = null)),
            preAuth.copy(sale = preAuth.sale.copy(holdCancelled = true)),
        ).forEach {
            assertThat(RefundablePayment.cancellation(it, "", now, ZoneOffset.UTC)).isNull()
        }
    }

    @Test
    fun `a sale awaiting its tip can only be cancelled, and once captured is refunded with the tip`() {
        val awaiting = record.copy(sale = sale.copy(tipOnReceipt = true, pspReference = "PSP1"))
        assertThat(reason(awaiting)).isEqualTo(RefundInvalidReason.AWAITING_TIP)
        val cancel = RefundablePayment.cancellation(awaiting, "", now, ZoneOffset.UTC)!!
        assertThat(cancel.amountMinor).isEqualTo(1_000)
        assertThat(cancel.merchantReference).startsWith("C-")
        assertThat(RefundablePayment.qrCode(awaiting)).isNull()

        // A capture Adyen did not take leaves it held; one whose outcome is unknown neither held nor refundable.
        val failed = awaiting.copy(sale = awaiting.sale.copy(tipMinor = 250, capturedMinor = 1_250, captureStatus = CaptureStatus.FAILED))
        assertThat(RefundablePayment.cancellation(failed, "", now, ZoneOffset.UTC)).isNotNull()
        assertThat(reason(failed)).isEqualTo(RefundInvalidReason.AWAITING_TIP)
        val unknown = failed.copy(sale = failed.sale.copy(captureStatus = CaptureStatus.UNKNOWN))
        assertThat(RefundablePayment.cancellation(unknown, "", now, ZoneOffset.UTC)).isNull()

        val captured = failed.copy(sale = failed.sale.copy(captureStatus = CaptureStatus.REQUESTED))
        assertThat(RefundablePayment.cancellation(captured, "", now, ZoneOffset.UTC)).isNull()
        val payment = refundable(captured)
        assertThat(payment.amountMinor).isEqualTo(1_250)
        assertThat(payment.itemsKnown).isTrue()
        assertThat(payment.request(RefundChoice.Everything, "", now, ZoneOffset.UTC)!!.amountMinor).isEqualTo(1_250)
        assertThat(RefundQrPayload.decode(RefundablePayment.qrCode(captured)!!)!!.amountMinor).isEqualTo(1_250)
    }

    @Test
    fun `a captured pre-authorisation is refunded up to the capture, by amount`() {
        val preAuth = record.copy(sale = sale.copy(kind = SaleKind.PRE_AUTHORISATION, pspReference = "PSP1"))
        val adjusted = preAuth.sale.copy(authorisedMinor = 1_500)
        assertThat(adjusted.heldMinor).isEqualTo(1_500)
        assertThat(adjusted.amountMinor).isEqualTo(1_500)
        assertThat(RefundablePayment.cancellation(preAuth.copy(sale = adjusted), "", now, ZoneOffset.UTC)!!.amountMinor).isEqualTo(1_500)

        val captured = preAuth.copy(sale = adjusted.copy(capturedMinor = 1_400, captureStatus = CaptureStatus.REQUESTED))
        assertThat(RefundablePayment.cancellation(captured, "", now, ZoneOffset.UTC)).isNull()
        val payment = refundable(captured)
        assertThat(payment.amountMinor).isEqualTo(1_400)
        assertThat(payment.itemsKnown).isFalse()
        // Its receipt's refund QR code carries what was captured, and scanning it finds the local sale.
        val qr = RefundQrPayload.decode(RefundablePayment.qrCode(captured)!!)!!
        assertThat(qr.amountMinor).isEqualTo(1_400)
        assertThat((RefundablePayment.find(captured, qr) as Refundability.Refundable).payment.local).isEqualTo(captured)
    }

    @Test
    fun `refund requests must refund something`() {
        assertThrows(IllegalArgumentException::class.java) { RefundStart(null, "T", "t", null, "AUD", 0, false, "R-1") }
    }
}
