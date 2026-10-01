package io.minimpos.app.feature

import io.minimpos.app.payment.AutoDelivery
import io.minimpos.app.payment.PaymentStart
import io.minimpos.app.payment.ReceiptDelivery
import io.minimpos.app.payment.ReceiptOffer
import io.minimpos.app.payment.SalePrint
import io.minimpos.app.payment.TransactionLifecycle
import io.minimpos.app.receipt.ActionResult
import io.minimpos.app.refund.RefundStart
import io.minimpos.core.receipt.ReceiptCopy
import io.minimpos.core.receipt.ReceiptDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    /**
     * A capture sent again did not go through.
     *
     * @property reason Why, as Adyen or the app worded it.
     */
    data class NotCaptured(
        val reason: String,
    ) : ActionOutcome

    /**
     * The connection test reached the terminal.
     *
     * @property globalStatus The terminal's overall status as it reported it; null when it reported none.
     * @property hasPrinter Whether it has a printer.
     */
    data class Connected(
        val globalStatus: String?,
        val hasPrinter: Boolean,
    ) : ActionOutcome

    /**
     * The connection test could not reach the terminal, or it did not answer properly.
     *
     * @property reason Why.
     */
    data class ConnectionFailed(
        val reason: String,
    ) : ActionOutcome

    /** The Checkout API test went through. */
    data object ApiWorks : ActionOutcome

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

/** This result as a finished [ActionState]: [success] on success, else the failure's message as an error. */
fun ActionResult.toState(success: ActionOutcome) =
    when (this) {
        ActionResult.Success -> ActionState(outcome = success, done = true)
        is ActionResult.Failure -> ActionState(outcome = ActionOutcome.Failed(message), isError = true)
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
)

/**
 * The receipt and status of one stored sale ([forSale]) or refund ([forRefund]), as every result and detail screen
 * offers them: the receipt itself and whether it can be printed or emailed, printing, emailing and re-checking
 * (TransactionStatus), with their progress and outcome in [state]. For a transaction just made (`fresh`) it also
 * delivers the receipt automatically, once per transaction whatever screens are made for it (see [ReceiptDelivery]).
 *
 * A view model makes one with its `viewModelScope`; prints and status checks stop when the screen closes, but an email
 * always finishes (`persisting`), since a sent sale receipt records the address on the sale.
 */
class TransactionActions private constructor(
    private val scope: CoroutineScope,
    offer: Flow<ReceiptOffer>,
    automation: (suspend () -> AutoDelivery)?,
    private val printing: suspend (ReceiptCopy) -> SalePrint,
    private val emailing: suspend (to: String) -> ActionResult,
    private val rechecking: suspend () -> Boolean,
) {
    private val _state = MutableStateFlow(TransactionActionsState())

    /** Where the actions stand. */
    val state: StateFlow<TransactionActionsState> = _state.asStateFlow()

    init {
        scope.launch {
            offer.collect { offered ->
                _state.update { it.copy(receipt = offered.receipt, canPrint = offered.canPrint, canEmail = offered.canEmail) }
            }
        }
        automation?.let { deliver ->
            scope.launch {
                offer.first { it.receipt != null }
                val automatic = deliver()
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
            val printed = printing(copy)
            _state.update {
                it.copy(print = printed.result.toState(ActionOutcome.Printed), merchantCopyPending = printed.merchantCopyDue)
            }
        }
    }

    /** Emails the receipt to [to]. */
    fun email(to: String) {
        _state.update { it.copy(email = ActionState(running = true)) }
        scope.launch {
            val result = persisting { emailing(to) }
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

    /** The actions for a sale or a refund. */
    companion object {
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
        ) = TransactionActions(
            scope,
            offer = receipts.saleOffer(saleId, justPaid = fresh),
            automation = if (fresh) ({ receipts.automationForSale(saleId) }) else null,
            printing = { receipts.printSale(saleId, it) },
            emailing = { receipts.emailSale(saleId, it) },
            rechecking = { payments.recheck(saleId) },
        )

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
        ) = TransactionActions(
            scope,
            offer = receipts.refundOffer(refundId),
            automation = if (fresh) ({ receipts.automationForRefund(refundId) }) else null,
            printing = { SalePrint(receipts.printRefund(refundId)) },
            emailing = { receipts.emailRefund(refundId, it) },
            rechecking = { refunds.recheck(refundId) },
        )
    }
}
