package io.minimpos.app.refund

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.settings.CaptureMode
import io.minimpos.app.refund.PaymentAction.ADJUST
import io.minimpos.app.refund.PaymentAction.CANCEL
import io.minimpos.app.refund.PaymentAction.CAPTURE
import io.minimpos.app.refund.PaymentAction.ENTER_TIP
import io.minimpos.app.refund.PaymentAction.REFUND
import io.minimpos.app.refund.PaymentAction.RETRY_CAPTURE
import org.junit.Test

/** Plain JUnit: where a stored sale stands, and so what can be done with it, follows from its fields alone. */
class PaymentStandingTest {
    private val sale =
        SaleEntity(
            id = "s1",
            createdAt = 1,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 909,
            taxMinor = 91,
            totalMinor = 1_000,
            status = SaleStatus.APPROVED,
            merchantReference = "MP-1",
            poiTransactionId = "BV0q001643892070000.PSP1",
            poiTimestamp = "2026-09-29T11:04:00.000Z",
            pspReference = "PSP1",
        )
    private val preAuth = sale.copy(kind = SaleKind.PRE_AUTHORISATION)
    private val tipSale = sale.copy(tipOnReceipt = true)

    private fun SaleEntity.actions(mode: CaptureMode = CaptureMode.API) = SaleWithLines(this, emptyList()).actions(mode)

    @Test
    fun `payments that were not approved stand nowhere else and allow nothing`() {
        SaleStatus.entries.filter { it != SaleStatus.APPROVED }.forEach { status ->
            listOf(sale, preAuth, tipSale.copy(holdCancelled = true, captureStatus = CaptureStatus.MANUAL)).forEach {
                val stored = it.copy(status = status)
                assertThat(stored.standing).isEqualTo(PaymentStanding.NOT_APPROVED)
                assertThat(stored.actions()).isEmpty()
                assertThat(stored.totalsShare).isEqualTo(TotalsShare.NONE)
            }
        }
    }

    @Test
    fun `a plain sale is charged at once and refunds do not change that`() {
        assertThat(sale.standing).isEqualTo(PaymentStanding.CHARGED)
        assertThat(sale.copy(refundedMinor = 1_000).standing).isEqualTo(PaymentStanding.CHARGED)
        assertThat(sale.standing.charged).isTrue()
        assertThat(sale.standing.held).isFalse()
        assertThat(sale.actions()).containsExactly(REFUND)
        assertThat(sale.copy(refundedMinor = 1_000).actions()).isEmpty()
        assertThat(sale.totalsShare).isEqualTo(TotalsShare.SALE)
    }

    @Test
    fun `payments taken with manual capture move from held through their capture, or are cancelled`() {
        val table =
            mapOf(
                preAuth to PaymentStanding.HELD,
                preAuth.copy(captureStatus = CaptureStatus.FAILED, capturedMinor = 800) to PaymentStanding.CAPTURE_FAILED,
                preAuth.copy(captureStatus = CaptureStatus.PENDING, capturedMinor = 800) to PaymentStanding.CAPTURE_SENDING,
                preAuth.copy(captureStatus = CaptureStatus.UNKNOWN, capturedMinor = 800) to PaymentStanding.CAPTURE_UNKNOWN,
                preAuth.copy(captureStatus = CaptureStatus.REQUESTED, capturedMinor = 800) to PaymentStanding.CAPTURE_REQUESTED,
                preAuth.copy(captureStatus = CaptureStatus.MANUAL, capturedMinor = 800) to PaymentStanding.CAPTURED_MANUALLY,
                preAuth.copy(holdCancelled = true) to PaymentStanding.HOLD_CANCELLED,
                // A cancellation accepted after a capture whose outcome was unknown still released the hold.
                preAuth.copy(holdCancelled = true, captureStatus = CaptureStatus.UNKNOWN) to PaymentStanding.HOLD_CANCELLED,
                // Refunded after the capture: a refund, not a cancellation.
                preAuth.copy(refundedMinor = 500, captureStatus = CaptureStatus.REQUESTED) to PaymentStanding.CAPTURE_REQUESTED,
                tipSale to PaymentStanding.AWAITING_TIP,
                tipSale.copy(tipMinor = 200, captureStatus = CaptureStatus.FAILED, capturedMinor = 1_200) to PaymentStanding.CAPTURE_FAILED,
                tipSale.copy(tipMinor = 200, captureStatus = CaptureStatus.MANUAL, capturedMinor = 1_200) to
                    PaymentStanding.CAPTURED_MANUALLY,
                tipSale.copy(holdCancelled = true) to PaymentStanding.HOLD_CANCELLED,
            )
        table.forEach { (stored, expected) -> assertThat(stored.standing).isEqualTo(expected) }
    }

