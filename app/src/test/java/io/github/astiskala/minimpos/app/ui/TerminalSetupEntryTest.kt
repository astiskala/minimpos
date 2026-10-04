package io.github.astiskala.minimpos.app.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class TerminalSetupEntryTest {
    @get:Rule(order = 0)
    val env = TestEnvironment(FakeDevice(detectedPoiId = "AMS1-1"))

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun openTerminal() {
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("section_terminal"), 15_000)
        compose.onNodeWithTag("section_terminal").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalMode"), 15_000)
    }

    @Test
    fun `untouched on-device setup puts scanning before connection choices without an environment prerequisite`() {
        openTerminal()
        compose.onNodeWithTag("quickSetupImport").assertIsDisplayed()
        val scan = compose.onNodeWithTag("quickSetupImport").fetchSemanticsNode().boundsInRoot
        val mode = compose.onNodeWithTag("terminalMode").fetchSemanticsNode().boundsInRoot
        assertThat(scan.top).isLessThan(mode.top)
        val before = await { env.container.settings.current() }
        compose.onNodeWithTag("quickSetupImport").performClick()
        compose.onNodeWithTag("back").performClick()
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
    }

    @Test
    fun `partially configured and complete setups keep the header scanner without the large section`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        openTerminal()
        compose.onNodeWithTag("quickSetupImport").assertDoesNotExist()
        compose.onNodeWithTag("scanSetup").assertIsDisplayed().performClick()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("scanSetup").assertIsDisplayed()
        assertThat(await { env.container.settings.current() }.terminal.merchantAccount).isEqualTo("Merchant")
    }

    @Test
    fun `scanning asks before discarding an unsaved key and cancel keeps its draft`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
        openTerminal()
        compose.onNodeWithTag("apiKey").performScrollTo().performTextInput("draft-key")
        compose.onNodeWithTag("scanSetup").performClick()
        compose.onNodeWithText("Unsaved keys will be discarded", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("apiKey").assertTextContains("draft-key")
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isNull()
        compose.onNodeWithTag("scanSetup").performClick()
        compose.onNodeWithText("Discard and scan").performClick()
        compose.onNodeWithTag("back").performClick()
        val field = compose.onNodeWithTag("apiKey").performScrollTo().fetchSemanticsNode()
        assertThat(field.config[SemanticsProperties.EditableText].text).isEmpty()
        assertThat(await { env.container.secrets.get(Secret.ADYEN_API_KEY) }).isNull()
    }
}
