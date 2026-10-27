package app.minimpos.app.payment

import app.minimpos.app.data.db.RefundRoute
import app.minimpos.app.data.db.SaleRoute
import app.minimpos.app.data.db.route
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.RefundStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Every financial operation the app sends, as the screens and setup changes need them, whichever way it was sent: card
 * payments and refunds on the terminal ([TransactionLifecycle]), and saved-card charges and their refunds through the
 * Checkout API ([CheckoutOperations]). It routes a stored transaction's status check by how that transaction was sent
 * ([recheck]), sends a refund the way its payment was taken ([refund]) and is the one answer to whether anything is being
 * sent now ([running]). Callers never pick a runner themselves.
 *
 * Payment links, captures and active status recovery also count as [running], holding setup and retained history
 * steady until each request and its durable answer finish. Every runner counts through its own [OperationKind], so
 * running and history deletion iterate one list rather than naming each runner's flags.
 *
 * @param payments Card payments (including scanned wallets) on the terminal.
 * @param refunds Referenced refunds and cancellations on the terminal.
 * @param charges Saved-card charges through the Checkout API.
 * @param checkoutRefunds Refunds of saved-card charges through the Checkout API.
 * @param links Payment links, whose status check asks Adyen about the link.
 * @param sales Where sales are stored, read to route a sale's status check.
 * @param refundRecords Where refunds are stored, read to route a refund's status check.
 * @param giftCards Stored-value activities on the terminal, which count as running and route their own status checks.
 * @param unreferenced Unreferenced card refunds on the terminal: refunds in history, sent and checked as payments.
 * @param holds Runners that only count as running, such as captures and refund cancellations.
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
    private val unreferenced: TransactionLifecycle<UnreferencedRefundStart>? = null,
    holds: List<OperationKind> = emptyList(),
) {
    private val kinds =
        listOfNotNull(
            payments.operationKind,
            refunds.operationKind,
            charges.operationKind,
            checkoutRefunds.operationKind,
            links.operationKind,
            giftCards?.operationKind,
            unreferenced?.operationKind,
        ).plus(holds)
            .toMutableList()

    /** Every runner held now. */
    private fun kinds(): List<OperationKind> = synchronized(kinds) { kinds.toList() }

    /** Holds [kind] too: a runner made after these operations, such as a multi-leg return that sends through them. */
    internal fun include(kind: OperationKind) {
        synchronized(kinds) { kinds += kind }
    }

    /** Releases every runner's process-only requests after confirmed complete history deletion. */
    internal fun forgetAll() = kinds().forEach(OperationKind::forgetAll)

    /** Releases process-only request material after durable history deletion, routed to every runner. */
    internal fun forget(ids: Set<String>) = kinds().forEach { it.forget(ids) }

    /** The idempotency keys the operations of removed history [items] were sent with, which simulated Adyen keeps too. */
    internal fun idempotencyKeys(items: List<HistoryItem>): Set<String> =
        items
            .filterIsInstance<HistoryItem.Sale>()
            .filter { it.sale.route == SaleRoute.SAVED_CARD }
            .map { SavedCardCharges.idempotencyKey(it.id) }
            .toSet()

    /**
     * Whether a card payment, refund, saved-card charge or Checkout refund is being sent now, so the configuration it
     * is sent with must not be replaced. Token deletion and its active reconciliation, stored-value activities,
     * unreferenced refunds and their cancellations also hold credentials steady. Unfinished history alone does not count.
     */
    val running: Boolean get() = kinds().any { it.running }

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

    private val unreferencedState = unreferenced?.state ?: MutableStateFlow(TransactionState.Idle)

    /**
     * The refund (or cancellation) running or just finished, whichever way it is sent, unreferenced refunds included;
     * the refund screens follow it to its result. [currentRefund] is its value now.
     */
    val refundProgress: Flow<TransactionState> =
        combine(refunds.state, unreferencedState, checkoutRefunds.state) { referenced, unreferenced, checkout ->
            following(following(referenced, unreferenced), checkout)
        }

    /** [refundProgress] now, for a screen's first frame. */
    val currentRefund: TransactionState
        get() = following(following(refunds.state.value, unreferencedState.value), checkoutRefunds.state.value)

    /**
     * Starts the unreferenced card refund [request] on the terminal, holding the Manager approval held now. Returns the
     * refund's ID; null, with nothing stored or sent, while any refund is running or none can be sent here.
     */
    fun unreferencedRefund(request: UnreferencedRefundStart): String? =
        if (currentRefund is TransactionState.Processing) null else unreferenced?.start(request)

    /**
     * Starts [request] the way its payment was taken: a saved-card charge's refund through the Checkout API, others on
     * the terminal. Returns the refund's ID; null, with nothing stored or sent, while a refund of either kind is running.
     */
    fun refund(request: RefundStart): String? =
        if (currentRefund is TransactionState.Processing) {
            null
        } else {
            when (request.route) {
                RefundRoute.CHECKOUT -> checkoutRefunds.start(request)
                RefundRoute.TERMINAL -> refunds.start(request)
                RefundRoute.CASH, RefundRoute.UNREFERENCED -> null
            }
        }

    /**
     * Starts one card leg [request] of a multi-leg return on the terminal with the [grant] the whole return started
     * with; null while a refund is running, or for a cash or Checkout leg, which are not terminal reversals.
     */
    internal fun refundLeg(
        request: RefundStart,
        grant: ManagerApproval.Grant,
    ): String? =
        if (currentRefund is TransactionState.Processing || request.route != RefundRoute.TERMINAL) null else refunds.start(request, grant)

    /** Leaves a finished refund, whichever way it was sent, so the next one can start. */
    fun acknowledgeRefund() {
        refunds.acknowledge()
        unreferenced?.acknowledge()
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
                when (sales.get(transaction.id)?.sale?.route) {
                    null -> Recheck.Settled
                    SaleRoute.PAYMENT_LINK -> links.check(transaction.id).recheck()
                    SaleRoute.SAVED_CARD -> charges.recheck(transaction.id)
                    SaleRoute.TERMINAL -> payments.recheck(transaction.id)
                }
            }

            is StoredTransaction.Refund -> {
                when (refundRecords.get(transaction.id)?.route) {
                    null, RefundRoute.CASH -> Recheck.Settled
                    RefundRoute.CHECKOUT -> checkoutRefunds.recheck(transaction.id)
                    RefundRoute.UNREFERENCED -> unreferenced?.recheck(transaction.id) ?: Recheck.Settled
                    RefundRoute.TERMINAL -> refunds.recheck(transaction.id)
                }
            }

            is StoredTransaction.GiftCard -> {
                giftCards?.recheck(transaction.id) ?: Recheck.Settled
            }
        }

    /** The operation a screen follows: [first] while it runs, else [second] unless idle, else [first]. */
    private fun following(
        first: TransactionState,
        second: TransactionState,
    ): TransactionState =
        when {
            first is TransactionState.Processing -> first
            second != TransactionState.Idle -> second
            else -> first
        }

    /** This link answer as a status check: settled once the link exists or ended, else still unknown for why. */
    private fun LinkUpdate.recheck(): Recheck =
        when (this) {
            LinkUpdate.Settled, LinkUpdate.StillOpen -> Recheck.Settled
            is LinkUpdate.NotDone -> Recheck.StillUnknown(failure)
            is LinkUpdate.Unconfirmed -> Recheck.StillUnknown(failure)
        }
}
