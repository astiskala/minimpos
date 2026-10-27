package app.minimpos.app.payment

import android.database.SQLException
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.transport.Fault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
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
 * Adyen's answer to one send of a stored Checkout operation, not stored yet, see [CheckoutBook.send].
 *
 * @property fault Why the send failed; null when Adyen answered or nothing was sent.
 * @property store Stores the answer and says what it established, given what [fault] establishes about the operation
 *   ([FailedSend.NOT_DONE] or [FailedSend.UNCONFIRMED]; null without a fault). Never called when the answer must leave
 *   an unresolved operation unchanged ([FailedSend.UNCHANGED]).
 */
class CheckoutReply(
    val fault: Fault? = null,
    val store: suspend (failed: FailedSend?) -> CheckoutAnswer,
)

/**
 * A stored Checkout operation that is still to be sent, see [CheckoutBook.unsent].
 *
 * @property pending Whether it was never sent (PENDING); false for one that was sent and whose outcome is unknown.
 * @property createdAt When it was opened, in epoch milliseconds, which decides whether it may be sent again (see
 *   [OperationIdentity.replayProblem]).
 * @property context The original payment context the current API target must match; null when none was recorded.
 * @property reason Last unresolved outcome, including Adyen’s persisted retry permission; null when unavailable.
 */
data class CheckoutRecord(
    val pending: Boolean,
    val createdAt: Long,
    val context: PaymentContext?,
    val reason: StoredReason? = null,
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
    /** What [request] charges or refunds, which its waiting screen shows. */
    fun amount(request: R): OperationAmount

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

    /**
     * Sends record [id] once with [client] and returns Adyen's answer, received at [now] (epoch ms), to be stored; what
     * a failed resend may change is [CheckoutOperations]' decision, not the book's.
     */
    suspend fun send(
        id: String,
        client: C,
        now: Long,
    ): CheckoutReply

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
 * a repeat returns Adyen's first answer instead of taking effect twice. Its [OperationIdentity] decides when an unknown
 * operation may be sent again (an older one is checked in the Customer Area) and keeps a resend whose failure cannot
 * have taken effect from changing what is stored. Manager approval
 * of kinds that need it is taken when an operation starts and checked again under the operation lock immediately
 * before anything is sent; resending an unknown one checks the approval held now. A never-sent operation that cannot be
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
 * @param approval Manager approval of this kind; [ManagerApproval.NOT_REQUIRED] for a kind sent without it.
 * @param mutex Serializes sends and rechecks; shared when a caller also serializes follow-up operations.
 */
class CheckoutOperations<R, C>(
    scope: CoroutineScope,
    private val book: CheckoutBook<R, C>,
    private val target: suspend () -> ApiTarget,
    private val onSucceeded: (id: String, request: R) -> Unit = { _, _ -> },
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { Uuid.random().toString() },
    private val approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
    private val mutex: Mutex = Mutex(),
) {
    private val runner = OperationRunner<R>(scope, onTerminal = false, amountOf = book::amount)

    /** The operation running now, which its screen follows to the result. */
    val state: StateFlow<TransactionState> = runner.state

    /** These operations as [FinancialOperations] holds them: busy while one runs or a stored request is sent again. */
    internal val operationKind =
        OperationKind({ mutex.isLocked || runner.state.value is TransactionState.Processing }, runner::forget, runner::forgetAll)

    /** Releases process-only requests after an explicitly confirmed complete history purge committed. */
    internal fun forgetAll() = runner.forgetAll()

    /** Releases process-only requests for records that history has already durably deleted. */
    internal fun forget(ids: Set<String>) = runner.forget(ids)

    /**
     * Stores and sends [request] in the background and returns the new record's ID straight away; null, with nothing
     * stored or sent, while another operation of this kind is running (such as a second tap on the same action). Call
     * it when the operator starts the operation: it keeps the Manager approval held now.
     */
    fun start(request: R): String? {
        val grant = approval.grant()
        return runner.start(request, newId, ::interrupted) { id ->
            book.open(id, request, clock.millis(), target().context)
            mutex.withLock {
                val pending = book.unsent(id)?.takeIf { it.pending }
                if (pending == null) runner.resolved(id) else send(id, pending, grant)
            }
        }
    }

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next operation can start. */
    fun acknowledge() = runner.acknowledge()

    /**
     * Sends record [id], whose outcome is unknown, again with its stored request and idempotency key, and stores Adyen's
     * answer. Anything else has nothing left to check ([Recheck.Settled]); nothing changes while approval, setup or the
     * original payment context is missing, or once [OperationIdentity.replayProblem] forbids sending it again.
     */
    suspend fun recheck(id: String): Recheck = mutex.withLock { recheckUnlocked(id) }

    /** Runs [recheck] while the caller already holds [mutex]. */
    internal suspend fun recheckUnlocked(id: String): Recheck {
        val record = book.unsent(id)?.takeUnless { it.pending } ?: return Recheck.Settled
        OperationIdentity.replayProblem(record.createdAt, clock.millis(), record.reason)?.let {
            return Recheck.StillUnknown(Failure.NotSetUp(it))
        }
        return send(id, record, grant = null)?.let(Recheck::StillUnknown) ?: Recheck.Settled
    }

    /**
     * Sends [record] [id] and stores the answer; returns why nothing (or nothing certain) is known yet. [grant] is the
     * approval taken when it started in this process; null for a resend, which needs the approval held now.
     */
    private suspend fun send(
        id: String,
        record: CheckoutRecord,
        grant: ManagerApproval.Grant?,
    ): Failure? {
        val approved = if (grant != null) approval.allows(grant) else approval.allowsNow()
        val problem = if (approved) book.missing(id) else SetupProblem.MANAGER_APPROVAL
        if (problem != null) return notSent(id, record.pending, problem)
        val client =
            when (val access = book.access(target(), record.context)) {
                is ApiAccess.Ready -> access.client

                // A fresh pending request is known not sent, including after its selected context changed. An unknown
                // operation may already have happened, so missing setup or a different context must leave it unchanged.
                is ApiAccess.Blocked -> return notSent(id, record.pending, access.problem)
            }
        if (!(if (grant != null) approval.allows(grant) else approval.allowsNow())) {
            return notSent(id, record.pending, SetupProblem.MANAGER_APPROVAL)
        }
        val reply = book.send(id, client, clock.millis())
        val fault = reply.fault
        val failed = fault?.let { OperationIdentity.failed(!record.pending, it) }
        val answer =
            if (fault != null && failed == FailedSend.UNCHANGED) {
                CheckoutAnswer.Unconfirmed(Failure.Remote(fault))
            } else {
                reply.store(failed)
            }
        return when (answer) {
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
    ) {
        book.interrupted(id, if (cause is SQLException || cause is IllegalStateException) DeviceFault.DATABASE else DeviceFault.UNEXPECTED)
        if (book.unsent(id) == null) runner.resolved(id)
    }
}
