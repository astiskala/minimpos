package app.minimpos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.minimpos.app.feature.sale.ReceiptToggle
import app.minimpos.app.ui.components.ReceiptPreview
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ReceiptToggleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `preview dividers stay a single row on the smallest screen`() {
        compose.setContent { MiniMposTheme { ReceiptPreview(ReceiptDocument(listOf(ReceiptElement.Divider))) } }
        compose.onNodeWithTag("receiptDivider").assertIsDisplayed().assertHeightIsEqualTo(16.dp)
    }

    @Test
    fun `the receipt action shows and hides the preview on the smallest screen`() {
        val receipt = ReceiptDocument(listOf(ReceiptElement.Text("Receipt preview")))
        compose.setContent {
            MiniMposTheme {
                var shown by remember { mutableStateOf(false) }
                Column { ReceiptToggle(receipt.takeIf { shown }, { shown = !shown }) }
            }
        }
        compose.onNodeWithText("Receipt preview").assertDoesNotExist()
        compose.onNodeWithText("Show receipt").assertIsDisplayed().performClick()
        compose.onNodeWithText("Receipt preview").assertIsDisplayed()
        compose.onNodeWithText("Hide receipt").assertIsDisplayed()
        compose.onNodeWithTag("toggleReceipt").performClick()
        compose.onNodeWithText("Receipt preview").assertDoesNotExist()
        compose.onNodeWithText("Show receipt").assertIsDisplayed()
    }
}
