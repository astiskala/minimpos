package io.github.astiskala.minimpos.app.ui

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
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SetupDiscoveryUiTest {
    private val phone = FakeDevice()

    @get:Rule(order = 0)
    val env = TestEnvironment(phone)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun chooseMode(
        mode: TerminalMode,
        stored: TerminalMode = mode,
    ) {
        compose.onNodeWithTag("terminalMode").performScrollTo().performClick()
        compose.onNodeWithTag("terminalMode_$mode").performClick()
        compose.awaitCondition("the destination is saved") { env.container.settingsState.value.terminal.mode == stored }
    }

    private fun assertSteps(vararg titles: String) =
        titles.forEachIndexed { index, title -> compose.onNodeWithTag("step_${index + 1}").assertTextContains(title) }

    @Test
    @Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
    fun `terminal setup steps follow the selected destination`() {
        val container = env.container
        env.useSimulator()
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("terminalMode")
        compose.onNodeWithTag("terminalMode").assertTextContains("Simulator", substring = true)
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithText("Sale ID").assertDoesNotExist()
        compose.onNodeWithTag("advanced").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("Sale ID"), 15_000)

        chooseMode(TerminalMode.TERMINAL)
        waitForTag("environment_TEST")
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("environment_TEST").performScrollTo().performClick()
        compose.awaitCondition("the TEST environment is saved") {
            container.settingsState.value.terminal.environment ==
                TerminalEnvironment.TEST
        }
        waitForTag("host")
        compose.onNodeWithTag("poiId").assertExists()
        compose.onNodeWithTag("keyIdentifier").assertExists()
        assertSteps("Environment", "Adyen API key", "Terminal", "Shared key", "Checkout API")
        compose.onNodeWithTag("merchantAccount").assertExists()

        chooseMode(TerminalMode.CLOUD)
        waitForTag("environment_LIVE")
        compose.onNodeWithTag("apiKey").assertDoesNotExist()
        compose.onNodeWithTag("environment_LIVE").performScrollTo().performClick()
        compose.awaitCondition("the LIVE environment is saved") {
            container.settingsState.value.terminal.environment ==
                TerminalEnvironment.LIVE
        }
        waitForTag("findTerminals")
        compose.onNodeWithTag("apiKey").assertExists()
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithTag("testApi").assertDoesNotExist()
        compose.onNodeWithTag("testConnection").assertExists()
        assertSteps("Environment", "Adyen API key", "Adyen account", "Terminal")
        compose.onNodeWithTag("livePrefix").assertExists()

        chooseMode(TerminalMode.PAYMENTS_APP)
        waitForTag("setUpTapToPay")
        compose.onNodeWithText("Not installed").assertExists()
        compose.onNodeWithTag("getPaymentsAppTest").assertExists()
        compose.onNodeWithTag("getPaymentsAppLive").assertExists()
        assertSteps("Adyen Payments app", "Adyen API key", "Checkout API", "Tap to Pay", "Shared key")
        compose.onNodeWithTag("environment").assertDoesNotExist()
        compose.onNodeWithTag("discoverSetup").assertDoesNotExist()
        compose.onNodeWithTag("keyIdentifier").assertExists()
        compose.onNodeWithTag("paymentsAppKey").assertExists()
        compose.onNodeWithTag("host").assertDoesNotExist()
        // Choosing the device's own default stores Automatic, so it keeps following the device.
        chooseMode(TerminalMode.SIMULATOR, TerminalMode.AUTO)
    }

    @Test
    fun `network TEST setup starts with an explicit environment on the smallest screen`() =
        chooseEnvironment(TerminalMode.TERMINAL, TerminalEnvironment.TEST)

    @Test
    fun `cloud LIVE setup shows the account and live prefix after choosing the environment`() =
        chooseEnvironment(TerminalMode.CLOUD, TerminalEnvironment.LIVE)

    @Test
    @Config(qualifiers = "en-rAU-w800dp-h1200dp-xhdpi")
    fun `a tablet selects its environment before network setup`() = chooseEnvironment(TerminalMode.TERMINAL, TerminalEnvironment.LIVE)

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese cloud setup selects TEST before credentials`() = chooseEnvironment(TerminalMode.CLOUD, TerminalEnvironment.TEST)

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `Japanese network setup selects LIVE before credentials`() = chooseEnvironment(TerminalMode.TERMINAL, TerminalEnvironment.LIVE)

    private fun chooseEnvironment(
        mode: TerminalMode,
        environment: TerminalEnvironment,
    ) {
        val container = env.container
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = mode)) }
        container.start()
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("step_1")
        compose.onNodeWithTag("step_1").assertTextContains(env.context.getString(R.string.settings_environment))
        assertEnvironmentHelpOrder(environment)
        compose.onNodeWithTag("apiKey").assertDoesNotExist()
        compose.waitUntilAtLeastOneExists(
            hasTestTag("connectionStatus") and hasText(env.context.getString(R.string.setup_environment), substring = true),
            15_000,
        )
        compose.onNodeWithTag("environment_$environment").assertIsDisplayed().performClick()
        waitForTag("apiKey")
        compose.onNodeWithTag("step_2").assertTextContains(env.context.getString(R.string.settings_adyen_key))
        compose.onNodeWithTag("step_3").assertTextContains(
            env.context.getString(if (mode == TerminalMode.CLOUD) R.string.settings_api_cloud else R.string.settings_step_terminal),
        )
        compose.awaitCondition("the selected environment is saved") {
            container.settingsState.value.terminal.environment == environment
        }
        assertRoleHelp(mode)
        if (environment == TerminalEnvironment.LIVE) {
            compose.onNodeWithTag("livePrefix").performScrollTo().assertIsDisplayed()
        } else {
            compose.onNodeWithTag("livePrefix").assertDoesNotExist()
        }
        assertThat(env.context.getString(R.string.settings_api_cloud_hint)).doesNotContain("API credentials")
        assertThat(env.context.getString(R.string.settings_api_hint)).doesNotContain("API credentials")
    }

    private fun assertEnvironmentHelpOrder(environment: TerminalEnvironment) {
        val heading = compose.onNodeWithTag("step_1").fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithTag("environmentHint").fetchSemanticsNode().boundsInRoot
        val choice = compose.onNodeWithTag("environment_$environment").fetchSemanticsNode().boundsInRoot
        assertThat(hint.top).isAtLeast(heading.bottom)
        assertThat(hint.bottom).isAtMost(choice.top)
    }

    private fun assertRoleHelp(mode: TerminalMode) {
        compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_terminals), substring = true).assertExists()
        compose.onNodeWithText("Checkout webservice role", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Required for real payments.", substring = true).assertDoesNotExist()
        if (mode == TerminalMode.CLOUD) {
            compose.onNodeWithTag("roleCloud").assertExists()
            compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_shared_key), substring = true).assertDoesNotExist()
        } else {
            compose.onNodeWithTag("roleCloud").assertDoesNotExist()
            compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_settings), substring = true).assertExists()
            compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_shared_key), substring = true).assertExists()
        }
    }

    @Test
    fun `Tap to Pay installs the Payments app first and saves its key without terminal discovery`() {
        phone.paymentsApps = setOf(TerminalEnvironment.TEST)
        val container = env.container
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP)) }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("step_1")
        compose.onNodeWithTag("step_1").assertIsDisplayed().assertTextContains("Adyen Payments app")
        compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_settings), substring = true).assertExists()
        compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_shared_key), substring = true).assertExists()
        compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_terminals), substring = true).assertDoesNotExist()
        compose.onNodeWithTag("environment").assertDoesNotExist()
        compose.onNodeWithTag("discoverSetup").assertDoesNotExist()
        compose.onNodeWithTag("apiKey").performScrollTo().performTextInput(" demo-key ")
        compose
            .onNodeWithTag("saveApiKey")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.awaitCondition("the API key is saved without discovery") {
            await { container.secrets.get(Secret.ADYEN_API_KEY) } == "demo-key"
        }
        compose.waitUntilDoesNotExist(hasTestTag("saveApiKey"), 15_000)
        compose.onNodeWithTag("terminalsResult").assertDoesNotExist()
        compose.onNodeWithTag("step_3").assertExists()
    }

    @Test
    fun `discovery refreshes manual fields after saving the API key without an account`() {
        val container = env.container
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST)) }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("apiKey")
        compose.onNodeWithTag("apiKey").performScrollTo().performTextInput("discovery-key")
        compose.onNodeWithTag("discoverSetup").performScrollTo().performClick()
        waitForTag("terminal_S1F2-000158213605014")
        compose.onNodeWithTag("terminal_S1F2-000158213605014").performClick()
        compose.awaitCondition("discovered settings reach the screen") {
            container.settingsState.value.terminal.merchantAccount ==
                "Merchant"
        }
        compose.onNodeWithTag("host").performScrollTo().assertTextContains("192.168.1.42", substring = true)
        compose.onNodeWithTag("poiId").assertTextContains("S1F2-000158213605014", substring = true)
        compose.onNodeWithTag("merchantAccount").performScrollTo().assertTextContains("Merchant", substring = true)
    }
}
