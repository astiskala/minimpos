package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.terminal.transport.Fault
import java.time.Duration

/** What a send of an identified operation that failed with a [Fault] establishes, see [OperationIdentity.send]. */
enum class FailedSend {
    /** It cannot have taken effect and no earlier send's outcome is unknown: the operation was not done. */
    NOT_DONE,

    /** It may have taken effect: the operation stays unknown until it is sent again with the same identity. */
    UNCONFIRMED,

    /**
     * An earlier send's outcome is unknown and this failure cannot have taken effect, so it says nothing about the earlier
     * one: the stored operation stays as it was, still unconfirmed.
     */
    UNCHANGED,
}

/**
 * One stored, identified Adyen operation as [OperationIdentity.send] sends it once: what its identity says about earlier
 * sends, and the kind's own approval, adapter, persistence and exchange. Saved-card charges, Checkout refunds, link
 * creation and expiry, captures, adjustments and refund cancellations each supply one; none orders the steps itself.
 *
 * @param C The adapter it is sent with.
 * @param A Adyen's answer to one send.
 */
internal interface IdentifiedSend<C, A> {
    /** Whether an earlier send's outcome is unknown, so a failure that cannot have taken effect proves nothing. */
    val unresolved: Boolean

    /**
     * Whether this identity was sent before, so it may be sent again only inside [OperationIdentity.REPLAY_WINDOW] and
     * with Adyen's retry permission; by default while it is [unresolved].
     */
    val resend: Boolean get() = unresolved

    /** When this identity was first sent, in epoch milliseconds; null when unknown. Read only for a [resend]. */
    val firstSentAt: Long?

    /** The last unresolved answer, with Adyen's persisted retry permission; null when none. Read only for a [resend]. */
    val reason: StoredReason?

    /** Whether Manager approval allows sending; asked before the adapter and again immediately before sending. */
    suspend fun approved(): Boolean

    /** What the record lacks before it can be sent anywhere, such as the PSP reference a refund needs; null when nothing. */
    suspend fun missing(): SetupProblem? = null

    /** The adapter for the record's original payment context at the current target. */
    suspend fun access(): ApiAccess<C>

    /** Persists that it is being sent, before it can take effect; false, sending nothing, when the record no longer allows it. */
    suspend fun sending(): Boolean = true

    /** Sends it once with [client] and its stored identity; a failed exchange is an answer with a [fault], not a throw. */
    suspend fun exchange(client: C): A

    /** Why [answer] failed; null when Adyen answered. */
    fun fault(answer: A): Fault?
}

/** What [OperationIdentity.send] found sending one identified operation once, for its kind to store and report. */
internal sealed interface Sent<out A> {
    /** Nothing was sent: Manager approval did not hold, before the adapter or immediately before sending. */
    data object Unapproved : Sent<Nothing>

    /**
     * Nothing was sent because [problem] must be resolved first: facts the record lacks, or setup or the original payment
     * context at the current target ([access] when the adapter was refused). An unresolved operation stays as it was; a
     * fresh one is known not sent.
     *
     * @property problem What must be resolved.
     * @property access The refused adapter; null when the record lacked facts.
     */
    data class Blocked(
        val problem: SetupProblem,
        val access: ApiAccess.Blocked? = null,
    ) : Sent<Nothing>

    /**
     * A resend that is not allowed ([SetupProblem.CHARGE_TOO_OLD] outside the replay window,
     * [SetupProblem.OPERATION_RECONCILIATION] without Adyen's retry permission): nothing was sent, it stays unconfirmed.
     *
     * @property problem Why it may not be sent again.
     */
    data class Expired(
        val problem: SetupProblem,
    ) : Sent<Nothing> {
        /** Why its outcome stays unconfirmed. */
        val failure: Failure get() = Failure.NotSetUp(problem)
    }

    /** The stored record no longer allows sending it; nothing was sent. */
    data object Withdrawn : Sent<Nothing>

    /**
     * It failed with [fault], which cannot have taken effect, after an earlier send whose outcome is unknown: what is
     * stored stays as it was ([FailedSend.UNCHANGED]).
     *
     * @property fault Why this send failed.
     */
    data class Unchanged(
        val fault: Fault,
    ) : Sent<Nothing> {
        /** Why its outcome stays unconfirmed. */
        val failure: Failure get() = Failure.Remote(fault)
    }

    /**
     * Adyen's [answer] to store; [failed] says what a failed answer establishes.
     *
     * @param A Adyen's answer type.
     * @property answer What came back.
     * @property failed [FailedSend.NOT_DONE] or [FailedSend.UNCONFIRMED] for a failed answer; null when Adyen answered.
     */
    data class Answered<A>(
        val answer: A,
        val failed: FailedSend?,
    ) : Sent<A>
}

