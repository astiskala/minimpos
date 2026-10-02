package io.minimpos.app.refund

import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.terminal.client.Decline

/**
 * Where a stored sale stands as a payment, worked out once from its status, kind, capture and cancellation. It is the
 * one reading of those fields: what can be done with the payment ([actions]), how it counts in the day's totals
 * ([totalsShare]), the status headings and notes, and the receipts all start from it, so none of them reads
 * [SaleEntity.captureStatus] or [SaleEntity.holdCancelled] itself.
 *
 * Payments taken with manual capture ([SaleEntity.manualCapture]: pre-authorisations and sales taken for tipping on the
 * receipt) move from [AWAITING_TIP] or [HELD] through a capture ([CAPTURE_SENDING], then [CAPTURE_REQUESTED],
 * [CAPTURE_FAILED] or [CAPTURE_UNKNOWN]; with older versions also [CAPTURED_MANUALLY]) or end in [HOLD_CANCELLED]. A
 * plain sale is [CHARGED] as soon as it is approved.
 *
 * @property capture The capture status a payment taken with manual capture stands here with; null for the standings
 *   before (or without) a capture.
 */
enum class PaymentStanding(
    val capture: CaptureStatus? = null,
) {
    /** The payment was not approved (yet): pending, declined, cancelled, failed or unknown, as its [SaleStatus] says. */
    NOT_APPROVED,

    /** An approved plain sale, charged straight away. Refunds do not change it. */
    CHARGED,

    /** A sale taken for tipping on the receipt that holds its bill until its tip is entered. */
    AWAITING_TIP,

    /** Holds its amount and no capture was attempted: a pre-authorisation (adjusted or not). */
    HELD,

    /** Holds its amount after Adyen did not take its capture ([CaptureStatus.FAILED]); it can be captured again. */
    CAPTURE_FAILED(CaptureStatus.FAILED),

    /** Its capture is being sent ([CaptureStatus.PENDING]). */
    CAPTURE_SENDING(CaptureStatus.PENDING),

    /** Whether Adyen received its capture is not known ([CaptureStatus.UNKNOWN]); it can be sent again safely. */
    CAPTURE_UNKNOWN(CaptureStatus.UNKNOWN),

    /** Captured: Adyen received the capture ([CaptureStatus.REQUESTED]) and confirms it in the Customer Area. */
    CAPTURE_REQUESTED(CaptureStatus.REQUESTED),

    /** Captured: left to staff in the Customer Area ([CaptureStatus.MANUAL]) by an older version, without Checkout API. */
    CAPTURED_MANUALLY(CaptureStatus.MANUAL),

    /** A held payment whose cancellation (a full reversal) was accepted, so nothing was charged. */
    HOLD_CANCELLED,
    ;

    /** Whether the payment still only holds its amount, so it can be cancelled: [AWAITING_TIP], [HELD] or [CAPTURE_FAILED]. */
    val held: Boolean get() = this == AWAITING_TIP || this == HELD || this == CAPTURE_FAILED

    /**
     * Whether it was captured, so it counts as charged and is refunded up to the capture: its [capture] counts as made
     * ([CaptureStatus.captured]), which is [CAPTURE_REQUESTED] or [CAPTURED_MANUALLY].
     */
    val captured: Boolean get() = capture?.captured == true

    /** Whether the payment counts as charged: [CHARGED] or [captured]. */
    val charged: Boolean get() = this == CHARGED || captured

    /** Working out the standing of a stored sale, and the tip rule of tipping on the receipt. */
    companion object {
        private const val PERCENT = 100

        /**
         * Adyen's rule of thumb for tipping on the receipt: a tip above this percentage of the bill needs an authorisation
         * adjustment before the capture; a smaller one is simply captured with the bill (overcapture).
         */
        const val TIP_ADJUSTMENT_PERCENT = 20

        /** Where [sale] stands. */
        fun of(sale: SaleEntity): PaymentStanding =
            when {
                sale.status != SaleStatus.APPROVED -> NOT_APPROVED
                !sale.manualCapture -> CHARGED
                sale.holdCancelled -> HOLD_CANCELLED
                else -> captureStanding(sale)
            }

        /** Whether a tip of [tipMinor] on a bill of [billMinor] is more than [TIP_ADJUSTMENT_PERCENT] of it. */
        fun tipNeedsAdjustment(
            billMinor: Long,
            tipMinor: Long,
        ): Boolean = tipMinor * PERCENT > billMinor * TIP_ADJUSTMENT_PERCENT

        private fun captureStanding(sale: SaleEntity): PaymentStanding {
            val status = sale.captureStatus ?: return if (sale.tipOnReceipt && sale.tipMinor == null) AWAITING_TIP else HELD
            return entries.single { it.capture == status }
        }
    }
}

/** Where this sale stands as a payment, see [PaymentStanding.of]. */
val SaleEntity.standing: PaymentStanding get() = PaymentStanding.of(this)

/**
 * Why this sale's payment was not approved, read from the ErrorCondition and refusal reason stored with it (see
 * [Decline]); null once it is approved. Without a stored ErrorCondition (pending, unknown or never sent) it gives no
 * [Decline.advice].
 */
val SaleEntity.decline: Decline? get() = Decline.of(status == SaleStatus.APPROVED, errorCondition, refusalReason)

