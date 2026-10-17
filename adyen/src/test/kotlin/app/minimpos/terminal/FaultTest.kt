package app.minimpos.terminal

import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.lang.reflect.Modifier

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
                Fault.NoAnswerFromTerminal("P400Plus-1", said),
                Fault.Abandoned,
                Fault.TerminalHttp(500),
                Fault.AdyenUnavailable(503, "010", said),
                Fault.StillInProgress,
            )
        notSent.forEach { assertWithMessage(it.toString()).that(it.mayHaveTakenEffect).isFalse() }
        maybeSent.forEach { assertWithMessage(it.toString()).that(it.mayHaveTakenEffect).isTrue() }
        val cases =
            Fault::class.java.declaredClasses
                .filter { Fault::class.java.isAssignableFrom(it) && !Modifier.isAbstract(it.modifiers) }
                .map { it.simpleName }
        assertThat((notSent + maybeSent).map { it.javaClass.simpleName }).containsExactlyElementsIn(cases)
    }

    @Test
    fun `fault subtypes retain their diagnostic properties`() {
        val unreachable = Fault.Unreachable("10.0.0.1:8443", terminal = true)
        assertThat(unreachable.host).isEqualTo("10.0.0.1:8443")
        assertThat(unreachable.terminal).isTrue()
        assertThat(Fault.Unreachable("api.adyen.com", terminal = false).terminal).isFalse()

        val unknownHost = Fault.UnknownHost("example", terminal = true)
        assertThat(unknownHost.host).isEqualTo("example")
        assertThat(unknownHost.terminal).isTrue()
        assertThat(Fault.UnknownHost("example", terminal = false).terminal).isFalse()

        assertThat(Fault.Untrusted("10.0.0.1").host).isEqualTo("10.0.0.1")
        assertThat(Fault.KeyRejected(said).said).isEqualTo(said)
        assertThat(Fault.TerminalRejected(said).said).isEqualTo(said)

        val offline = Fault.TerminalOffline("P400Plus-1", said)
        assertThat(offline.poiId).isEqualTo("P400Plus-1")
        assertThat(offline.said).isEqualTo(said)

        assertThat(Fault.AppRefused(said).said).isEqualTo(said)
        assertThat(Fault.Credential(ApiKey.ADYEN).key).isEqualTo(ApiKey.ADYEN)

        val permission = Fault.Permission(ApiKey.PAYMENTS_APP, "role")
        assertThat(permission.key).isEqualTo(ApiKey.PAYMENTS_APP)
        assertThat(permission.role).isEqualTo("role")
        assertThat(Fault.Permission(ApiKey.ADYEN).role).isNull()

        assertThat(Fault.NotFound("P400Plus-1").poiId).isEqualTo("P400Plus-1")

        val rejected = Fault.AdyenRejected(422, "137", said)
        assertThat(rejected.http).isEqualTo(422)
        assertThat(rejected.errorCode).isEqualTo("137")
        assertThat(rejected.said).isEqualTo(said)
        assertThat(Fault.AdyenRejected(400).errorCode).isNull()

        assertThat(Fault.UnreadableReply(said).said).isEqualTo(said)
        assertThat(Fault.UnreadableReply().said).isNull()
        assertThat(Fault.Malformed(MalformedPart.SETTINGS).part).isEqualTo(MalformedPart.SETTINGS)

        val noAnswer = Fault.NoAnswerFromTerminal("P400Plus-1", said)
        assertThat(noAnswer.poiId).isEqualTo("P400Plus-1")
        assertThat(noAnswer.said).isEqualTo(said)

        assertThat(Fault.TerminalHttp(500).code).isEqualTo(500)

        val unavailable = Fault.AdyenUnavailable(503, "010", said)
        assertThat(unavailable.http).isEqualTo(503)
        assertThat(unavailable.errorCode).isEqualTo("010")
        assertThat(unavailable.said).isEqualTo(said)
        assertThat(Fault.AdyenUnavailable(503).errorCode).isNull()
    }

    @Test
    fun `external text keeps the words and drops blanks`() {
        assertThat(ExternalText.of("  Not enough balance ")?.text).isEqualTo("Not enough balance")
        assertThat(ExternalText.of(" ")).isNull()
        assertThat(ExternalText.of(null)).isNull()
        assertThat(ExternalText("Refused").toString()).isEqualTo("Refused")
        val boxed: Any = ExternalText("Refused")
        assertThat(boxed.toString()).isEqualTo("Refused")
        assertThat(ExternalText::text.get(ExternalText("Refused"))).isEqualTo("Refused")
        assertThat(ExternalText::class.java.getDeclaredMethod("getText").invoke(ExternalText("Refused"))).isEqualTo("Refused")
    }
}
