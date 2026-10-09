package app.minimpos.terminal

import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class FaultTest {
    private val said = ExternalText("said")

    @Test
    fun `each fault states once whether the request may have taken effect`() {
        // Not processed (safe to change and retry) versus establish the outcome first: pinned case by case.
        val notSent =
            listOf(
                Fault.Unreachable("10.0.0.1:8443", terminal = true),
                Fault.UnknownHost("example", terminal = false),
                Fault.Untrusted("10.0.0.1"),
                Fault.KeyRejected(said),
                Fault.TerminalRejected(null),
                Fault.TerminalOffline("P400Plus-1", said),
                Fault.NotStarted,
                Fault.AppRefused(said),
                Fault.Unsupported,
                Fault.NoLateReply,
                Fault.NoRecord,
                Fault.RequestNotEncrypted,
                Fault.Credential(ApiKey.ADYEN),
                Fault.Permission(ApiKey.PAYMENTS_APP, "role"),
                Fault.NotFound("P400Plus-1"),
                Fault.AdyenRejected(422, "137", said),
                Fault.ListTooLarge,
            )
        val maybeSent =
            listOf(
                Fault.TimedOut,
                Fault.ConnectionLost,
                Fault.ReplyUnverified,
                Fault.UnreadableReply(said),
                Fault.Malformed(MalformedPart.SETTINGS),
                Fault.NoAnswerFromTerminal("P400Plus-1", null),
                Fault.Abandoned,
                Fault.TerminalHttp(500),
                Fault.AdyenUnavailable(503),
                Fault.StillInProgress,
            )
        notSent.forEach { assertWithMessage(it.toString()).that(it.mayHaveTakenEffect).isFalse() }
        maybeSent.forEach { assertWithMessage(it.toString()).that(it.mayHaveTakenEffect).isTrue() }
        val cases =
            Fault::class.java.declaredClasses
                .filter { Fault::class.java.isAssignableFrom(it) && !it.isInterface }
                .map { it.simpleName }
        assertThat((notSent + maybeSent).map { it.javaClass.simpleName }).containsExactlyElementsIn(cases)
    }

    @Test
    fun `external text keeps the words and drops blanks`() {
        assertThat(ExternalText.of("  Not enough balance ")?.text).isEqualTo("Not enough balance")
        assertThat(ExternalText.of(" ")).isNull()
        assertThat(ExternalText.of(null)).isNull()
        assertThat(ExternalText("Refused").toString()).isEqualTo("Refused")
    }
}
