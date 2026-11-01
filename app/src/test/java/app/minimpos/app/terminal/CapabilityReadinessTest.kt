package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapabilityReadinessTest {
    private val defaults = AppSettings()

    @Test
    fun `audio guidance distinguishes model eligibility from software and Support enablement`() {
        val physical = TerminalState(poiId = "AMS1-123", apiSetup = ApiSetup.Incomplete(SetupProblem.API_KEY))
        assertThat(physical.audioGuidanceMinimumSoftware).isEqualTo("1.117")
        assertThat(physical.copy(poiId = "P630-123").audioGuidanceMinimumSoftware).isEqualTo("1.124")
        assertThat(physical.copy(poiId = "S1E-123").audioGuidanceMinimumSoftware).isNull()
        assertThat(
            physical.copy(capabilities = DestinationCapabilities(supportsTerminalFeatures = false)).audioGuidanceMinimumSoftware,
        ).isNull()
    }

    @Test
    fun `terminal scanning distinguishes unknown and absent hardware and unsupported destinations`() {
        val state = AdyenCapabilitiesState(capabilities = DestinationCapabilities(scannedWallets = true))
        assertThat(state.terminalScannerStanding).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.copy(nativeScanner = false).terminalScannerStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.copy(nativeScanner = true).terminalScannerStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        val setup = resolve(defaults.copy(terminal = defaults.terminal.copy(mode = TerminalMode.PAYMENTS_APP)))
        assertThat(state.copy(capabilities = setup.capabilities, nativeScanner = true).terminalScannerStanding)
            .isEqualTo(CapabilityStanding.UNSUPPORTED)
    }

    @Test
    fun `printing distinguishes disabled unknown and absent printers`() {
        val terminal =
            defaults.copy(
                terminal = defaults.terminal.copy(mode = TerminalMode.TERMINAL, poiIdOverride = "S1U2-000158213605014"),
            )
        val setup = resolve(terminal)
        val id = checkNotNull(setup.poiId)
        assertThat(setup.printingStanding(emptyMap())).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(setup.printingStanding(mapOf(id to false))).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(setup.printingStanding(mapOf(id to true))).isEqualTo(CapabilityStanding.AVAILABLE)
        val off = resolve(terminal.copy(receipt = terminal.receipt.copy(printerMode = PrinterMode.OFF)))
        assertThat(off.printingStanding(mapOf(id to true))).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        val noPrinter = defaults.copy(simulator = defaults.simulator.copy(hasPrinter = false))
        assertThat(resolve(noPrinter).printingStanding(emptyMap())).isEqualTo(CapabilityStanding.UNSUPPORTED)
    }

    private fun resolve(settings: AppSettings) =
        TerminalSetup.resolve(settings, emptySet(), FakeDevice(), verified = true, pendingImport = false)

    @Test
    fun `Payments app version eligibility uses only the selected environment and never claims beta readiness`() {
        val device = FakeDevice(paymentsAppVersions = mapOf(TerminalEnvironment.TEST to "1.8.0", TerminalEnvironment.LIVE to "1.6.0"))
        val settings = defaults.copy(terminal = defaults.terminal.copy(mode = TerminalMode.PAYMENTS_APP))

        fun standing(environment: TerminalEnvironment) =
            TerminalSetup
                .resolve(
                    settings.copy(terminal = settings.terminal.copy(environment = environment)),
                    emptySet(),
                    device,
                    true,
                    false,
                ).capabilities.remoteStatus
        assertThat(standing(TerminalEnvironment.TEST)).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(standing(TerminalEnvironment.LIVE)).isEqualTo(CapabilityStanding.UNSUPPORTED)
    }
}
