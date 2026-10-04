package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.ConnectionDestination
import io.github.astiskala.minimpos.app.data.settings.ConnectionSetup
import io.github.astiskala.minimpos.app.data.settings.EmailSettings
import io.github.astiskala.minimpos.app.data.settings.HistorySettings
import io.github.astiskala.minimpos.app.data.settings.PaymentSettings
import io.github.astiskala.minimpos.app.data.settings.ReceiptSettings
import io.github.astiskala.minimpos.app.data.settings.ReceiptTipping
import io.github.astiskala.minimpos.app.data.settings.SecuritySettings
import io.github.astiskala.minimpos.app.data.settings.ShopperReferenceSource
import io.github.astiskala.minimpos.app.data.settings.SimulatorSettings
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.core.tax.TaxMode
import io.github.astiskala.minimpos.terminal.transport.CloudRegion
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Test

/** Plain JUnit: the settings model owns the limits of its numbers and which settings belong to one device. */
class AppSettingsTest {
    @Test
    fun `every number is brought within its section's limits`() {
        val wild =
            AppSettings(
                terminal = TerminalSettings(keyVersion = 0, timeoutSeconds = 5_000),
                receipt =
                    ReceiptSettings(
                        charsPerLine = 10,
                        markedTaxRateMilliPercent = -1,
                        markedTaxRateMarker = " ",
                        markedTaxRateNote = " note ",
                    ),
                email = EmailSettings(port = 70_000),
                security = SecuritySettings(autoLockMinutes = -1),
                simulator = SimulatorSettings(delayMillis = -5),
                history = HistorySettings(retentionDays = -3),
            ).normalized()
        assertThat(wild.terminal.keyVersion).isEqualTo(TerminalSettings.KEY_VERSIONS.first)
        assertThat(wild.terminal.timeoutSeconds).isEqualTo(TerminalSettings.MAX_TIMEOUT_SECONDS)
        assertThat(wild.receipt.charsPerLine).isEqualTo(ReceiptSettings.CHARS_PER_LINE.first)
        assertThat(wild.receipt.markedTaxRateMilliPercent).isEqualTo(0)
        assertThat(wild.receipt.markedTaxRateMarker).isEqualTo("※")
        assertThat(wild.receipt.markedTaxRateNote).isEqualTo("note")
        assertThat(ReceiptSettings(markedTaxRateMilliPercent = 100_001, markedTaxRateMarker = " * ").normalized().markedTaxRateMilliPercent)
            .isEqualTo(100_000)
        assertThat(ReceiptSettings(markedTaxRateMarker = " * ").normalized().markedTaxRateMarker).isEqualTo("*")
        assertThat(wild.email.port).isEqualTo(EmailSettings.PORTS.last)
        assertThat(wild.security.autoLockMinutes).isEqualTo(0)
        assertThat(wild.simulator.delayMillis).isEqualTo(0)
        assertThat(wild.history.retentionDays).isEqualTo(0)
        assertThat(TerminalSettings(keyVersion = 10_000).normalized().keyVersion).isEqualTo(TerminalSettings.KEY_VERSIONS.last)
        assertThat(SimulatorSettings(delayMillis = 90_000).normalized().delayMillis).isEqualTo(SimulatorSettings.DELAY_MILLIS.last.toLong())
        // Values within their limits, and the defaults, are left alone.
        assertThat(AppSettings().normalized()).isEqualTo(AppSettings())
    }

    @Test
    fun `a new installation asks for nothing extra at checkout, prints automatically and taxes as is usual there`() {
        val australia = AppSettings.forNewInstallation("AU")
        assertThat(australia.payment.askTransactionReference).isFalse()
        assertThat(australia.payment.shopperReferenceSource).isEqualTo(ShopperReferenceSource.NONE)
        assertThat(australia.payment.asksCustomerReference).isFalse()
        assertThat(australia.receipt.autoPrint).isTrue()
        assertThat(australia.payment.receiptTipping).isEqualTo(ReceiptTipping.DISABLED)
        listOf("", "AU", "HK", "JP", "US").forEach { country ->
            listOf("en", "zh", "ja").forEach { language ->
                assertThat(AppSettings.forNewInstallation(country, language).receipt.showReferences).isTrue()
            }
        }
        assertThat(australia.payment.taxMode).isEqualTo(TaxMode.INCLUSIVE)
        assertThat(AppSettings.forNewInstallation("US").payment.taxMode).isEqualTo(TaxMode.EXCLUSIVE)
        assertThat(AppSettings().payment.askTransactionReference).isFalse()
        assertThat(AppSettings().payment.shopperReferenceSource).isEqualTo(ShopperReferenceSource.NONE)
        assertThat(AppSettings().receipt.autoPrint).isTrue()
        assertThat(australia.copy(payment = PaymentSettings(), receipt = ReceiptSettings())).isEqualTo(AppSettings())
    }

