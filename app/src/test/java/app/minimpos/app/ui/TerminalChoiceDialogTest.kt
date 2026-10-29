package app.minimpos.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.feature.settings.TerminalChoiceDialog
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Choosing one of an account's many cloud terminals on the smallest terminal. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class TerminalChoiceDialogTest {
    @get:Rule
    val compose = createRecordingComposeRule()

    // Sorted as the listing returns them: the S1F2 comes after hundreds of other models.
    private val poiIds = (1..300).map { "AMS1-000168" + it.toString().padStart(9, '0') } + "S1F2-000158204502740"

    @Test
    fun `a search finds a terminal among hundreds by part of its serial number`() {
        var chosen: String? = null
        compose.setContent { MiniMposTheme { TerminalChoiceDialog(poiIds) { chosen = it } } }
        compose
            .onNodeWithText(
                "After selection, Mini mPOS checks the connection if setup is complete. No payment is taken.",
            ).assertIsDisplayed()
        compose.onNodeWithTag("terminalSearch").performTextInput("2740")
        compose.onNodeWithTag("terminal_S1F2-000158204502740").assertIsDisplayed().performClick()
        assertThat(chosen).isEqualTo("S1F2-000158204502740")
    }

    @Test
    fun `a search ignores case and says when nothing matches`() {
        compose.setContent { MiniMposTheme { TerminalChoiceDialog(poiIds) {} } }
        compose.onNodeWithTag("terminalSearch").performTextInput("s1f2")
        compose.onNodeWithTag("terminal_S1F2-000158204502740").assertIsDisplayed()
        compose.onNodeWithTag("terminalSearch").performTextInput("x")
        compose.onNodeWithText("No terminals match.").assertIsDisplayed()
    }

    @Test
    fun `a few terminals are listed without a search field`() {
        compose.setContent { MiniMposTheme { TerminalChoiceDialog(poiIds.takeLast(3)) {} } }
        compose.onNodeWithTag("terminal_S1F2-000158204502740").assertIsDisplayed()
        compose.onNodeWithTag("terminalSearch").assertDoesNotExist()
        compose
            .onNodeWithText(
                "After selection, Mini mPOS checks the connection if setup is complete. No payment is taken.",
            ).assertIsDisplayed()
    }
}
