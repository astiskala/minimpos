package app.minimpos.app.payment

import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.terminal.Attempt
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RefundParams
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TransactionDetails
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import kotlin.uuid.Uuid

/** Where the current transaction of a [TransactionLifecycle] is; there is at most one at a time. */
sealed interface TransactionState {
    /** None is running and the last one has been acknowledged. */
    data object Idle : TransactionState

    /**
     * One is running.
     *
     * @property id The stored sale or refund.
     * @property amount What it charges or refunds, as requested: for a payment of a partly paid sale, the balance.
     * @property onTerminal Whether the shopper follows it on a terminal; false for one the app sends to Adyen itself.
     * @property canCancel Whether the terminal can be asked to stop it, which it can once its request is being sent.
     * @property cancelling Whether asking it to stop was already requested (an AbortRequest was sent).
     */
    data class Processing(
        val id: String,
        val amount: OperationAmount,
        val onTerminal: Boolean,
        val canCancel: Boolean = false,
        val cancelling: Boolean = false,
    ) : TransactionState

    /**
     * It ended, whatever the outcome; the stored record holds it. Stays until [TransactionLifecycle.acknowledge].
     *
     * @property id The stored sale or refund.
     */
    data class Finished(
        val id: String,
    ) : TransactionState
}

/**
 * What a financial operation charges or refunds, as its request asks for it.
 *
 * @property amountMinor In minor units of [currency].
 * @property currency ISO 4217 code.
 */
data class OperationAmount(
    val amountMinor: Long,
    val currency: String,
)

/** What checking the outcome of an unconfirmed transaction again found, see [TransactionLifecycle.recheck]. */
sealed interface Recheck {
    /** The outcome is known now and stored (or there was nothing left to check). */
    data object Settled : Recheck

    /**
     * The outcome is still unknown.
     *
     * @property failure Why: the terminal could not be asked (setup, original context), did not answer, has no late
     *   reply (the Payments app), or reports the transaction as still in progress.
     */
    data class StillUnknown(
        val failure: Failure,
    ) : Recheck
}

/** How a transaction ended, whatever its kind; each [TransactionBook] maps it to its own stored status. */
enum class SettlementStatus {
    /** Approved (a payment) or accepted (a refund). */
    SUCCEEDED,

    /** Cancelled or aborted on the terminal ([Decline.cancelled][app.minimpos.terminal.client.Decline.cancelled]). */
    CANCELLED,

    /** Refused for another reason, such as `Refusal` or `Busy`. */
    DECLINED,

    /** It never took effect, so nothing was charged or refunded (for example the terminal is not set up). */
    FAILED,

    /** The outcome could not be established; it can be checked again with [TransactionLifecycle.recheck]. */
    UNKNOWN,
}

/**
 * The outcome of a transaction as it is stored.
 *
 * @property status How it ended.
 * @property message What the terminal said when it did not approve, verbatim; null when it succeeded or said nothing.
 * @property details What the terminal answered; null when it did not answer.
 * @property reason Why it did not succeed when there is no answer to tell (not set up, a fault, outcome unconfirmed),
 *   which the screens word; null otherwise.
 */
data class Settlement(
    val status: SettlementStatus,
    val message: String?,
    val details: TransactionDetails? = null,
    val reason: StoredReason? = null,
)

/** The Terminal API request a transaction sends. */
sealed interface TerminalOperation {
    /** What it charges or refunds, which its waiting screen shows. */
    val amount: OperationAmount

    /** Whether it moves money only with Manager approval, see [ManagerApproval]. */
    val needsApproval: Boolean

    /**
     * A card payment. MOTO and MKE payments need Manager approval; ordinary card entry does not.
     *
     * @property params What to charge.
     * @property audioGuidancePoiId Confirmed audio-guidance terminal; null for ordinary payments.
     * @property expected Checked destination for scanned or Mexican-routed payments; null for ordinary collection.
     * @property setupIdentity Exact checked store/scan setup fingerprint; null for ordinary collection.
     * @property cashbackIdentity Non-secret configuration identity confirmed for a cash request; null for ordinary collection.
     */
    data class Pay(
        val params: PaymentParams,
        val audioGuidancePoiId: String? = null,
        val expected: PaymentContext? = null,
        val setupIdentity: String? = null,
        val cashbackIdentity: String? = null,
    ) : TerminalOperation {
        override val amount: OperationAmount get() = OperationAmount(params.amountMinor, params.currency)
        override val needsApproval: Boolean get() = params.cashBackMinor != null || params.cardEntryOverride.needsManagerApproval
    }

