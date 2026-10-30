package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.ScreenshotRule
import app.minimpos.app.ScreenshotTolerance
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.feature.settings.TerminalChoiceDialog
import app.minimpos.app.ui.theme.MiniMposTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/**
 * Golden images of Settings and its picker dialogs in the simulator on a regular phone and, for the dialogs, the AMS1; see
 * `docs/ui/SCREENSHOT_TESTING.md`. The screens come before the token cleanup and the picker extraction in
 * `docs/ui/DESIGN_AUDIT.md`, so a change to how they look shows up as a difference.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class SettingsGoldensTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule(order = 2)
    val screenshot = ScreenshotRule(compose, ScreenshotTolerance.SCREEN)

    private val container = env.container

    // Sorted as the listing returns them, so the dialog has a search field and a long list.
    private val poiIds = (1..300).map { "AMS1-000168" + it.toString().padStart(9, '0') } + "S1F2-000158204502740"

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(currencyCode = "AUD"),
                receipt = it.receipt.copy(businessName = "Corner Cafe"),
            )
        }
        await { container.catalog.seedDefaults(GST_RATES) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun openSettings() {
        compose.setContent { MiniMposApp(container) }
        waitForTag("settings")
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
    }

    private fun openSection(tag: String) {
        openSettings()
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    @Test
    fun `settings list`() {
        openSettings()
        screenshot.capture()
    }

    @Test
    fun `terminal setup`() {
        openSection("section_terminal")
        waitForTag("quickSetupImport")
        screenshot.capture()
    }

    @Test
    fun `product price with an error`() {
        compose.setContent { MiniMposApp(container) }
        waitForTag("settings")
        compose.openProducts()
        waitForTag("addFab")
        compose.onNodeWithTag("addFab").performClick()
        waitForTag("productPrice")
        compose.onNodeWithTag("productPrice").performTextInput("1e17")
        screenshot.capture()
    }

    @Test
    fun `tax settings`() {
        openSection("section_tax")
        waitForTag("addTaxRate")
        screenshot.capture()
    }

    @Test
    fun `email settings`() {
        openSection("section_email")
        waitForTag("emailCapture")
        screenshot.capture()
    }

    @Test
    fun `receipt settings`() {
        openSection("section_receipts")
        waitForTag("printTest")
        screenshot.capture()
    }

    @Test
    fun `security settings`() {
        openSection("section_security")
        waitForTag("setPin")
        screenshot.capture()
    }

    private fun openCurrencyPicker() {
        openSection("section_payments")
        waitForTag("currency")
        compose.onNodeWithTag("currency").performScrollTo().performClick()
        waitForTag("currencySearch")
    }

    @Test
    fun `currency picker`() {
        openCurrencyPicker()
        screenshot.capture(screen = true)
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `currency picker on the smallest screen`() {
        openCurrencyPicker()
        screenshot.capture(screen = true)
    }

    @Test
    fun `terminal picker`() {
        compose.setContent { MiniMposTheme { TerminalChoiceDialog(poiIds) {} } }
        waitForTag("terminalSearch")
        screenshot.capture(screen = true)
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `terminal picker on the smallest screen`() {
        compose.setContent { MiniMposTheme { TerminalChoiceDialog(poiIds) {} } }
        waitForTag("terminalSearch")
        screenshot.capture(screen = true)
    }
}
