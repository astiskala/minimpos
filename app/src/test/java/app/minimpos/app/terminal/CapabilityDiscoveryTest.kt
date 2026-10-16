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
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.CredentialLookup
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

    private val detailsApi =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) = CredentialLookup.Allowed

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
            ): TerminalCapabilitiesLookup = TerminalCapabilitiesLookup.Found(currentCapabilities)

            override suspend fun updateCapabilities(
                id: String,
                environment: TerminalEnvironment,
                patch: TerminalCapabilitiesPatch,
            ): TerminalCapabilitiesUpdate {
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
        await { env.container.secrets.set(Secret.ADYEN_API_KEY, "api-key") }
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal
                        .withConnection(TerminalMode.CLOUD, TerminalEnvironment.TEST)
                        .copy(
                            merchantAccount = "HarbourCoffeeCOM",
                            poiIdOverride = "S1F2-000158213605014",
                            storeId = "ST1",
                        ),
            )
        }
        val state = await { env.container.capabilityDiscovery.refresh() }
        assertThat(state.tippingStanding("AUD", enabledLocally = false)).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.dccStanding).isEqualTo(CapabilityStanding.NEEDS_CONFIGURATION)
        assertThat(state.surchargeStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.giftCardStanding("AUD")).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(state.giftCardsFor("AUD")).containsExactly("svs")
        assertThat(state.offlineStanding("AUD")).isEqualTo(CapabilityStanding.UNSUPPORTED)

        val dccUpdated = await { env.container.capabilityDiscovery.updateDcc(true) }
        assertThat(dccUpdated).isEqualTo(CapabilityPatchResult.Updated)
        assertThat(env.container.capabilityDiscovery.state.value.dccStanding).isEqualTo(CapabilityStanding.AVAILABLE)
        assertThat(patches).containsExactly(TerminalCapabilitiesPatch.Dcc(true))

        // Assignment drift blocks remote writes.
        listedMerchant = "OtherMerchant"
        val blocked = await { env.container.capabilityDiscovery.updateMoto(MotoCapability(enabled = true, maxAmount = 300)) }
        assertThat(blocked).isEqualTo(CapabilityPatchResult.NotDone(Failure.NotSetUp(SetupProblem.SETUP_CHANGED)))
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
        compose.awaitCondition("DCC enabled in simulator") { env.container.capabilityDiscovery.state.value.dccEnabled }
    }
}
