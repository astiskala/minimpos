package app.minimpos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.feature.MerchantCopy
import app.minimpos.app.feature.ReceiptActions
import app.minimpos.app.feature.ReceiptEvents
import app.minimpos.app.feature.TransactionActionsState
import app.minimpos.app.receipt.PrintRenderer
import app.minimpos.app.share.ReceiptImage
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.PrintJobsPreview
import app.minimpos.app.ui.components.ReceiptPreview
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.receipt.ReceiptCopy
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ReceiptToggleTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun awaitSlips(count: Int = 1) =
        compose.awaitCondition("receipt layout measured") {
            compose.onAllNodesWithTag("receiptImage").fetchSemanticsNodes().size == count
        }

    @Test
    fun `preview keeps the shared slip proportions on the smallest screen`() {
        val document = ReceiptDocument(listOf(ReceiptElement.Divider))
        compose.setContent { MiniMposTheme { ReceiptPreview(document) } }
        awaitSlips()
        val bounds =
            compose
                .onNodeWithTag("receiptImage")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val shared = ReceiptImage.render(document)
        assertThat(bounds.height / bounds.width).isWithin(0.005f).of(shared.height.toFloat() / shared.width)
    }

    @Test
    fun `the simulated print and preview use the same slip layout`() {
        val document =
            ReceiptDocument(
                listOf(
                    ReceiptElement.Text("Corner Cafe"),
                    ReceiptElement.Divider,
                    ReceiptElement.Text("-"),
                    ReceiptElement.Qr("MPR1*demo", "Refund code"),
                    ReceiptElement.Link("https://example.com/receipt"),
                ),
            )
        compose.setContent {
            MiniMposTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ReceiptPreview(document)
                    PrintJobsPreview(PrintRenderer.jobs(document, dividerWidth = 32))
                }
            }
        }
        awaitSlips(2)
        val slips = compose.onAllNodesWithTag("receiptImage")
        assertThat(slips.fetchSemanticsNodes()).hasSize(2)
        val preview = slips[0].getUnclippedBoundsInRoot()
        val printed = slips[1].getUnclippedBoundsInRoot()
        assertThat(printed.width).isEqualTo(preview.width)
        assertThat(printed.height).isEqualTo(preview.height)
        compose.onNodeWithText("-".repeat(32)).assertDoesNotExist()
        assertThat(compose.onAllNodesWithText("-").fetchSemanticsNodes()).hasSize(2)
    }

    @Test
    fun `references keep the shared layout and remain available to accessibility`() {
        val document =
            ReceiptDocument(
                listOf(
                    ReceiptElement.Row("Total", "$34.00"),
                    ReceiptElement.Row("Reference", "261022-102607-2YSD"),
                    ReceiptElement.Qr("MPR1*demo", "Refund code"),
                    ReceiptElement.Link("https://example.com/receipt"),
                ),
            )
        compose.setContent { MiniMposTheme { ReceiptPreview(document) } }
        awaitSlips()
        val shared = ReceiptImage.render(document)
        val bounds = compose.onNodeWithTag("receiptImage").fetchSemanticsNode().boundsInRoot
        assertThat(bounds.height / bounds.width).isWithin(0.005f).of(shared.height.toFloat() / shared.width)
        listOf("Total", "$34.00", "Reference", "261022-102607-2YSD", "Refund code", "https://example.com/receipt").forEach {
            compose.onNodeWithText(it).assertExists()
        }
    }

    @Test
    fun `the receipt action shows and hides the preview on the smallest screen`() {
        val receipt = ReceiptDocument(listOf(ReceiptElement.Text("Receipt preview")))
        val events =
            object : ReceiptEvents {
                override fun print(copy: ReceiptCopy) = Unit

                override fun email(to: String) = Unit

                override fun share() = Unit
            }
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    Column { ReceiptActions(TransactionActionsState(receipt = receipt), events, MerchantCopy.NONE) }
                }
            }
        }
        compose.onNodeWithText("Receipt preview").assertDoesNotExist()
        compose.onNodeWithText("Show receipt").assertIsDisplayed().performClick()
        awaitSlips()
        compose.onNodeWithText("Receipt preview").assertIsDisplayed()
        compose.onNodeWithText("Hide receipt").assertIsDisplayed()
        compose.onNodeWithTag("toggleReceipt").performClick()
        compose.onNodeWithText("Receipt preview").assertDoesNotExist()
        compose.onNodeWithText("Show receipt").assertIsDisplayed()
    }
}