    @Test
    fun `regional receipt defaults remain ordinary configurable settings`() {
        val australia = AppSettings.forNewInstallation("AU")
        assertThat(australia.receipt.markedTaxRateMilliPercent).isEqualTo(0)
        assertThat(australia.receipt.showTaxAmounts).isTrue()
        val hongKong = AppSettings.forNewInstallation("HK")
        assertThat(hongKong.payment.chargeTax).isFalse()
        assertThat(hongKong.receipt.showTaxAmounts).isFalse()
        assertThat(hongKong.receipt.showTaxRateTotals).isFalse()
        listOf("GB", "DE", "RO", "SE").forEach { country ->
            val settings = AppSettings.forNewInstallation(country)
            assertThat(settings.receipt.showTaxRateTotals).isTrue()
            assertThat(settings.receipt.showTaxAmounts).isTrue()
            assertThat(settings.receipt.markedTaxRateMilliPercent).isNull()
        }
        listOf("NZ", "SG", "MY", "US", "CA", "MX", "BR", "").forEach { country ->
            val settings = AppSettings.forNewInstallation(country)
            assertThat(settings.receipt.showTaxRateTotals).isFalse()
            assertThat(settings.receipt.showTaxAmounts).isTrue()
            assertThat(settings.receipt.markedTaxRateMilliPercent).isNull()
        }
    }

    @Test
    fun `Japanese language selects initial receipt style without changing regional pricing`() {
        val japanese = AppSettings.forNewInstallation("AU", "ja")
        assertThat(japanese.receipt.showTaxAmounts).isFalse()
        assertThat(japanese.receipt.showTaxRateTotals).isTrue()
        assertThat(japanese.receipt.markedTaxRateMilliPercent).isEqualTo(8_000)
        assertThat(japanese.payment.taxMode).isEqualTo(TaxMode.INCLUSIVE)
        assertThat(AppSettings.forNewInstallation("US", "ja").payment.taxMode).isEqualTo(TaxMode.EXCLUSIVE)
        assertThat(AppSettings.forNewInstallation("HK", "ja").payment.chargeTax).isFalse()
        assertThat(AppSettings.forNewInstallation(" gb ").receipt.showTaxRateTotals).isTrue()
    }

    @Test
    fun `every EU country and the UK starts with taxable totals by rate`() {
        val countries =
            listOf(
                "AT",
                "BE",
                "BG",
                "CY",
                "CZ",
                "DE",
                "DK",
                "EE",
                "ES",
                "FI",
                "FR",
                "GB",
                "GR",
                "HR",
                "HU",
                "IE",
                "IT",
                "LT",
                "LU",
                "LV",
                "MT",
                "NL",
                "PL",
                "PT",
                "RO",
                "SE",
                "SI",
                "SK",
            )
        countries.forEach { country ->
            assertThat(AppSettings.forNewInstallation(country).receipt.showTaxRateTotals).isTrue()
        }
        assertThat(AppSettings.forNewInstallation("CH").receipt.showTaxRateTotals).isFalse()
    }

    @Test
    fun `only the device's own terminal fields stay behind when settings move to another terminal`() {
        val sender =
            TerminalSettings(
                mode = TerminalMode.TERMINAL,
                environment = TerminalEnvironment.LIVE,
                cloudRegion = CloudRegion.AU,
                host = "192.168.1.20",
                poiIdOverride = "S1F2-000158",
                paymentsAppInstallationId = "INSTALLATION-1",
                storeId = "ST1",
                saleId = "Shop",
                keyIdentifier = "KEY",
                keyVersion = 3,
                timeoutSeconds = 180,
                merchantAccount = "Merchant",
                liveUrlPrefix = "abc",
            )
        val receiver = TerminalSettings(mode = TerminalMode.SIMULATOR, host = "10.0.0.2", environment = TerminalEnvironment.TEST)
        val shared = sender.withDeviceFieldsOf(TerminalSettings())
        assertThat(shared.mode).isEqualTo(TerminalMode.AUTO)
        assertThat(shared.environment).isNull()
        assertThat(shared.host).isEmpty()
        assertThat(shared.poiIdOverride).isEmpty()
        assertThat(shared.cloudRegion).isNull()
        assertThat(shared.paymentsAppInstallationId).isEmpty()
        // The store is shared by the merchant account's devices.
        assertThat(shared.storeId).isEqualTo("ST1")
        assertThat(shared.withDeviceFieldsOf(receiver))
            .isEqualTo(
                sender.copy(
                    mode = TerminalMode.SIMULATOR,
                    environment = TerminalEnvironment.TEST,
                    cloudRegion = null,
                    host = "10.0.0.2",
                    poiIdOverride = "",
                    paymentsAppInstallationId = "",
                ),
            )
    }

