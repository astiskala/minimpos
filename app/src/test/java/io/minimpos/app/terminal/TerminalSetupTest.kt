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
    ) = TerminalSetup.resolve(settings.copy(terminal = terminal), saved, device)

    @Test
    fun `off a terminal payments go to the simulator, or a terminal with its address, POIID and key`() {
        assertThat(TerminalSetup.automaticMode(phone)).isEqualTo(TerminalMode.SIMULATOR)
        val simulator = resolve()
        assertThat(simulator.mode).isEqualTo(TerminalMode.SIMULATOR)
        assertThat(simulator.poiId).isEqualTo(TerminalSetup.SIMULATOR_POI_ID)
        assertThat(simulator.host).isNull()
        assertThat(simulator.problem).isNull()
        assertThat(simulator.onTerminal).isFalse()
        assertThat(simulator.checksConnection).isFalse()

        val network = TerminalSettings(mode = TerminalMode.TERMINAL)
        assertThat(resolve(network).poiId).isNull()
        assertThat(resolve(network.copy(poiIdOverride = " S1U2-1 ")).poiId).isEqualTo("S1U2-1")
        assertThat(resolve(network, passphrase).problem).isEqualTo(SetupProblem.POI_ID)
        val withId = network.copy(poiIdOverride = "S1U2-000158213605014")
        assertThat(resolve(withId, passphrase).problem).isEqualTo(SetupProblem.HOST)
        val withHost = withId.copy(host = " 10.0.0.9 ")
        assertThat(resolve(withHost, passphrase).problem).isEqualTo(SetupProblem.KEY_IDENTIFIER)
        assertThat(resolve(withHost, passphrase).host).isEqualTo("10.0.0.9")
        val withKey = withHost.copy(keyIdentifier = "key")
        assertThat(resolve(withKey).problem).isEqualTo(SetupProblem.PASSPHRASE)
        assertThat(resolve(withKey.copy(keyVersion = 0), passphrase).problem).isEqualTo(SetupProblem.KEY_VERSION)
        assertThat(resolve(withKey, passphrase).problem).isNull()
        assertThat(resolve(withKey, passphrase).checksConnection).isTrue()
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
        assertThat(setup.problem).isEqualTo(SetupProblem.KEY_IDENTIFIER)
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
        assertThat(resolve(terminal, key).apiSetup.problem).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        val merchant = terminal.copy(merchantAccount = "Merchant")
        assertThat(resolve(merchant).apiSetup.problem).isEqualTo(SetupProblem.API_KEY)
        assertThat(resolve(merchant, key).apiSetup.problem).isEqualTo(SetupProblem.ENVIRONMENT)
        val live = merchant.copy(environment = TerminalEnvironment.LIVE)
        assertThat(resolve(live, key).apiSetup.problem).isEqualTo(SetupProblem.LIVE_PREFIX)
        assertThat(resolve(live.copy(liveUrlPrefix = "abc-Company"), key).apiSetup).isEqualTo(ApiSetup.Complete)
        assertThat(resolve(merchant.copy(environment = TerminalEnvironment.TEST), key).apiSetup).isEqualTo(ApiSetup.Complete)
    }

    @Test
    fun `a terminal in the cloud needs the merchant account, the API key and its POIID, but no shared key or address`() {
        val cloud = TerminalSettings(mode = TerminalMode.CLOUD, host = "10.0.0.9")
        val key = setOf(Secret.CHECKOUT_API_KEY)
        assertThat(resolve(cloud, key).problem).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        val merchant = cloud.copy(merchantAccount = "Merchant")
        assertThat(resolve(merchant).problem).isEqualTo(SetupProblem.CLOUD_API_KEY)
        assertThat(resolve(merchant, key).problem).isEqualTo(SetupProblem.POI_ID)
        val ready = resolve(merchant.copy(poiIdOverride = " S1F2-000158213605014 "), key)
        assertThat(ready.problem).isNull()
        assertThat(ready.poiId).isEqualTo("S1F2-000158213605014")
        assertThat(ready.host).isNull()
        // The same key does the captures, once its environment is known.
        assertThat(ready.apiSetup.problem).isEqualTo(SetupProblem.ENVIRONMENT)
        val live = resolve(merchant.copy(poiIdOverride = "S1F2-1", environment = TerminalEnvironment.LIVE, liveUrlPrefix = "abc"), key)
        assertThat(live.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(live.apiSetup).isEqualTo(ApiSetup.Complete)
        // A printer is judged by the terminal's model, as on the network.
        assertThat(resolve(merchant.copy(poiIdOverride = "S1F2-1"), key).printerAvailable(emptyMap())).isTrue()
    }

    @Test
    fun `the Payments app needs one installed app on a phone, the shared key and boarding`() {
        val phone = FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST))
        val tapToPay = TerminalSettings(mode = TerminalMode.PAYMENTS_APP, poiIdOverride = "S1F2-1")
        assertThat(resolve(tapToPay, passphrase, onTerminal).problem).isEqualTo(SetupProblem.PAYMENTS_APP_ON_TERMINAL)
        assertThat(resolve(tapToPay, passphrase).problem).isEqualTo(SetupProblem.PAYMENTS_APP_MISSING)
        val both = FakeDevice(paymentsApps = TerminalEnvironment.entries.toSet())
        assertThat(resolve(tapToPay, passphrase, both).problem).isEqualTo(SetupProblem.PAYMENTS_APP_AMBIGUOUS)
        assertThat(resolve(tapToPay, passphrase, both).environment).isNull()
        assertThat(resolve(tapToPay, passphrase, phone).problem).isEqualTo(SetupProblem.KEY_IDENTIFIER)
        val keyed = tapToPay.copy(keyIdentifier = "key")
        assertThat(resolve(keyed, passphrase, phone).problem).isEqualTo(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
        // The configured POIID is not the Payments app's: its installation ID is.
        assertThat(resolve(keyed, passphrase, phone).poiId).isNull()
        val boarded = resolve(keyed.copy(paymentsAppInstallationId = "INSTALLATION-1"), passphrase, phone)
        assertThat(boarded.problem).isNull()
        assertThat(boarded.poiId).isEqualTo("INSTALLATION-1")
        // The installed Payments app tells the environment, so captures need no connection first; there is no printer.
        assertThat(boarded.environment).isEqualTo(TerminalEnvironment.TEST)
        val api = keyed.copy(paymentsAppInstallationId = "INSTALLATION-1", merchantAccount = "Merchant")
        assertThat(resolve(api, passphrase + Secret.CHECKOUT_API_KEY, phone).apiSetup).isEqualTo(ApiSetup.Complete)
        assertThat(boarded.printerAvailable(mapOf("INSTALLATION-1" to true))).isFalse()
    }

    @Test
    fun `Tap to Pay is boarded on a phone with one Payments app, for the merchant account, with its API key`() {
        val phone = FakeDevice(paymentsApps = setOf(TerminalEnvironment.LIVE))
        val merchant = AppSettings().let { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        val key = setOf(Secret.PAYMENTS_APP_API_KEY)

        fun boarding(
            device: DeviceInfo = phone,
            settings: AppSettings = merchant,
            saved: Set<Secret> = key,
        ) = TerminalSetup.boarding(settings, saved, device)
        assertThat(boarding()).isEqualTo(BoardingSetup.Ready(TerminalEnvironment.LIVE))
        assertThat(boarding(onTerminal)).isEqualTo(BoardingSetup.Blocked(SetupProblem.PAYMENTS_APP_ON_TERMINAL))
        assertThat(boarding(FakeDevice())).isEqualTo(BoardingSetup.Blocked(SetupProblem.PAYMENTS_APP_MISSING))
        val both = FakeDevice(paymentsApps = TerminalEnvironment.entries.toSet())
        assertThat(boarding(both)).isEqualTo(BoardingSetup.Blocked(SetupProblem.PAYMENTS_APP_AMBIGUOUS))
        assertThat(boarding(settings = AppSettings())).isEqualTo(BoardingSetup.Blocked(SetupProblem.MERCHANT_ACCOUNT))
        assertThat(boarding(saved = passphrase)).isEqualTo(BoardingSetup.Blocked(SetupProblem.PAYMENTS_APP_API_KEY))
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
