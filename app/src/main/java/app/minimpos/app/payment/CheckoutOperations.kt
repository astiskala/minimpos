package app.minimpos.app.payment

import android.database.SQLException
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.payment.PaymentContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid

/** What sending a stored Checkout operation once found, as its [CheckoutBook] stored it. */
sealed interface CheckoutAnswer {
    /** Adyen took it: a saved card was charged, or a refund was received. */
    data object Taken : CheckoutAnswer

    /** It ended without taking effect: refused, or not taken in a way that cannot have taken effect. */
    data object NotTaken : CheckoutAnswer

    /**
     * It may have taken effect; the record is unknown until it is sent again with the same idempotency key.
     *
     * @property failure What prevented the confirmation.
     */
    data class Unconfirmed(
        val failure: Failure,
    ) : CheckoutAnswer
}

/**
 * A stored Checkout operation that is still to be sent, see [CheckoutBook.unsent].
 *
 * @property pending Whether it was never sent (PENDING); false for one that was sent and whose outcome is unknown.
 * @property createdAt When it was opened, in epoch milliseconds, which decides whether it may be sent again (see
 *   [CheckoutOperations.RECHECK_WINDOW]).
 * @property context The original payment context the current API target must match; null when none was recorded.
 */
data class CheckoutRecord(
    val pending: Boolean,
    val createdAt: Long,
    val context: PaymentContext?,
)

/**
 * How one kind of Checkout API operation is stored and sent, for [CheckoutOperations]: saved-card charges
 * ([SavedCardCharges]) and their refunds ([CheckoutRefunds]). Each send is made only from what the record stored and
 * with the record's own idempotency key, so sending it again cannot take effect twice. Every function runs off the main
 * thread and must tolerate a record that no longer exists.
 *
 * @param R What the screen collected for one operation.
 * @param C The Checkout adapter the operation is sent with.
 */
interface CheckoutBook<R, C> {
    /**
     * Stores [request] as the new PENDING record [id], opened at [createdAt] (epoch ms) while [current] is the current
     * API target's payment context (null for a fixed fake target).
     */
    suspend fun open(
        id: String,
        request: R,
        createdAt: Long,
        current: PaymentContext?,
    )

    /** Record [id] while it is PENDING or UNKNOWN; null for anything else, which has nothing left to send. */
    suspend fun unsent(id: String): CheckoutRecord?

    /** What record [id] lacks before it can be sent anywhere, such as the PSP reference a refund needs; null when nothing. */
    suspend fun missing(id: String): SetupProblem? = null

    /** This kind's adapter at [target], for a record in its original [context]. */
    fun access(
        target: ApiTarget,
        context: PaymentContext?,
    ): ApiAccess<C>

    /** Sends record [id] once with [client] and stores Adyen's answer, received at [now] (epoch ms). */
    suspend fun send(
        id: String,
        client: C,
        now: Long,
    ): CheckoutAnswer

    /** Stores that the never-sent record [id] was not sent, because [problem] must be resolved first. */
    suspend fun notSent(
        id: String,
        problem: SetupProblem,
    )

    /** Stores record [id] as unknown after the device failed with [fault] while handling it. */
    suspend fun interrupted(
        id: String,
        fault: DeviceFault,
    )
}

/**
 * Runs Checkout API operations of one kind on an application-wide scope, the Checkout side of [FinancialOperations]:
 * starting one ([start]) and sending one whose outcome is unknown again ([recheck]). At most one starts at a time;
 * sends and checks run one at a time.
 *
 * Each operation is stored PENDING before Adyen is called and is sent from what was stored with its idempotency key, so
 * a repeat returns Adyen's first answer instead of taking effect twice. Adyen keeps idempotency keys for at least
 * [RECHECK_WINDOW]; an older unknown operation is not sent again and is checked in the Customer Area. Manager approval
 * is checked again under the operation lock immediately before anything is sent. A never-sent operation that cannot be
 * sent (approval, missing facts or no Checkout API) is stored as not done; an unknown one stays unknown; and one whose
 * original payment context no longer matches the credentials is never changed.
 *
 * @param R What the screen collected for one operation.
 * @param C The Checkout adapter it is sent with.
 * @param scope Where operations run, so one survives leaving the screen.
 * @param book How the records are stored and sent.
 * @param target Where Checkout requests go now: [app.minimpos.app.terminal.AdyenApi.target] in the app.
 * @param onSucceeded Called once with the record ID and its original request when Adyen took an operation started in
 *   this process, including on a [recheck].
 * @param clock Stamps the records and decides whether an unknown one may be sent again.
 * @param newId Generates record IDs.
 * @param permits Whether Manager approval allows the operation now, checked under the operation lock.
 * @param mutex Serializes sends and rechecks; shared when a caller also serializes follow-up operations.
 * @param recheckWindow Maximum age for resending an unknown operation; null when idempotency retries do not expire locally.
 */
