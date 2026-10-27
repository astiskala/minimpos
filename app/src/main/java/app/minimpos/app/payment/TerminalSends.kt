package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.terminal.Attempt
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The stored record of one terminal request, as its runner keeps it, while [TerminalSends] sends it or checks it
 * again. Every function is called off the main thread and must tolerate a record that no longer exists.
 */
internal interface TerminalRecord {
    /** Records that the request is being sent to the terminal [poiId]. */
    suspend fun sending(poiId: String)

    /** Records the actual connection's non-secret [context]. */
    suspend fun recordContext(context: PaymentContext)

    /** Stores [settlement]; false only after a healthy read proves the record is absent. Storage failures throw. */
    suspend fun settle(settlement: Settlement): Boolean
}

/**
 * A stored terminal request whose outcome is unknown, as [TerminalSends.recheck] asks about it.
 *
 * @property serviceId Its original ServiceID.
 * @property kind The message category it was sent with, which the status check asks for.
 * @property context Its original connection context; null when none was recorded.
 * @property record Where the answer is stored.
 */
internal class UnresolvedRequest(
    val serviceId: String,
    val kind: TransactionKind,
    val context: PaymentContext?,
    val record: TerminalRecord,
)

/**
 * Sends stored terminal requests and checks unknown ones again, for every runner of terminal operations
 * ([TransactionLifecycle] for payments and refunds, [GiftCards] for stored-value stages): the one owner of what happens
 * between a stored PENDING request and its stored settlement, so a runner decides only what it stores and in what order.
 *
 * Manager approval is checked before anything is sent and again after setup and persistence, immediately before
 * sending. While a request is being sent its ServiceID is kept, so [cancel] can address an abort, and the runner's
 * [TransactionState.Processing] says it can be cancelled. The answer is classified once ([settlementOf]) and stored,
 * and a late answer the destination keeps is released only after a resolved settlement is durably stored. A status
 * check asks for the original message category in the original context.
 *
 * @param scope Where aborts are sent.
 * @param gateway Sends the requests.
 * @param state The runner's current operation, by the ID its screens follow.
 */
internal class TerminalSends(
    private val scope: CoroutineScope,
    private val gateway: TerminalGateway,
    private val state: MutableStateFlow<TransactionState>,
) {
    /** The ServiceID and message category of the request being sent, by the operation ID screens follow. */
    private val sending = mutableMapOf<String, Pair<String, TransactionKind>>()
    private val rechecks = Mutex()

    /** Whether an original terminal request is being checked now; setup and history maintenance wait for it. */
    val checking: Boolean get() = rechecks.isLocked

    /**
     * Sends [request] with [serviceId] for the stored [record] of the operation screens follow as [operation], when
     * [approved] holds before sending and again immediately before the request leaves, and stores how it ended.
     * Returns the settlement as stored; null when the record proved to be gone.
     */
    suspend fun send(
        operation: String,
        serviceId: String,
        request: TerminalOperation,
        record: TerminalRecord,
        approved: suspend () -> Boolean,
    ): Settlement? =
        try {
            val onSending: suspend (String) -> Unit = { poiId ->
                synchronized(sending) { sending[operation] = serviceId to request.kind }
                record.sending(poiId)
                state.update { current ->
                    (current as? TransactionState.Processing)?.takeIf { it.id == operation }?.copy(canCancel = true) ?: current
                }
            }
            val attempt =
                if (approved()) {
                    call(request, serviceId, record, approved, onSending)
                } else {
                    Attempt.NotSetUp(SetupProblem.MANAGER_APPROVAL)
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
            if (record.settle(settlement)) {
                if (settlement.status != SettlementStatus.UNKNOWN && attempt is Attempt.Made) attempt.onStored()
                settlement
            } else {
                null
            }
        } finally {
            synchronized(sending) { sending.remove(operation) }
        }

    /**
     * Asks the terminal to stop the operation running now (an AbortRequest for its request's ServiceID). Does nothing
     * before its request is being sent, or once stopping it was asked for; the outcome still arrives through the request.
     */
    @Synchronized
    fun cancel() {
        val processing = state.value as? TransactionState.Processing ?: return
        if (processing.cancelling || !processing.canCancel) return
        val (serviceId, kind) = synchronized(sending) { sending[processing.id] } ?: return
        state.value = processing.copy(cancelling = true)
        scope.launch { gateway.abort(serviceId, kind) }
    }

    /**
     * Asks the terminal to stop the payment sent with [serviceId], such as one a stored `Busy` refusal names; whether
     * the abort was sent.
     */
    suspend fun abort(serviceId: String): Boolean = gateway.abort(serviceId)

    /**
     * Asks the terminal once more about the request [unresolved] finds (null when nothing is left to check), with its
     * original ServiceID, message category and context, and stores the answer; [settled] is told a resolved settlement
     * once it is stored. Status checks run one at a time.
     */
    suspend fun recheck(
        unresolved: suspend () -> UnresolvedRequest?,
        settled: (Settlement) -> Unit = {},
    ): Recheck =
        rechecks.withLock {
            val request = unresolved() ?: return@withLock Recheck.Settled
            val attempt = gateway.status(request.serviceId, request.kind, request.context)
            val outcome =
                when (attempt) {
                    is Attempt.NotSetUp -> return@withLock Recheck.StillUnknown(Failure.NotSetUp(attempt.problem))
                    is Attempt.Made -> attempt.result
                }
            if (outcome is TransactionOutcome.Unknown) return@withLock Recheck.StillUnknown(Failure.Remote(outcome.fault))
            val settlement = settlementOf(outcome)
            if (request.record.settle(settlement)) {
                attempt.onStored()
                settled(settlement)
            }
            Recheck.Settled
        }

    private suspend fun call(
        request: TerminalOperation,
        serviceId: String,
        record: TerminalRecord,
        approved: suspend () -> Boolean,
        onSending: suspend (String) -> Unit,
    ): Attempt<TransactionOutcome> {
        val onContext: suspend (PaymentContext) -> Unit = { record.recordContext(it) }
        return when (request) {
            is TerminalOperation.Pay -> {
                gateway.pay(
                    request.params,
                    serviceId,
                    audioGuidancePoiId = request.audioGuidancePoiId,
                    expected = request.expected,
                    expectedIdentity = request.setupIdentity,
                    cashbackIdentity = request.cashbackIdentity,
                    unreferencedIdentity = request.unreferencedIdentity,
                    authorized = approved,
                    onContext = onContext,
                    onSending = onSending,
                )
            }

            is TerminalOperation.Refund -> {
                gateway.refund(request.params, serviceId, request.expected, approved, onContext, onSending)
            }

            is TerminalOperation.StoredValue -> {
                gateway.storedValue(request.params, serviceId, request.expected, approved, onContext, onSending)
            }
        }
    }
}

/** Classifies a terminal [outcome] as it is stored: the one reading of every stored terminal request's answer. */
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
