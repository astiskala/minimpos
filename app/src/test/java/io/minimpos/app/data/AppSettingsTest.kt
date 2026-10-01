package io.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.EmailSettings
import io.minimpos.app.data.settings.HistorySettings
import io.minimpos.app.data.settings.ReceiptSettings
import io.minimpos.app.data.settings.SecuritySettings
import io.minimpos.app.data.settings.SimulatorSettings
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Test

/** Plain JUnit: the settings model owns the limits of its numbers and which terminal settings belong to one device. */
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
    fun `only the device's own terminal fields stay behind when settings move to another terminal`() {
        val sender =
            TerminalSettings(
                mode = TerminalMode.TERMINAL,
                environment = TerminalEnvironment.LIVE,
                host = "192.168.1.20",
                poiIdOverride = "S1F2-000158",
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
        assertThat(shared.withDeviceFieldsOf(receiver))
            .isEqualTo(
                sender.copy(mode = TerminalMode.SIMULATOR, environment = TerminalEnvironment.TEST, host = "10.0.0.2", poiIdOverride = ""),
            )
    }
}
