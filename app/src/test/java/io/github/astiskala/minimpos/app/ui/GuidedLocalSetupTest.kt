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
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakeTerminal
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.feature.settings.SettingsSectionScreen
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.MiniMposTheme
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.SharedKeyLookup
import io.github.astiskala.minimpos.terminal.transport.SharedKeyUpdate
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import kotlinx.coroutines.flow.first
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
    private val terminal = FakeTerminal()
    private var remoteKey: DiscoveredKey? = DiscoveredKey("terminal-key", 2, terminal.passphrase)
    private val queries = mutableListOf<String?>()
    private var creations = 0
    private val details: TerminalDetailsApi =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) =
                io.github.astiskala.minimpos.terminal.transport.CredentialLookup.Allowed

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
        compose.onNodeWithTag("terminal_$POI_ID").assertDoesNotExist()
        assertThat(queries).isNotEmpty()
        assertThat(queries.toSet()).containsExactly(POI_ID)
    }

    @Test
    fun `on terminal Settings discovery offers confirmed key creation without a terminal selector`() {
        remoteKey = null
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
