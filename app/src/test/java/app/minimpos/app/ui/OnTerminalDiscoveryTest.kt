package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.data.security.Secret
import app.minimpos.app.feature.settings.SettingsSectionScreen
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.SharedKeyUpdate
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class OnTerminalDiscoveryTest {
    private val terminal = FakeTerminal()
    private var remoteKey: DiscoveredKey? = DiscoveredKey("terminal-key", 2, terminal.passphrase)
    private val queries = mutableListOf<String?>()
    private var creations = 0
    private val details: TerminalDetailsApi =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) = app.minimpos.terminal.transport.CredentialLookup.Allowed

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing {
                queries += id
                return TerminalListing.Listed(listOf(TerminalDetails(POI_ID, "Merchant", "")), environment)
            }

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup = remoteKey?.let(SharedKeyLookup::Found) ?: SharedKeyLookup.Missing

            override suspend fun createSharedKey(
                id: String,
                environment: TerminalEnvironment,
                key: DiscoveredKey,
            ): SharedKeyUpdate {
                creations++
                remoteKey = key
                terminal.passphrase = key.passphrase
                return SharedKeyUpdate.Ready(key, created = true)
            }
        }

    @get:Rule(order = 0)
    val env = TestEnvironment(FakeDevice(detectedPoiId = POI_ID, model = "S1F2"), terminal = terminal, terminalDetails = details)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    /** Opens Terminal settings with a saved API key, then tests it and starts the discovery it unlocks. */
    private fun discover() {
        val container = env.container
        await { container.secrets.set(Secret.ADYEN_API_KEY, "imported-key") }
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        container.start()
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.SettingsSection("terminal")))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    SettingsSectionScreen("terminal", navigator)
                }
            }
        }
        // The API test applies to the account on screen, so it must have loaded first. Learning the terminal's
        // environment restarts the setup steps and forgets an earlier test, so wait for that too.
        compose.waitUntilAtLeastOneExists(hasTestTag("merchantAccount") and hasText("Merchant"), 15_000)
        compose.awaitCondition("Terminal environment learned") {
            container.terminalStatus.state.value.environment == TerminalEnvironment.TEST
        }
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("discoverSetup"), 15_000)
        compose.onNodeWithTag("discoverSetup").performScrollTo().performClick()
    }

    @Test
    fun `on terminal discovery fills its details and leaves testing visible`() {
        discover()
        compose.waitUntilAtLeastOneExists(hasTestTag("keyIdentifier") and hasText("terminal-key", substring = true), 15_000)
        compose.onNodeWithTag("step_2").assertExists()
        compose.onNodeWithTag("merchantAccount").assertExists()
        compose.onNodeWithTag("testConnection").assertExists()
        compose.onNodeWithTag("testApi").assertExists()
        compose.onNodeWithTag("keyIdentifier").performScrollTo().assertTextContains("terminal-key", substring = true)
        compose.onNodeWithTag("terminal_$POI_ID").assertDoesNotExist()
        assertThat(queries).isNotEmpty()
        assertThat(queries.toSet()).containsExactly(POI_ID)
    }

    @Test
    fun `on terminal Settings discovery offers confirmed key creation without a terminal selector`() {
        remoteKey = null
        val container = env.container
        discover()
        compose.waitUntilAtLeastOneExists(hasTestTag("createSharedKey"), 15_000)
        compose.onNodeWithTag("terminal_$POI_ID").assertDoesNotExist()
        assertThat(creations).isEqualTo(0)
        compose.onNodeWithTag("createSharedKey").performScrollTo().performClick()
        compose
            .onNodeWithTag(
                "sharedKeyConfirmation",
            ).assertTextContains(POI_ID, substring = true)
            .assertTextContains("TEST", substring = true)
        compose.onNodeWithTag("confirmSharedKey").performClick()
        compose.awaitCondition("Created key saved and checked") {
            creations == 1 && await { container.secrets.keyCreationExists.first() } == false
        }
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo(checkNotNull(remoteKey).passphrase)
        assertThat(queries.toSet()).containsExactly(POI_ID)
    }

    private companion object {
        const val POI_ID = "S1F2-000158213605014"
    }
}
