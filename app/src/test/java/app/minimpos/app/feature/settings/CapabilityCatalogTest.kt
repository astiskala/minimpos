package app.minimpos.app.feature.settings

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.CapabilityStanding
import app.minimpos.app.terminal.DestinationCapabilities
import app.minimpos.app.terminal.MotoCapability
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CapabilityCatalogTest {
    private val settings = AppSettings()
    private val terminal =
        TerminalState(
            loaded = true,
            capabilities = DestinationCapabilities(supportsTerminalFeatures = true, supportsOfflinePayments = true),
        )

    private fun catalog(
        settings: AppSettings = this.settings,
        terminal: TerminalState = this.terminal,
        remote: AdyenCapabilitiesState = AdyenCapabilitiesState(),
        wallets: WalletAvailability = WalletAvailability(),
    ) = CapabilityCatalog(settings, terminal, remote, wallets, "EUR")

    @Test
    fun `simulated wallets need neither camera nor terminal scanner`() {
        val wallets =
            WalletAvailability(
                context = PaymentContext("SIMULATOR", "SIM", "POS", "demo", "TEST", null),
                identity = "simulated",
                supported = true,
                checked = true,
                simulated = true,
            )
        val destination = terminal.copy(capabilities = terminal.capabilities.copy(scannedWallets = true))
        assertThat(catalog(terminal = destination, wallets = wallets).item(CapabilityFeature.SCANNED_WALLETS).status)
            .isEqualTo(CapabilityStatus.READY)
    }

    @Test
    fun `a disabled printer is Off and MKE enablement needs evidence beyond a local switch`() {
        val off = settings.copy(receipt = settings.receipt.copy(printerMode = PrinterMode.OFF))
        assertThat(catalog(off).item(CapabilityFeature.PRINTING).status).isEqualTo(CapabilityStatus.OFF)
        assertThat(
            catalog(terminal = terminal.copy(printingStanding = CapabilityStanding.UNSUPPORTED))
                .item(CapabilityFeature.PRINTING)
                .status,
        ).isEqualTo(CapabilityStatus.UNSUPPORTED)
        val mke = settings.copy(payment = settings.payment.copy(allowMke = true))
        assertThat(catalog(mke).item(CapabilityFeature.MKE).status).isEqualTo(CapabilityStatus.UNVERIFIED)
        assertThat(catalog(mke, remote = AdyenCapabilitiesState(simulated = true)).item(CapabilityFeature.MKE).status)
            .isEqualTo(CapabilityStatus.READY)
    }

    @Test
    fun `every Adyen feature has a reading but app-only and PIN features are absent`() {
        val names = catalog().items().map { it.feature.name }
        assertThat(names).containsExactlyElementsIn(CapabilityFeature.entries.map { it.name }).inOrder()
        assertThat(names).containsNoneOf("SPLIT", "CASH", "SHARING", "CAMERA", "MANAGER_PIN")
        assertThat(CapabilityFeature.entries.filter { it.editableAtAdyen }).containsExactly(
            CapabilityFeature.TIPPING,
            CapabilityFeature.DCC,
            CapabilityFeature.MOTO,
            CapabilityFeature.OFFLINE,
        )
    }

    @Test
    fun `an unready first reading never presents defaults as established remote settings`() {
        listOf(
            AdyenCapabilitiesState(),
            AdyenCapabilitiesState(checked = true, failure = Failure.Remote(Fault.TimedOut)),
        ).forEach { remote ->
            assertThat(remote.editable).isFalse()
            listOf(CapabilityFeature.DCC, CapabilityFeature.SURCHARGE, CapabilityFeature.OFFLINE).forEach {
                assertThat(catalog(remote = remote).item(it).status).isEqualTo(CapabilityStatus.UNVERIFIED)
            }
        }
        assertThat(catalog(terminal = terminal.copy(loaded = false)).items().all { it.status == CapabilityStatus.UNVERIFIED }).isTrue()
    }

    @Test
    fun `local Off is distinct from Adyen enablement still needed for MOTO`() {
        val remote = AdyenCapabilitiesState(checked = true, moto = MotoCapability(enabled = false))
        assertThat(catalog(remote = remote).item(CapabilityFeature.MOTO).status).isEqualTo(CapabilityStatus.OFF)
        val enabled = settings.copy(payment = settings.payment.copy(allowMoto = true))
        assertThat(catalog(enabled, remote = remote).item(CapabilityFeature.MOTO).status).isEqualTo(CapabilityStatus.NEEDS_SETUP)
        assertThat(catalog(enabled, remote = remote.copy(moto = MotoCapability(true))).item(CapabilityFeature.MOTO).status)
            .isEqualTo(CapabilityStatus.READY)
        assertThat(catalog(remote = remote.copy(dccEnabled = true)).item(CapabilityFeature.DCC).status).isEqualTo(CapabilityStatus.READY)
    }

    @Test
    fun `saving cards and Giving do not infer readiness from a switch alone`() {
        val enabled =
            settings.copy(
                payment =
                    settings.payment.copy(
                        offerCardSaving = true,
                        shopperReferenceSource = ShopperReferenceSource.NONE,
                        askGiving = ReceiptTipping.DEFAULT_ON,
                    ),
            )
        assertThat(catalog(enabled).item(CapabilityFeature.CARD_SAVING).requirement).isEqualTo(CapabilityRequirement.REFERENCE)
        assertThat(catalog(enabled).item(CapabilityFeature.GIVING).status).isEqualTo(CapabilityStatus.UNVERIFIED)
        assertThat(catalog(enabled, remote = AdyenCapabilitiesState(simulated = true)).item(CapabilityFeature.GIVING).status)
            .isEqualTo(CapabilityStatus.READY)
        assertThat(
            catalog(
                terminal = terminal.copy(capabilities = DestinationCapabilities(supportsTerminalFeatures = false)),
            ).item(CapabilityFeature.TIPPING).status,
        ).isEqualTo(CapabilityStatus.UNSUPPORTED)
    }
}
