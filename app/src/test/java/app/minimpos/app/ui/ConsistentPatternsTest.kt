package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.settings.EmailSettings
import app.minimpos.app.data.settings.TerminalMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The shared patterns: settings that wait for what they need, Adyen-side drafts and stepped screens. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ConsistentPatternsTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private fun open(section: String) {
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("section_$section"), 15_000)
        compose.onNodeWithTag("section_$section").performScrollTo().performClick()
    }

    @Test
    fun `asking for an email on the terminal waits for email receipts`() {
        env.useSimulator { it.copy(payment = it.payment.copy(collectEmailOnTerminal = true)) }
        open("shopper_display")
        compose.waitUntilAtLeastOneExists(hasTestTag("collectEmailOnTerminal"), 15_000)
        compose
            .onNodeWithTag("collectEmailOnTerminal")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertIsOff()
        compose
            .onNodeWithTag("blockedReason")
            .performScrollTo()
            .assertTextContains(env.context.getString(R.string.settings_email_receipts_first))
        env.updateSettings { it.copy(email = EmailSettings(host = "smtp.example.com", fromAddress = "shop@example.com")) }
        compose.waitUntil(15_000) { runCatching { compose.onNodeWithTag("blockedReason").assertDoesNotExist() }.isSuccess }
        compose.onNodeWithTag("collectEmailOnTerminal").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `an Adyen-side section is a draft until Save to Adyen`() {
        env.useSimulator()
        open("capabilities")
        val discovery = env.container.capabilityDiscovery
        compose.awaitCondition("capabilities read") { discovery.state.value.let { it.simulated && !it.checking } }
        compose.waitUntilAtLeastOneExists(hasTestTag("capDccEnable"), 15_000)
        compose.onNodeWithTag("capDccEnable").performScrollTo().performClick()
        assertThat(discovery.state.value.dccEnabled).isFalse()
        compose.onNodeWithTag("saveCapDcc").performScrollTo().performClick()
        compose.awaitCondition("DCC saved") { discovery.state.value.dccEnabled }
    }

    @Test
    fun `reconciliation reveals its later steps once totals are checked`() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("reconciliation").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("step_1"), 15_000)
        compose.onNodeWithTag("step_2").assertDoesNotExist()
        compose.onNodeWithTag("printToday").assertDoesNotExist()
        compose.onNodeWithTag("reconcileTerminal").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("step_1_done", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("step_2").performScrollTo()
        compose.onNodeWithTag("step_3").performScrollTo()
        compose.onNodeWithTag("printToday").performScrollTo().assertIsEnabled()
    }

    @Test
    fun `home says payments are not set up yet and its payment tiles explain that instead of opening`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD)) }
        compose.setContent { MiniMposApp(env.container) }
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalSetup"), 15_000)
        // The tiles stay pressable but explain what is missing rather than opening a sale that could only fail.
        compose.onNodeWithTag("newSale").performClick()
        compose.waitUntilAtLeastOneExists(hasText(env.context.getString(R.string.setup_required_message)), 15_000)
        compose.onNodeWithText(env.context.getString(R.string.action_cancel)).performClick()
        compose.onNodeWithTag("productGrid").assertDoesNotExist()
        // History needs no setup.
        compose.onNodeWithTag("history").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("back"), 15_000)
        compose.onNodeWithTag("back").performClick()
        compose
            .onNodeWithTag("terminalSetup")
            .assertTextContains(env.context.getString(R.string.setup_required_title), substring = true)
            .assertTextContains(env.context.getString(R.string.home_setup_text), substring = true)
            .performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalMode"), 15_000)
    }

    @Test
    fun `the dialog's way out finishes the setup in Terminal settings`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD)) }
        compose.setContent { MiniMposApp(env.container) }
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalSetup"), 15_000)
        compose.onNodeWithTag("refund").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirm"), 15_000)
        compose.onNodeWithTag("confirm").assertTextContains(env.context.getString(R.string.setup_required_action)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalMode"), 15_000)
    }
}
