package app.minimpos.app.payment

import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.RefundStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Every financial operation the app sends, as the screens and setup changes need them, whichever way it was sent: card
 * payments and refunds on the terminal ([TransactionLifecycle]), and saved-card charges and their refunds through the
 * Checkout API ([CheckoutOperations]). It routes a stored transaction's status check by how that transaction was sent
 * ([recheck]), sends a refund the way its payment was taken ([refund]) and is the one answer to whether anything is being
 * sent now ([running]). Callers never pick a runner themselves.
 *
 * Payment links are checked again through [PaymentLinks] here too, but do not count as [running]: a link is stored
 * before Adyen is asked for it and its later checks never act on changed credentials.
 *
 * @param payments Card payments (including scanned wallets) on the terminal.
 * @param refunds Referenced refunds and cancellations on the terminal.
 * @param charges Saved-card charges through the Checkout API.
 * @param checkoutRefunds Refunds of saved-card charges through the Checkout API.
 * @param links Payment links, whose status check asks Adyen about the link.
 * @param sales Where sales are stored, read to route a sale's status check.
 * @param refundRecords Where refunds are stored, read to route a refund's status check.
 * @param giftCards Stored-value activities on the terminal, which count as running and route their own status checks.
 */
class FinancialOperations(
    private val payments: TransactionLifecycle<PaymentStart>,
    private val refunds: TransactionLifecycle<RefundStart>,
    private val charges: SavedCardCharges,
    private val checkoutRefunds: CheckoutRefunds,
    private val links: PaymentLinks,
    private val sales: SaleRepository,
    private val refundRecords: RefundRepository,
    private val giftCards: GiftCards? = null,
) {
    /** Releases every runner's process-only requests after confirmed complete history deletion. */
    internal fun forgetAll() {
        payments.forgetAll()
        refunds.forgetAll()
        charges.forgetAll()
        checkoutRefunds.forgetAll()
        links.forgetAll()
        giftCards?.forgetAll()
    }

    /** Releases process-only request material after durable history deletion, routed to every runner. */
    internal fun forget(ids: Set<String>) {
        payments.forget(ids)
        refunds.forget(ids)
        charges.forget(ids)
        checkoutRefunds.forget(ids)
        links.forget(ids)
        giftCards?.forget(ids)
    }

    /**
     * Whether a card payment, refund, saved-card charge or Checkout refund is being sent now, so the configuration it
     * is sent with must not be replaced. Token deletion and its active reconciliation, and stored-value activities,
     * also hold credentials steady. Unfinished history alone does not count.
     */
    val running: Boolean
        get() =
            charges.managing ||
                listOfNotNull(payments.state, refunds.state, charges.state, checkoutRefunds.state, giftCards?.state)
                    .any { it.value is TransactionState.Processing }

    /**
     * The payment running or just finished, whichever way it is sent: on the terminal (scanned wallets included) or as a
     * saved-card charge. The payment screens follow it to its result; [TransactionState.Processing] says what it
     * requests and whether the shopper follows it on the terminal. [currentPayment] is its value now.
     */
    val paymentProgress: Flow<TransactionState> = combine(payments.state, charges.state, ::following)

    /** [paymentProgress] now, for a screen's first frame. */
    val currentPayment: TransactionState get() = following(payments.state.value, charges.state.value)

    /**
     * Asks the terminal to stop the running payment once it can ([TransactionState.Processing.canCancel]); a saved-card
     * charge cannot be stopped once sent.
     */
    fun cancelPayment() = payments.cancel()

    /** Leaves a finished payment, whichever way it was sent, so the next one can start; leaving its result calls it. */
    fun acknowledgePayment() {
        payments.acknowledge()
        charges.acknowledge()
    }

    /**
     * The refund (or cancellation) running or just finished, whichever way it is sent; the refund screens follow it to
     * its result. [currentRefund] is its value now.
     */
    val refundProgress: Flow<TransactionState> = combine(refunds.state, checkoutRefunds.state, ::following)

    /** [refundProgress] now, for a screen's first frame. */
    val currentRefund: TransactionState get() = following(refunds.state.value, checkoutRefunds.state.value)

    /**
     * Starts [request] the way its payment was taken: a saved-card charge's refund through the Checkout API, others on
     * the terminal. Returns the refund's ID; null, with nothing stored or sent, while a refund of either kind is running.
     */
    fun refund(request: RefundStart): String? =
        when {
            currentRefund is TransactionState.Processing || request.cash -> null
            request.checkout -> checkoutRefunds.start(request)
            else -> refunds.start(request)
        }

    /**
     * Starts one card leg [request] of a multi-leg return on the terminal with the [grant] the whole return started
     * with; null while a refund is running, or for a cash or Checkout leg, which are not terminal reversals.
     */
    internal fun refundLeg(
        request: RefundStart,
        grant: ManagerApproval.Grant,
    ): String? =
        when {
            currentRefund is TransactionState.Processing || request.cash || request.checkout -> null
            else -> refunds.start(request, grant)
        }

    /** Leaves a finished refund, whichever way it was sent, so the next one can start. */
    fun acknowledgeRefund() {
        refunds.acknowledge()
        checkoutRefunds.acknowledge()
    }

    /**
     * Asks again about [transaction] whose outcome is unknown, the way it was sent: the terminal's TransactionStatus, the
     * saved-card charge or Checkout refund sent again with its stored identity, or Adyen about a payment link. Anything
     * settled or gone has nothing left to check ([Recheck.Settled]).
     */
    suspend fun recheck(transaction: StoredTransaction): Recheck =
        when (transaction) {
            is StoredTransaction.Sale -> {
                val sale = sales.get(transaction.id)?.sale
                when {
                    sale == null -> Recheck.Settled
                    sale.paymentLink -> links.check(sale.id).recheck()
                    sale.savedCard -> charges.recheck(sale.id)
                    else -> payments.recheck(sale.id)
                }
            }

            is StoredTransaction.Refund -> {
                val refund = refundRecords.get(transaction.id)
                when {
                    refund == null -> Recheck.Settled
                    refund.checkoutRefund -> checkoutRefunds.recheck(refund.id)
                    else -> refunds.recheck(refund.id)
                }
            }

            is StoredTransaction.GiftCard -> {
                giftCards?.recheck(transaction.id) ?: Recheck.Settled
            }
        }

    /** The operation a screen follows: whichever is running, else one sent through the Checkout API that finished, else the terminal's. */
    private fun following(
        terminal: TransactionState,
        checkout: TransactionState,
    ): TransactionState =
        when {
            terminal is TransactionState.Processing -> terminal
            checkout != TransactionState.Idle -> checkout
            else -> terminal
        }

    /** This link answer as a status check: settled once the link exists or ended, else still unknown for why. */
    private fun LinkUpdate.recheck(): Recheck =
        when (this) {
            LinkUpdate.Settled, LinkUpdate.StillOpen -> Recheck.Settled
            is LinkUpdate.NotDone -> Recheck.StillUnknown(failure)
            is LinkUpdate.Unconfirmed -> Recheck.StillUnknown(failure)
        }
}
