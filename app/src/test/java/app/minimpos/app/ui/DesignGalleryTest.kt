package app.minimpos.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isEnabled
import app.minimpos.app.ScreenshotRule
import app.minimpos.app.ScreenshotTolerance
import app.minimpos.app.TestEnvironment
import app.minimpos.app.ui.components.ActionGroup
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.BlockedReason
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.ChoiceChip
import app.minimpos.app.ui.components.CompactSecondaryButton
import app.minimpos.app.ui.components.CompactTertiaryButton
import app.minimpos.app.ui.components.ConfirmDialog
import app.minimpos.app.ui.components.Description
import app.minimpos.app.ui.components.EmptyState
import app.minimpos.app.ui.components.GroupAction
import app.minimpos.app.ui.components.LabeledValue
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.NumericKeypad
import app.minimpos.app.ui.components.OutcomeHeader
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.StatusBadge
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.app.ui.components.Subheading
import app.minimpos.app.ui.components.SwitchRow
import app.minimpos.app.ui.components.TertiaryButton
import app.minimpos.app.ui.components.TransactionRow
import app.minimpos.app.ui.components.fieldErrorIcon
import app.minimpos.app.ui.theme.Dimens
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.MinTouchTarget
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.app.ui.theme.Spacing
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/**
 * Golden images of the shared components in the real theme, one test per group (`CONTRIBUTING.md`). The
 * text is fixed here, not read from resources, so rewording a screen never changes a golden. Components are compared
 * exactly. The `Regular` gallery sizes the window to its content; [Dimens] is set explicitly because the gallery is not
 * a whole screen. The Compact variants run at the AMS1's size and the Medium ones at the S1F2's, where the app itself
 * switches to those dimensions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h640dp-xhdpi")
class DesignGalleryTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule(order = 2)
    val screenshot = ScreenshotRule(compose, ScreenshotTolerance.EXACT)

    @Composable
    private fun Gallery(
        dimens: Dimens,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        MiniMposTheme {
            CompositionLocalProvider(LocalDimens provides dimens) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.padding(Spacing.Large), verticalArrangement = Arrangement.spacedBy(Spacing.Medium), content = content)
                }
            }
        }
    }

    private fun show(
        dimens: Dimens = Dimens.Regular,
        content: @Composable ColumnScope.() -> Unit,
    ) = screenshot.setContent { Gallery(dimens, content) }

    /** Check the effective hit area, which Compose can expand beyond a compact control's drawn bounds. */
    private fun capture(
        variant: String? = null,
        screen: Boolean = false,
    ) {
        compose.onAllNodes(hasClickAction() and isEnabled()).fetchSemanticsNodes().forEach { node ->
            val minimum = with(node.layoutInfo.density) { MinTouchTarget.toPx() }
            assertWithMessage("Touch width: ${node.config}").that(node.touchBoundsInRoot.width).isAtLeast(minimum)
            assertWithMessage("Touch height: ${node.config}").that(node.touchBoundsInRoot.height).isAtLeast(minimum)
        }
        screenshot.capture(variant, screen)
    }

    @Composable
    private fun ColumnScope.Buttons() {
        PrimaryButton("Charge $12.50", {}, icon = Icons.Default.Add)
        PrimaryButton("Charging", {}, loading = true)
        PrimaryButton("Charge $12.50", {}, enabled = false)
        SecondaryButton("Print receipt", {})
        SecondaryButton("Print receipt", {}, enabled = false)
        TertiaryButton("Show receipt", {})
        TertiaryButton("Remove saved key", {}, destructive = true)
    }

    @Test
    fun `buttons in every variant`() {
        show { Buttons() }
        capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `buttons in every variant on a compact screen`() {
        show(Dimens.Compact) { Buttons() }
        capture("compact")
    }

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h600dp-xhdpi")
    fun `buttons in every variant on a medium screen`() {
        show(Dimens.Medium) { Buttons() }
        capture("medium")
    }

    @Composable
    private fun ColumnScope.CompactActions() {
        CompactSecondaryButton("Download", {})
        CompactSecondaryButton("Pause", {})
        CompactTertiaryButton("Cancel", {})
        CompactSecondaryButton("Download", {}, enabled = false)
        CompactTertiaryButton("Remove", {}, destructive = true)
        CompactTertiaryButton("Cancel", {}, enabled = false)
    }

    @Test
    fun `compact secondary and tertiary actions`() {
        show { CompactActions() }
        capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `compact actions on the smallest screen`() {
        show(Dimens.Compact) { CompactActions() }
        capture()
    }

    @Test
    fun `card and notices`() {
        show {
            Card {
                LabeledValue("Subtotal", "$11.36")
                LabeledValue("GST", "$1.14")
                LabeledValue("Total", "$12.50", emphasize = true)
            }
            Notice("Card payments need the Checkout API to be set up first.")
            Notice("Changing the destination clears the saved terminal.", kind = NoticeKind.WARNING)
        }
        capture()
    }

    @Test
    fun `text roles`() {
        show {
            SectionHeader("Receipts")
            Subheading("Business details")
            Description("Shown at the top of every receipt, so shoppers know who they paid.")
        }
        capture()
    }

    @Test
    fun `feedback and status badges`() {
        show {
            PrimaryButton("Pay", {}, enabled = false)
            BlockedReason("Enter the shared key to take payments.")
            ActionMessage("Connection checked.", isError = false)
            ActionMessage("The terminal did not answer.", isError = true)
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Large)) {
                StatusKind.entries.forEach { StatusBadge(it) }
            }
        }
        capture()
    }

    @Test
    fun `empty state`() {
        screenshot.setContent {
            Gallery(Dimens.Regular) {
                EmptyState(Icons.AutoMirrored.Filled.ReceiptLong, "No sales yet", "Completed sales appear here.")
            }
        }
        capture()
    }

    @Test
    fun `switch row and text field with an error`() {
        show {
            SwitchRow("Print automatically", checked = true, onChange = {}, subtitle = "Prints the customer receipt after each sale.")
            SwitchRow("Email receipts", checked = false, onChange = {})
            SwitchRow("Cash refunds", checked = false, onChange = {}, subtitle = "Require Manager PIN for this action.", enabled = false)
            OutlinedTextField(
                value = "12,5",
                onValueChange = {},
                label = { Text("Price") },
                isError = true,
                trailingIcon = fieldErrorIcon(true),
                supportingText = { Text("Enter a price such as 12.50.") },
            )
        }
        capture()
    }

    private val receiptActions =
        listOf(
            GroupAction("Print receipt", Icons.Default.Print, "print") {},
            GroupAction("Print merchant copy", Icons.Default.Print, "printMerchant", loading = true) {},
            GroupAction("Email receipt", Icons.Default.Email, "emailReceipt") {},
            GroupAction("Share receipt", Icons.Default.Share, "shareReceipt") {},
            GroupAction("Show receipt", Icons.AutoMirrored.Filled.ReceiptLong, "toggleReceipt") {},
        )

    @Test
    fun `action group`() {
        show { ActionGroup(receiptActions) }
        capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `action group on the smallest screen`() {
        show(Dimens.Compact) { ActionGroup(receiptActions) }
        capture("compact")
    }

    @Test
    fun `choice chips and transaction rows`() {
        show {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Small)) {
                ChoiceChip("Method", selected = false, onClick = {}, dropdown = true)
                ChoiceChip("All", selected = true, onClick = {})
                ChoiceChip("Sales", selected = false, onClick = {})
            }
            TransactionRow("261015-101000-CCCCC", "10:05 am", "$6.40", "Declined", StatusKind.ERROR, {})
            TransactionRow("261015-094000-BBBBB", "9:40 am · VISA", "$23.80", "Approved", StatusKind.SUCCESS, {})
            TransactionRow("261015-093000-AAAAA", "9:30 am", "$5.00", "Unknown", StatusKind.WARNING, {})
        }
        capture()
    }

    @Test
    fun `outcome header`() {
        show { OutcomeHeader(StatusKind.SUCCESS, "Approved", "$12.50") }
        capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `outcome header on the smallest screen`() {
        show(Dimens.Compact) {
            OutcomeHeader(StatusKind.SUCCESS, "Approved", "$12.50")
            OutcomeHeader(StatusKind.WARNING, "Refund requested", "$1,234.50")
        }
        capture("compact")
    }

    private val keypad: @Composable ColumnScope.() -> Unit = {
        NumericKeypad(onDigit = {}, onDoubleZero = {}, onBackspace = {})
    }

    @Test
    fun keypad() {
        show(content = keypad)
        capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `keypad on a compact screen`() {
        show(Dimens.Compact, keypad)
        capture("compact")
    }

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h600dp-xhdpi")
    fun `keypad on a medium screen`() {
        show(Dimens.Medium, keypad)
        capture("medium")
    }

    @Test
    fun `confirm dialog`() {
        screenshot.setContent {
            MiniMposTheme {
                ConfirmDialog(
                    title = "Remove the shared key?",
                    message = "Payments stop until you enter it again.",
                    confirmLabel = "Remove",
                    onConfirm = {},
                    onDismiss = {},
                    destructive = true,
                )
            }
        }
        capture(screen = true)
    }
}
