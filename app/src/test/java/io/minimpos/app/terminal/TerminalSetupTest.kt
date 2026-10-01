package io.minimpos.app.terminal

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.FakeDevice
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.CaptureMode
import io.minimpos.app.data.settings.PrinterMode
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Test

/** Plain JUnit: where payments go and what is missing follows from the settings, the saved secrets and the device. */
class TerminalSetupTest {
    private val phone = FakeDevice()
    private val onTerminal = FakeDevice(detectedPoiId = "S1F2-000158213605014")
    private val passphrase = setOf(Secret.TERMINAL_PASSPHRASE)

    private fun resolve(
        terminal: TerminalSettings = TerminalSettings(),
        saved: Set<Secret> = emptySet(),
        device: DeviceInfo = phone,
        settings: AppSettings = AppSettings(),
    ) = TerminalSetup.resolve(settings.copy(terminal = terminal), saved, device, TerminalTexts())

    @Test
    fun `off a terminal payments go to the simulator, or a terminal with its address, POIID and key`() {
        assertThat(TerminalSetup.automaticMode(phone)).isEqualTo(TerminalMode.SIMULATOR)
        val simulator = resolve()
        assertThat(simulator.mode).isEqualTo(TerminalMode.SIMULATOR)
        assertThat(simulator.poiId).isEqualTo(TerminalSetup.SIMULATOR_POI_ID)
        assertThat(simulator.host).isNull()
        assertThat(simulator.problem).isNull()
        assertThat(simulator.onTerminal).isFalse()

        val network = TerminalSettings(mode = TerminalMode.TERMINAL)
        assertThat(resolve(network).poiId).isNull()
        assertThat(resolve(network.copy(poiIdOverride = " S1U2-1 ")).poiId).isEqualTo("S1U2-1")
        assertThat(resolve(network, passphrase).problem).contains("POIID")
        val withId = network.copy(poiIdOverride = "S1U2-000158213605014")
        assertThat(resolve(withId, passphrase).problem).contains("IP address")
        val withHost = withId.copy(host = " 10.0.0.9 ")
        assertThat(resolve(withHost, passphrase).problem).contains("key identifier")
        assertThat(resolve(withHost, passphrase).host).isEqualTo("10.0.0.9")
        val withKey = withHost.copy(keyIdentifier = "key")
        assertThat(resolve(withKey).problem).contains("passphrase")
        assertThat(resolve(withKey.copy(keyVersion = 0), passphrase).problem).contains("version")
        assertThat(resolve(withKey, passphrase).problem).isNull()
    }

    @Test
    fun `on a terminal its own POIID and localhost apply, and only the shared key is needed`() {
        assertThat(TerminalSetup.automaticMode(onTerminal)).isEqualTo(TerminalMode.TERMINAL)
        val terminal = TerminalSettings(poiIdOverride = " S1U2-1 ", host = "10.0.0.9")
        val setup = resolve(terminal, device = onTerminal)
        assertThat(setup.mode).isEqualTo(TerminalMode.TERMINAL)
        assertThat(setup.onTerminal).isTrue()
        assertThat(setup.poiId).isEqualTo("S1F2-000158213605014")
        assertThat(setup.host).isEqualTo(TerminalSetup.LOCALHOST)
        assertThat(setup.problem).contains("key identifier")
        assertThat(resolve(terminal.copy(keyIdentifier = "key"), passphrase, onTerminal).problem).isNull()
        // The simulator can still be chosen on a terminal.
        assertThat(resolve(terminal.copy(mode = TerminalMode.SIMULATOR), device = onTerminal).host).isNull()
    }

    @Test
    fun `the Checkout API is used once anything of it is set up, and needs the environment and live prefix`() {
        assertThat(resolve().apiSetup).isEqualTo(ApiSetup.Simulated)
        val terminal = TerminalSettings(mode = TerminalMode.TERMINAL)
        val key = setOf(Secret.CHECKOUT_API_KEY)
        assertThat(resolve(terminal).apiSetup).isEqualTo(ApiSetup.CustomerArea)
        assertThat(resolve(terminal).apiSetup.mode).isEqualTo(CaptureMode.CUSTOMER_AREA)
        assertThat(resolve(terminal).apiSetup.problem).isNull()
        assertThat(resolve(terminal, key).apiSetup.mode).isEqualTo(CaptureMode.API)
        assertThat(resolve(terminal, key).apiSetup.problem).isEqualTo("Enter the merchant account in Terminal settings")
        val merchant = terminal.copy(merchantAccount = "Merchant")
        assertThat(resolve(merchant).apiSetup.problem).isEqualTo("Enter the Checkout API key in Terminal settings")
        assertThat(resolve(merchant, key).apiSetup.problem).contains("Test the connection to the terminal first")
        val live = merchant.copy(environment = TerminalEnvironment.LIVE)
        assertThat(resolve(live, key).apiSetup.problem).isEqualTo("Enter the live URL prefix in Terminal settings")
        assertThat(resolve(live.copy(liveUrlPrefix = "abc-Company"), key).apiSetup).isEqualTo(ApiSetup.Complete)
        assertThat(resolve(merchant.copy(environment = TerminalEnvironment.TEST), key).apiSetup).isEqualTo(ApiSetup.Complete)
    }

    @Test
    fun `printing follows the printer setting, the simulator and what the terminal reported`() {
        val defaults = AppSettings()
        assertThat(resolve().printerAvailable(emptyMap())).isTrue()
        val noPrinter = defaults.copy(simulator = defaults.simulator.copy(hasPrinter = false))
        assertThat(resolve(settings = noPrinter).printerAvailable(emptyMap())).isFalse()
        val on = noPrinter.copy(receipt = defaults.receipt.copy(printerMode = PrinterMode.ON))
        assertThat(resolve(settings = on).printerAvailable(emptyMap())).isTrue()
        val off = defaults.copy(receipt = defaults.receipt.copy(printerMode = PrinterMode.OFF))
        assertThat(resolve(settings = off).printerAvailable(emptyMap())).isFalse()

        // A terminal not asked yet is judged by its model, then by what it reported.
        val terminal = TerminalSettings(mode = TerminalMode.TERMINAL)
        val f2 = resolve(terminal.copy(poiIdOverride = "S1F2-000158213605014"))
        assertThat(f2.printerAvailable(emptyMap())).isTrue()
        assertThat(f2.printerAvailable(mapOf("S1F2-000158213605014" to false))).isFalse()
        val u2 = resolve(terminal.copy(poiIdOverride = "S1U2-000158213605014"))
        assertThat(u2.printerAvailable(emptyMap())).isFalse()
        assertThat(u2.printerAvailable(mapOf("S1U2-000158213605014" to true))).isTrue()
        assertThat(resolve(terminal).printerAvailable(emptyMap())).isFalse()
    }
}
