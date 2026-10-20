package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.secretFingerprint
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

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
    ) = TerminalSetup.resolve(settings.copy(terminal = terminal), saved, device, verified = true, pendingImport = false)

    @Test
    fun `environment selection is required for network cloud and Payments app destinations`() {
        listOf(phone, FakeDevice(model = "Tablet")).forEach { device ->
            listOf(TerminalMode.TERMINAL, TerminalMode.CLOUD, TerminalMode.PAYMENTS_APP).forEach { mode ->
                val setup = resolve(TerminalSettings(mode = mode), device = device)
                assertThat(setup.capabilities.selectsEnvironment).isTrue()
                assertThat(setup.environment).isNull()
                assertThat(setup.connectionProblem).isEqualTo(SetupProblem.ENVIRONMENT)
                assertThat(setup.problem).isEqualTo(SetupProblem.ENVIRONMENT)
            }
        }
        assertThat(resolve(device = onTerminal).capabilities.selectsEnvironment).isFalse()
        assertThat(resolve().capabilities.selectsEnvironment).isFalse()
        val paymentsApp = FakeDevice(paymentsApps = setOf(TerminalEnvironment.LIVE))
        val setup =
            resolve(
                TerminalSettings(mode = TerminalMode.PAYMENTS_APP, environment = TerminalEnvironment.TEST),
                device = paymentsApp,
            )
        assertThat(setup.capabilities.selectsEnvironment).isTrue()
        assertThat(setup.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(setup.connectionProblem).isEqualTo(SetupProblem.PAYMENTS_APP_MISSING)
    }

    @Test
    fun `off a terminal payments go to the simulator, or a terminal with its address, POIID and key`() {
        assertThat(TerminalSetup.automaticMode(phone)).isEqualTo(TerminalMode.SIMULATOR)
        val simulator = resolve()
        assertThat(simulator.mode).isEqualTo(TerminalMode.SIMULATOR)
        assertThat(simulator.poiId).isEqualTo(TerminalSetup.SIMULATOR_POI_ID)
        assertThat(simulator.host).isNull()
        assertThat(simulator.connectionProblem).isNull()
        assertThat(simulator.problem).isNull()
        assertThat(simulator.onTerminal).isFalse()
        assertThat(simulator.capabilities.checksConnection).isFalse()

        val network = TerminalSettings(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST)
        assertThat(resolve(network).poiId).isNull()
        assertThat(resolve(network.copy(poiIdOverride = " S1U2-1 ")).poiId).isEqualTo("S1U2-1")
        assertThat(resolve(network, passphrase).connectionProblem).isEqualTo(SetupProblem.POI_ID)
        val withId = network.copy(poiIdOverride = "S1U2-000158213605014")
        assertThat(resolve(withId, passphrase).connectionProblem).isEqualTo(SetupProblem.HOST)
        val withHost = withId.copy(host = " 10.0.0.9 ")
        assertThat(resolve(withHost, passphrase).connectionProblem).isEqualTo(SetupProblem.KEY_IDENTIFIER)
        assertThat(resolve(withHost, passphrase).host).isEqualTo("10.0.0.9")
        val withKey = withHost.copy(keyIdentifier = "key")
        assertThat(resolve(withKey).connectionProblem).isEqualTo(SetupProblem.PASSPHRASE)
        assertThat(resolve(withKey.copy(keyVersion = 0), passphrase).connectionProblem).isEqualTo(SetupProblem.KEY_VERSION)
        assertThat(resolve(withKey, passphrase).connectionProblem).isNull()
        assertThat(resolve(withKey, passphrase).capabilities.checksConnection).isTrue()
    }

    @Test
    fun `on a terminal its own POIID and localhost apply, and only the shared key reaches it`() {
        assertThat(TerminalSetup.automaticMode(onTerminal)).isEqualTo(TerminalMode.TERMINAL)
        val terminal = TerminalSettings(poiIdOverride = " S1U2-1 ", host = "10.0.0.9")
        val setup = resolve(terminal, device = onTerminal)
        assertThat(setup.mode).isEqualTo(TerminalMode.TERMINAL)
        assertThat(setup.onTerminal).isTrue()
        assertThat(setup.poiId).isEqualTo("S1F2-000158213605014")
        assertThat(setup.host).isEqualTo(TerminalSetup.LOCALHOST)
        assertThat(setup.connectionProblem).isEqualTo(SetupProblem.KEY_IDENTIFIER)
        assertThat(resolve(terminal.copy(keyIdentifier = "key"), passphrase, onTerminal).connectionProblem).isNull()
        // The simulator can still be chosen on a terminal.
        assertThat(resolve(terminal.copy(mode = TerminalMode.SIMULATOR), device = onTerminal).host).isNull()
    }

    @Test
    fun `the Checkout API needs the merchant account, key, environment and live prefix, and payments wait for it`() {
        assertThat(resolve().apiSetup).isEqualTo(ApiSetup.Simulated)
        val terminal = TerminalSettings(mode = TerminalMode.TERMINAL, poiIdOverride = "S1F2-1", host = "10.0.0.9", keyIdentifier = "key")
        val key = setOf(Secret.ADYEN_API_KEY)
        val all = key + Secret.TERMINAL_PASSPHRASE
        assertThat(resolve(terminal).apiSetup.problem).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        assertThat(resolve(terminal, key).apiSetup.problem).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        val merchant = terminal.copy(merchantAccount = "Merchant")
        assertThat(resolve(merchant).apiSetup.problem).isEqualTo(SetupProblem.API_KEY)
        assertThat(resolve(merchant, key).apiSetup.problem).isEqualTo(SetupProblem.ENVIRONMENT)
        val live = merchant.copy(environment = TerminalEnvironment.LIVE)
        assertThat(resolve(live, key).apiSetup.problem).isEqualTo(SetupProblem.LIVE_PREFIX)
        assertThat(resolve(live.copy(liveUrlPrefix = "abc-Company"), key).apiSetup).isEqualTo(ApiSetup.Complete)
        assertThat(resolve(merchant.copy(environment = TerminalEnvironment.TEST), key).apiSetup).isEqualTo(ApiSetup.Complete)

        // The terminal can be reached without it, but payments wait for it, after what the terminal itself needs.
        val passphrase = setOf(Secret.TERMINAL_PASSPHRASE)
        assertThat(resolve(terminal, passphrase).connectionProblem).isEqualTo(SetupProblem.ENVIRONMENT)
        assertThat(
            resolve(terminal.copy(environment = TerminalEnvironment.TEST), passphrase).problem,
        ).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        assertThat(resolve(merchant.copy(environment = TerminalEnvironment.TEST), passphrase).problem).isEqualTo(SetupProblem.API_KEY)
        assertThat(
            resolve(merchant.copy(host = "", environment = TerminalEnvironment.TEST), passphrase).problem,
        ).isEqualTo(SetupProblem.HOST)
        assertThat(resolve(merchant, all).problem).isEqualTo(SetupProblem.ENVIRONMENT)
        assertThat(resolve(live, all).problem).isEqualTo(SetupProblem.LIVE_PREFIX)
    }

    @Test
    fun `payment links are offered with complete Checkout setup or without credentials in the simulator`() {
        val links = AppSettings()
        val key = setOf(Secret.ADYEN_API_KEY)
        val api = TerminalSettings(mode = TerminalMode.TERMINAL, merchantAccount = "Merchant", environment = TerminalEnvironment.TEST)
        assertThat(resolve(api, key, settings = links).paymentLinks).isTrue()
        assertThat(resolve(api, key).paymentLinks).isTrue()
        assertThat(resolve(api, settings = links).paymentLinks).isFalse()
        assertThat(resolve(api.copy(mode = TerminalMode.SIMULATOR), settings = links).paymentLinks).isTrue()
    }

    @Test
    fun `a terminal in the cloud needs the merchant account, the API key and its POIID, but no shared key or address`() {
        val cloud = TerminalSettings(mode = TerminalMode.CLOUD, host = "10.0.0.9", environment = TerminalEnvironment.TEST)
        val key = setOf(Secret.ADYEN_API_KEY)
        assertThat(resolve(cloud, key).connectionProblem).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        val merchant = cloud.copy(merchantAccount = "Merchant")
        assertThat(resolve(merchant).connectionProblem).isEqualTo(SetupProblem.CLOUD_API_KEY)
        assertThat(resolve(merchant, key).connectionProblem).isEqualTo(SetupProblem.POI_ID)
        val ready = resolve(merchant.copy(poiIdOverride = " S1F2-000158213605014 "), key)
        assertThat(ready.connectionProblem).isNull()
        assertThat(ready.poiId).isEqualTo("S1F2-000158213605014")
        assertThat(ready.host).isNull()
        // The same key does the captures, once its environment is known.
        assertThat(ready.apiSetup).isEqualTo(ApiSetup.Complete)
        val live = resolve(merchant.copy(poiIdOverride = "S1F2-1", environment = TerminalEnvironment.LIVE, liveUrlPrefix = "abc"), key)
        assertThat(live.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(live.apiSetup).isEqualTo(ApiSetup.Complete)
        // A printer is judged by the terminal's model, as on the network.
        assertThat(resolve(merchant.copy(poiIdOverride = "S1F2-1"), key).printerAvailable(emptyMap())).isTrue()
    }

    @Test
    fun `the Payments app needs the selected app on a phone boarding and the shared key`() {
        val phone = FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST))
        val tapToPay = TerminalSettings(mode = TerminalMode.PAYMENTS_APP, environment = TerminalEnvironment.TEST, poiIdOverride = "S1F2-1")
        assertThat(resolve(tapToPay, passphrase, onTerminal).connectionProblem).isEqualTo(SetupProblem.PAYMENTS_APP_ON_TERMINAL)
        assertThat(resolve(tapToPay, passphrase).connectionProblem).isEqualTo(SetupProblem.PAYMENTS_APP_MISSING)
        val both = FakeDevice(paymentsApps = TerminalEnvironment.entries.toSet())
        assertThat(resolve(tapToPay, passphrase, both).connectionProblem).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        assertThat(resolve(tapToPay, passphrase, both).environment).isEqualTo(TerminalEnvironment.TEST)
        // Then, in the order Settings asks for them: the merchant account boarding needs, boarding, the shared key.
        assertThat(resolve(tapToPay, passphrase, phone).connectionProblem).isEqualTo(SetupProblem.MERCHANT_ACCOUNT)
        val merchant = tapToPay.copy(merchantAccount = "Merchant")
        assertThat(resolve(merchant, passphrase, phone).connectionProblem).isEqualTo(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
        assertThat(resolve(merchant.copy(paymentsAppInstallationId = "INSTALLATION-1"), passphrase, phone).connectionProblem)
            .isEqualTo(SetupProblem.KEY_IDENTIFIER)
        val keyed = merchant.copy(keyIdentifier = "key")
        // The configured POIID is not the Payments app's: its installation ID is.
        assertThat(resolve(keyed, passphrase, phone).poiId).isNull()
        val boarded = resolve(keyed.copy(paymentsAppInstallationId = "INSTALLATION-1"), passphrase, phone)
        assertThat(boarded.connectionProblem).isNull()
        assertThat(boarded.problem).isEqualTo(SetupProblem.API_KEY)
        assertThat(boarded.paymentsApps).containsExactly(TerminalEnvironment.TEST)
        assertThat(boarded.poiId).isEqualTo("INSTALLATION-1")
        // The selected environment also supplies the Checkout endpoint; there is no printer.
        assertThat(boarded.environment).isEqualTo(TerminalEnvironment.TEST)
        val api = keyed.copy(paymentsAppInstallationId = "INSTALLATION-1", merchantAccount = "Merchant")
        assertThat(resolve(api, passphrase + Secret.ADYEN_API_KEY, phone).apiSetup).isEqualTo(ApiSetup.Complete)
        assertThat(boarded.printerAvailable(mapOf("INSTALLATION-1" to true))).isFalse()
        val forcedPrinter =
            boarded.copy(
                settings = boarded.settings.copy(receipt = boarded.settings.receipt.copy(printerMode = PrinterMode.ON)),
            )
        assertThat(forcedPrinter.printerAvailable(mapOf("INSTALLATION-1" to true))).isFalse()
    }

    @Test
    fun `Tap to Pay boards only the selected Payments app with the merchant account and Adyen API key`() {
        val phone = FakeDevice(paymentsApps = setOf(TerminalEnvironment.LIVE))
        val merchant =
            AppSettings().let {
                it.copy(terminal = it.terminal.copy(merchantAccount = " Merchant ", environment = TerminalEnvironment.LIVE))
            }

        fun boarding(
            device: DeviceInfo = phone,
            settings: AppSettings = merchant,
            read: Map<Secret, String?> = mapOf(Secret.ADYEN_API_KEY to "adyen-key"),
        ) = TerminalSetup.boarding(settings, device, read)
        val ready = BoardingOwner.Ready(TerminalEnvironment.LIVE, "Merchant", "adyen-key")
        assertThat(boarding()).isEqualTo(ready)
        assertThat(ready.toString()).doesNotContain("adyen-key")
        assertThat(ready.fingerprint).isEqualTo(secretFingerprint("adyen-key"))
        assertThat(boarding(onTerminal)).isEqualTo(BoardingOwner.Blocked(SetupProblem.PAYMENTS_APP_ON_TERMINAL))
        assertThat(boarding(FakeDevice())).isEqualTo(BoardingOwner.Blocked(SetupProblem.PAYMENTS_APP_MISSING))
        val both = FakeDevice(paymentsApps = TerminalEnvironment.entries.toSet())
        assertThat(boarding(both)).isEqualTo(ready)
        assertThat(boarding(settings = merchant.copy(terminal = merchant.terminal.copy(environment = null))))
            .isEqualTo(BoardingOwner.Blocked(SetupProblem.ENVIRONMENT))
        assertThat(boarding(settings = merchant.copy(terminal = merchant.terminal.copy(environment = TerminalEnvironment.TEST))))
            .isEqualTo(BoardingOwner.Blocked(SetupProblem.PAYMENTS_APP_MISSING))
        assertThat(boarding(settings = merchant.copy(terminal = merchant.terminal.copy(merchantAccount = ""))))
            .isEqualTo(BoardingOwner.Blocked(SetupProblem.MERCHANT_ACCOUNT))
        assertThat(boarding(read = emptyMap())).isEqualTo(BoardingOwner.Blocked(SetupProblem.API_KEY))
        // A key saved but no longer readable has to be entered again.
        assertThat(boarding(read = mapOf(Secret.ADYEN_API_KEY to null))).isEqualTo(BoardingOwner.Blocked(SetupProblem.UNREADABLE_API_KEY))
    }

    @Test
    fun `the secrets a setup needs are read once, and one that cannot be decrypted is what to enter again`() {
        val local =
            TerminalSettings(
                mode = TerminalMode.TERMINAL,
                poiIdOverride = "S1U2-1",
                host = "10.0.0.9",
                keyIdentifier = "key",
                environment = TerminalEnvironment.TEST,
            )
        val api = local.copy(merchantAccount = "Merchant", environment = TerminalEnvironment.TEST)
        val both = passphrase + Secret.ADYEN_API_KEY
        val setup = resolve(api, both)
        assertThat(setup.secretsToRead(both)).containsExactly(Secret.TERMINAL_PASSPHRASE, Secret.ADYEN_API_KEY)
        assertThat(setup.secretsToRead(passphrase)).containsExactly(Secret.TERMINAL_PASSPHRASE)
        assertThat(resolve().secretsToRead(both)).containsExactly(Secret.ADYEN_API_KEY)

        val unlocked = setup.unlock(mapOf(Secret.TERMINAL_PASSPHRASE to "correct horse", Secret.ADYEN_API_KEY to "api-key"))
        assertThat(unlocked.setup).isEqualTo(setup)
        assertThat(unlocked.terminalKey!!.keyIdentifier).isEqualTo("key")
        assertThat(unlocked.apiKey).isEqualTo("api-key")
        assertThat(unlocked.toString()).doesNotContain("correct horse")
        assertThat(unlocked.toString()).doesNotContain("api-key")

        val noPassphrase = setup.unlock(mapOf(Secret.TERMINAL_PASSPHRASE to null, Secret.ADYEN_API_KEY to "api-key"))
        assertThat(noPassphrase.setup.connectionProblem).isEqualTo(SetupProblem.UNREADABLE_PASSPHRASE)
        assertThat(noPassphrase.terminalKey).isNull()
        assertThat(noPassphrase.setup.apiSetup).isEqualTo(ApiSetup.Complete)
        val noApiKey = setup.unlock(mapOf(Secret.TERMINAL_PASSPHRASE to "correct horse", Secret.ADYEN_API_KEY to null))
        assertThat(noApiKey.setup.connectionProblem).isNull()
        assertThat(noApiKey.setup.apiSetup).isEqualTo(ApiSetup.Incomplete(SetupProblem.UNREADABLE_API_KEY))
        // What is missing anyway comes first.
        assertThat(resolve(local.copy(host = ""), both).unlock(mapOf(Secret.TERMINAL_PASSPHRASE to null)).setup.connectionProblem)
            .isEqualTo(SetupProblem.HOST)
        // In the cloud the API key is what reaches the terminal.
        val cloud =
            TerminalSettings(
                mode = TerminalMode.CLOUD,
                merchantAccount = "Merchant",
                poiIdOverride = "S1F2-1",
                environment = TerminalEnvironment.TEST,
            )
        val key = setOf(Secret.ADYEN_API_KEY)
        assertThat(resolve(cloud, key).unlock(mapOf(Secret.ADYEN_API_KEY to null)).setup.connectionProblem)
            .isEqualTo(SetupProblem.UNREADABLE_API_KEY)
    }

    @Test
    fun `each destination says what it can do`() {
        val tapToPay = FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST))
        val destinations =
            mapOf(
                TerminalMode.SIMULATOR to resolve(),
                TerminalMode.TERMINAL to resolve(TerminalSettings(mode = TerminalMode.TERMINAL)),
                TerminalMode.CLOUD to resolve(TerminalSettings(mode = TerminalMode.CLOUD)),
                TerminalMode.PAYMENTS_APP to resolve(TerminalSettings(mode = TerminalMode.PAYMENTS_APP), device = tapToPay),
            ).mapValues { it.value.destination }
        destinations.forEach { (mode, destination) -> assertThat(destination.mode).isEqualTo(mode) }
        assertThat(resolve(TerminalSettings(mode = TerminalMode.AUTO), device = onTerminal).destination)
            .isEqualTo(destinations[TerminalMode.TERMINAL])
        // Only the Payments app takes no abort or diagnosis, and gives up on a missing answer sooner.
        assertThat(destinations.filterValues { !it.aborts }.keys).containsExactly(TerminalMode.PAYMENTS_APP)
        assertThat(destinations.filterValues { !it.diagnoses }.keys).containsExactly(TerminalMode.PAYMENTS_APP)
        assertThat(destinations.getValue(TerminalMode.PAYMENTS_APP).recovery.attempts).isEqualTo(1)
        // A cloud payment waits as long as Adyen requires, every other destination as long as Adyen advises.
        assertThat(destinations.getValue(TerminalMode.CLOUD).transactionTimeout).isEqualTo(160.seconds)
        assertThat(destinations.getValue(TerminalMode.TERMINAL).transactionTimeout).isEqualTo(TerminalClient.DEFAULT_TRANSACTION_TIMEOUT)
        // Each is reached with its own secret.
        assertThat(destinations.mapValues { it.value.secrets })
            .containsExactly(
                TerminalMode.SIMULATOR,
                emptySet<Secret>(),
                TerminalMode.TERMINAL,
                setOf(Secret.TERMINAL_PASSPHRASE),
                TerminalMode.CLOUD,
                setOf(Secret.ADYEN_API_KEY),
                TerminalMode.PAYMENTS_APP,
                setOf(Secret.TERMINAL_PASSPHRASE),
            )
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
        val ams1Id = "AMS1-000168223606144"
        val ams1 = resolve(device = FakeDevice(detectedPoiId = ams1Id))
        assertThat(ams1.printerAvailable(emptyMap())).isFalse()
        assertThat(ams1.printerAvailable(mapOf(ams1Id to false))).isFalse()
        val u2 = resolve(terminal.copy(poiIdOverride = "S1U2-000158213605014"))
        assertThat(u2.printerAvailable(emptyMap())).isFalse()
        assertThat(u2.printerAvailable(mapOf("S1U2-000158213605014" to true))).isTrue()
        assertThat(resolve(terminal).printerAvailable(emptyMap())).isFalse()
    }

    @Test
    fun `each destination knows which of its details are already supplied`() {
        // The simulator has no connection details to supply.
        val rules = DestinationRules.of(TerminalMode.SIMULATOR, phone)
        assertThat(rules.suppliedDetails(TerminalSettings(host = "192.168.1.9"), emptySet())).isFalse()

        // A terminal: address, POIID, key identifier or a saved passphrase.
        val terminal = DestinationRules.of(TerminalMode.TERMINAL, phone)
        assertThat(terminal.suppliedDetails(TerminalSettings(), emptySet())).isFalse()
        assertThat(terminal.suppliedDetails(TerminalSettings(host = "192.168.1.9"), emptySet())).isTrue()
        assertThat(terminal.suppliedDetails(TerminalSettings(poiIdOverride = "S1F2-000158213605014"), emptySet())).isTrue()
        assertThat(terminal.suppliedDetails(TerminalSettings(keyIdentifier = "key"), emptySet())).isTrue()
        assertThat(terminal.suppliedDetails(TerminalSettings(), passphrase)).isTrue()

        // A cloud terminal: only its POIID; a saved passphrase belongs to no cloud terminal.
        val cloud = DestinationRules.of(TerminalMode.CLOUD, phone)
        assertThat(cloud.suppliedDetails(TerminalSettings(), passphrase)).isFalse()
        assertThat(cloud.suppliedDetails(TerminalSettings(poiIdOverride = "S1F2-000158213605014"), emptySet())).isTrue()

        // Tap to Pay: boarding or the shared key.
        val paymentsApp = DestinationRules.of(TerminalMode.PAYMENTS_APP, phone)
        assertThat(paymentsApp.suppliedDetails(TerminalSettings(), emptySet())).isFalse()
        assertThat(paymentsApp.suppliedDetails(TerminalSettings(paymentsAppInstallationId = "installed"), emptySet())).isTrue()
        assertThat(paymentsApp.suppliedDetails(TerminalSettings(keyIdentifier = "key"), emptySet())).isTrue()
        assertThat(paymentsApp.suppliedDetails(TerminalSettings(), passphrase)).isTrue()
    }
}