    @Test
    fun `the device's fields of every section stay behind, and everything else travels`() {
        val sender =
            AppSettings(
                terminal = TerminalSettings(mode = TerminalMode.TERMINAL, host = "192.168.1.20", merchantAccount = "Merchant"),
                payment = PaymentSettings(defaultTaxRateId = 7, referencePrefix = "SHOP"),
                receipt =
                    ReceiptSettings(
                        title = "Harbour Coffee Co.",
                        showTaxAmounts = false,
                        showTaxRateTotals = true,
                        markedTaxRateMilliPercent = 5_500,
                        markedTaxRateMarker = "*",
                        markedTaxRateNote = "* Reduced rate",
                    ),
                simulator = SimulatorSettings(hasPrinter = false),
                history = HistorySettings(retentionDays = 30),
            )
        val shared = sender.shared()
        assertThat(shared).isEqualTo(shared.withDeviceFieldsOf(AppSettings()))
        assertThat(shared.terminal.host).isEmpty()
        assertThat(shared.payment.defaultTaxRateId).isNull()
        assertThat(shared.simulator).isEqualTo(SimulatorSettings())
        // What does not belong to the device travels as it is.
        assertThat(shared.copy(terminal = sender.terminal, payment = sender.payment, simulator = sender.simulator)).isEqualTo(sender)
        // Putting the device's fields back gives the settings again.
        assertThat(shared.withDeviceFieldsOf(sender)).isEqualTo(sender)

        val receiver = AppSettings(terminal = TerminalSettings(host = "10.0.0.2"), simulator = SimulatorSettings(delayMillis = 0))
        val taken = receiver.takingOver(shared, defaultTaxRateId = 3)
        assertThat(taken.terminal.host).isEqualTo("10.0.0.2")
        assertThat(taken.terminal.merchantAccount).isEqualTo("Merchant")
        assertThat(taken.simulator).isEqualTo(receiver.simulator)
        assertThat(taken.payment).isEqualTo(sender.payment.copy(defaultTaxRateId = 3))
        assertThat(taken.receipt).isEqualTo(sender.receipt)
        assertThat(taken.history).isEqualTo(sender.history)
    }

    @Test
    fun `the helper imports an environment only for a network or cloud terminal`() {
        val network = TerminalSettings(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST)
        assertThat(ConnectionSetup(environment = TerminalEnvironment.LIVE).appliedTo(network, onTerminal = false).environment)
            .isEqualTo(TerminalEnvironment.LIVE)
        val cloud = network.copy(mode = TerminalMode.CLOUD, cloudRegion = CloudRegion.AU)
        val imported = ConnectionSetup(environment = TerminalEnvironment.LIVE).appliedTo(cloud, onTerminal = false)
        assertThat(imported.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(imported.cloudRegion).isNull()
        assertThat(ConnectionSetup(environment = TerminalEnvironment.LIVE).appliedTo(network, onTerminal = true)).isEqualTo(network)
        val app = network.copy(mode = TerminalMode.PAYMENTS_APP)
        assertThat(ConnectionSetup(environment = TerminalEnvironment.LIVE).appliedTo(app, onTerminal = false)).isEqualTo(app)
    }

    @Test
    fun `a connection chooses where payments go only where the device can take them that way`() {
        val simulated =
            TerminalSettings(mode = TerminalMode.SIMULATOR, environment = TerminalEnvironment.TEST, cloudRegion = CloudRegion.AU)
        val phone = { destination: ConnectionDestination -> ConnectionSetup(destination).appliedTo(simulated, onTerminal = false) }
        val terminal = { destination: ConnectionDestination -> ConnectionSetup(destination).appliedTo(simulated, onTerminal = true) }
        assertThat(phone(ConnectionDestination.NETWORK).mode).isEqualTo(TerminalMode.TERMINAL)
        assertThat(phone(ConnectionDestination.CLOUD).mode).isEqualTo(TerminalMode.CLOUD)
        assertThat(phone(ConnectionDestination.TAP_TO_PAY).mode).isEqualTo(TerminalMode.PAYMENTS_APP)
        assertThat(phone(ConnectionDestination.CLOUD).environment).isNull()
        assertThat(phone(ConnectionDestination.CLOUD).cloudRegion).isNull()
        // A phone is no terminal, and a terminal takes payments only itself, where Automatic is that.
        assertThat(phone(ConnectionDestination.THIS_TERMINAL)).isEqualTo(simulated)
        assertThat(terminal(ConnectionDestination.THIS_TERMINAL).mode).isEqualTo(TerminalMode.AUTO)
        ConnectionDestination.entries.filter { it != ConnectionDestination.THIS_TERMINAL }.forEach {
            assertThat(terminal(it)).isEqualTo(simulated)
        }
        // The same destination keeps what was found for it, and blank or missing values change nothing.
        val cloud = simulated.copy(mode = TerminalMode.CLOUD, merchantAccount = "Merchant", storeId = "ST1")
        assertThat(ConnectionSetup(ConnectionDestination.CLOUD, merchantAccount = " ", storeId = "").appliedTo(cloud, onTerminal = false))
            .isEqualTo(cloud)
        assertThat(ConnectionSetup(liveUrlPrefix = " abc ", storeId = "ST2").appliedTo(cloud, onTerminal = false))
            .isEqualTo(cloud.copy(liveUrlPrefix = "abc", storeId = "ST2"))
    }
}
