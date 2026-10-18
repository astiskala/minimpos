package app.minimpos.app.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.FakeDevice
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.closeViewModels
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.settings.CapabilitiesViewModel
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.RemoteTerminalCapabilities
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.TerminalCapabilitiesLookup
import app.minimpos.terminal.transport.TerminalCapabilitiesPatch
import app.minimpos.terminal.transport.TerminalCapabilitiesUpdate
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalGratuity
import app.minimpos.terminal.transport.TerminalListing
import app.minimpos.terminal.transport.TerminalMotoSettings
import app.minimpos.terminal.transport.TerminalOfflineLimits
import app.minimpos.terminal.transport.WalletMethod
import app.minimpos.terminal.transport.WalletMethodListing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CapabilityDiscoveryTest {
    private var currentCapabilities =
        RemoteTerminalCapabilities(
            gratuities = listOf(TerminalGratuity("AUD", true, listOf("10%", "15%"), true)),
            dccEnabled = false,
            surchargeConfigured = true,
            moto = TerminalMotoSettings(enabled = false, maxAmount = 200),
            offline = TerminalOfflineLimits(chipFloorLimit = 50, maxStoreAndForwardMinor = mapOf("AUD" to 5_000L), maxPayments = 10),
        )
    private val patches = mutableListOf<TerminalCapabilitiesPatch>()
    private var listedMerchant = "HarbourCoffeeCOM"
    private var lookupOverride: TerminalCapabilitiesLookup? = null
    private var updateOverride: TerminalCapabilitiesUpdate? = null

    private val detailsApi =
        object : TerminalDetailsApi {
            override suspend fun credential(
                environment: TerminalEnvironment,
                cloud: Boolean,
            ) = CredentialLookup.Allowed

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing =
                TerminalListing.Listed(
                    listOf(TerminalDetails("S1F2-000158213605014", listedMerchant, "192.0.2.1", storeId = "ST1", countryCode = "AU")),
                    environment,
                )

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup = SharedKeyLookup.Missing

            override suspend fun capabilities(
                id: String,
                environment: TerminalEnvironment,
            ): TerminalCapabilitiesLookup = lookupOverride ?: TerminalCapabilitiesLookup.Found(currentCapabilities)

            override suspend fun updateCapabilities(
                id: String,
                environment: TerminalEnvironment,
                patch: TerminalCapabilitiesPatch,
            ): TerminalCapabilitiesUpdate {
                updateOverride?.let { return it }
                patches += patch
                currentCapabilities =
                    when (patch) {
                        is TerminalCapabilitiesPatch.Gratuity -> {
                            val gratuity = patch.gratuity
                            val retained = currentCapabilities.gratuities.filterNot { it.currency == patch.currency }
                            currentCapabilities.copy(
                                gratuities = if (gratuity == null) retained else retained + gratuity,
                            )
                        }

                        is TerminalCapabilitiesPatch.Dcc -> {
                            currentCapabilities.copy(dccEnabled = patch.enabled)
                        }

                        is TerminalCapabilitiesPatch.Moto -> {
                            currentCapabilities.copy(moto = patch.settings)
                        }

                        is TerminalCapabilitiesPatch.Offline -> {
                            currentCapabilities.copy(
                                offline =
                                    TerminalOfflineLimits(
                                        chipFloorLimit = patch.chipFloorLimit,
                                        maxStoreAndForwardMinor =
                                            patch.maxStoreAndForwardMinor
                                                ?.toLong()
                                                ?.let { mapOf(patch.currency to it) }
                                                .orEmpty(),
                                        maxPayments = patch.maxPayments,
                                    ),
                            )
                        }
                    }
                return TerminalCapabilitiesUpdate.Ready(currentCapabilities)
            }
        }

    @get:Rule(order = 0)
    val env =
        TestEnvironment(
            device = FakeDevice(country = "AU", paymentsApps = setOf(TerminalEnvironment.TEST)),
            terminalDetails = detailsApi,
            walletMethods = { _, _ ->
                WalletMethodListing.Listed(
                    listOf(
                        WalletMethod(
                            type = "svs",
                            enabled = true,
                            allowed = true,
                            channel = "pos",
                            currencies = setOf("AUD"),
                            countries = setOf("AU"),
                            storeIds = setOf("ST1"),
                        ),
                    ),
                )
            },
        )

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `discovers capabilities and patches terminal settings after verifying unchanged assignment`() {
        val discovery = env.container.capabilityDiscovery
        await { env.container.secrets.set(Secret.ADYEN_API_KEY, "api-key") }
        env.updateSettings {
            val conn = it.terminal.withConnection(TerminalMode.CLOUD, TerminalEnvironment.TEST)
            it.copy(terminal = conn.copy(merchantAccount = "HarbourCoffeeCOM", poiIdOverride = "S1F2-000158213605014", storeId = "ST1"))
        }
        val state = await { discovery.refresh() }
        assertThat(state.tippingStanding("AUD", enabledLocally = false)).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.dccStanding).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.surchargeStanding to state.giftCardStanding("AUD"))
            .isEqualTo(CapabilityStanding.AVAILABLE to CapabilityStanding.AVAILABLE)
        assertThat(state.giftCardsFor("AUD")).containsExactly("svs")
        assertThat(state.offlineStanding("AUD")).isEqualTo(CapabilityStanding.UNSUPPORTED)

        assertThat(await { discovery.updateDcc(true) }).isEqualTo(CapabilityPatchResult.Updated)
        assertThat(discovery.state.value.dccStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(patches).containsExactly(TerminalCapabilitiesPatch.Dcc(true))

        val grat = GratuityCapability("AUD", usePredefinedEntries = false, allowCustomAmount = true)
        assertThat(await { discovery.updateGratuity("AUD", grat) }).isEqualTo(CapabilityPatchResult.Updated)
        assertThat(await { discovery.updateGratuity("AUD", null) }).isEqualTo(CapabilityPatchResult.Updated)
        assertThat(await { discovery.updateMoto(MotoCapability(enabled = true, maxAmount = 300)) }).isEqualTo(CapabilityPatchResult.Updated)
        assertThat(await { discovery.updateOffline("AUD", 25, 2_500, 5) }).isEqualTo(CapabilityPatchResult.Updated)

        updateOverride = TerminalCapabilitiesUpdate.Failed(Fault.TimedOut, uncertain = true)
        assertThat(await { discovery.updateDcc(false) }).isEqualTo(CapabilityPatchResult.Unconfirmed(Failure.Remote(Fault.TimedOut)))
        updateOverride = TerminalCapabilitiesUpdate.Failed(Fault.Permission(ApiKey.ADYEN), uncertain = false)
        assertThat(
            await { discovery.updateDcc(false) },
        ).isEqualTo(CapabilityPatchResult.NotDone(Failure.Remote(Fault.Permission(ApiKey.ADYEN))))
        updateOverride = null

        lookupOverride = TerminalCapabilitiesLookup.Failed(Fault.TimedOut)
        assertThat(await { discovery.refresh() }.failure).isEqualTo(Failure.Remote(Fault.TimedOut))
        lookupOverride = null

        listedMerchant = "OtherMerchant"
        val blocked = await { discovery.updateMoto(MotoCapability(enabled = true, maxAmount = 300)) }
        assertThat(blocked).isEqualTo(CapabilityPatchResult.NotDone(Failure.NotSetUp(SetupProblem.SETUP_CHANGED)))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `updates simulator and view model capabilities and handles non-discoverable or incomplete setups`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env.useSimulator()
        val vm = CapabilitiesViewModel(discovery = env.container.capabilityDiscovery)
        try {
            await { vm.state.first { it.checked && it.simulated } }
            vm.saveGratuity("EUR", GratuityCapability("EUR", true, listOf("10%"), true))
            await { vm.actions.first { it.gratuity.done } }
            vm.saveGratuity("EUR", null)
            await { vm.state.first { it.gratuityFor("EUR") == null } }

            vm.saveDcc(true)
            await { vm.actions.first { it.dcc.done } }
            assertThat(vm.state.value.dccEnabled).isTrue()

            vm.saveMoto(MotoCapability(enabled = true, maxAmount = 150))
            await { vm.actions.first { it.moto.done } }
            assertThat(vm.state.value.moto).isEqualTo(MotoCapability(enabled = true, maxAmount = 150))

            vm.saveOffline("EUR", chipFloorLimit = 30, maxStoreAndForwardMinor = 4_000, maxPayments = 8)
            await { vm.actions.first { it.offline.done } }
            assertThat(vm.state.value.offline.maxStoreAndForwardMinor).containsEntry("EUR", 4_000L)

            vm.saveOffline("EUR", chipFloorLimit = null, maxStoreAndForwardMinor = null, maxPayments = null)
            await { vm.state.first { it.offline.maxStoreAndForwardMinor.isEmpty() } }

            // Non-discoverable destination (Payments app) rejects remote capability writes.
            env.updateSettings { it.copy(terminal = it.terminal.withConnection(TerminalMode.PAYMENTS_APP, TerminalEnvironment.TEST)) }
            await { vm.state.first { !it.capabilities.supportsTerminalFeatures } }
            assertUnsupportedAndStandings(vm.state.value)

            vm.saveDcc(false)
            val failedDcc = await { vm.actions.first { it.dcc.isError } }
            assertThat(failedDcc.dcc.outcome).isEqualTo(ActionOutcome.Failed(Failure.Remote(Fault.Unsupported)))

            // Incomplete cloud setup reports setup problem on refresh and patch.
            env.updateSettings {
                it.copy(
                    terminal =
                        it.terminal
                            .withConnection(TerminalMode.CLOUD, null)
                            .copy(merchantAccount = "", poiIdOverride = ""),
                )
            }
            val problemState = await { env.container.capabilityDiscovery.refresh() }
            assertThat(problemState.problem).isEqualTo(SetupProblem.ENVIRONMENT)
            assertThat(await { env.container.capabilityDiscovery.updateDcc(true) })
                .isEqualTo(CapabilityPatchResult.NotDone(Failure.NotSetUp(SetupProblem.ENVIRONMENT)))
        } finally {
            closeViewModels(listOf(vm))
            Dispatchers.resetMain()
        }
    }

    private fun assertUnsupportedAndStandings(unsupportedState: AdyenCapabilitiesState) {
        assertThat(unsupportedState.giftCardsFor("AUD")).isEmpty()
        assertThat(unsupportedState.tippingStanding("AUD", true)).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(unsupportedState.dccStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(unsupportedState.givingStanding(true)).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(unsupportedState.surchargeStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(unsupportedState.partialAuthStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(unsupportedState.giftCardStanding("AUD")).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(unsupportedState.motoStanding(true)).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(unsupportedState.offlineStanding("AUD")).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(unsupportedState.reconciliationStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(unsupportedState.shopperDisplayStanding).isEqualTo(CapabilityStanding.UNSUPPORTED)
        assertThat(AdyenCapabilitiesState(capabilities = DestinationCapabilities(supportsShopperDisplay = true)).shopperDisplayStanding)
            .isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(AdyenCapabilitiesState(capabilities = DestinationCapabilities(supportsTerminalFeatures = true)).givingStanding(false))
            .isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(AdyenCapabilitiesState(capabilities = DestinationCapabilities(supportsTerminalFeatures = true)).givingStanding(true))
            .isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(AdyenCapabilitiesState(capabilities = DestinationCapabilities(supportsTerminalFeatures = true)).motoStanding(false))
            .isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(AdyenCapabilitiesState(capabilities = DestinationCapabilities(supportsTerminalFeatures = true)).motoStanding(true))
            .isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(
            AdyenCapabilitiesState(
                capabilities = DestinationCapabilities(supportsOfflinePayments = true),
                offline = OfflineCapability(maxStoreAndForwardMinor = mapOf("AUD" to 1_000L), maxPayments = 5),
            ).offlineStanding("AUD"),
        ).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(
            AdyenCapabilitiesState(
                capabilities = DestinationCapabilities(supportsOfflinePayments = true),
                offline = OfflineCapability(maxStoreAndForwardMinor = mapOf("AUD" to 1_000L), maxPayments = 0),
            ).offlineStanding("AUD"),
        ).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
    }

    @Test
    fun `capabilities screen displays standing matrix and saves simulator updates`() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("section_capabilities").performScrollTo().performClick()
        compose.awaitCondition("capabilities loaded") { env.container.capabilityDiscovery.state.value.checked }
        compose.onNodeWithTag("cap_tipping").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cap_partial_auth").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("capDccEnable").performScrollTo().performClick()
        compose.onNodeWithTag("saveCapDcc").performScrollTo().performClick()
        compose.awaitCondition("DCC enabled in simulator") { env.container.capabilityDiscovery.state.value.dccEnabled }
    }
}
