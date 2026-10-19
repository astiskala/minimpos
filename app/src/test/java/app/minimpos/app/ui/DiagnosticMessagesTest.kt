package app.minimpos.app.ui

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.minimpos.app.TestEnvironment
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.Failure
import app.minimpos.app.feature.text
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiagnosticMessagesTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `support failures keep HTTP and error codes but never echo unstructured secret text`() {
        val failure = Failure.Remote(Fault.AdyenRejected(422, "14_001", ExternalText("api-secret-echo")))
        compose.setContent {
            MiniMposTheme {
                Text(failure.text(redactExternal = true), Modifier.testTag("diagnosticFault"))
            }
        }
        compose.onNodeWithTag("diagnosticFault").assertTextContains("HTTP 422", substring = true)
        compose.onNodeWithTag("diagnosticFault").assertTextContains("14_001", substring = true)
        compose.onNodeWithText("api-secret-echo", substring = true).assertDoesNotExist()
    }
}