    /**
     * A referenced refund, which always needs Manager approval.
     *
     * @property params The payment to refund, and how much.
     * @property amount The amount refunded, the whole original amount for a full one.
     */
    data class Refund(
        val params: RefundParams,
        override val amount: OperationAmount,
    ) : TerminalOperation {
        override val needsApproval: Boolean get() = true
    }
}

/**
 * How one kind of transaction ([R] is what its screen collected) is stored, for [TransactionLifecycle]. Sales and
 * refunds are the two implementations. Every function is called off the main thread and must tolerate a record that
 * no longer exists.
 */
interface TransactionBook<R> {
    /** The kind of transaction, for status checks and aborts. */
    val kind: TransactionKind

    /** Existing record ID when [request] adds a tender to an already stored sale; null for a new record. */
    fun existingId(request: R): String? = null

    /**
     * Stores [request] as [id] at [createdAt] (epoch milliseconds) with its original [serviceId] before sending.
     * False retains a known-not-sent refusal after a stale balance review.
     */
    suspend fun open(
        id: String,
        request: R,
        serviceId: String,
        createdAt: Long,
    ): Boolean

    /** The request to send for [request]: pure, so it also says what the waiting screen shows and whether it needs approval. */
    fun operation(request: R): TerminalOperation

    /** Erases transient request material after every attempt, including persistence failures; recovery facts remain intact. */
    fun release(request: R) = Unit

    /** Original destination required for a locally referenced refund; null for new payments or reviewed foreign receipts. */
    fun expectedContext(request: R): PaymentContext? = null

    /** Records that record [id] is being sent to the terminal [poiId]; nothing by default. */
    suspend fun sending(
        id: String,
        poiId: String,
    ) = Unit

    /** Stores [settlement] for [id]; false only after a healthy read proves the record is absent. Storage failures throw. */
    suspend fun settle(
        id: String,
        settlement: Settlement,
    ): Boolean

    /** The ServiceID of record [id] when its outcome is still unknown, so it can be checked again; else null. */
    suspend fun unsettledServiceId(id: String): String?

    /**
     * Sends [abort] for the ServiceID of the transaction a stored `Busy` refusal named for record [id], and once it was
     * sent records that the refusal names none, so it is not offered again. Returns whether it was sent: false without
     * one (and for refunds, whose screens offer no abort).
     */
    suspend fun abortBusy(
        id: String,
        abort: suspend (serviceId: String) -> Boolean,
    ): Boolean = false

    /** Records the actual connection's non-secret context for [id]. */
    suspend fun recordContext(
        id: String,
        context: PaymentContext,
    ) = Unit

    /** Original connection context for recovery; null when no request was sent. */
    suspend fun context(id: String): PaymentContext? = null
}

/**
 * Runs transactions of one kind (payments or refunds) on an application-wide scope, so one in progress survives screen
 * changes (the Adyen payment UI takes over the display on the terminal). At most one runs at a time. It is the terminal
 * side of [FinancialOperations], which callers ask to check an unknown outcome again; the single-flight and safety-net
 * skeleton is shared with [CheckoutOperations].
 *
 * Every transaction is stored as PENDING before the terminal is called and always ends in a final status, even if
 * something unexpected fails, so an interrupted one can be traced. Terminal outcomes are classified in one place (see
 * [SettlementStatus]); an unknown outcome can be checked again later with [recheck]. [onSucceeded] is told once per
 * transaction that succeeds while the app runs, for the automatic receipt and to clear what the payment was rung up
 * from.
 *
 * @param R What the screen collected for one transaction, as the [book] stores it.
 * @param scope Where transactions run.
 * @param gateway Sends the requests.
 * @param book How the records are stored.
 * @param onSucceeded Called once with the record ID and its original request when a transaction succeeds, including
 *   a [recheck] of one started in this process; an immediate success is reported before [TransactionState.Finished].
 * @param clock Stamps the records.
 * @param newId Generates record IDs.
 * @param newServiceId Generates the ServiceIDs of the requests.
 * @param approval Manager approval of the operations that need it ([TerminalOperation.needsApproval]): taken when one
 *   starts and checked again immediately before sending.
 */
