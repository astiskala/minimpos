package io.minimpos.app.refund

import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus

/**
 * The rules for payments taken with manual capture, which only hold an amount on the card until they are captured:
 * pre-authorisations, and sales taken for tipping on the receipt. A held payment can be cancelled ([isHeld], see
 * [RefundablePayment.cancellation]); once captured ([SaleEntity.captured]) it is refunded like any sale. Pure: the
 * capture itself is `io.minimpos.app.payment.Captures`.
 */
object PaymentHold {
    private const val PERCENT = 100

    /**
     * Adyen's rule of thumb for tipping on the receipt: a tip above this percentage of the bill needs an authorisation
     * adjustment before the capture; a smaller one is simply captured with the bill (overcapture).
     */
    const val TIP_ADJUSTMENT_PERCENT = 20

    /**
     * Whether [sale] still only holds its amount: an approved pre-authorisation or tip-on-receipt sale that was neither
     * cancelled nor captured (a capture Adyen did not take, [CaptureStatus.FAILED], leaves it held). Only then can it be
     * cancelled.
     */
    fun isHeld(sale: SaleEntity): Boolean =
        (sale.kind == SaleKind.PRE_AUTHORISATION || sale.tipOnReceipt) &&
            sale.status == SaleStatus.APPROVED &&
            sale.refundedMinor == 0L &&
            (sale.captureStatus == null || sale.captureStatus == CaptureStatus.FAILED)

    /** Whether [sale] was taken for tipping on the receipt and its tip has not been entered yet. */
    fun awaitingTip(sale: SaleEntity): Boolean = sale.tipOnReceipt && sale.tipMinor == null && isHeld(sale)

    /** Whether the tip of [sale] can be entered: it is [awaitingTip] and has the PSP reference a capture refers to. */
    fun canEnterTip(sale: SaleEntity): Boolean = awaitingTip(sale) && sale.pspReference != null

    /**
     * Whether the pre-authorisation [sale] can be captured, or its amount adjusted: it [isHeld] and has the PSP reference
     * the Checkout API refers to.
     */
    fun canCapture(sale: SaleEntity): Boolean = sale.kind == SaleKind.PRE_AUTHORISATION && isHeld(sale) && sale.pspReference != null

    /**
     * Whether the capture of [sale] should be sent again as it was: its outcome is unknown, or Adyen did not take the
     * capture of a tip (a pre-authorisation whose capture failed is captured again with a new amount instead).
     */
    fun canRetryCapture(sale: SaleEntity): Boolean {
        val retry =
            sale.captureStatus == CaptureStatus.UNKNOWN || (sale.captureStatus == CaptureStatus.FAILED && sale.tipOnReceipt)
        return retry && sale.capturedMinor != null && sale.pspReference != null && sale.refundedMinor == 0L
    }

    /** Whether a tip of [tipMinor] on a bill of [billMinor] is more than [TIP_ADJUSTMENT_PERCENT] of it. */
    fun needsAdjustment(
        billMinor: Long,
        tipMinor: Long,
    ): Boolean = tipMinor * PERCENT > billMinor * TIP_ADJUSTMENT_PERCENT
}
