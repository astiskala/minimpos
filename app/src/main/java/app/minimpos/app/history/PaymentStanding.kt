package app.minimpos.app.history

import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.RefundCancelStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundRoute
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleRoute
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.route
import app.minimpos.app.data.repo.cashAwaitingHandover
import app.minimpos.app.data.repo.remainingAmountKnown
import app.minimpos.terminal.client.Decline
import app.minimpos.terminal.client.RetryAdvice
import app.minimpos.terminal.client.TerminalClient

/**
 * Where a stored sale stands as a payment, worked out once from its status, kind, capture and cancellation. It is the
 * one reading of those fields: what can be done with the payment ([actions]), what about it still needs attention
 * ([issues], which day totals and History's issue filter share through [HistoryAccounting]), the status headings and
 * notes, and the receipts all start from it, so none of them reads [SaleEntity.captureStatus] or
 * [SaleEntity.holdCancelled] itself.
 *
 * Payments taken with manual capture ([SaleEntity.manualCapture]: pre-authorisations and sales taken for tipping on the
 * receipt) move from [AWAITING_TIP] or [HELD] through a capture ([CAPTURE_SENDING], then [CAPTURE_REQUESTED],
 * [CAPTURE_FAILED] or [CAPTURE_UNKNOWN]) or end in [HOLD_CANCELLED]. A
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

    /** A held payment whose cancellation (a full reversal) was accepted, so nothing was charged. */
    HOLD_CANCELLED,
    ;

    /** Whether the payment still only holds its amount, so it can be cancelled: [AWAITING_TIP], [HELD] or [CAPTURE_FAILED]. */
    val held: Boolean get() = this == AWAITING_TIP || this == HELD || this == CAPTURE_FAILED

    /**
     * Whether it was captured, so it counts as charged and is refunded up to the capture: its [capture] counts as made
     * ([CaptureStatus.captured]), which is [CAPTURE_REQUESTED].
     */
    val captured: Boolean get() = capture?.captured == true

    /** Whether the payment counts as charged: [CHARGED] or [captured]. */
    val charged: Boolean get() = this == CHARGED || captured

    /** Working out the standing of a stored sale, and the tip rule of tipping on the receipt. */
    companion object {
        private const val PERCENT = 100

        /**
         * Whether capturing [amountMinor] requires an authorization change or repeating an unresolved one.
         * A non-null [tipMinor] applies the receipt-tip overcapture rule; null is an ordinary capture.
         */
        fun captureNeedsAdjustment(
            sale: SaleEntity,
            amountMinor: Long,
            tipMinor: Long? = null,
        ): Boolean =
            sale.adjustmentPending ||
                (amountMinor > sale.heldMinor && (tipMinor == null || tipNeedsAdjustment(sale.totalMinor, tipMinor)))

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

        /** A reported surcharge may not be increased through an adjustment or overcapture. */
        fun captureAmountAllowed(
            sale: SaleEntity,
            amountMinor: Long,
        ): Boolean = amountMinor > 0 && ((sale.surchargeMinor ?: 0) == 0L || amountMinor <= sale.heldMinor)

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
 * What the result screen offers a stored sale beside Home; the one reading of its outcome, so the screen only words it.
 * See [saleResultNext].
 */
enum class SaleResultNext {
    /** Collect the remaining balance of a partially paid sale on another card. */
    PAY_REMAINING,

    /** The payment was approved: start the next one of its kind. */
    NEW_SALE,

    /** The payment failed and may be retried: back to checkout with the same cart. */
    TRY_AGAIN,

    /** Nothing is offered. */
    NONE,
}

/**
 * What the result of [sale] offers beside Home, the one reading of its outcome: collecting its remaining balance, the
 * next payment once it was approved, or a retry after a refusal the terminal's advice allows. A payment link takes its
 * cart when Adyen makes it, so a link offers a retry only when it was never made, nothing while that is unknown, and
 * the next sale otherwise, whether it was paid, cancelled or expired.
 */
fun saleResultNext(sale: SaleEntity): SaleResultNext =
    when {
        sale.isPartiallyPaid && !sale.remainingAmountKnown -> {
            SaleResultNext.NONE
        }

        sale.isPartiallyPaid -> {
            SaleResultNext.PAY_REMAINING
        }

        sale.route == SaleRoute.PAYMENT_LINK -> {
            when (sale.status) {
                SaleStatus.FAILED -> {
                    SaleResultNext.TRY_AGAIN
                }

                SaleStatus.PENDING, SaleStatus.UNKNOWN -> {
                    SaleResultNext.NONE
                }

                SaleStatus.AWAITING_PAYMENT, SaleStatus.APPROVED, SaleStatus.DECLINED, SaleStatus.CANCELLED, SaleStatus.EXPIRED -> {
                    SaleResultNext.NEW_SALE
                }
            }
        }

        sale.approved -> {
            SaleResultNext.NEW_SALE
        }

        sale.status in setOf(SaleStatus.DECLINED, SaleStatus.CANCELLED, SaleStatus.FAILED) &&
            sale.decline?.advice != RetryAdvice.DO_NOT_RETRY -> {
            SaleResultNext.TRY_AGAIN
        }

        else -> {
            SaleResultNext.NONE
        }
    }

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
 * @property simulatedLink Whether this is an offline demo link, including after completion or cancellation.
 * @property standing Full payment standing retained for receipt notes.
 * @property cashout Whether this is standalone cash-out, with no merchandise.
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
    val simulatedLink: Boolean = false,
    /** Current standing retained through receipt rendering. */
    val standing: PaymentStanding = PaymentStanding.CHARGED,
    val cashout: Boolean = false,
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
                tipMinor = sale.tipMinor?.takeIf { sale.tipOnReceipt || it > 0 },
                awaitingTip = standing == PaymentStanding.AWAITING_TIP,
                heldNowMinor = sale.heldMinor.takeIf { sale.manualCapture && it != sale.totalMinor },
                capturedMinor = sale.capturedMinor?.takeIf { preAuthorisation && standing.captured },
                unpaidLink = unpaidLink,
                linkExpiresAt = sale.paymentLinkExpiresAt?.takeIf { unpaidLink != null },
                paidOnline = sale.paidOnline,
                simulatedLink = sale.simulatedLink,
                standing = standing,
                cashout = sale.cashout,
            )
        }
    }
}

