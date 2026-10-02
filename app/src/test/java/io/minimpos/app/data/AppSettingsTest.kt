package io.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.ConnectionDestination
import io.minimpos.app.data.settings.ConnectionSetup
import io.minimpos.app.data.settings.EmailSettings
import io.minimpos.app.data.settings.HistorySettings
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.app.data.settings.ReceiptSettings
import io.minimpos.app.data.settings.SecuritySettings
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.app.data.settings.SimulatorSettings
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.core.tax.TaxMode
import io.minimpos.terminal.transport.CloudRegion
import io.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Test

/** Plain JUnit: the settings model owns the limits of its numbers and which settings belong to one device. */
class AppSettingsTest {
    @Test
    fun `every number is brought within its section's limits`() {
        val wild =
            AppSettings(
                terminal = TerminalSettings(keyVersion = 0, timeoutSeconds = 5_000),
                receipt = ReceiptSettings(charsPerLine = 10),
                email = EmailSettings(port = 70_000),
                security = SecuritySettings(autoLockMinutes = -1),
                simulator = SimulatorSettings(delayMillis = -5),
                history = HistorySettings(retentionDays = -3),
            ).normalized()
        assertThat(wild.terminal.keyVersion).isEqualTo(TerminalSettings.KEY_VERSIONS.first)
        assertThat(wild.terminal.timeoutSeconds).isEqualTo(TerminalSettings.MAX_TIMEOUT_SECONDS)
        assertThat(wild.receipt.charsPerLine).isEqualTo(ReceiptSettings.CHARS_PER_LINE.first)
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
        assertThat(australia.payment.taxMode).isEqualTo(TaxMode.INCLUSIVE)
        assertThat(AppSettings.forNewInstallation("US").payment.taxMode).isEqualTo(TaxMode.EXCLUSIVE)
        // The constructor's defaults are what a value left out of a transfer means, so they keep their old meaning.
        assertThat(AppSettings().payment.askTransactionReference).isTrue()
        assertThat(AppSettings().payment.shopperReferenceSource).isEqualTo(ShopperReferenceSource.CUSTOMER_REFERENCE)
        assertThat(AppSettings().receipt.autoPrint).isFalse()
        assertThat(australia.copy(payment = PaymentSettings(), receipt = ReceiptSettings())).isEqualTo(AppSettings())
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
                receipt = ReceiptSettings(title = "Harbour Coffee Co."),
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
