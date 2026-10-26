package app.minimpos.app.feature

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.payment.AutoDelivery
import app.minimpos.app.payment.CaptureResult
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.Recheck
import app.minimpos.app.payment.SharedReceipt
import app.minimpos.app.payment.StoredTransaction
import app.minimpos.app.receipt.ActionResult
import app.minimpos.core.receipt.ReceiptCopy
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.terminal.transport.ExternalText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * What a finished action reports, for the screen to word (see [OutcomeMessage]). View models never hold display text:
 * a failure travels typed as [Failed], with any words Adyen, a terminal or a mail server wrote kept verbatim inside it.
 */
sealed interface ActionOutcome {
    /** A receipt (or the test receipt) was printed. */
    data object Printed : ActionOutcome

    /**
     * A receipt was emailed.
     *
     * @property to The address it was sent to.
     */
    data class Emailed(
        val to: String,
    ) : ActionOutcome

    /** The busy terminal was asked to cancel the transaction it is working on. */
    data object AbortSent : ActionOutcome

    /** Adyen says the payment link has not been paid yet. */
    data object LinkNotPaid : ActionOutcome

    /**
     * A manual payment-link check found a settled status.
     * @property status The stored status after the check, not a separate confirmation of settlement.
     */
    data class LinkChecked(
        val status: SaleStatus,
    ) : ActionOutcome

    /** A tip, capture or adjustment did not go through, see [CaptureResult.toState]. */
    sealed interface CaptureFailed : ActionOutcome

    /**
     * A tip, capture or adjustment did not go through: Adyen did not take it, or the Checkout API is not set up.
     *
     * @property failure Why.
     * @property step What was sent.
     */
    data class NotCaptured(
        val failure: Failure,
        val step: CaptureStep,
    ) : CaptureFailed

    /**
     * Adyen did not confirm a tip, capture or adjustment; only the same request may be sent again.
     *
     * @property failure What prevented the confirmation.
     * @property step What was sent.
     */
    data class CaptureUnconfirmed(
        val failure: Failure,
        val step: CaptureStep,
    ) : CaptureFailed

    /**
     * The card issuer refused the higher amount a tip, capture or adjustment needed, so nothing was captured (and a tip
     * was not saved, so a smaller one can be entered).
     *
     * @property step What was sent.
     * @property amountMinor The amount asked for, in minor units of [currency].
     * @property currency The payment's ISO 4217 currency code.
     * @property said Adyen's refusal reason; null when it gave none.
     */
    data class CaptureRefused(
        val step: CaptureStep,
        val amountMinor: Long,
        val currency: String,
        val said: ExternalText?,
    ) : CaptureFailed

    /** The payment no longer allows the tip, capture or adjustment (captured or cancelled meanwhile, or gone). */
    data object CaptureNotAllowed : CaptureFailed

    /**
     * The connection test reached the terminal.
     *
     * @property globalStatus The terminal's overall status as it reported it; null when it reported none.
     * @property hasPrinter Whether it has a printer.
     * @property setupOnly Whether only required fields were checked because this destination has no diagnosis API.
     */
    data class Connected(
        val globalStatus: String?,
        val hasPrinter: Boolean,
        val setupOnly: Boolean = false,
    ) : ActionOutcome

    /**
     * The connection test could not reach the terminal, or it did not answer properly.
     *
     * @property failure Why.
     */
    data class ConnectionFailed(
        val failure: Failure,
    ) : ActionOutcome

    /** The Checkout API test went through. */
    data object ApiWorks : ActionOutcome

    /**
     * The Adyen Payments app is boarded on this phone, so Tap to Pay works.
     *
     * @property installationId The boarded instance's ID.
     */
    data class TapToPayReady(
        val installationId: String,
    ) : ActionOutcome

    /** The Payments app instance on this phone was revoked and forgotten. */
    data object TapToPayRemoved : ActionOutcome

    /**
     * The test email was sent.
     *
     * @property to The address it was sent to.
     */
    data class TestEmailSent(
        val to: String,
    ) : ActionOutcome

    /** The sale or refund no longer exists. */
    data object Missing : ActionOutcome

    /**
     * Adyen did not confirm what was asked (such as expiring a payment link), so it may have happened; check again.
     *
     * @property failure What prevented the confirmation.
     */
    data class Unconfirmed(
        val failure: Failure,
    ) : ActionOutcome

    /**
     * The action failed.
     *
     * @property failure Why.
     */
    data class Failed(
        val failure: Failure,
    ) : ActionOutcome
}

/**
 * The state of a button-triggered action such as printing, emailing or a connection test.
 *
 * @property running Whether it is in progress (the button shows a spinner).
 * @property outcome What to report, or null.
 * @property isError Whether it failed.
 * @property done Whether it finished successfully.
 */
data class ActionState(
    val running: Boolean = false,
    val outcome: ActionOutcome? = null,
    val isError: Boolean = false,
    val done: Boolean = false,
) {
    /** This state once its outcome has been seen and closed, still telling whether the action succeeded or failed. */
    fun dismissed(): ActionState = copy(outcome = null)
}

