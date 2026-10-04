package io.github.astiskala.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
class SetupDiscoveryUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    @Test
    fun `discovery refreshes manual fields after saving the API key without an account`() {
        val container = env.container
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
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