/**
 * What a sale's receipt says about it as a payment, read from where it stands ([PaymentStanding.of]), so the receipt
 * does not combine the sale's fields itself.
 *
 * @property approved Whether the payment was approved; an unapproved one is marked as not completed.
 * @property preAuthorisation Whether it is a pre-authorisation, titled and totalled as an amount held.
 * @property tipMinor The tip entered for a sale taken for tipping on the receipt, in minor units; null while none is.
 * @property awaitingTip Whether it still awaits the tip written on the receipt ([PaymentStanding.AWAITING_TIP]), so a
 *   paper receipt gets blank tip lines.
 * @property heldNowMinor What a pre-authorisation holds after an adjustment, when that differs from its amount; else
 *   null.
 * @property capturedMinor What a captured pre-authorisation ([PaymentStanding.captured]) captured; else null.
 * @property unpaidLink The address of the payment link a sale still awaits its payment through
 *   ([SaleStatus.AWAITING_PAYMENT]), so its receipt is an unpaid one with the link; else null.
 * @property linkExpiresAt When that link stops working, in epoch milliseconds; null without one.
 * @property paidOnline Whether it was paid through its payment link, which the receipt says.
 */
data class ReceiptStanding(
    val approved: Boolean,
    val preAuthorisation: Boolean,
    val tipMinor: Long?,
    val awaitingTip: Boolean,
    val heldNowMinor: Long?,
    val capturedMinor: Long?,
    val unpaidLink: String? = null,
    val linkExpiresAt: Long? = null,
    val paidOnline: Boolean = false,
) {
    /** Reading a sale. */
    companion object {
        /** What [sale]'s receipt says about it. */
        fun of(sale: SaleEntity): ReceiptStanding {
            val standing = sale.standing
            val preAuthorisation = sale.kind == SaleKind.PRE_AUTHORISATION
            val unpaidLink = sale.paymentLinkUrl?.takeIf { sale.awaitsLinkPayment }
            return ReceiptStanding(
                approved = standing != PaymentStanding.NOT_APPROVED,
                preAuthorisation = preAuthorisation,
                tipMinor = sale.tipMinor?.takeIf { sale.tipOnReceipt },
                awaitingTip = standing == PaymentStanding.AWAITING_TIP,
                heldNowMinor = sale.authorisedMinor?.takeIf { preAuthorisation && it != sale.totalMinor },
                capturedMinor = sale.capturedMinor?.takeIf { preAuthorisation && standing.captured },
                unpaidLink = unpaidLink,
                linkExpiresAt = sale.paymentLinkExpiresAt?.takeIf { unpaidLink != null },
                paidOnline = sale.paymentLink && standing.charged,
            )
        }
    }
}

/** Whether this sale's payment link has been created and not paid yet, so the shopper can still pay with it. */
val SaleEntity.awaitsLinkPayment: Boolean get() = paymentLink && status == SaleStatus.AWAITING_PAYMENT && paymentLinkUrl != null

/** What the operator can do with a stored payment now, see [actions]. */
enum class PaymentAction {
    /** Refund it, as [RefundablePayment.check] allows: approved and charged, with something left to refund. */
    REFUND,

    /** Cancel the hold with a full reversal ([RefundablePayment.cancellation]): it is [PaymentStanding.held]. */
    CANCEL,

    /** Enter the tip written on the receipt and capture it ([PaymentStanding.AWAITING_TIP]). */
    ENTER_TIP,

    /** Capture a held pre-authorisation. */
    CAPTURE,

    /** Change what a held pre-authorisation holds. */
    ADJUST,

    /**
     * Send the capture again as it was (same amount and idempotency key): its outcome is unknown, or Adyen did not take
     * the capture of a tip. A pre-authorisation whose capture failed is captured again with a new amount instead.
     */
    RETRY_CAPTURE,
}

/**
 * What can be done with this stored payment now. Captures, adjustments and retries go through the Checkout API, so they
 * also need the PSP reference it refers to, and a cancellation the terminal's transaction details.
 */
val SaleWithLines.actions: Set<PaymentAction>
    get() =
        buildSet {
            if (RefundablePayment.check(this@actions) is Refundability.Refundable) add(PaymentAction.REFUND)
            if (RefundablePayment.cancellable(this@actions)) add(PaymentAction.CANCEL)
            if (sale.pspReference != null) addAll(captureActions(sale))
        }

/** What the Checkout API can do with [sale], which has a PSP reference. */
private fun captureActions(sale: SaleEntity): Set<PaymentAction> {
    val standing = sale.standing
    val capture = sale.kind == SaleKind.PRE_AUTHORISATION && standing.held
    val retry = standing == PaymentStanding.CAPTURE_UNKNOWN || (standing == PaymentStanding.CAPTURE_FAILED && sale.tipOnReceipt)
    return buildSet {
        if (standing == PaymentStanding.AWAITING_TIP) add(PaymentAction.ENTER_TIP)
        if (capture) addAll(listOf(PaymentAction.CAPTURE, PaymentAction.ADJUST))
        if (retry && sale.capturedMinor != null) add(PaymentAction.RETRY_CAPTURE)
    }
}

/** How a stored payment counts in History's day totals, see [totalsShare]. */
enum class TotalsShare {
    /** Not at all: it was not approved, or it held its amount and was cancelled, so nothing was charged. */
    NONE,

    /** As a sale, at [SaleEntity.amountMinor]: a sale (one awaiting its tip at its bill), or a captured pre-authorisation. */
    SALE,

    /** As held, at [SaleEntity.heldMinor]: a pre-authorisation that has not been captured. */
    HELD,
}

/** How this sale counts in the day's totals: a pre-authorisation counts as a sale only once it is captured. */
val SaleEntity.totalsShare: TotalsShare
    get() {
        val standing = standing
        return when {
            standing == PaymentStanding.NOT_APPROVED || standing == PaymentStanding.HOLD_CANCELLED -> TotalsShare.NONE
            kind == SaleKind.SALE || standing.charged -> TotalsShare.SALE
            else -> TotalsShare.HELD
        }
    }