/** This result as a finished [ActionState]: [success] on success, else why not as an error. */
fun ActionResult.toState(success: ActionOutcome) =
    when (this) {
        ActionResult.Success -> ActionState(outcome = success, done = true)
        ActionResult.Missing -> ActionState(outcome = ActionOutcome.Missing, isError = true)
        is ActionResult.Failed -> ActionState(outcome = ActionOutcome.Failed(failure), isError = true)
    }

/** What a tip, capture or adjustment sent, which the wording of its failure names. */
enum class CaptureStep {
    /** Entering the tip written on the receipt, and capturing the bill plus the tip. */
    TIP,

    /** Capturing a pre-authorisation, or sending a capture again. */
    CAPTURE,

    /** Changing what a pre-authorisation holds, without capturing. */
    ADJUSTMENT,
}

/**
 * This result of a [step] that asked for [amountMinor] (in minor units of [currency]) as a finished [ActionState]:
 * done once Adyen received or adjusted it; else why not, as an error.
 */
fun CaptureResult.toState(
    step: CaptureStep,
    amountMinor: Long,
    currency: String,
): ActionState =
    when (this) {
        CaptureResult.Requested, CaptureResult.Adjusted -> {
            ActionState(done = true)
        }

        is CaptureResult.Refused -> {
            ActionState(outcome = ActionOutcome.CaptureRefused(step, amountMinor, currency, said), isError = true)
        }

        is CaptureResult.NotDone -> {
            ActionState(outcome = ActionOutcome.NotCaptured(failure, step), isError = true)
        }

        is CaptureResult.Unconfirmed -> {
            ActionState(outcome = ActionOutcome.CaptureUnconfirmed(failure, step), isError = true)
        }

        CaptureResult.NotAllowed -> {
            ActionState(outcome = ActionOutcome.CaptureNotAllowed, isError = true)
        }
    }

/**
 * Where the receipt and status actions on one stored transaction stand, see [TransactionActions].
 *
 * @property print The latest print.
 * @property merchantCopyPending Whether the customer copy of a sale has printed and its merchant copy is due next.
 * @property email The latest email.
 * @property rechecking Whether a transaction status check is running.
 * @property stillUnknown Why the latest status check could not establish the outcome; null when none ran or it did.
 * @property receipt The receipt as it prints now with the current settings; null until the transaction is loaded (or
 *   once it is gone).
 * @property canPrint Whether printing is offered (Settings › Receipts › Printer, and what the terminal reported).
 * @property canEmail Whether emailing the receipt is offered: email is set up and, on the result just after a payment,
 *   checkout captures emails at all.
 * @property canShare Whether sharing the receipt through Android's share sheet is offered (on phones and tablets).
 * @property share A receipt the screen should hand to the share sheet now, then report with
 *   [TransactionActions.shared]; null while none is waiting.
 */
data class TransactionActionsState(
    val print: ActionState = ActionState(),
    val merchantCopyPending: Boolean = false,
    val email: ActionState = ActionState(),
    val rechecking: Boolean = false,
    val stillUnknown: Failure? = null,
    val receipt: ReceiptDocument? = null,
    val canPrint: Boolean = false,
    val canEmail: Boolean = false,
    val canShare: Boolean = false,
    val share: SharedReceipt? = null,
)

/**
 * The receipt and status of one stored sale ([forSale]) or refund ([forRefund]), as every result and detail screen
 * offers them: the receipt itself and whether it can be printed, emailed or shared, printing, emailing, sharing and
 * re-checking (asked the way the transaction was sent, see [FinancialOperations.recheck]), with their progress and
 * outcome in [state]. Whichever screen is opened first for a transaction that just succeeded claims its automatic
 * delivery (see [ReceiptDelivery.arm]); every later screen, and any screen for an older transaction, delivers nothing
 * automatically. A [fresh] sale screen, on the result just after its payment, offers email only as
 * [ReceiptDelivery.offer] allows right after a payment.
 *
 * A view model makes one with its `viewModelScope`; manual prints and status checks stop when the screen closes, but a
 * manual email always finishes (`persisting`), since a sent sale receipt records the address on the sale. Automatic
 * delivery belongs to the receipt module's application scope, so closing the claiming screen cannot abandon it.
 */
