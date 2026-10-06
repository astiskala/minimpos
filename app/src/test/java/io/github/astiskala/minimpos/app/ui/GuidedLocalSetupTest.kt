package io.github.astiskala.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.feature.settings.SettingsSectionScreen
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.MiniMposTheme
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
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class GuidedLocalSetupTest {
    private val details =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) =
                io.github.astiskala.minimpos.terminal.transport.CredentialLookup.Allowed

            override suspend fun terminals(environment: TerminalEnvironment): TerminalListing =
                TerminalListing.Listed(listOf(TerminalDetails(POI_ID, "Merchant", "")), environment)

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): DiscoveredKey = DiscoveredKey("terminal-key", 2, "correct horse battery staple")
        }

    @get:Rule(order = 0)
    val env = TestEnvironment(FakeDevice(detectedPoiId = POI_ID, model = "S1F2"), terminalDetails = details)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `Automatic helper setup on a terminal collapses discovered details and leaves testing visible`() {
        val container = env.container
        await { container.secrets.set(Secret.ADYEN_API_KEY, "imported-key") }
        container.start()
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.SettingsSection("terminal")))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    SettingsSectionScreen("terminal", navigator, automaticSetup = true, helperSetup = true)
                }
            }
        }
        compose.waitUntilAtLeastOneExists(hasTestTag("keyIdentifier"), 15_000)
        compose.onNodeWithTag("step_2").assertExists()
        compose.onNodeWithTag("merchantAccount").assertExists()
        compose.onNodeWithTag("testConnection").assertExists()
        compose.onNodeWithTag("testApi").assertExists()
        compose.onNodeWithTag("keyIdentifier").performScrollTo().assertTextContains("terminal-key", substring = true)
    }

    private companion object {
        const val POI_ID = "S1F2-000158213605014"
    }
}
