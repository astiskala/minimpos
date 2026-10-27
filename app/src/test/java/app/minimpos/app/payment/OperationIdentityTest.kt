package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** How an identified operation is sent once: in which order it is checked, when it may be sent again, what a failure establishes. */
class OperationIdentityTest {
    private val day = 24 * 60 * 60 * 1000L
    private val started = 1_000_000L

    /** An operation answered with [answer] (null when Adyen answered), recording each step [OperationIdentity.send] takes. */
    private class Fake(
        override val unresolved: Boolean = false,
        override val resend: Boolean = unresolved,
        override val firstSentAt: Long? = null,
        override val reason: StoredReason? = null,
        private val approvals: MutableList<Boolean> = mutableListOf(true, true),
        private val lacking: SetupProblem? = null,
        private val access: ApiAccess<String> = ApiAccess.Ready("client"),
        private val stored: Boolean = true,
        private val answer: Fault? = null,
    ) : IdentifiedSend<String, Fault?> {
        val steps = mutableListOf<String>()

        override suspend fun approved() = approvals.removeAt(0).also { steps += "approved" }

        override suspend fun missing() = lacking.also { steps += "missing" }

        override suspend fun access() = access.also { steps += "access" }

        override suspend fun sending() = stored.also { steps += "sending" }

        override suspend fun exchange(client: String) = answer.also { steps += "exchange $client" }

        override fun fault(answer: Fault?) = answer
    }

    private fun Fake.sent(now: Long = started) = runBlocking { OperationIdentity.send(this@sent, now) }

    @Test
    fun `approval is checked before the adapter and again immediately before it is persisted and sent`() {
        val sent = Fake()
        assertThat(sent.sent()).isEqualTo(Sent.Answered(null, null))
        assertThat(sent.steps).containsExactly("approved", "missing", "access", "approved", "sending", "exchange client").inOrder()
        val unapproved = Fake(approvals = mutableListOf(false))
        assertThat(unapproved.sent()).isEqualTo(Sent.Unapproved)
        assertThat(unapproved.steps).containsExactly("approved")
        val expiring = Fake(approvals = mutableListOf(true, false))
        assertThat(expiring.sent()).isEqualTo(Sent.Unapproved)
        assertThat(expiring.steps).containsExactly("approved", "missing", "access", "approved").inOrder()
    }

    @Test
    fun `missing facts, setup or context send nothing, and a withdrawn record is not sent`() {
        val lacking = Fake(lacking = SetupProblem.PAYMENT_CONTEXT)
        assertThat(lacking.sent()).isEqualTo(Sent.Blocked(SetupProblem.PAYMENT_CONTEXT))
        assertThat(lacking.steps).doesNotContain("access")
        val unavailable = ApiAccess.Unavailable(SetupProblem.API_KEY)
        val blocked = Fake(access = unavailable)
        assertThat(blocked.sent()).isEqualTo(Sent.Blocked(SetupProblem.API_KEY, unavailable))
        assertThat(Fake(access = ApiAccess.ContextMismatch).sent())
            .isEqualTo(Sent.Blocked(SetupProblem.PAYMENT_CONTEXT, ApiAccess.ContextMismatch))
        assertThat(blocked.steps.none { it.startsWith("exchange") || it == "sending" }).isTrue()
        val withdrawn = Fake(stored = false)
        assertThat(withdrawn.sent()).isEqualTo(Sent.Withdrawn)
        assertThat(withdrawn.steps.last()).isEqualTo("sending")
    }

    @Test
    fun `a stored request is sent again only inside Adyen's retention window`() {
        fun resend(now: Long) = Fake(resend = true, firstSentAt = started).sent(now)
        assertThat(resend(started)).isEqualTo(Sent.Answered(null, null))
        assertThat(resend(started + 7 * day - 1)).isEqualTo(Sent.Answered(null, null))
        assertThat(resend(started + 7 * day)).isEqualTo(Sent.Expired(SetupProblem.CHARGE_TOO_OLD))
        assertThat(Fake(resend = true).sent()).isEqualTo(Sent.Expired(SetupProblem.CHARGE_TOO_OLD))
        // A first send ahead of the device clock cannot be placed in the window.
        assertThat(resend(started - 1)).isEqualTo(Sent.Expired(SetupProblem.CHARGE_TOO_OLD))
        // A first send has no window to respect, and an expired resend is refused before anything else is asked.
        assertThat(Fake().sent(started + 8 * day)).isEqualTo(Sent.Answered(null, null))
        val expired = Fake(unresolved = true, firstSentAt = started)
        assertThat(expired.sent(started + 8 * day)).isEqualTo(Sent.Expired(SetupProblem.CHARGE_TOO_OLD))
        assertThat(expired.steps).isEmpty()
    }

    @Test
    fun `an HTTP error that did not permit a retry needs reconciliation first`() {
        fun resend(fault: Fault) =
            Fake(resend = true, firstSentAt = started, reason = StoredReason.Unconfirmed(Failure.Remote(fault))).sent()
        assertThat(resend(Fault.AdyenUnavailable(500))).isEqualTo(Sent.Expired(SetupProblem.OPERATION_RECONCILIATION))
        assertThat(resend(Fault.AdyenUnavailable(503, transientError = true))).isEqualTo(Sent.Answered(null, null))
        assertThat(resend(Fault.TimedOut)).isEqualTo(Sent.Answered(null, null))
    }

    @Test
    fun `a failure that cannot have taken effect proves nothing about an earlier unknown send`() {
        fun failed(
            unresolved: Boolean,
            fault: Fault,
        ) = Fake(unresolved = unresolved, firstSentAt = started, answer = fault).sent()
        assertThat(failed(unresolved = false, fault = Fault.TimedOut)).isEqualTo(Sent.Answered(Fault.TimedOut, FailedSend.UNCONFIRMED))
        assertThat(failed(unresolved = true, fault = Fault.TimedOut)).isEqualTo(Sent.Answered(Fault.TimedOut, FailedSend.UNCONFIRMED))
        assertThat(failed(unresolved = false, fault = Fault.NotStarted)).isEqualTo(Sent.Answered(Fault.NotStarted, FailedSend.NOT_DONE))
        assertThat(failed(unresolved = true, fault = Fault.NotStarted)).isEqualTo(Sent.Unchanged(Fault.NotStarted))
    }
}
