package io.minimpos.app.payment

import android.database.SQLException
import io.minimpos.app.terminal.TerminalGateway
import io.minimpos.terminal.client.PaymentParams
import io.minimpos.terminal.client.RefundParams
import io.minimpos.terminal.client.TerminalClient
import io.minimpos.terminal.client.TransactionDetails
import io.minimpos.terminal.client.TransactionKind
import io.minimpos.terminal.client.TransactionOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID

/** Where the current transaction of a [TransactionLifecycle] is; there is at most one at a time. */
sealed interface TransactionState {
    /** None is running and the last one has been acknowledged. */
    data object Idle : TransactionState

    /**
     * One is running.
     *
     * @property id The stored sale or refund.
     * @property serviceId The nexo ServiceID of the request, once it is being sent; needed to cancel it.
     * @property cancelling Whether a cancel (AbortRequest) has been sent.
     */
    data class Processing(
        val id: String,
        val serviceId: String? = null,
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

/** How a transaction ended, whatever its kind; each [TransactionBook] maps it to its own stored status. */
enum class SettlementStatus {
    /** Approved (a payment) or accepted (a refund). */
    SUCCEEDED,

    /** Cancelled or aborted on the terminal ([Decline.cancelled][io.minimpos.terminal.client.Decline.cancelled]). */
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
 * @property message Why it did not succeed, for display; null when it succeeded.
 * @property details What the terminal answered; null when it did not answer.
 */
data class Settlement(
    val status: SettlementStatus,
    val message: String?,
    val details: TransactionDetails? = null,
)

/** The Terminal API request a transaction sends. */
sealed interface TerminalOperation {
    /**
     * A card payment.
     *
     * @property params What to charge.
     */
    data class Pay(
        val params: PaymentParams,
    ) : TerminalOperation

    /**
     * A referenced refund.
     *
     * @property params The payment to refund, and how much.
     */
    data class Refund(
        val params: RefundParams,
    ) : TerminalOperation
}

/**
 * How one kind of transaction ([R] is what its screen collected) is stored, for [TransactionLifecycle]. Sales and
 * refunds are the two implementations. Every function is called off the main thread and must tolerate a record that
 * no longer exists.
 */
interface TransactionBook<R> {
    /** The kind of transaction, for status checks and aborts. */
    val kind: TransactionKind

    /** Stores [request] as a new PENDING record [id], sent with [serviceId] and started at [createdAt] (epoch ms). */
    suspend fun open(
        id: String,
        request: R,
        serviceId: String,
        createdAt: Long,
    )

    /** The request to send for [request]. */
    fun operation(request: R): TerminalOperation

    /** Records that record [id] is being sent to the terminal [poiId]; nothing by default. */
    suspend fun sending(
        id: String,
        poiId: String,
    ) = Unit

    /** Stores [settlement] as the outcome of record [id]. */
    suspend fun settle(
        id: String,
        settlement: Settlement,
    )

    /** The ServiceID of record [id] when its outcome is still unknown, so it can be checked again; else null. */
    suspend fun unsettledServiceId(id: String): String?
}

/**
 * Runs transactions of one kind (payments or refunds) on an application-wide scope, so one in progress survives screen
 * changes (the Adyen payment UI takes over the display on the terminal). At most one runs at a time.
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
 * @param unknownOutcome Stored as the message of a transaction whose outcome the terminal could not confirm.
 * @param onSucceeded Called with the record ID and the request when a transaction succeeds (not when a [recheck]
 *   settles one), before the state becomes [TransactionState.Finished].
 * @param clock Stamps the records.
 * @param newId Generates record IDs.
 * @param newServiceId Generates the ServiceIDs of the requests.
 */
class TransactionLifecycle<R>(
    private val scope: CoroutineScope,
    private val gateway: TerminalGateway,
    private val book: TransactionBook<R>,
    private val unknownOutcome: String,
    private val onSucceeded: (id: String, request: R) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val newServiceId: () -> String = { TerminalClient.randomServiceId() },
) {
    private val _state = MutableStateFlow<TransactionState>(TransactionState.Idle)

    /** The current transaction; the waiting and result screens follow it. */
    val state: StateFlow<TransactionState> = _state.asStateFlow()
    private val busyServiceIds = mutableMapOf<String, String>()

    /**
     * Starts [request] in the background and returns the new record's ID straight away.
     *
     * @throws IllegalStateException if a transaction is already in progress.
     */
    fun start(request: R): String {
        check(_state.value !is TransactionState.Processing) { "A ${book.kind.name.lowercase()} is already in progress" }
        val id = newId()
        _state.value = TransactionState.Processing(id)
        scope.launch {
            try {
                run(id, request)
            } catch (e: CancellationException) {
                throw e
            } catch (
                // Whatever went wrong, the record must not be left looking as if it were still in progress.
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                try {
                    book.settle(id, Settlement(SettlementStatus.UNKNOWN, e.message))
                } catch (ignored: SQLException) {
                    // It stays PENDING, which the next start turns into UNKNOWN.
                }
                _state.value = TransactionState.Finished(id)
            }
        }
        return id
    }

    /**
     * Asks the terminal to cancel the running transaction (an AbortRequest). Does nothing before its request is being
     * sent. The outcome still arrives through the transaction itself, usually as cancelled.
     */
    fun cancel() {
        val processing = _state.value as? TransactionState.Processing ?: return
        val serviceId = processing.serviceId ?: return
        _state.value = processing.copy(cancelling = true)
        scope.launch { gateway.abort(serviceId, book.kind) }
    }

    /** For a transaction refused with ErrorCondition Busy: the ServiceID of the transaction the terminal is busy with. */
    fun busyServiceId(id: String): String? = synchronized(busyServiceIds) { busyServiceIds[id] }

    /**
     * Adyen: with Busy, the in-progress transaction can be cancelled with an AbortRequest for its ServiceID. Returns
     * whether the abort was sent; it can be sent once per refused transaction.
     */
    suspend fun abortBusyTransaction(id: String): Boolean {
        val serviceId = synchronized(busyServiceIds) { busyServiceIds.remove(id) } ?: return false
        return gateway.abort(serviceId)
    }

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next transaction can start. */
    fun acknowledge() {
        if (_state.value is TransactionState.Finished) _state.value = TransactionState.Idle
    }

    /**
     * Asks the terminal again (TransactionStatus) about record [id] whose outcome is unknown, and stores the answer.
     * Returns false when the outcome is still unknown, or the record is not unknown (or was never sent).
     */
    suspend fun recheck(id: String): Boolean {
        val serviceId = book.unsettledServiceId(id) ?: return false
        val outcome = gateway.status(serviceId, book.kind)
        if (outcome is TransactionOutcome.Unknown) return false
        book.settle(id, settle(id, outcome))
        return true
    }

    private suspend fun run(
        id: String,
        request: R,
    ) {
        val serviceId = newServiceId()
        book.open(id, request, serviceId, clock.millis())
        val onSending: suspend (String) -> Unit = { poiId ->
            book.sending(id, poiId)
            _state.update { current ->
                (current as? TransactionState.Processing)?.takeIf { it.id == id }?.copy(serviceId = serviceId) ?: current
            }
        }
        val outcome =
            when (val operation = book.operation(request)) {
                is TerminalOperation.Pay -> gateway.pay(operation.params, serviceId, onSending)
                is TerminalOperation.Refund -> gateway.refund(operation.params, serviceId, onSending)
            }
        val settlement = settle(id, outcome)
        book.settle(id, settlement)
        if (settlement.status == SettlementStatus.SUCCEEDED) onSucceeded(id, request)
        _state.value = TransactionState.Finished(id)
    }

    /** Classifies [outcome] and remembers the transaction a busy terminal names. */
    private fun settle(
        id: String,
        outcome: TransactionOutcome,
    ): Settlement =
        when (outcome) {
            is TransactionOutcome.Completed -> {
                val details = outcome.details
                val decline = details.decline
                decline?.busyServiceId?.let { synchronized(busyServiceIds) { busyServiceIds[id] = it } }
                val status =
                    when {
                        decline == null -> SettlementStatus.SUCCEEDED
                        decline.cancelled -> SettlementStatus.CANCELLED
                        else -> SettlementStatus.DECLINED
                    }
                Settlement(status, decline?.let { details.message ?: it.errorCondition }, details)
            }

            is TransactionOutcome.NotProcessed -> {
                Settlement(SettlementStatus.FAILED, outcome.reason)
            }

            is TransactionOutcome.Unknown -> {
                Settlement(SettlementStatus.UNKNOWN, unknownOutcome)
            }
        }
}
