package app.minimpos.app.terminal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import app.minimpos.app.feature.settings.CapabilitiesViewModel
import app.minimpos.app.feature.settings.CapabilityDraft
import app.minimpos.app.feature.settings.CapabilityFeature
import app.minimpos.app.feature.settings.CapabilityLimit
import app.minimpos.app.feature.settings.CapabilityTip
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRoles
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
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
    private var lookupGate: CompletableDeferred<Unit>? = null
    private var updateOverride: TerminalCapabilitiesUpdate? = null

    private val detailsApi =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) =
                CredentialLookup.Allowed(CredentialRoles(AdyenRole.required(), emptyList()))

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
            ): TerminalCapabilitiesLookup {
                lookupGate?.await()
                return lookupOverride ?: TerminalCapabilitiesLookup.Found(currentCapabilities)
            }

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
        assertThat(state.checked).isTrue()
        assertThat(state.editable).isTrue()
        assertThat(state.dccEnabled).isFalse()
        assertThat(state.surchargeConfigured).isTrue()
        assertThat(state.gratuityFor("AUD")?.predefinedEntries).containsExactly("10%", "15%")
        assertThat(state.giftCardsFor("AUD")).containsExactly("svs")
        assertThat(state.capabilities.supportsOfflinePayments).isFalse()

        assertThat(await { discovery.updateDcc(true) }).isEqualTo(CapabilityPatchResult.Updated)
        assertThat(discovery.state.value.dccEnabled).isTrue()
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
            await { vm.state.first { it.simulated && !it.checking } }
            vm.edit(CapabilityFeature.TIPPING, CapabilityDraft(enabled = true, tips = listOf(CapabilityTip(text = "10")), custom = true))
            await { vm.actions.first { it.gratuity.done } }
            vm.edit(CapabilityFeature.TIPPING, CapabilityDraft())
            await { vm.state.first { it.gratuityFor("EUR") == null } }

            vm.edit(CapabilityFeature.DCC, CapabilityDraft(enabled = true))
            await { vm.actions.first { it.dcc.done } }
            assertThat(vm.state.value.dccEnabled).isTrue()

            vm.edit(CapabilityFeature.MOTO, CapabilityDraft(enabled = true, motoLimit = CapabilityLimit(false, "150")))
            await { vm.actions.first { it.moto.done } }
            assertThat(vm.state.value.moto).isEqualTo(MotoCapability(enabled = true, maxAmount = 150))

            vm.edit(
                CapabilityFeature.OFFLINE,
                CapabilityDraft(
                    chip = CapabilityLimit(false, "0.30"),
                    storeAndForward = CapabilityLimit(false, "40"),
                    count = CapabilityLimit(false, "8"),
                ),
            )
            await { vm.actions.first { it.offline.done } }
            assertThat(vm.state.value.offline.maxStoreAndForwardMinor).containsEntry("EUR", 4_000L)

            vm.edit(CapabilityFeature.OFFLINE, CapabilityDraft())
            await { vm.state.first { it.offline.maxStoreAndForwardMinor.isEmpty() } }

            // Non-discoverable destination (Payments app) rejects remote capability writes.
            env.updateSettings { it.copy(terminal = it.terminal.withConnection(TerminalMode.PAYMENTS_APP, TerminalEnvironment.TEST)) }
            await { vm.state.first { !it.capabilities.supportsTerminalFeatures } }
            assertThat(vm.state.value.giftCardsFor("AUD")).isEmpty()
            assertThat(await { env.container.capabilityDiscovery.updateDcc(false) })
                .isEqualTo(CapabilityPatchResult.NotDone(Failure.Remote(Fault.Unsupported)))

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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `setup change while capabilities are checked refreshes them for the new setup`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        await { env.container.secrets.set(Secret.ADYEN_API_KEY, "api-key") }
        env.updateSettings {
            val conn = it.terminal.withConnection(TerminalMode.CLOUD, TerminalEnvironment.TEST)
            it.copy(terminal = conn.copy(merchantAccount = "HarbourCoffeeCOM", poiIdOverride = "S1F2-000158213605014", storeId = "ST1"))
        }
        val lookup = CompletableDeferred<Unit>()
        lookupGate = lookup
        val vm = CapabilitiesViewModel(discovery = env.container.capabilityDiscovery)
        try {
            val originalIdentity = await { vm.state.first { it.checking } }.identity
            // Cancelling the old refresh also ends checking; wait for a published reading on the new setup.
            env.updateSettings { it.copy(terminal = it.terminal.copy(storeId = "ST2")) }
            lookup.complete(Unit)

            val refreshed =
                await {
                    vm.state.first {
                        !it.checking && it.problem == null && it.identity != originalIdentity && it.storeId == "ST1"
                    }
                }
            assertThat(refreshed.failure).isNull()
            assertThat(refreshed.giftCardsFor("AUD")).containsExactly("svs")
        } finally {
            closeViewModels(listOf(vm))
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `cancelling a refresh clears checking and permits another refresh`() =
        runTest {
            await { env.container.secrets.set(Secret.ADYEN_API_KEY, "api-key") }
            env.updateSettings {
                val connection = it.terminal.withConnection(TerminalMode.CLOUD, TerminalEnvironment.TEST)
                it.copy(terminal = connection.copy(merchantAccount = "HarbourCoffeeCOM", poiIdOverride = "S1F2-000158213605014"))
            }
            val gate = CompletableDeferred<Unit>()
            lookupGate = gate
            val discovery = env.container.capabilityDiscovery
            val refresh = env.scope.launch { discovery.refresh() }
            await { discovery.state.first { it.checking } }
            await { refresh.cancelAndJoin() }
            assertThat(refresh.isCancelled).isTrue()
            assertThat(discovery.state.value.checking).isFalse()
            gate.complete(Unit)
            val next = await { discovery.refresh() }
            assertThat(next.checking).isFalse()
            assertThat(next.failure).isNull()
            assertThat(next.surchargeConfigured).isTrue()
        }

    private fun CapabilitiesViewModel.edit(
        feature: CapabilityFeature,
        draft: CapabilityDraft,
    ) = saveEditor(
        feature,
        if (feature ==
            CapabilityFeature.MOTO
        ) {
            draft.copy(motoCurrency = state.value.motoCurrency)
        } else {
            draft
        },
        "EUR",
        state.value.identity,
    )

    @Test
    fun `capabilities screen displays standing matrix and saves simulator updates`() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("section_capabilities").performScrollTo().performClick()
        compose.awaitCondition("capabilities loaded") {
            env.container.capabilityDiscovery.state.value
                .let { it.simulated && !it.checking }
        }
        compose.onNodeWithTag("cap_tipping").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cap_partial_auth").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("cap_dcc").performScrollTo().performClick()
        compose.onNodeWithTag("capDccEnable").performScrollTo().performClick()
        compose.onNodeWithTag("saveCapDcc").performScrollTo().performClick()
        compose.awaitCondition("DCC enabled in simulator") { env.container.capabilityDiscovery.state.value.dccEnabled }
    }

    @Test
    fun `an overflowing MOTO limit cannot be saved as an inherited limit`() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("section_capabilities").performScrollTo().performClick()
        compose.awaitCondition("capabilities loaded") {
            env.container.capabilityDiscovery.state.value
                .let { it.simulated && !it.checking }
        }
        compose.onNodeWithTag("cap_moto").performScrollTo().performClick()
        compose.onNodeWithTag("capMotoEnable").performScrollTo().performClick()
        compose.onNodeWithTag("capMotoMaxAmountInherited").performScrollTo().performClick()
        compose.onNodeWithTag("capMotoMaxAmount").performScrollTo().performTextInput("2147483648")
        compose.onNodeWithTag("saveCapMoto").performScrollTo().assertIsNotEnabled()
    }
}
