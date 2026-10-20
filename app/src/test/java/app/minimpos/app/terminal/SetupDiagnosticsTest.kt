package app.minimpos.app.terminal

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRoles
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SetupDiagnosticsTest {
    private val required = AdyenRole.required()
    private var credential: CredentialLookup = CredentialLookup.Allowed(CredentialRoles(required, emptyList()))
    private var credentialCalls = 0
    private val details =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment): CredentialLookup {
                credentialCalls++
                return credential
            }

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing = TerminalListing.Failed(Fault.TimedOut)

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup = error("Diagnostics must not read shared keys")
        }

    @get:Rule
    val env = TestEnvironment(terminalDetails = details)

    @Test
    fun `support roles survive unrelated access failure without duplicate introspection`() {
        env.useLinks()
        val reading = inspect()
        assertThat(reading.api).isEqualTo(ApiCheck.Failed(Failure.Remote(Fault.TimedOut)))
        assertThat(reading.facts["apiHasRequiredRoles"]).isEqualTo("true")
        assertThat(reading.facts["apiRolesRequired"]).isEqualTo("[${AdyenRole.names(required)}]")
        assertThat(reading.facts["apiRolesPresent"]).isEqualTo("[${AdyenRole.names(required)}]")
        assertThat(reading.facts["apiRolesMissing"]).isEqualTo("[]")
        assertThat(credentialCalls).isEqualTo(1)
    }

    @Test
    fun `missing roles differ from unavailable and simulator checks`() {
        env.useLinks()
        val missing = listOf(AdyenRole.TERMINAL_SETTINGS)
        credential = CredentialLookup.Failed(Fault.Permission(ApiKey.ADYEN, AdyenRole.names(missing)), CredentialRoles(required, missing))
        val denied = inspect()
        assertThat(denied.facts["apiHasRequiredRoles"]).isEqualTo("false")
        assertThat(denied.facts["apiRolesMissing"]).isEqualTo("[${AdyenRole.names(missing)}]")
        assertThat(denied.facts["apiRolesPresent"]).isEqualTo("[${AdyenRole.names(required - missing.toSet())}]")
        listOf(Fault.Credential(ApiKey.ADYEN), Fault.Permission(ApiKey.ADYEN), Fault.TimedOut, Fault.UnreadableReply()).forEach {
            credential = CredentialLookup.Failed(it)
            val unavailable = inspect()
            listOf("apiHasRequiredRoles", "apiRolesPresent", "apiRolesMissing").forEach { field ->
                assertThat(unavailable.facts[field]).isEqualTo("NOT_CHECKED")
            }
        }
        val calls = credentialCalls
        env.useSimulator()
        assertThat(inspect().facts["apiHasRequiredRoles"]).isEqualTo("NOT_CHECKED")
        assertThat(credentialCalls).isEqualTo(calls)
    }

    private fun inspect(): SetupDiagnostics = checkNotNull(await { env.container.terminalStatus.inspect() })
}
