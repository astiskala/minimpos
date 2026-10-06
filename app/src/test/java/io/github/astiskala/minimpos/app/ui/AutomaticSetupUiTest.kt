package io.github.astiskala.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakePaymentsApp
import io.github.astiskala.minimpos.app.FakeTerminal
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.feature.settings.SettingsSectionScreen
import io.github.astiskala.minimpos.app.feature.transfer.TransferImportScreen
import io.github.astiskala.minimpos.app.feature.transfer.TransferImportViewModel
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.MiniMposTheme
import io.github.astiskala.minimpos.core.codec.QrChunks
import io.github.astiskala.minimpos.core.codec.SealedSecrets
import io.github.astiskala.minimpos.core.codec.Transfer
import io.github.astiskala.minimpos.core.codec.TransferCodec
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class AutomaticSetupUiTest {
    private var lookupAvailable = true
    private var sharedKeyAvailable = true
    private var emptyListing = false
    private val device = FakeDevice()
    private val paymentsApp = FakePaymentsApp()
    private val searches = mutableListOf<TerminalEnvironment>()
    private val details =
        object : TerminalDetailsApi {
            override suspend fun terminals(environment: TerminalEnvironment): TerminalListing {
                searches += environment
                return if (lookupAvailable) {
                    TerminalListing.Listed(
                        if (emptyListing) emptyList() else listOf(TerminalDetails(POI_ID, "Merchant", "192.168.1.42")),
                        environment,
                    )
                } else {
                    TerminalListing.Failed("Lookup denied")
                }
            }

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): DiscoveredKey? = if (sharedKeyAvailable) DiscoveredKey("store-key", 2, "correct horse battery staple") else null
        }

    @get:Rule(order = 0)
    val env = TestEnvironment(device, terminal = FakeTerminal(), paymentsApp = paymentsApp, terminalDetails = details)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun testApi() {
        waitForTag("testApi")
        compose.onNodeWithTag("step_3").assertDoesNotExist()
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        waitForTag("step_3")
    }

    private fun importAutomatic(
        destination: String = "network",
        environment: TerminalEnvironment = TerminalEnvironment.TEST,
        automatic: Boolean = true,
        manualDetails: String = "",
        secrets: String = """{"ADYEN_API_KEY":"imported-key"}""",
    ) {
        val container = env.container
        container.start()
        val seal = TransferSeal(iterations = 1_000)
        val code = seal.newCode()
        val sealed = SealedSecrets(seal.seal(secrets.toByteArray(), code))
        val account = if (destination == "tapToPay") """"merchantAccount":"Merchant",""" else ""
        val connection =
            """{"destination":"$destination","environment":"$environment","automatic":$automatic,$account$manualDetails"liveUrlPrefix":"1797a841fbb37ca7-AdyenDemo"}"""
        val vm = TransferImportViewModel(container.setupTransfer, "AUD")
        QrChunks
            .split(
                TransferCodec.encode(Transfer(sealedSecrets = sealed, connection = connection)),
                "AUTO",
            ).forEach { vm.onCode(it.encode()) }
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.TransferImport))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    when (val route = navigator.current) {
                        is Route.SettingsSection -> {
                            SettingsSectionScreen(
                                route.section,
                                navigator,
                                automaticSetup = route.automaticSetup,
                                helperSetup = route.helperSetup,
                            )
                        }

                        else -> {
                            TransferImportScreen(navigator, vm = vm)
                        }
                    }
                }
            }
        }
        waitForTag("import")
        compose.onNodeWithTag("transferCodeInput").performScrollTo().performTextInput(code)
        compose.onNodeWithTag("import").performClick()
        waitForTag("importDone")
        assertThat(searches).isEmpty()
        compose
            .onNodeWithTag(
                "importFinished",
            ).assertIsDisplayed()
            .assertTextContains(
                env.context.getString(
                    if (automatic && destination != "tapToPay") R.string.transfer_continue_setup else R.string.transfer_review_setup,
                ),
            ).performClick()
    }

    @Test
    fun `Automatic import offers device-side terminal selection and refreshes all discovered fields`() {
        importAutomatic()
        chooseTerminal()
        testApi()
        waitForTag("step_3_summary")
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("apiKey").assertDoesNotExist()
        compose.onNodeWithTag("terminalMode").assertDoesNotExist()
        compose.onNodeWithTag("testConnection").assertExists()
        compose.onNodeWithTag("testApi").assertExists()
        compose.onNodeWithTag("step_3").performScrollTo().performClick()
        compose.onNodeWithTag("host").performScrollTo().assertTextContains("192.168.1.42", substring = true)
        compose.onNodeWithTag("step_2").performScrollTo().performClick()
        compose.onNodeWithTag("merchantAccount").performScrollTo().assertTextContains("Merchant", substring = true)
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery staple")
        assertThat(searches).containsExactly(TerminalEnvironment.TEST)
    }

    @Test
    fun `Automatic LIVE cloud setup keeps the required imported prefix and selected environment`() {
        importAutomatic("cloud", TerminalEnvironment.LIVE)
        chooseTerminal()
        testApi()
        waitForTag("step_3_summary")
        compose.onNodeWithTag("livePrefix").assertDoesNotExist()
        compose.onNodeWithTag("poiId").assertDoesNotExist()
        compose.onNodeWithTag("testConnection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("step_2").performScrollTo().performClick()
        compose.onNodeWithTag("livePrefix").performScrollTo().assertTextContains("1797a841fbb37ca7-AdyenDemo", substring = true)
        assertThat(env.container.settingsState.value.terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(searches).containsExactly(TerminalEnvironment.LIVE)
    }

    private fun chooseTerminal() {
        waitForTag("terminal_$POI_ID")
        compose.onNodeWithTag("terminal_$POI_ID").performClick()
        compose.awaitCondition("discovered fields reach Settings") {
            env.container.settingsState.value.terminal.merchantAccount == "Merchant"
        }
        compose.waitUntilDoesNotExist(hasTestTag("terminal_$POI_ID"), 15_000)
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `unavailable lookup keeps the imported key and offers manual completion`() {
        lookupAvailable = false
        assertManualFallback()
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `an empty terminal list offers manual completion instead of an empty chooser`() {
        emptyListing = true
        assertManualFallback()
    }

    private fun assertManualFallback() {
        importAutomatic()
        compose.awaitCondition("manual completion is offered") {
            compose
                .onAllNodes(hasText(env.context.getString(R.string.settings_discovery_manual)), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose
            .onNodeWithText(
                env.context.getString(R.string.settings_discovery_manual),
                useUnmergedTree = true,
            ).performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("merchantAccount").assertExists()
        compose.onNodeWithTag("merchantAccount").performScrollTo().performTextInput("Merchant")
        compose.awaitCondition("the manual account is saved") {
            env.container.settingsState.value.terminal.merchantAccount == "Merchant"
        }
        testApi()
        compose.onNodeWithTag("host").assertExists()
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("imported-key")
        assertThat(searches).containsExactly(TerminalEnvironment.TEST)
    }

    @Test
    fun `missing shared-key permissions still import the account and address with manual fallback`() {
        sharedKeyAvailable = false
        importAutomatic()
        chooseTerminal()
        compose
            .onNodeWithText(
                env.context.getString(R.string.settings_discovery_manual),
                useUnmergedTree = true,
            ).performScrollTo()
            .assertIsDisplayed()
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
        assertThat(env.container.settingsState.value.terminal.host).isEqualTo("192.168.1.42")
    }

    @Test
    fun `Tap to Pay import requires API testing before boarding and registration before shared-key entry`() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        importAutomatic("tapToPay")
        waitForTag("step_2_summary")
        compose.onNodeWithTag("setUpTapToPay").assertDoesNotExist()
        testApi()
        waitForTag("setUpTapToPay")
        compose.onNodeWithTag("merchantAccount").assertDoesNotExist()
        compose.onNodeWithTag("getPaymentsAppTest").assertDoesNotExist()
        compose.onNodeWithTag("step_2").performScrollTo().performClick()
        compose.onNodeWithTag("merchantAccount").performScrollTo().assertTextContains("Merchant", substring = true)
        assertThat(searches).isEmpty()
        assertThat(paymentsApp.opened).isEmpty()
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithTag("findSharedKey").assertDoesNotExist()
    }

    @Test
    fun `Tap to Pay import for a boarded phone still requires manual key entry`() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.updateSettings { it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = FakePaymentsApp.INSTALLATION_ID)) }
        importAutomatic("tapToPay")
        testApi()
        waitForTag("keyIdentifier")
        compose.onNodeWithTag("keyIdentifier").performScrollTo().assertExists()
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
        assertThat(searches).isEmpty()
        assertThat(paymentsApp.opened).isEmpty()
    }

    @Test
    fun `Manual helper import collapses supplied network details without discovery and permits editing`() {
        importAutomatic(
            automatic = false,
            manualDetails = """"merchantAccount":"Merchant","host":"192.168.1.42","poiId":"$POI_ID","keyIdentifier":"manual-key",""",
            secrets = """{"ADYEN_API_KEY":"imported-key","TERMINAL_PASSPHRASE":"manual secret"}""",
        )
        testApi()
        waitForTag("step_4_summary")
        compose.onNodeWithTag("passphrase").assertDoesNotExist()
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("testConnection").assertExists()
        compose.onNodeWithTag("step_3").performScrollTo().performClick()
        compose.onNodeWithTag("host").performScrollTo().assertTextContains("192.168.1.42", substring = true)
        compose.onNodeWithTag("step_3").performScrollTo().performClick()
        compose.onNodeWithTag("host").assertDoesNotExist()
        assertThat(searches).isEmpty()
    }

    @Test
    fun `Tap to Pay helper key stays saved and a secret draft prevents collapsing its editor`() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.updateSettings { it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = FakePaymentsApp.INSTALLATION_ID)) }
        importAutomatic(
            destination = "tapToPay",
            automatic = false,
            manualDetails = """"keyIdentifier":"manual-key","keyVersion":3,""",
            secrets = """{"ADYEN_API_KEY":"imported-key","TERMINAL_PASSPHRASE":"manual secret","PAYMENTS_APP_API_KEY":"boarding-key"}""",
        )
        testApi()
        waitForTag("step_4_summary")
        compose.onNodeWithTag("paymentsAppKey").assertDoesNotExist()
        compose.onNodeWithTag("setUpTapToPay").assertExists()
        compose.onNodeWithTag("step_4").performScrollTo().performClick()
        compose.onNodeWithTag("passphrase").performScrollTo().performTextInput("unsaved secret")
        compose.onNodeWithTag("step_4").performScrollTo().performClick()
        compose.onNodeWithTag("passphrase").performScrollTo().assertTextContains("unsaved secret", substring = true)
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("manual secret")
        assertThat(env.container.settingsState.value.terminal.keyVersion).isEqualTo(3)
        assertThat(searches).isEmpty()
        assertThat(paymentsApp.opened).isEmpty()
        compose.onNodeWithTag("setUpTapToPay").performScrollTo().performClick()
        compose.awaitCondition("registration completes without resetting the draft") {
            env.container.settingsState.value.terminal.paymentsAppInstallationId == FakePaymentsApp.INSTALLATION_ID
        }
        compose.onNodeWithTag("passphrase").performScrollTo().assertTextContains("unsaved secret", substring = true)
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("manual secret")
    }

    private companion object {
        const val POI_ID = "S1F2-000158213605014"
    }
}