class TransactionLifecycle<R>(
    private val scope: CoroutineScope,
    private val gateway: TerminalGateway,
    private val book: TransactionBook<R>,
    private val onSucceeded: (id: String, request: R) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { Uuid.random().toString() },
    private val newServiceId: () -> String = { TerminalClient.randomServiceId() },
    private val approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
) {
    private val runner = OperationRunner<R>(scope, onTerminal = true) { book.operation(it).amount }

    /** The current transaction; the waiting and result screens follow it. */
    val state: StateFlow<TransactionState> = runner.state

    /** The ServiceID of the running transaction, once its request is being sent; how [cancel] addresses its abort. */
    private val serviceIds = mutableMapOf<String, String>()
    private val rechecks = Mutex()

    /** Releases process-only requests after an explicitly confirmed complete history purge committed. */
    internal fun forgetAll() = runner.forgetAll()

    /** Releases process-only requests for records that history has already durably deleted. */
    internal fun forget(ids: Set<String>) = runner.forget(ids)

    /**
     * Starts [request] in the background and returns the new record's ID straight away; null, with nothing stored or
     * sent, while a transaction is already in progress (such as a second tap on the same action). Call it when the
     * operator starts the transaction: it keeps the Manager approval held now.
     */
    fun start(request: R): String? = start(request, approval.grant())

    /**
     * Starts [request] like [start], holding [grant], which a multi-leg operation took when the operator started it, so
     * each of its later legs keeps the approval the whole operation was given.
     */
    fun start(
        request: R,
        grant: ManagerApproval.Grant,
    ): String? =
        runner.start(
            request,
            id = { book.existingId(request) ?: newId() },
            interrupted = { id, _ ->
                // The request may already have been sent, so the outcome is unconfirmed rather than failed.
                val unexpected = Failure.Device(DeviceFault.UNEXPECTED)
                stored(id, Settlement(SettlementStatus.UNKNOWN, null, reason = StoredReason.Unconfirmed(unexpected)))
            },
            release = { book.release(request) },
        ) { id ->
            try {
                run(id, request, grant)
            } finally {
                synchronized(serviceIds) { serviceIds.remove(id) }
            }
        }

    /**
     * Asks the terminal to cancel the running transaction (an AbortRequest). Does nothing before its request is being
     * sent, or once cancelling it was asked for. The outcome still arrives through the transaction itself, usually as
     * cancelled.
     */
    @Synchronized
    fun cancel() {
        val processing = runner.state.value as? TransactionState.Processing ?: return
        if (processing.cancelling || !processing.canCancel) return
        val serviceId = synchronized(serviceIds) { serviceIds[processing.id] } ?: return
        runner.state.value = processing.copy(cancelling = true)
        scope.launch { gateway.abort(serviceId, book.kind) }
    }

    /**
     * Adyen: with Busy, the in-progress transaction can be cancelled with an AbortRequest for its ServiceID, which the
     * stored refusal names and the record keeps until the abort was sent. Returns whether the abort was sent; it can
     * be sent once per refused transaction.
     */
    suspend fun abortBusyTransaction(id: String): Boolean = book.abortBusy(id) { gateway.abort(it) }

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next transaction can start. */
    fun acknowledge() = runner.acknowledge()

    /**
     * Asks the terminal again (TransactionStatus) about record [id] whose outcome is unknown, and stores the answer.
     * A record whose outcome is not unknown has nothing left to check ([Recheck.Settled]).
     */
    suspend fun recheck(id: String): Recheck =
        rechecks.withLock {
            val serviceId = book.unsettledServiceId(id) ?: return@withLock Recheck.Settled
            val attempt = gateway.status(serviceId, book.kind, book.context(id))
            val outcome =
                when (attempt) {
                    is Attempt.NotSetUp -> return@withLock Recheck.StillUnknown(Failure.NotSetUp(attempt.problem))
                    is Attempt.Made -> attempt.result
                }
            if (outcome is TransactionOutcome.Unknown) return@withLock Recheck.StillUnknown(Failure.Remote(outcome.fault))
            val settlement = settlementOf(outcome)
            if (!stored(id, settlement)) return@withLock Recheck.Settled
            attempt.onStored()
            complete(id, settlement)
            Recheck.Settled
        }

    private suspend fun allowed(
        operation: TerminalOperation,
        grant: ManagerApproval.Grant,
    ): Boolean =
        when {
            operation is TerminalOperation.Pay && operation.params.cashBackMinor != null -> approval.allowsConfigured(grant)
            operation.needsApproval -> approval.allows(grant)
            else -> true
        }

    private suspend fun run(
        id: String,
        request: R,
        grant: ManagerApproval.Grant,
    ) {
        val serviceId = newServiceId()
        if (!book.open(id, request, serviceId, clock.millis())) {
            runner.resolved(id)
            return
        }
        val onSending: suspend (String) -> Unit = { poiId ->
            synchronized(serviceIds) { serviceIds[id] = serviceId }
            book.sending(id, poiId)
            runner.state.update { current ->
                (current as? TransactionState.Processing)?.takeIf { it.id == id }?.copy(canCancel = true) ?: current
            }
        }
        val operation = book.operation(request)
        val attempt =
            if (!allowed(operation, grant)) {
                Attempt.NotSetUp(SetupProblem.MANAGER_APPROVAL)
            } else {
                when (operation) {
                    is TerminalOperation.Pay -> {
                        gateway.pay(
                            operation.params,
                            serviceId,
                            audioGuidancePoiId = operation.audioGuidancePoiId,
                            expected = operation.expected,
                            expectedIdentity = operation.setupIdentity,
                            cashbackIdentity = operation.cashbackIdentity,
                            onSending = onSending,
                            onContext = { book.recordContext(id, it) },
                        )
                    }

                    is TerminalOperation.Refund -> {
                        gateway.refund(operation.params, serviceId, onSending = onSending, onContext = {
                            book.recordContext(id, it)
                        }, expected = book.expectedContext(request))
                    }
                }
            }
        val settlement =
            when (attempt) {
                is Attempt.Made -> {
                    settlementOf(attempt.result)
                }

                is Attempt.NotSetUp -> {
                    Settlement(
                        SettlementStatus.FAILED,
                        null,
                        reason = StoredReason.NotDone(Failure.NotSetUp(attempt.problem)),
                    )
                }
            }
        if (!stored(id, settlement)) return
        if (settlement.status != SettlementStatus.UNKNOWN && attempt is Attempt.Made) attempt.onStored()
        complete(id, settlement)
    }

    private fun complete(
        id: String,
        settlement: Settlement,
    ) {
        if (settlement.status == SettlementStatus.UNKNOWN) return
        val request = runner.resolved(id) ?: return
        if (settlement.status == SettlementStatus.SUCCEEDED) onSucceeded(id, request)
    }

    /** Stores [settlement] for [id]; when the record is gone, releases the process-only request and returns false. */
    private suspend fun stored(
        id: String,
        settlement: Settlement,
    ): Boolean = book.settle(id, settlement).also { if (!it) runner.resolved(id) }
}

/** Classifies a terminal [outcome] as it is stored: one reading shared by every runner of terminal operations. */
internal fun settlementOf(outcome: TransactionOutcome): Settlement =
    when (outcome) {
        is TransactionOutcome.Completed -> {
            val details = outcome.details
            val decline = details.decline
            val status =
                when {
                    decline == null -> SettlementStatus.SUCCEEDED
                    decline.cancelled -> SettlementStatus.CANCELLED
                    else -> SettlementStatus.DECLINED
                }
            // A decline is worded from its stored ErrorCondition; only the terminal's own text is kept as its message.
            Settlement(status, details.message?.text?.takeIf { decline != null }, details)
        }

        is TransactionOutcome.NotProcessed -> {
            Settlement(SettlementStatus.FAILED, null, reason = StoredReason.NotDone(Failure.Remote(outcome.fault)))
        }

        is TransactionOutcome.Unknown -> {
            Settlement(SettlementStatus.UNKNOWN, null, reason = StoredReason.Unconfirmed(Failure.Remote(outcome.fault)))
        }
    }