class CheckoutOperations<R, C>(
    scope: CoroutineScope,
    private val book: CheckoutBook<R, C>,
    private val target: suspend () -> ApiTarget,
    private val onSucceeded: (id: String, request: R) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { Uuid.random().toString() },
    private val permits: suspend () -> Boolean = { true },
    private val mutex: Mutex = Mutex(),
    private val recheckWindow: Duration? = RECHECK_WINDOW,
) {
    private val runner = OperationRunner<R>(scope)

    /** The operation running now, which its screen follows to the result. */
    val state: StateFlow<TransactionState> = runner.state

    /**
     * Stores and sends [request] in the background and returns the new record's ID straight away; null, with nothing
     * stored or sent, while another operation of this kind is running (such as a second tap on the same action).
     */
    fun start(request: R): String? =
        runner.start(request, newId, ::interrupted) { id ->
            book.open(id, request, clock.millis(), target().context)
            mutex.withLock { book.unsent(id)?.takeIf { it.pending }?.let { send(id, it) } }
        }

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next operation can start. */
    fun acknowledge() = runner.acknowledge()

    /**
     * Sends record [id], whose outcome is unknown, again with its stored request and idempotency key, and stores Adyen's
     * answer. Anything else has nothing left to check ([Recheck.Settled]); nothing changes while approval, setup or the
     * original payment context is missing, or once it is older than [recheckWindow].
     */
    suspend fun recheck(id: String): Recheck = mutex.withLock { recheckUnlocked(id) }

    /** Runs [recheck] while the caller already holds [mutex]. */
    internal suspend fun recheckUnlocked(id: String): Recheck {
        val record = book.unsent(id)?.takeUnless { it.pending } ?: return Recheck.Settled
        if (recheckWindow != null && Duration.ofMillis(clock.millis() - record.createdAt) > recheckWindow) {
            return Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD))
        }
        return send(id, record)?.let(Recheck::StillUnknown) ?: Recheck.Settled
    }

    /** Sends [record] [id] and stores the answer; returns why nothing (or nothing certain) is known yet. */
    private suspend fun send(
        id: String,
        record: CheckoutRecord,
    ): Failure? {
        val problem = if (permits()) book.missing(id) else SetupProblem.MANAGER_APPROVAL
        if (problem != null) return notSent(id, record.pending, problem)
        val client =
            when (val access = book.access(target(), record.context)) {
                is ApiAccess.Ready -> access.client

                // Nothing was sent; only an operation never sent before is known not to have happened, and a different
                // payment context must change nothing.
                is ApiAccess.Blocked -> return notSent(id, record.pending && access is ApiAccess.Unavailable, access.problem)
            }
        return when (val answer = book.send(id, client, clock.millis())) {
            is CheckoutAnswer.Unconfirmed -> {
                answer.failure
            }

            CheckoutAnswer.Taken -> {
                runner.resolved(id)?.let { onSucceeded(id, it) }
                null
            }

            CheckoutAnswer.NotTaken -> {
                runner.resolved(id)
                null
            }
        }
    }

    /** Nothing was sent because of [problem]: a never-sent operation is stored as not done when [record]; else unchanged. */
    private suspend fun notSent(
        id: String,
        record: Boolean,
        problem: SetupProblem,
    ): Failure {
        if (record) {
            book.notSent(id, problem)
            runner.resolved(id)
        }
        return Failure.NotSetUp(problem)
    }

    private suspend fun interrupted(
        id: String,
        cause: Exception,
    ) = book.interrupted(id, if (cause is SQLException || cause is IllegalStateException) DeviceFault.DATABASE else DeviceFault.UNEXPECTED)

    /** Values shared with the operations' kinds and tests. */
    companion object {
        /** How long after an operation was opened its unknown outcome may be sent again: Adyen keeps keys at least this long. */
        val RECHECK_WINDOW: Duration = Duration.ofDays(7)
    }
}
