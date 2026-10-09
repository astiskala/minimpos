package app.minimpos.app.feature

import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.payment.CaptureResult
import app.minimpos.app.payment.LinkUpdate
import app.minimpos.app.payment.PaymentLinks
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.SharedReceipt
import app.minimpos.app.payment.StoredTransaction
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.receipt.ActionResult
import app.minimpos.app.refund.RefundStart
import app.minimpos.core.receipt.ReceiptCopy
import app.minimpos.core.receipt.ReceiptDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * What a finished action reports, for the screen to word (see [OutcomeMessage]). View models never hold display text:
 * a failure worded elsewhere (by the module that failed, or by Adyen) travels as [Failed].
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

    /** A tip, capture or adjustment did not go through, see [CaptureResult.toState]. */
    sealed interface CaptureFailed : ActionOutcome

    /**
     * A tip, capture or adjustment did not go through: Adyen did not take it, its outcome is unknown (it can be sent
     * again safely), or the Checkout API is not set up.
     *
     * @property reason Why, as Adyen or the app worded it.
     * @property step What was sent.
     */
    data class NotCaptured(
        val reason: String,
        val step: CaptureStep,
    ) : CaptureFailed

    /**
     * The card issuer refused the higher amount a tip, capture or adjustment needed, so nothing was captured (and a tip
     * was not saved, so a smaller one can be entered).
     *
     * @property step What was sent.
     * @property amountMinor The amount asked for, in minor units of [currency].
     * @property currency The payment's ISO 4217 currency code.
     * @property reason Adyen's refusal reason.
     */
    data class CaptureRefused(
        val step: CaptureStep,
        val amountMinor: Long,
        val currency: String,
        val reason: String,
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
     * @property reason Why, as the terminal or Adyen worded it; null when it did not answer at all.
     */
    data class ConnectionFailed(
        val reason: String?,
    ) : ActionOutcome

    /**
     * Nothing was sent, because something must be entered, installed or fixed first.
     *
     * @property problem What.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : ActionOutcome

    /** The Payments app (or the terminal) did not answer, and gave no reason. */
    data object NoAnswer : ActionOutcome

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

    /**
     * A secret (password, passphrase, API key or PIN) could not be stored on this device.
     *
     * @property reason Why, in English; null when the device gave no reason.
     */
    data class SecretNotStored(
        val reason: String?,
    ) : ActionOutcome

    /**
     * The action failed for a reason worded elsewhere, shown as it is.
     *
     * @property message Why.
     */
    data class Failed(
        val message: String,
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
)

/** This result as a finished [ActionState]: [success] on success, else why not as an error. */
fun ActionResult.toState(success: ActionOutcome) =
    when (this) {
        ActionResult.Success -> ActionState(outcome = success, done = true)
        is ActionResult.Failure -> ActionState(outcome = ActionOutcome.Failed(message), isError = true)
        is ActionResult.NotSetUp -> ActionState(outcome = ActionOutcome.NotSetUp(problem), isError = true)
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
            ActionState(outcome = ActionOutcome.CaptureRefused(step, amountMinor, currency, reason), isError = true)
        }

        is CaptureResult.Failed -> {
            ActionState(outcome = ActionOutcome.NotCaptured(message, step), isError = true)
        }

        is CaptureResult.NotSetUp -> {
            ActionState(outcome = ActionOutcome.NotSetUp(problem), isError = true)
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
 * @property stillUnknown Whether the latest status check found the outcome still unknown.
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
    val stillUnknown: Boolean = false,
    val receipt: ReceiptDocument? = null,
    val canPrint: Boolean = false,
    val canEmail: Boolean = false,
    val canShare: Boolean = false,
    val share: SharedReceipt? = null,
)

/**
 * The receipt and status of one stored sale ([forSale], or [forLink] for one paid through a payment link) or refund
 * ([forRefund]), as every result and detail screen offers them: the receipt itself and whether it can be printed,
 * emailed or shared, printing, emailing, sharing and re-checking (TransactionStatus, or asking Adyen about the link),
 * with their progress and outcome in [state]. For a transaction just made (`fresh`) it also delivers the receipt
 * automatically, once per transaction whatever screens are made for it (see [ReceiptDelivery]).
 *
 * A view model makes one with its `viewModelScope`; prints and status checks stop when the screen closes, but an email
 * always finishes (`persisting`), since a sent sale receipt records the address on the sale.
 */
class TransactionActions private constructor(
    private val scope: CoroutineScope,
    private val receipts: ReceiptDelivery,
    private val transaction: StoredTransaction,
    fresh: Boolean,
    private val rechecking: suspend () -> Boolean,
) {
    private val _state = MutableStateFlow(TransactionActionsState())

    /** The receipt to share when asked, as last offered. */
    @Volatile private var shareable: SharedReceipt? = null

    /** Where the actions stand. */
    val state: StateFlow<TransactionActionsState> = _state.asStateFlow()

    init {
        val offer = receipts.offer(transaction, fresh)
        scope.launch {
            offer.collect { offered ->
                shareable = offered.share
                _state.update {
                    it.copy(
                        receipt = offered.receipt,
                        canPrint = offered.canPrint,
                        canEmail = offered.canEmail,
                        canShare = offered.canShare,
                    )
                }
            }
        }
        if (fresh) {
            scope.launch {
                offer.first { it.receipt != null }
                val automatic = receipts.automation(transaction)
                if (automatic.print) print()
                automatic.emailTo?.let(::email)
            }
        }
    }

    /**
     * Prints [copy] of the receipt. After the customer copy of a sale, [TransactionActionsState.merchantCopyPending] says
     * whether the merchant copy is due (it waits for the operator to tear off the first).
     */
    fun print(copy: ReceiptCopy = ReceiptCopy.CUSTOMER) {
        _state.update { it.copy(print = ActionState(running = true), merchantCopyPending = false) }
        scope.launch {
            val printed = receipts.print(transaction, copy)
            _state.update {
                it.copy(print = printed.result.toState(ActionOutcome.Printed), merchantCopyPending = printed.merchantCopyDue)
            }
        }
    }

    /** Emails the receipt to [to]. */
    fun email(to: String) {
        _state.update { it.copy(email = ActionState(running = true)) }
        scope.launch {
            val result = persisting { receipts.email(transaction, to) }
            _state.update { it.copy(email = result.toState(ActionOutcome.Emailed(to))) }
        }
    }

    /** Asks the terminal again for an unknown outcome; the stored transaction updates when it is settled. */
    fun recheck() {
        _state.update { it.copy(rechecking = true, stillUnknown = false) }
        scope.launch {
            val settled = rechecking()
            _state.update { it.copy(rechecking = false, stillUnknown = !settled) }
        }
    }

    /** Asks for the receipt to be shared; the screen hands [TransactionActionsState.share] to the share sheet. */
    fun share() {
        val shared = shareable ?: return
        _state.update { it.copy(share = shared) }
    }

    /** Reports that the screen handed the waiting receipt to the share sheet, so it is not shared twice. */
    fun shared() = _state.update { it.copy(share = null) }

    /** The actions for a sale or a refund. */
    companion object {
        /** Prints a whole-day local summary; History filters never enter receipt delivery. */
        suspend fun printReport(
            receipts: ReceiptDelivery,
            date: LocalDate,
        ): ActionState = receipts.printReport(date).toState(ActionOutcome.Printed)

        /** Whether History offers report printing, following current printer capability. */
        fun canPrintReports(receipts: ReceiptDelivery): Flow<Boolean> = receipts.canPrintReports

        /**
         * The actions on sale [saleId], in [scope]: receipts through [receipts], status checks through [payments]. A
         * [fresh] sale (on the result just after its payment) gets its automatic receipt, and email is offered only
         * when checkout captures emails.
         */
        fun forSale(
            scope: CoroutineScope,
            saleId: String,
            receipts: ReceiptDelivery,
            payments: TransactionLifecycle<PaymentStart>,
            fresh: Boolean,
        ) = TransactionActions(scope, receipts, StoredTransaction.Sale(saleId), fresh) { payments.recheck(saleId) }

        /**
         * The actions on sale [saleId], paid through a payment link, in [scope]: as [forSale], except that emailing is
         * offered whenever email is set up (it is how the link reaches the shopper), and re-checking asks Adyen about
         * the link through [links] (true once the sale is settled: paid, expired or cancelled).
         */
        fun forLink(
            scope: CoroutineScope,
            saleId: String,
            receipts: ReceiptDelivery,
            links: PaymentLinks,
            fresh: Boolean,
        ) = TransactionActions(scope, receipts, StoredTransaction.Sale(saleId), fresh) { links.check(saleId) == LinkUpdate.Settled }

        /**
         * The actions on refund [refundId], in [scope]: receipts through [receipts] (a refund has one copy only), status
         * checks through [refunds]. A [fresh] refund (on the result just after it) gets its automatic receipt.
         */
        fun forRefund(
            scope: CoroutineScope,
            refundId: String,
            receipts: ReceiptDelivery,
            refunds: TransactionLifecycle<RefundStart>,
            fresh: Boolean,
        ) = TransactionActions(scope, receipts, StoredTransaction.Refund(refundId), fresh) { refunds.recheck(refundId) }
    }
}
