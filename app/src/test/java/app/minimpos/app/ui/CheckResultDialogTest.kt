package app.minimpos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.Failure
import app.minimpos.app.feature.StatusCheck
import app.minimpos.app.feature.TransactionActionsState
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.terminal.transport.Fault
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class CheckResultDialogTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `receipt test print reports its result in a dismissible popup on AMS1`() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("section_receipts").performScrollTo().performClick()
        // The asynchronously measured preview changes the action's position once it appears.
        compose.waitUntilAtLeastOneExists(hasTestTag("receiptImage"), 15_000)
        repeat(2) {
            compose.onNodeWithTag("printTest").performScrollTo().assertIsDisplayed()
            compose.clickWhenEnabled("printTest")
            compose.waitUntilAtLeastOneExists(hasTestTag("testPrintResult"), 15_000)
            compose.onNodeWithTag("testPrintResult").assertIsDisplayed().assertTextContains("Printed")
            compose.onNodeWithTag("testPrintResultOk").performClick()
            compose.waitUntilDoesNotExist(hasTestTag("testPrintResult"), 15_000)
        }
    }

    @Test
    fun `test email reports its recipient in a dismissible popup`() {
        env.useSimulator { it.copy(email = it.email.copy(host = "smtp.example.com", fromAddress = "shop@example.com")) }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("section_email").performScrollTo().performClick()
        compose.onNodeWithTag("sendTestEmail").performScrollTo().performClick()
        compose.onNodeWithTag("dialogInput").performTextInput("shopper@example.com")
        compose.onNodeWithTag("dialogConfirm").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("testEmailResult"), 15_000)
        compose.onNodeWithTag("testEmailResult").assertIsDisplayed().assertTextContains("shopper@example.com", substring = true)
        compose.onNodeWithTag("testEmailResultOk").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("testEmailResult"), 15_000)
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `unresolved status checks show repeatable dismissible results in Japanese`() {
        compose.setContent {
            MiniMposTheme {
                var state by remember { mutableStateOf(TransactionActionsState()) }
                Column {
                    StatusCheck(
                        state,
                        onRecheck = { state = state.copy(stillUnknown = Failure.Remote(Fault.TimedOut)) },
                        onDismiss = { state = state.copy(stillUnknown = null) },
                    )
                }
            }
        }
        repeat(2) {
            compose.onNodeWithTag("checkAgain").performClick()
            compose.onNodeWithTag("statusCheckResult").assertIsDisplayed()
            compose.onNodeWithTag("statusCheckResultOk").performClick()
            compose.onNodeWithTag("statusCheckResult").assertDoesNotExist()
        }
    }
}
