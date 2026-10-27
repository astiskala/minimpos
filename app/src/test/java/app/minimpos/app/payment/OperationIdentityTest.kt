package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** When an identified operation may be sent again, and what a failed send of it establishes. */
class OperationIdentityTest {
    private val day = 24 * 60 * 60 * 1000L
    private val started = 1_000_000L

    @Test
    fun `a stored request is sent again only inside Adyen's retention window`() {
        assertThat(OperationIdentity.replayProblem(started, started, null)).isNull()
        assertThat(OperationIdentity.replayProblem(started, started + 7 * day - 1, null)).isNull()
        assertThat(OperationIdentity.replayProblem(started, started + 7 * day, null)).isEqualTo(SetupProblem.CHARGE_TOO_OLD)
        assertThat(OperationIdentity.replayProblem(null, started, null)).isEqualTo(SetupProblem.CHARGE_TOO_OLD)
        // A first send ahead of the device clock cannot be placed in the window.
        assertThat(OperationIdentity.replayProblem(started, started - 1, null)).isEqualTo(SetupProblem.CHARGE_TOO_OLD)
    }

    @Test
    fun `an HTTP error that did not permit a retry needs reconciliation first`() {
        fun unconfirmed(fault: Fault) = StoredReason.Unconfirmed(Failure.Remote(fault))
        assertThat(OperationIdentity.replayProblem(started, started, unconfirmed(Fault.AdyenUnavailable(500))))
            .isEqualTo(SetupProblem.OPERATION_RECONCILIATION)
        assertThat(OperationIdentity.replayProblem(started, started, unconfirmed(Fault.AdyenUnavailable(503, transientError = true))))
            .isNull()
        assertThat(OperationIdentity.replayProblem(started, started, unconfirmed(Fault.TimedOut))).isNull()
    }

    @Test
    fun `a failure that cannot have taken effect proves nothing about an earlier unknown send`() {
        assertThat(OperationIdentity.failed(unresolved = false, fault = Fault.TimedOut)).isEqualTo(FailedSend.UNCONFIRMED)
        assertThat(OperationIdentity.failed(unresolved = true, fault = Fault.TimedOut)).isEqualTo(FailedSend.UNCONFIRMED)
        assertThat(OperationIdentity.failed(unresolved = false, fault = Fault.NotStarted)).isEqualTo(FailedSend.NOT_DONE)
        assertThat(OperationIdentity.failed(unresolved = true, fault = Fault.NotStarted)).isEqualTo(FailedSend.UNCHANGED)
    }
}
