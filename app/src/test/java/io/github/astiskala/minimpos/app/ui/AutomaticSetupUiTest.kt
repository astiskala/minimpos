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
    val env = TestEnvironment(terminal = FakeTerminal(), terminalDetails = details)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun importAutomatic(
        destination: String = "network",
        environment: TerminalEnvironment = TerminalEnvironment.TEST,
    ) {
        val container = env.container
        container.start()
        val seal = TransferSeal(iterations = 1_000)
        val code = seal.newCode()
        val sealed = SealedSecrets(seal.seal("""{"ADYEN_API_KEY":"imported-key"}""".toByteArray(), code))
        val connection =
            """{"destination":"$destination","environment":"$environment","automatic":true,"liveUrlPrefix":"1797a841fbb37ca7-AdyenDemo"}"""
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
                                discoverTerminals = route.discoverTerminals,
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
            .assertTextContains(env.context.getString(R.string.transfer_continue_setup))
            .performClick()
    }

    @Test
    fun `Automatic import offers device-side terminal selection and refreshes all discovered fields`() {
        importAutomatic()
        chooseTerminal()
        compose.onNodeWithTag("host").performScrollTo().assertTextContains("192.168.1.42", substring = true)
        compose.onNodeWithTag("merchantAccount").performScrollTo().assertTextContains("Merchant", substring = true)
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery staple")
        assertThat(searches).containsExactly(TerminalEnvironment.TEST)
    }

    @Test
    fun `Automatic LIVE cloud setup keeps the required imported prefix and selected environment`() {
        importAutomatic("cloud", TerminalEnvironment.LIVE)
        chooseTerminal()
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
        compose.onNodeWithTag("host").assertExists()
        compose.onNodeWithTag("merchantAccount").assertExists()
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

    private companion object {
        const val POI_ID = "S1F2-000158213605014"
    }
}
