package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeManagement
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.paymentsapp.BoardingCredentialCheck
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TapToPayDiagnosticsTest {
    private val phone = FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST))
    private val management = FakeManagement()
    private val paymentsApp = FakePaymentsApp()
    private val env =
        TestEnvironment(phone, dispatcher = UnconfinedTestDispatcher(), paymentsApp = paymentsApp, management = management)
    private val container = env.container
    private val diagnostics = container.tapToPay.diagnostics

    @After
    fun tearDown() = env.close()

    private fun configured(installation: String = FakePaymentsApp.INSTALLATION_ID) {
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.PAYMENTS_APP,
                        merchantAccount = "Merchant",
                        paymentsAppInstallationId = installation,
                    ),
            )
        }
        await {
            container.secrets.set(Secret.ADYEN_API_KEY, "unrelated-terminal-key")
            container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "boarding-key")
        }
    }

    @Test
    fun `explicit connection test includes boarding diagnostics but ordinary checks do not`() {
        configured()
        paymentsApp.boarded = true
        await { container.terminalStatus.check() }
        assertThat(management.credentialChecks).isEqualTo(0)
        assertThat(paymentsApp.opened).isEmpty()

        await { container.terminalStatus.check(includeDiagnostics = true) }
        assertThat(management.credentialChecks).isEqualTo(1)
        assertThat(diagnostics.state.value.clientKey).isEqualTo(ClientKeyCheck.PRESENT)
        assertThat(paymentsApp.opened).hasSize(1)
        assertThat(paymentsApp.opened.single()).doesNotContain("reboard")
        assertThat(management.requests).isEmpty()
        assertThat(management.revoked).isEmpty()
    }

    @Test
    fun `explicit simulator connection test never inspects an unrelated boarding key`() {
        configured()
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.SIMULATOR)) }
        await { container.terminalStatus.check(includeDiagnostics = true) }
        assertThat(management.credentialChecks).isEqualTo(0)
        assertThat(paymentsApp.opened).isEmpty()
    }

    @Test
    fun `support inspection refreshes phone checks without boarding or setup verification`() {
        configured()
        paymentsApp.boarded = true
        val before = await { container.settings.current() }
        val reading = checkNotNull(await { container.terminalStatus.inspect() })
        assertThat(reading.facts["installationId"]).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
        assertThat(reading.facts["supportsDiagnosis"]).isEqualTo("false")
        assertThat(reading.facts.values.joinToString()).doesNotContain("boarding-key")
        assertThat(reading.facts.values.joinToString()).doesNotContain("unrelated-terminal-key")
        assertThat(diagnostics.state.value.clientKey).isEqualTo(ClientKeyCheck.PRESENT)
        assertThat(management.requests).isEmpty()
        assertThat(management.revoked).isEmpty()
        assertThat(paymentsApp.opened).hasSize(1)
        assertThat(await { container.settings.current() }).isEqualTo(before)
    }

    @Test
    fun `check uses separate boarding credential and never infers legacy boarding identity`() {
        configured()
        paymentsApp.boarded = true
        val check = await { diagnostics.refresh() }
        assertThat(check.clientKey).isEqualTo(ClientKeyCheck.PRESENT)
        assertThat(check.credential?.username).isEqualTo("ws@Company.Test")
        assertThat(check.associated).isFalse()
        assertThat(check.boarding).isEqualTo(PaymentsAppBoardingStatus.BOARDED)
        assertThat(check.checkedAt).isNotNull()
        assertThat(check.boardingAt).isNotNull()
        assertThat(management.connections.map { it.first }).containsExactly("boarding-key")
        assertThat(management.requests).isEmpty()
        assertThat(management.revoked).isEmpty()
        assertThat(paymentsApp.opened).hasSize(1)
        assertThat(paymentsApp.opened.single()).doesNotContain("reboard")
        assertThat(await { container.settings.current() }.boardingIdentity).isNull()
    }

    @Test
    fun `only successful introspection confirms absence and refreshing sees a newly generated key`() {
        configured()
        listOf(
            Fault.Permission(ApiKey.PAYMENTS_APP) to ClientKeyCheck.PERMISSION_UNAVAILABLE,
            Fault.Credential(ApiKey.PAYMENTS_APP) to ClientKeyCheck.AUTHENTICATION_FAILED,
            Fault.TimedOut to ClientKeyCheck.FAILED,
            Fault.UnreadableReply() to ClientKeyCheck.FAILED,
        ).forEach { (fault, expected) ->
            management.credentialResult = BoardingCredentialCheck.Failed(fault)
            val check = await { diagnostics.refresh() }
            assertThat(check.clientKey).isEqualTo(expected)
            assertThat(check.credential).isNull()
        }
        management.credentialResult = BoardingCredentialCheck.Verified("CRED-1", "ws@Company.Test", true, false)
        assertThat(await { diagnostics.refresh() }.clientKey).isEqualTo(ClientKeyCheck.MISSING)
        management.credentialResult = BoardingCredentialCheck.Verified("CRED-1", "ws@Company.Test", true, true)
        assertThat(await { diagnostics.refresh() }.clientKey).isEqualTo(ClientKeyCheck.PRESENT)
        assertThat(management.requests).isEmpty()
        assertThat(management.revoked).isEmpty()
        assertThat(paymentsApp.opened.all { "/boarded?" in it && "reboard" !in it }).isTrue()
    }

    @Test
    fun `replacement key and TEST LIVE change invalidate cache without automatic network checks`() {
        configured()
        await { diagnostics.refresh() }
        val checks = management.credentialChecks
        await { container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "replacement-key") }
        await { diagnostics.state.first { it.clientKey == ClientKeyCheck.NOT_CHECKED } }
        assertThat(management.credentialChecks).isEqualTo(checks)
        await { diagnostics.refresh() }
        phone.paymentsApps = setOf(TerminalEnvironment.LIVE)
        container.terminalSetup.readDevice()
        await { diagnostics.state.first { it.clientKey == ClientKeyCheck.NOT_CHECKED } }
        val live = await { diagnostics.refresh() }
        assertThat(live.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(management.connections.last()).isEqualTo("replacement-key" to TerminalEnvironment.LIVE)
        assertThat(paymentsApp.opened.last()).startsWith("https://www.adyen.com/boarded?")
    }

    @Test
    fun `an in flight result cannot validate a replaced credential`() =
        runTest {
            configured()
            val entered = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            management.onCredential = {
                entered.complete(Unit)
                resume.await()
            }
            val work = env.scope.launch { diagnostics.refresh() }
            await { entered.await() }
            await { container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "replacement-key") }
            resume.complete(Unit)
            await { work.join() }
            assertThat(diagnostics.state.value.clientKey).isEqualTo(ClientKeyCheck.NOT_CHECKED)
        }

    @Test
    fun `new boarding retains evidence but credential changes do not rewrite its history`() {
        configured(installation = "")
        await { container.tapToPay.board() }
        val identity = checkNotNull(await { container.settings.current() }.boardingIdentity)
        assertThat(identity.credentialId).isEqualTo("CRED-1")
        assertThat(identity.username).isEqualTo("ws@Company.Test")
        assertThat(identity.installationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
        assertThat(identity.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(await { diagnostics.refresh() }.associated).isTrue()
        management.credentialResult = BoardingCredentialCheck.Verified("CRED-2", "ws@Company.Other", true, true)
        await { container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "other-boarding-key") }
        assertThat(await { diagnostics.refresh() }.associated).isFalse()
        assertThat(await { diagnostics.refresh() }.boardingIdentity?.username).isEqualTo("ws@Company.Test")
        assertThat(await { container.settings.current() }.boardingIdentity).isEqualTo(identity)
        assertThat(await { container.settings.current() }.shared().boardingIdentity).isNull()
    }

    @Test
    fun `report redacts free text secrets PII and callbacks but retains validated support trace`() {
        configured()
        await { diagnostics.refresh() }
        val trace = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        val text =
            "apiKey=super-secret clientKey=public-secret bearer=secret shopper@example.org 4111111111111111 " +
                "https://callback/?token=private\nerrorCode=1029_605\ntraceparent=$trace"
        val sale =
            SaleEntity(
                id = "sale-1",
                createdAt = 0,
                currency = "AUD",
                taxMode = "EXCLUSIVE",
                netMinor = 100,
                taxMinor = 0,
                totalMinor = 100,
                status = SaleStatus.UNKNOWN,
                merchantReference = "reference-private",
                shopperEmail = "shopper@example.org",
                serviceId = "SERVICE1",
                context = PaymentContext("PAYMENTS_APP", FakePaymentsApp.INSTALLATION_ID, "POS", "Merchant", "TEST"),
                reason =
                    StoredReason.Unconfirmed(
                        Failure.Remote(
                            Fault.UnreadableReply(
                                ExternalText(
                                    "$text\nrequestId=boarding-key\ntraceId=boarding-key\ntraceparent=boarding-key\nerrorCode=boarding-key",
                                ),
                            ),
                        ),
                    ),
            )
        val report = await { diagnostics.report(sale) }
        listOf(
            "super-secret",
            "public-secret",
            "bearer",
            "shopper@example.org",
            "4111111111111111",
            "callback",
            "private",
            "unrelated-terminal-key",
            "boarding-key",
            "reference-private",
        ).forEach { assertThat(report).doesNotContain(it) }
        assertThat(report).contains("traceId=4bf92f3577b34da6a3ce929d0e0e4736")
        assertThat(report).contains("errorCode=1029_605")
        assertThat(report).contains("failureStage=response-decoding")
        assertThat(report).contains("ServiceID=SERVICE1")
        assertThat(report).contains("SaleID=POS")
        assertThat(report).contains("message=redacted")
    }
}
