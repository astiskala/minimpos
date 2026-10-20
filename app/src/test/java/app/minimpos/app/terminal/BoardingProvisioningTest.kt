package app.minimpos.app.terminal

import app.minimpos.app.FakeBoardingCredentials
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeManagement
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.JournalRead
import app.minimpos.app.data.security.RecoveryJournal
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.secretFingerprint
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.transfer.TransferContents
import app.minimpos.terminal.paymentsapp.ProvisionedCredential
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.serializer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BoardingProvisioningTest {
    private val phone = FakeDevice(paymentsApps = TerminalEnvironment.entries.toSet())
    private val credentials = FakeBoardingCredentials()
    private val management = FakeManagement()
    private val paymentsApp = FakePaymentsApp()

    @get:Rule
    val env = TestEnvironment(phone, paymentsApp = paymentsApp, management = management, boardingCredentials = credentials)
    private val container get() = env.container

    @Before
    fun configure() {
        env.useCheckoutApi()
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP)) }
    }

    @Test
    fun `primary key provisions once and generated key boards revokes and never transfers`() =
        await {
            assertThat(container.tapToPay.board()).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
            assertThat(credentials.connections).containsExactly("key" to TerminalEnvironment.TEST)
            assertThat(credentials.requests.single().first).isEqualTo("HarbourCoffeeCOM")
            assertThat(credentials.requests.single().third).isFalse()
            assertThat(management.connections.map { it.first }.toSet()).containsExactly("generated-boarding-key")
            assertThat(container.tapToPay.board(reboard = true)).isInstanceOf(TapToPayOutcome.Boarded::class.java)
            assertThat(credentials.requests).hasSize(1)
            val exported = container.setupTransfer.export(TransferContents(catalogue = false), "AUD")
            assertThat(exported.secrets).containsExactly(Secret.ADYEN_API_KEY)
            assertThat(container.tapToPay.unregister()).isEqualTo(TapToPayOutcome.Unregistered)
            assertThat(management.revoked).containsExactly(FakePaymentsApp.INSTALLATION_ID)
            assertThat(credentials.requests).hasSize(1)
        }

    @Test
    fun `uncertain creation recovers same intent without reusing an unrelated credential`() =
        await {
            credentials.result = ProvisionedCredential.Failed(Fault.TimedOut)
            assertThat(container.tapToPay.board()).isEqualTo(TapToPayOutcome.Failed(Failure.Remote(Fault.TimedOut)))
            assertThat(paymentsApp.opened).isEmpty()
            val reference = credentials.requests.single().second
            credentials.result = ProvisionedCredential.Ready("recovered-key")
            assertThat(container.tapToPay.board()).isInstanceOf(TapToPayOutcome.Boarded::class.java)
            assertThat(credentials.requests.map { it.second }).containsExactly(reference, reference).inOrder()
            assertThat(credentials.requests.map { it.third }).containsExactly(false, true).inOrder()
            assertThat((container.terminalSetup.boarding() as BoardingSetup.Ready).apiKey).isEqualTo("recovered-key")
        }

    @Test
    fun `definitely rejected creation can retry without recovery but later key failure must recover`() =
        await {
            val denied = Fault.Permission(ApiKey.ADYEN, "Management API - API credentials read and write")
            credentials.result = ProvisionedCredential.Failed(denied)
            container.tapToPay.board()
            credentials.result = ProvisionedCredential.Failed(denied, creationMayExist = true)
            container.tapToPay.board()
            credentials.result = ProvisionedCredential.Ready("ready")
            container.tapToPay.board()
            assertThat(credentials.requests.map { it.third }).containsExactly(false, false, true).inOrder()
            assertThat(credentials.requests[1].second).isEqualTo(credentials.requests[2].second)
            assertThat(credentials.requests[0].second).isNotEqualTo(credentials.requests[1].second)
        }

    @Test
    fun `failed intent persistence never sends provisioning requests and explicit setup replaces an unreadable journal`() =
        await {
            env.cipher.failEncrypt = true
            assertThat(container.tapToPay.board()).isEqualTo(TapToPayOutcome.Failed(Failure.Device(DeviceFault.SECURE_STORAGE)))
            assertThat(credentials.requests).isEmpty()
            assertThat(paymentsApp.opened).isEmpty()
            env.cipher.failEncrypt = false
            container.secrets.journal(RecoveryJournal.BOARDING_CREDENTIAL, String.serializer()).write("broken")
            assertThat(container.terminalSetup.boarding()).isEqualTo(BoardingSetup.Blocked(SetupProblem.UNREADABLE_PAYMENTS_APP_KEY))
            assertThat(container.tapToPay.board()).isInstanceOf(TapToPayOutcome.Boarded::class.java)
            assertThat(credentials.requests.single().third).isFalse()
            assertThat((container.terminalSetup.boarding() as BoardingSetup.Ready).apiKey).isEqualTo("generated-boarding-key")
        }

    @Test
    fun `credentials another Adyen API key created are forgotten once this key creates its own`() =
        await {
            val earlier =
                BoardingCredential(
                    secretFingerprint("earlier-key"),
                    "HarbourCoffeeCOM",
                    TerminalEnvironment.TEST,
                    "earlier",
                    "earlier-boarding-key",
                )
            container.terminalSetup.boardingCredentials.write(listOf(earlier))
            assertThat(container.terminalSetup.boarding()).isEqualTo(BoardingSetup.Blocked(SetupProblem.PAYMENTS_APP_API_KEY))
            assertThat(container.tapToPay.board()).isInstanceOf(TapToPayOutcome.Boarded::class.java)
            assertThat(credentials.requests.single().third).isFalse()
            assertThat(management.connections.map { it.first }.toSet()).containsExactly("generated-boarding-key")
            val stored = (container.terminalSetup.boardingCredentials.read() as JournalRead.Present).value
            assertThat(stored.map { it.owner to it.apiKey }).isEqualTo(listOf(secretFingerprint("key") to "generated-boarding-key"))
        }

    @Test
    fun `failed persistence after remote creation retains intent and never hands off the unsaved key`() =
        await {
            credentials.beforeProvision = { env.cipher.failEncrypt = true }
            assertThat(container.tapToPay.board()).isEqualTo(TapToPayOutcome.Failed(Failure.Device(DeviceFault.SECURE_STORAGE)))
            assertThat(paymentsApp.opened).isEmpty()
            val reference = credentials.requests.single().second
            env.cipher.failEncrypt = false
            credentials.beforeProvision = {}
            assertThat(container.tapToPay.board()).isInstanceOf(TapToPayOutcome.Boarded::class.java)
            assertThat(credentials.requests.map { it.second }).containsExactly(reference, reference).inOrder()
            assertThat(credentials.requests.map { it.third }).containsExactly(false, true).inOrder()
        }

    @Test
    fun `account and environment changes never reuse generated key and switching back reuses stored record`() =
        await {
            container.tapToPay.board()
            env.updateSettings {
                it.copy(terminal = it.terminal.copy(merchantAccount = "Other", paymentsAppInstallationId = ""))
            }
            paymentsApp.boarded = false
            container.tapToPay.board()
            env.updateSettings {
                it.copy(terminal = it.terminal.selectEnvironment(TerminalEnvironment.LIVE).copy(liveUrlPrefix = "live-prefix"))
            }
            paymentsApp.boarded = false
            container.tapToPay.board()
            assertThat(credentials.connections)
                .containsExactly(
                    "key" to TerminalEnvironment.TEST,
                    "key" to TerminalEnvironment.TEST,
                    "key" to TerminalEnvironment.LIVE,
                ).inOrder()
            env.updateSettings {
                it.copy(terminal = it.terminal.selectEnvironment(TerminalEnvironment.TEST).copy(merchantAccount = "HarbourCoffeeCOM"))
            }
            container.tapToPay.board()
            assertThat(credentials.requests).hasSize(3)
            assertThat((container.terminalSetup.boardingCredentials.read() as JournalRead.Present).value).hasSize(3)
        }

    @Test
    fun `environment changed during provisioning cannot board either app`() =
        await {
            credentials.beforeProvision = {
                env.updateSettings { it.copy(terminal = it.terminal.selectEnvironment(TerminalEnvironment.LIVE)) }
            }
            assertThat(container.tapToPay.board()).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.SETUP_CHANGED))
            assertThat(paymentsApp.opened).isEmpty()
            assertThat(credentials.connections).containsExactly("key" to TerminalEnvironment.TEST)
            assertThat(
                container.settings
                    .current()
                    .terminal.paymentsAppInstallationId,
            ).isEmpty()
        }

    @Test
    fun `settings changed during provisioning cannot board or activate result`() =
        await {
            credentials.beforeProvision = {
                env.updateSettings { it.copy(terminal = it.terminal.copy(storeId = "changed")) }
            }
            assertThat(container.tapToPay.board()).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.SETUP_CHANGED))
            assertThat(paymentsApp.opened).isEmpty()
            assertThat(
                container.settings
                    .current()
                    .terminal.paymentsAppInstallationId,
            ).isEmpty()
        }

    @Test
    fun `read only diagnostics never provision a missing credential`() =
        await {
            container.tapToPay.diagnostics.refresh()
            container.terminalStatus.check()
            assertThat(credentials.requests).isEmpty()
            assertThat(management.requests).isEmpty()
        }
}