class TransactionActions private constructor(
    private val scope: CoroutineScope,
    private val receipts: ReceiptDelivery,
    private val transaction: StoredTransaction,
    fresh: Boolean,
    private val operations: FinancialOperations,
) : ReceiptEvents {
    /** The receipt to share when asked, as last offered. */
    @Volatile private var shareable: SharedReceipt? = null

    /** Where the actions stand. */
    val state: StateFlow<TransactionActionsState>
        field = MutableStateFlow(TransactionActionsState())

    init {
        scope.launch {
            receipts.offer(transaction, fresh).collect { offered ->
                shareable = offered.share
                state.update {
                    it.copy(
                        receipt = offered.receipt,
                        canPrint = offered.canPrint,
                        canEmail = offered.canEmail,
                        canShare = offered.canShare,
                    )
                }
            }
        }
        // Whichever screen is opened first claims the transaction's one automatic delivery, fresh or not.
        scope.launch {
            state.first { it.receipt != null }
            var previous = AutoDelivery()
            receipts.automation(transaction).collect { automatic ->
                if (automatic.printing != previous.printing || automatic.printed != previous.printed) {
                    val printed = automatic.printed
                    state.update {
                        it.copy(
                            print = printed?.result?.toState(ActionOutcome.Printed) ?: ActionState(running = automatic.printing),
                            merchantCopyPending = printed?.merchantCopyDue == true,
                        )
                    }
                }
                if (automatic.emailing != previous.emailing || automatic.emailed != previous.emailed) {
                    val emailed = automatic.emailed?.toState(ActionOutcome.Emailed(checkNotNull(automatic.emailTo)))
                    state.update { it.copy(email = emailed ?: ActionState(running = automatic.emailing)) }
                }
                previous = automatic
            }
        }
    }

    /**
     * Prints [copy] of the receipt. After the customer copy of a sale, [TransactionActionsState.merchantCopyPending] says
     * whether the merchant copy is due (it waits for the operator to tear off the first).
     */
    override fun print(copy: ReceiptCopy) {
        state.update { it.copy(print = ActionState(running = true), merchantCopyPending = false) }
        scope.launch {
            val printed = receipts.print(transaction, copy)
            state.update {
                it.copy(print = printed.result.toState(ActionOutcome.Printed), merchantCopyPending = printed.merchantCopyDue)
            }
        }
    }

    /** Emails the receipt to [to]. */
    override fun email(to: String) {
        state.update { it.copy(email = ActionState(running = true)) }
        scope.launch {
            val result = persisting { receipts.email(transaction, to) }
            state.update { it.copy(email = result.toState(ActionOutcome.Emailed(to))) }
        }
    }

    /** Asks again for an unknown outcome, the way the transaction was sent; the stored transaction updates when it is settled. */
    fun recheck() {
        state.update { it.copy(rechecking = true, stillUnknown = null) }
        scope.launch {
            val checked = operations.recheck(transaction)
            state.update { it.copy(rechecking = false, stillUnknown = (checked as? Recheck.StillUnknown)?.failure) }
        }
    }

    /** Acknowledges the unresolved-status popup without changing the stored transaction or allowing a new identity. */
    fun dismissCheckResult() {
        state.update { it.copy(stillUnknown = null) }
    }

    /** Asks for the receipt to be shared; the screen hands [TransactionActionsState.share] to the share sheet. */
    override fun share() {
        val shared = shareable ?: return
        scope.launch {
            if (receipts.manualAllowed()) state.update { it.copy(share = shared) }
        }
    }

    /** Reports that the screen handed the waiting receipt to the share sheet, so it is not shared twice. */
    fun shared() = state.update { it.copy(share = null) }

    /** The actions for a sale or a refund. */
    companion object {
        /** Prints a whole-day local summary of [date] in [zone]; History filters never enter receipt delivery. */
        suspend fun printReport(
            receipts: ReceiptDelivery,
            date: LocalDate,
            zone: ZoneId,
        ): ActionState = receipts.printReport(date, zone).toState(ActionOutcome.Printed)

        /** Whether History offers report printing, following current printer capability. */
        fun canPrintReports(receipts: ReceiptDelivery): Flow<Boolean> = receipts.canPrintReports

        /**
         * The actions on sale [saleId] (taken on a terminal, with a saved card or through a payment link), in [scope]:
         * receipts through [receipts] and status checks through [operations]. A [fresh] sale (on the result just after
         * its payment) offers emailing only when checkout captures emails or the sale is a payment link.
         */
        fun forSale(
            scope: CoroutineScope,
            saleId: String,
            receipts: ReceiptDelivery,
            operations: FinancialOperations,
            fresh: Boolean,
        ) = TransactionActions(scope, receipts, StoredTransaction.Sale(saleId), fresh, operations)

        /**
         * The actions on refund [refundId], in [scope]: receipts through [receipts] (a refund has one copy only, and
         * email is offered whenever it is set up) and status checks through [operations].
         */
        fun forRefund(
            scope: CoroutineScope,
            refundId: String,
            receipts: ReceiptDelivery,
            operations: FinancialOperations,
        ) = TransactionActions(scope, receipts, StoredTransaction.Refund(refundId), fresh = false, operations)

        /**
         * The actions on stored-value activity [groupId], in [scope]: its receipt through [receipts] and status checks
         * of an unknown stage, with its original message category, through [operations].
         */
        fun forGiftCard(
            scope: CoroutineScope,
            groupId: String,
            receipts: ReceiptDelivery,
            operations: FinancialOperations,
        ) = TransactionActions(scope, receipts, StoredTransaction.GiftCard(groupId), fresh = false, operations)
    }
}
