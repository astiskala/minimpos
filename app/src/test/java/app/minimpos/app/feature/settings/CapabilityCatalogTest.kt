package app.minimpos.app.feature.settings

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.CapabilityStanding
import app.minimpos.app.terminal.DestinationCapabilities
import app.minimpos.app.terminal.GratuityCapability
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
            mode = TerminalMode.TERMINAL,
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
    fun `a local switch that is off says when Adyen already has the feature set up`() {
        val remote =
            AdyenCapabilitiesState(checked = true, gratuities = listOf(GratuityCapability("EUR")), moto = MotoCapability(enabled = true))
        listOf(CapabilityFeature.TIPPING, CapabilityFeature.MOTO).forEach {
            assertThat(
                catalog(remote = remote).item(it),
            ).isEqualTo(CapabilityItem(it, CapabilityStatus.OFF, CapabilityRequirement.LOCAL_ONLY))
            assertThat(catalog(remote = AdyenCapabilitiesState(checked = true)).item(it).requirement).isEqualTo(CapabilityRequirement.LOCAL)
            // A failed reading never claims that Adyen's side is ready.
            val failed = remote.copy(failure = Failure.Remote(Fault.TimedOut))
            assertThat(catalog(remote = failed).item(it).requirement).isEqualTo(CapabilityRequirement.LOCAL)
        }
        assertThat(
            catalog(remote = remote.copy(gratuities = listOf(GratuityCapability("AUD")))).item(CapabilityFeature.TIPPING).requirement,
        ).isEqualTo(CapabilityRequirement.LOCAL)
        val listed = AdyenCapabilitiesState(checked = true, simulated = true, methodsChecked = true)
        assertThat(catalog(remote = listed).item(CapabilityFeature.GIFT_CARDS))
            .isEqualTo(CapabilityItem(CapabilityFeature.GIFT_CARDS, CapabilityStatus.OFF, CapabilityRequirement.LOCAL_ONLY))
        assertThat(catalog(remote = listed.copy(methodsChecked = false)).item(CapabilityFeature.GIFT_CARDS).requirement)
            .isEqualTo(CapabilityRequirement.LOCAL)
    }

    @Test
    fun `the simulator's surcharge setting stands in for Adyen's surcharge rules`() {
        val simulated = AdyenCapabilitiesState(checked = true, simulated = true)
        assertThat(catalog(remote = simulated).item(CapabilityFeature.SURCHARGE))
            .isEqualTo(CapabilityItem(CapabilityFeature.SURCHARGE, CapabilityStatus.OFF, CapabilityRequirement.LOCAL))
        val surcharged = settings.copy(simulator = settings.simulator.copy(surchargeMinor = 50))
        assertThat(catalog(surcharged, remote = simulated).item(CapabilityFeature.SURCHARGE).status).isEqualTo(CapabilityStatus.READY)
        // A real terminal's surcharge comes from Adyen, whatever the simulator setting says.
        assertThat(catalog(surcharged, remote = AdyenCapabilitiesState(checked = true)).item(CapabilityFeature.SURCHARGE))
            .isEqualTo(CapabilityItem(CapabilityFeature.SURCHARGE, CapabilityStatus.OFF, CapabilityRequirement.ADYEN))
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

    @Test
    fun `capability indicators do not claim unsupported saved tip overrides or audio guidance are ready`() {
        val enabled =
            settings.copy(
                payment =
                    settings.payment.copy(
                        terminalTipping = ReceiptTipping.DEFAULT_ON,
                        dynamicTipOverride = true,
                        audioGuidance = true,
                    ),
            )
        val physical = terminal.copy(poiId = "S1F2-123")
        val remote = AdyenCapabilitiesState(checked = true)
        assertThat(
            catalog(enabled, physical, remote).item(CapabilityFeature.TIPPING).status,
        ).isEqualTo(CapabilityStatus.NEEDS_SETUP)
        assertThat(catalog(enabled, physical, remote).item(CapabilityFeature.AUDIO_GUIDANCE).status).isEqualTo(CapabilityStatus.UNVERIFIED)
        val old = physical.copy(softwareVersion = "1.95.1")
        assertThat(catalog(enabled, old, remote).item(CapabilityFeature.TIPPING).status).isEqualTo(CapabilityStatus.NEEDS_SETUP)
        val ordinaryTips = remote.copy(gratuities = listOf(GratuityCapability("EUR")))
        assertThat(catalog(enabled, old, ordinaryTips).item(CapabilityFeature.TIPPING).status).isEqualTo(CapabilityStatus.READY)
        assertThat(catalog(enabled, old, remote).item(CapabilityFeature.AUDIO_GUIDANCE).status).isEqualTo(CapabilityStatus.UNSUPPORTED)
        val modern = physical.copy(softwareVersion = "1.117.0")
        assertThat(catalog(enabled, modern, remote).item(CapabilityFeature.TIPPING).status).isEqualTo(CapabilityStatus.READY)
        assertThat(
            catalog(enabled, modern, remote).item(CapabilityFeature.AUDIO_GUIDANCE).requirement,
        ).isEqualTo(CapabilityRequirement.SUPPORT)
    }
}