    @Test
    fun `held payments can be cancelled, captured ones are charged`() {
        assertThat(PaymentStanding.entries.filter { it.held })
            .containsExactly(PaymentStanding.AWAITING_TIP, PaymentStanding.HELD, PaymentStanding.CAPTURE_FAILED)
        assertThat(PaymentStanding.entries.filter { it.captured })
            .containsExactly(PaymentStanding.CAPTURE_REQUESTED, PaymentStanding.CAPTURED_MANUALLY)
        assertThat(PaymentStanding.entries.filter { it.charged })
            .containsExactly(PaymentStanding.CHARGED, PaymentStanding.CAPTURE_REQUESTED, PaymentStanding.CAPTURED_MANUALLY)
    }

    @Test
    fun `a held pre-authorisation is captured or cancelled, and adjusted only through the Checkout API`() {
        assertThat(preAuth.actions(CaptureMode.API)).containsExactly(CANCEL, CAPTURE, ADJUST)
        assertThat(preAuth.actions(CaptureMode.CUSTOMER_AREA)).containsExactly(CANCEL, CAPTURE)
        // Without a PSP reference the Checkout API has nothing to refer to.
        assertThat(preAuth.copy(pspReference = null).actions()).containsExactly(CANCEL)
        // Without the terminal's transaction details a reversal has nothing to refer to.
        assertThat(preAuth.copy(poiTransactionId = null).actions()).containsExactly(CAPTURE, ADJUST)
        // A capture Adyen did not take leaves it held; it is captured again with a new amount, not retried.
        assertThat(preAuth.copy(captureStatus = CaptureStatus.FAILED, capturedMinor = 800).actions())
            .containsExactly(CANCEL, CAPTURE, ADJUST)
        assertThat(preAuth.copy(captureStatus = CaptureStatus.PENDING, capturedMinor = 800).actions()).isEmpty()
        assertThat(preAuth.copy(captureStatus = CaptureStatus.UNKNOWN, capturedMinor = 800).actions()).containsExactly(RETRY_CAPTURE)
        assertThat(preAuth.copy(captureStatus = CaptureStatus.MANUAL, capturedMinor = 800).actions()).containsExactly(REFUND)
        assertThat(preAuth.copy(holdCancelled = true).actions()).isEmpty()
    }

    @Test
    fun `a sale awaiting its tip takes the tip or is cancelled, and a failed tip capture is retried`() {
        assertThat(tipSale.actions()).containsExactly(ENTER_TIP, CANCEL)
        assertThat(tipSale.copy(pspReference = null).actions()).containsExactly(CANCEL)
        val failed = tipSale.copy(tipMinor = 200, captureStatus = CaptureStatus.FAILED, capturedMinor = 1_200)
        assertThat(failed.actions()).containsExactly(CANCEL, RETRY_CAPTURE)
        assertThat(failed.copy(captureStatus = CaptureStatus.UNKNOWN).actions()).containsExactly(RETRY_CAPTURE)
        assertThat(failed.copy(captureStatus = CaptureStatus.UNKNOWN, capturedMinor = null).actions()).isEmpty()
        assertThat(failed.copy(captureStatus = CaptureStatus.REQUESTED).actions()).containsExactly(REFUND)
    }

    @Test
    fun `day totals count pre-authorisations as held until they are captured, and cancelled holds not at all`() {
        assertThat(preAuth.totalsShare).isEqualTo(TotalsShare.HELD)
        assertThat(preAuth.copy(captureStatus = CaptureStatus.UNKNOWN).totalsShare).isEqualTo(TotalsShare.HELD)
        assertThat(preAuth.copy(captureStatus = CaptureStatus.MANUAL).totalsShare).isEqualTo(TotalsShare.SALE)
        assertThat(preAuth.copy(holdCancelled = true).totalsShare).isEqualTo(TotalsShare.NONE)
        assertThat(tipSale.totalsShare).isEqualTo(TotalsShare.SALE)
        assertThat(tipSale.copy(holdCancelled = true).totalsShare).isEqualTo(TotalsShare.NONE)
    }

    @Test
    fun `tips above a fifth of the bill need the authorisation raised first`() {
        assertThat(PaymentStanding.tipNeedsAdjustment(1_000, 200)).isFalse()
        assertThat(PaymentStanding.tipNeedsAdjustment(1_000, 201)).isTrue()
        assertThat(PaymentStanding.tipNeedsAdjustment(1_000, 0)).isFalse()
    }
}