/** Whether this sale's payment link has been created and not paid yet, so the shopper can still pay with it. */
val SaleEntity.awaitsLinkPayment: Boolean
    get() = route == SaleRoute.PAYMENT_LINK && status == SaleStatus.AWAITING_PAYMENT && paymentLinkUrl != null

/**
 * Whether this sale's payment link screen still has something to offer: the link can be paid, or whether it was created
 * is unknown and can be asked about there.
 */
val SaleEntity.linkUnsettled: Boolean
    get() = route == SaleRoute.PAYMENT_LINK && (status == SaleStatus.AWAITING_PAYMENT || status == SaleStatus.UNKNOWN)

/** Whether this sale was paid through its payment link, which its receipt and detail say. */
val SaleEntity.paidOnline: Boolean get() = route == SaleRoute.PAYMENT_LINK && standing.charged

/**
 * Whether this sale was sent to a terminal, so terminal totals and reconciliation include it: not seeded sample
 * history, a payment link or a saved-card charge, which go through Adyen's Checkout API instead.
 */
val SaleEntity.terminalOperation: Boolean get() = !sample && route == SaleRoute.TERMINAL

/** Whether this refund was sent to a terminal, so terminal totals include it: not a Checkout refund of a saved-card charge. */
val RefundEntity.terminalOperation: Boolean get() = route.onTerminal

/** What about a stored sale still needs attention, see [issues]. */
enum class PaymentIssue {
    /** Approved cashback awaits an operator handover, or its returned amount needs investigation. */
    CASH_HANDOVER,

    /** Whether the payment went through is not known yet: it is still pending or its outcome is unknown. */
    OUTCOME,

    /** An authorisation adjustment was sent and its outcome is unknown; only the same request may be sent again. */
    ADJUSTMENT,

    /** Its capture is being sent, failed or has an unknown outcome ([PaymentStanding.CAPTURE_SENDING] and the like). */
    CAPTURE,
}

/**
 * What about this sale still needs attention, the one issue reading History's filter, day totals and reconciliation
 * share; empty when nothing does.
 */
val SaleEntity.issues: Set<PaymentIssue>
    get() =
        buildSet {
            if (status == SaleStatus.PENDING || status == SaleStatus.UNKNOWN) add(PaymentIssue.OUTCOME)
            if (cashAwaitingHandover) add(PaymentIssue.CASH_HANDOVER)
            if (adjustmentPending) add(PaymentIssue.ADJUSTMENT)
            if (standing in CAPTURE_ISSUES) add(PaymentIssue.CAPTURE)
        }

