package app.minimpos.app.data

import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeManagement
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.repo.ImportMode
import app.minimpos.app.data.security.TransferSeal
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.terminal.SetupImportOutcome
import app.minimpos.core.codec.SealedSecrets
import app.minimpos.core.codec.Transfer
import app.minimpos.core.codec.TransferCodec
import app.minimpos.terminal.paymentsapp.ManagementResult
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TapToPayImportTest {
    private val phone = FakePaymentsApp()
    private val management = FakeManagement()

    @get:Rule
    val env =
        TestEnvironment(
            FakeDevice(paymentsApps = TerminalEnvironment.entries.toSet()),
            paymentsApp = phone,
            management = management,
            verifiedSetup = false,
        )

    @Test
    fun `LIVE import registers the selected app instead of reusing the active TEST installation`() {
        val result = importLive(fails = false)
        assertThat(result).isInstanceOf(SetupImportOutcome.Committed::class.java)
        val terminal = await { env.container.settings.current() }.terminal
        assertThat(terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
    }

    @Test
    fun `failed LIVE import never clears the active TEST registration`() {
        val result = importLive(fails = true)
        assertThat(result).isEqualTo(SetupImportOutcome.Failed(failure = Failure.Remote(Fault.AdyenUnavailable(503))))
        val terminal = await { env.container.settings.current() }.terminal
        assertThat(terminal.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(terminal.paymentsAppInstallationId).isEqualTo("TEST-INSTALLATION")
    }

    private fun importLive(fails: Boolean): SetupImportOutcome {
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.PAYMENTS_APP,
                        environment = TerminalEnvironment.TEST,
                        merchantAccount = "Merchant",
                        paymentsAppInstallationId = "TEST-INSTALLATION",
                    ),
            )
        }
        management.result =
            if (fails) {
                ManagementResult.Failed(
                    Fault.AdyenUnavailable(503),
                )
            } else {
                ManagementResult.Done("BT", FakePaymentsApp.INSTALLATION_ID)
            }
        val public =
            Transfer(
                connection =
                    """{"destination":"tapToPay","environment":"LIVE","merchantAccount":"Merchant",
                |"liveUrlPrefix":"live-prefix","keyIdentifier":"shared","keyVersion":1}
                    """.trimMargin(),
            )
        val secrets = """{"ADYEN_API_KEY":"key","TERMINAL_PASSPHRASE":"shared passphrase"}"""
        val seal = TransferSeal(iterations = 1_000)
        val code = seal.newCode()
        val encrypted = seal.seal(secrets.toByteArray(), code, TransferCodec.authenticationData(public))
        val received = env.container.setupTransfer.receive(public.copy(sealedSecrets = SealedSecrets(encrypted)))
        val result = await { env.container.setupImport.import(received, ImportMode.MERGE, code, board = true) }
        assertThat(phone.opened.first()).startsWith("https://www.adyen.com/boarded?")
        assertThat(phone.opened.first()).doesNotContain("reboard=true")
        assertThat(phone.packages.toSet()).containsExactly("com.adyen.ipp.mobile.companion.live")
        return result
    }
}
