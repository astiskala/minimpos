package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.made
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintOutcome
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/** The terminal as the app sees it: where payments go, operations without complete setup, and the published status. */
@RunWith(RobolectricTestRunner::class)
class TerminalTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val payment = PaymentParams(BigDecimal("1.00"), "AUD", "MP-1")

    @After
    fun tearDown() = env.close()

    /** A second environment that plays an Adyen terminal whose shared key passphrase is [passphrase]. */
    private fun onTerminal(
        poiId: String = "S1F2-000158213605014",
        passphrase: String = "correct horse battery staple",
        block: (TestEnvironment, FakeTerminal) -> Unit,
    ) {
        val fake = FakeTerminal(passphrase)
        val terminal = TestEnvironment(FakeDevice(detectedPoiId = poiId), fake)
        try {
            block(terminal, fake)
        } finally {
            terminal.close()
        }
    }

    private fun status(env: TestEnvironment = this.env) =
        await {
            env.container.terminalStatus.state
                .first { it.loaded }
        }

    @Test
    fun `without complete setup nothing is sent, and every operation says what to enter`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST)) }
        val gateway = container.gateway
        var sending: String? = null
        assertThat(await { gateway.pay(payment, "S1") { sending = it } }).isEqualTo(Attempt.NotSetUp(SetupProblem.POI_ID))
        assertThat(sending).isNull()
        assertThat(await { gateway.status("S1", TransactionKind.REFUND) }).isInstanceOf(TransactionOutcome.Unknown::class.java)
        assertThat(await { gateway.abort("S1") }).isFalse()
        assertThat(
            await { gateway.print(listOf(PrintJob.Text(listOf(PrintLine.Text("x"))))) },
        ).isEqualTo(Attempt.NotSetUp(SetupProblem.POI_ID))
        assertThat((await { gateway.diagnose() } as TerminalConnection.NotSetUp).problem).isEqualTo(SetupProblem.POI_ID)
    }

    @Test
    fun `on a terminal its own POIID and localhost apply, and payments need the shared key and the Checkout API`() =
        onTerminal { terminal, fake ->
            val gateway = terminal.container.gateway
            assertThat(terminal.container.terminalStatus.automaticMode).isEqualTo(TerminalMode.TERMINAL)
            terminal.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "key", host = "10.0.0.9")) }
            assertThat((await { gateway.diagnose() } as TerminalConnection.NotSetUp).problem).isEqualTo(SetupProblem.PASSPHRASE)

            await { terminal.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
            // The terminal answers now, but payments wait for the Checkout API.
            assertThat(await { gateway.diagnose() }).isInstanceOf(TerminalConnection.Connected::class.java)
            assertThat(await { gateway.pay(payment, "PAY0") }).isEqualTo(Attempt.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))
            terminal.useCheckoutApi()
            var sending: String? = null
            val paid = await { gateway.pay(payment, "PAY1") { sending = it } }.made()
            assertThat((paid as TransactionOutcome.Completed).details.success).isTrue()
            assertThat(sending).isEqualTo("S1F2-000158213605014")
            assertThat(await { gateway.abort("PAY1", TransactionKind.PAYMENT) }).isTrue()
            assertThat(await { gateway.status("PAY1", TransactionKind.PAYMENT) }).isInstanceOf(TransactionOutcome.Completed::class.java)
            // The transport is reused while host and key stay the same.
            assertThat(fake.hosts).containsExactly("localhost")

            // A passphrase that was saved but can no longer be decrypted is reported as such.
            terminal.cipher.fail = true
            assertThat((await { gateway.diagnose() } as TerminalConnection.NotSetUp).problem).isEqualTo(SetupProblem.UNREADABLE_PASSPHRASE)
        }

    @Test
    fun `connection checks report setup, rejection and success, and learn about the printer`() =
        onTerminal(poiId = "AMS1-000168223606144") { terminal, _ ->
            val status = terminal.container.terminalStatus
            assertThat(status(terminal).connection).isEqualTo(TerminalConnection.Unknown)
            assertThat(status(terminal).setupProblem).isEqualTo(SetupProblem.KEY_IDENTIFIER)
            assertThat((await { status.check() } as TerminalConnection.NotSetUp).problem).isEqualTo(SetupProblem.KEY_IDENTIFIER)

            terminal.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "key")) }
            await { terminal.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "wrong") }
            assertThat((await { status.check() } as TerminalConnection.Failed).message).contains("shared key")
            // AMS1 terminals have no printer as far as their name tells, until one answers.
            assertThat(status(terminal).printerAvailable).isFalse()

            await { terminal.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
            val connected = await { status.check() } as TerminalConnection.Connected
            assertThat(connected.diagnosis.hasPrinter).isTrue()
            val state = await { status.state.first { it.connection == connected } }
            // Connected, but payments wait for the Checkout API.
            assertThat(state.setupProblem).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
            terminal.useCheckoutApi()
            assertThat(await { status.state.first { it.setupProblem == null } }.connection).isEqualTo(connected)
            assertThat(state.printerAvailable).isTrue()
            assertThat(terminal.container.gateway.printers.value).containsExactly("AMS1-000168223606144", true)
        }

    @Test
    fun `the status follows the printer setting, the simulator and what the terminal reported`() {
        env.useSimulator()
        assertThat(status().printerAvailable).isTrue()
        env.useSimulator { it.copy(simulator = it.simulator.copy(hasPrinter = false)) }
        assertThat(await { container.terminalStatus.state.first { !it.printerAvailable } }.mode).isEqualTo(TerminalMode.SIMULATOR)
        // A print refused for want of a printer is remembered for that terminal.
        val failed = await { container.gateway.print(listOf(PrintJob.QrCode("x"))) }.made() as PrintOutcome.Failed
        assertThat(failed.noPrinter).isTrue()
        assertThat(container.gateway.printers.value[TerminalSetup.SIMULATOR_POI_ID]).isFalse()

        env.updateSettings { it.copy(receipt = it.receipt.copy(printerMode = PrinterMode.ON)) }
        assertThat(await { container.terminalStatus.state.first { it.printerAvailable } }.printerAvailable).isTrue()
        env.updateSettings {
            it.copy(
                receipt = it.receipt.copy(printerMode = PrinterMode.OFF),
                simulator = it.simulator.copy(hasPrinter = true),
            )
        }
        assertThat(await { container.terminalStatus.state.first { !it.printerAvailable } }.printerAvailable).isFalse()

        // A terminal not asked yet is judged by its model.
        env.updateSettings {
            it.copy(
                receipt = it.receipt.copy(printerMode = PrinterMode.AUTO),
                terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, poiIdOverride = "S1F2-000158213605014"),
            )
        }
        val f2 = await { container.terminalStatus.state.first { it.mode == TerminalMode.TERMINAL } }
        assertThat(f2.printerAvailable).isTrue()
        assertThat(f2.onTerminal).isFalse()
        env.updateSettings { it.copy(terminal = it.terminal.copy(poiIdOverride = "S1U2-000158213605014")) }
        assertThat(await { container.terminalStatus.state.first { it.poiId == "S1U2-000158213605014" } }.printerAvailable).isFalse()
        env.updateSettings { it.copy(terminal = it.terminal.copy(poiIdOverride = "")) }
        assertThat(await { container.terminalStatus.state.first { it.poiId == null } }.printerAvailable).isFalse()
    }

    @Test
    fun `the app checks the connection in the background, retries failures and remembers the environment`() =
        onTerminal(poiId = "AMS1-000168223606144") { terminal, _ ->
            val container = terminal.container
            container.start()
            await { container.terminalStatus.state.first { it.connection is TerminalConnection.NotSetUp } }
            terminal.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "key")) }
            await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "wrong") }
            await { container.terminalStatus.state.first { it.connection is TerminalConnection.Failed } }
            // Home retries a failed check; with the right key it now connects.
            await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
            await { container.terminalStatus.recheckIfFailed() }
            val connected = await { container.terminalStatus.state.first { it.connection is TerminalConnection.Connected } }
            assertThat(connected.printerAvailable).isTrue()
            await { container.terminalStatus.recheckIfFailed() }

            val setups = TerminalSetupSource(container.settings, container.secrets, container.device)
            container.gateway.environmentDetected(await { setups.current() }, TerminalEnvironment.LIVE)
            await { container.settingsState.first { it.terminal.environment == TerminalEnvironment.LIVE } }
            assertThat(await { container.terminalStatus.state.first { it.environment == TerminalEnvironment.LIVE } }.onTerminal).isTrue()
        }
}
