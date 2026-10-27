package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.terminal.transport.Fault
import java.time.Duration

/** What a send of an identified operation that failed with a [Fault] establishes, see [OperationIdentity.failed]. */
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
 * Operation identity: the persisted key and request facts one logical Adyen operation is sent with, so sending it again
 * cannot take effect twice; a separate renewal gets a new identity. The one owner of when a stored request may be sent
 * again ([replayProblem]) and of what a failed send establishes about it ([failed]), shared by the Checkout operations
 * ([CheckoutOperations]), captures and adjustments ([Captures]) and refund cancellations ([RefundCancellations]).
 */
internal object OperationIdentity {
    /** How long after an operation was first sent it may be sent again: Adyen keeps idempotency keys at least this long. */
    val REPLAY_WINDOW: Duration = Duration.ofDays(7)

    /**
     * Why a stored request must not be sent again at [now] (epoch ms): its first send ([firstSentAt], epoch ms) is
     * unknown, outside [REPLAY_WINDOW] or ahead of the device clock, or the last unresolved answer ([reason]; null for an
     * interrupted exchange without an HTTP answer) was an HTTP error that did not explicitly permit a retry. Null when it
     * may be sent again.
     */
    fun replayProblem(
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
    fun failed(
        unresolved: Boolean,
        fault: Fault,
    ): FailedSend =
        when {
            fault.mayHaveTakenEffect -> FailedSend.UNCONFIRMED
            unresolved -> FailedSend.UNCHANGED
            else -> FailedSend.NOT_DONE
        }
}
