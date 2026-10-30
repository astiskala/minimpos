package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
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
import java.util.Locale
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/**
 * Golden images in French (`fr-FR`) on the AMS1, where the longest text of the supported languages is most likely to
 * wrap or be cut off; see `docs/ui/SCREENSHOT_TESTING.md`. Everything else in the app is compared in `en-AU`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "fr-rFR-w320dp-h460dp-hdpi")
class LocaleGoldensTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule(order = 2)
    val screenshot = ScreenshotRule(compose, ScreenshotTolerance.SCREEN, locale = Locale.forLanguageTag("fr-FR"))

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(currencyCode = "EUR"),
                receipt = it.receipt.copy(businessName = "Café du Coin"),
            )
        }
        await { container.catalog.seedDefaults(GST_RATES) }
    }

    @Test
    fun `home in French`() {
        compose.setContent { MiniMposApp(container) }
        compose.waitUntilAtLeastOneExists(hasTestTag("newSale"), 15_000)
        screenshot.capture()
    }

    @Test
    fun `terminal picker in French`() {
        val poiIds = (1..300).map { "AMS1-000168" + it.toString().padStart(9, '0') }
        compose.setContent { MiniMposTheme { TerminalChoiceDialog(poiIds) {} } }
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalSearch"), 15_000)
        screenshot.capture(screen = true)
    }
}
