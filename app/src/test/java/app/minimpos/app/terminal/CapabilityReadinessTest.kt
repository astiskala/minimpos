package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.WalletMethod
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapabilityReadinessTest {
    private val defaults = AppSettings()
    private val wallet =
        WalletMethod(
            type = "wechatpay_pos",
            enabled = true,
            allowed = true,
            channel = "pos",
            currencies = setOf("AUD"),
        )

    @Test
    fun `audio guidance distinguishes model eligibility from software and Support enablement`() {
        val physical = TerminalState(poiId = "AMS1-123", apiSetup = ApiSetup.Incomplete(SetupProblem.API_KEY))
        assertThat(physical.audioGuidanceMinimumSoftware).isEqualTo("1.117")
        assertThat(physical.copy(poiId = "P630-123").audioGuidanceMinimumSoftware).isEqualTo("1.124")
        assertThat(physical.copy(poiId = "S1E-123").audioGuidanceMinimumSoftware).isNull()
        assertThat(
            physical
                .copy(capabilities = DestinationCapabilities(supportsTerminalFeatures = false))
                .audioGuidanceMinimumSoftware,
        ).isNull()
        assertThat(
            AdyenCapabilitiesState(
                context =
                    PaymentContext("TERMINAL", "AMS1-123", "POS1", "merchant", "TEST", null),
            ).audioGuidanceStanding,
        ).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(AdyenCapabilitiesState(simulated = true).audioGuidanceStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(AdyenCapabilitiesState().audioGuidanceStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
    }

    @Test
    fun `Tap to Pay leaves device camera usable but blocks terminal-only features`() {
        val setup = resolve(defaults.copy(terminal = defaults.terminal.copy(mode = TerminalMode.PAYMENTS_APP)))
        val state = AdyenCapabilitiesState(capabilities = setup.capabilities, cameraAvailable = true, nativeScanner = true)
        assertThat(state.cameraScanningStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.terminalScannerStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.scannedWalletStanding("AUD")).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.abortStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.diagnosisStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.remoteStatusStanding).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.receiptTippingStanding(true, CapabilityStanding.AVAILABLE)).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.partialAuthStanding(true)).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.partialAuthStanding(false)).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
    }

    @Test
    fun `scanning separates unsupported hardware unverified hardware and wallet configuration`() {
        val state = AdyenCapabilitiesState(capabilities = DestinationCapabilities(scannedWallets = true))
        assertThat(state.cameraScanningStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.terminalScannerStanding).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.scannedWalletStanding("AUD")).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.copy(nativeScanner = false).terminalScannerStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.copy(nativeScanner = false).scannedWalletStanding("AUD")).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.copy(nativeScanner = true).terminalScannerStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.copy(nativeScanner = true).scannedWalletStanding("AUD")).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        val configured = state.copy(cameraAvailable = true, nativeScanner = false, methods = listOf(wallet))
        assertThat(configured.scannedWalletStanding("AUD")).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(configured.scannedWalletStanding("JPY")).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(configured.copy(cameraAvailable = false).scannedWalletStanding("AUD")).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(configured.copy(cameraAvailable = false, nativeScanner = true).scannedWalletStanding("AUD"))
            .isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.copy(simulated = true, nativeScanner = false).scannedWalletStanding("AUD")).isEqualTo(CapabilityStanding.AVAILABLE)
    }

    @Test
    fun `printing and receipt tips distinguish disabled unknown and absent printers`() {
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
        val state = AdyenCapabilitiesState()
        assertThat(state.receiptTippingStanding(false, CapabilityStanding.AVAILABLE)).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(
            state.receiptTippingStanding(true, CapabilityStanding.NEEDS_CONFIGURATION),
        ).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.receiptTippingStanding(true, CapabilityStanding.UNSUPPORTED)).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(state.receiptTippingStanding(true, CapabilityStanding.AVAILABLE)).isEqualTo(CapabilityStanding.AVAILABLE)
        val noPrinter = defaults.copy(simulator = defaults.simulator.copy(hasPrinter = false))
        assertThat(resolve(noPrinter).printingStanding(emptyMap())).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(TerminalState(onTerminal = true).receiptSharingStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(TerminalState(onTerminal = false).receiptSharingStanding).isEqualTo(CapabilityStanding.AVAILABLE)
    }

    @Test
    fun `local offers must be enabled even when Adyen has configured them`() {
        val state = AdyenCapabilitiesState(simulated = true, moto = MotoCapability(enabled = true))
        assertThat(state.tippingStanding("AUD", false)).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.givingStanding(false)).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.motoStanding(false)).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.abortStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.diagnosisStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.remoteStatusStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.shopperDisplayStanding(true)).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.shopperDisplayStanding(false)).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        val unconfigured = state.copy(simulated = false)
        assertThat(unconfigured.tippingStanding("AUD", true)).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(unconfigured.tippingStanding("AUD", true, dynamicOverride = true)).isEqualTo(CapabilityStanding.AVAILABLE)
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