/** The standings of a capture that still needs attention, see [PaymentIssue.CAPTURE]. */
private val CAPTURE_ISSUES = setOf(PaymentStanding.CAPTURE_FAILED, PaymentStanding.CAPTURE_UNKNOWN, PaymentStanding.CAPTURE_SENDING)

/** Whether this sale is an offline demo link, regardless of its status or the device's current destination. */
val SaleEntity.simulatedLink: Boolean get() = route == SaleRoute.PAYMENT_LINK && context?.simulated == true

/** Whether this sale moved no money: seeded sample history, or a payment its original context took with the simulator. */
val SaleEntity.simulatedSale: Boolean get() = sample || context?.simulated == true

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

    /**
     * Ask again whether the payment went through, which is unknown: the terminal's status of its stored ServiceID, or a
     * saved-card charge sent again with its stored idempotency key. A payment link is asked about through its link.
     */
    CHECK_AGAIN,
}

/**
 * What can be done with this stored payment now. Captures, adjustments and retries go through the Checkout API, so they
 * also need the PSP reference it refers to, and a cancellation the terminal's transaction details.
 */
val SaleWithLines.actions: Set<PaymentAction>
    get() =
        buildSet {
            if (sale.sample) return@buildSet
            if (RefundablePayment.check(this@actions) is Refundability.Refundable) add(PaymentAction.REFUND)
            if (RefundablePayment.cancellable(this@actions)) add(PaymentAction.CANCEL)
            if (sale.pspReference != null) addAll(captureActions(sale))
            if (sale.canCheckAgain) add(PaymentAction.CHECK_AGAIN)
        }

/** Whether this sale's unknown outcome can be asked about again the way it was sent, see [PaymentAction.CHECK_AGAIN]. */
private val SaleEntity.canCheckAgain: Boolean
    get() = status == SaleStatus.UNKNOWN && (route == SaleRoute.SAVED_CARD || (route == SaleRoute.TERMINAL && serviceId != null))

/**
 * Whether this refund's unknown outcome can be asked about again the way it was sent: the terminal's status of its
 * stored ServiceID, or a Checkout refund sent again with its stored idempotency key.
 */
val RefundEntity.canCheckAgain: Boolean
    get() = status == RefundStatus.UNKNOWN && (route == RefundRoute.CHECKOUT || serviceId != null)

/**
 * Whether the delayed unreferenced refund [this] can be asked to be cancelled now, given the merchant confirmed Adyen
 * Support configured a refund [delayed]: accepted, with a PSP reference, and no cancellation received yet. Adyen
 * enforces the delay itself, so an expired delay is its rejection, not this reading.
 */
fun RefundEntity.cancellable(delayed: Boolean): Boolean =
    route == RefundRoute.UNREFERENCED && status == RefundStatus.REQUESTED && pspReference != null &&
        cancelStatus != RefundCancelStatus.REQUESTED && (delayed || cancelStatus?.unresolved == true)

/**
 * Whether this accepted unreferenced refund was approved synchronously by the acquirer (`acquirerResponseCode=Approved`)
 * rather than only requested; either way Adyen confirms the refund itself only in the Customer Area.
 */
val RefundEntity.approvedSynchronously: Boolean
    get() = route == RefundRoute.UNREFERENCED && status == RefundStatus.REQUESTED && TerminalClient.refundApproved(acquirerResponseCode)

/** What the Checkout API can do with [sale], which has a PSP reference. */
private fun captureActions(sale: SaleEntity): Set<PaymentAction> {
    val standing = sale.standing
    val capture = sale.kind == SaleKind.PRE_AUTHORISATION && standing.held
    val retry = standing == PaymentStanding.CAPTURE_UNKNOWN || (standing == PaymentStanding.CAPTURE_FAILED && sale.tipOnReceipt)
    return buildSet {
        if (standing == PaymentStanding.AWAITING_TIP) add(PaymentAction.ENTER_TIP)
        if (capture) {
            add(PaymentAction.CAPTURE)
            if ((sale.surchargeMinor ?: 0) == 0L) add(PaymentAction.ADJUST)
        }
        if (retry && sale.capturedMinor != null) add(PaymentAction.RETRY_CAPTURE)
    }
}

/** Whether this record is a standalone card cash-out, with no merchandise or taxable sale value. */
val SaleEntity.cashout: Boolean get() = requestedCashBackMinor != null && totalMinor == 0L