/**
 * Operation identity: the persisted key and request facts one logical Adyen operation is sent with, so sending it again
 * cannot take effect twice; a separate renewal gets a new identity. The one owner of sending an identified operation
 * ([send]): when a stored request may be sent again, in which order approval, setup and original context are checked
 * before anything leaves, that it is persisted before it can take effect, and what a failed send establishes. Checkout
 * operations ([CheckoutOperations]), captures and adjustments ([Captures]), refund cancellations ([RefundCancellations])
 * and link expiry ([PaymentLinks]) only store and report what it found.
 */
internal object OperationIdentity {
    /**
     * How long after an operation was first sent it may be sent again: Adyen's API idempotency guide keeps keys per
     * company account for 7 to 14 days after first submission, for every identified endpoint the app calls.
     */
    val REPLAY_WINDOW: Duration = Duration.ofDays(7)

    /**
     * Sends [operation] once at [now] (epoch ms): a resend only inside [REPLAY_WINDOW] and with Adyen's retry permission,
     * then Manager approval, the record's own facts, the adapter for its original context and approval again
     * immediately before it is persisted as sending and exchanged. A failure that cannot have taken effect after an
     * earlier unknown send changes nothing ([Sent.Unchanged]). Runs on the caller's context; callers that must finish a
     * committed send despite cancellation run it non-cancellable.
     */
    suspend fun <C, A> send(
        operation: IdentifiedSend<C, A>,
        now: Long,
    ): Sent<A> =
        unsent(operation, now) ?: when (val access = operation.access()) {
            is ApiAccess.Blocked -> Sent.Blocked(access.problem, access)
            is ApiAccess.Ready -> exchanged(operation, access.client)
        }

    /** Why [operation] is not sent at [now] before its adapter is asked for: an expired resend, approval or missing facts. */
    private suspend fun unsent(
        operation: IdentifiedSend<*, *>,
        now: Long,
    ): Sent<Nothing>? {
        val expired = if (operation.resend) replayProblem(operation.firstSentAt, now, operation.reason) else null
        return when {
            expired != null -> Sent.Expired(expired)
            !operation.approved() -> Sent.Unapproved
            else -> operation.missing()?.let { Sent.Blocked(it) }
        }
    }

    /** Sends [operation] with [client] once approval still holds and it is persisted, and classifies a failed answer. */
    private suspend fun <C, A> exchanged(
        operation: IdentifiedSend<C, A>,
        client: C,
    ): Sent<A> {
        if (!operation.approved()) return Sent.Unapproved
        if (!operation.sending()) return Sent.Withdrawn
        val answer = operation.exchange(client)
        val failed = operation.fault(answer)?.let { it to failed(operation.unresolved, it) }
        return when {
            failed == null -> Sent.Answered(answer, null)
            failed.second == FailedSend.UNCHANGED -> Sent.Unchanged(failed.first)
            else -> Sent.Answered(answer, failed.second)
        }
    }

    /**
     * Why a stored request must not be sent again at [now] (epoch ms): its first send ([firstSentAt], epoch ms) is
     * unknown, outside [REPLAY_WINDOW] or ahead of the device clock, or the last unresolved answer ([reason]; null for an
     * interrupted exchange without an HTTP answer) was an HTTP error that did not explicitly permit a retry. Null when it
     * may be sent again.
     */
    private fun replayProblem(
        firstSentAt: Long?,
        now: Long,
        reason: StoredReason?,
    ): SetupProblem? {
        if (firstSentAt == null || now < firstSentAt || Duration.ofMillis(now - firstSentAt) >= REPLAY_WINDOW) {
            return SetupProblem.CHARGE_TOO_OLD
        }
        val fault = ((reason as? StoredReason.Unconfirmed)?.failure as? Failure.Remote)?.fault
        return SetupProblem.OPERATION_RECONCILIATION.takeIf { fault is Fault.AdyenUnavailable && !fault.transientError }
    }

    /**
     * What a send that failed with [fault] establishes about the operation, given whether an earlier send's outcome is
     * unknown ([unresolved]): a failure that may have taken effect leaves it unconfirmed, and one that cannot have taken
     * effect proves it was not done only when no earlier send might have.
     */
    private fun failed(
        unresolved: Boolean,
        fault: Fault,
    ): FailedSend =
        when {
            fault.mayHaveTakenEffect -> FailedSend.UNCONFIRMED
            unresolved -> FailedSend.UNCHANGED
            else -> FailedSend.NOT_DONE
        }
}
