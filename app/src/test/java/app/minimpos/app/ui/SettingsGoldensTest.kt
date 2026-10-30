package app.minimpos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.ScreenshotRule
import app.minimpos.app.ScreenshotTolerance
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.feature.lock.SetPinScreen
import app.minimpos.app.feature.settings.BusinessReviewScreen
import app.minimpos.app.feature.settings.IntegrationTroubleshooting
import app.minimpos.app.feature.settings.TerminalChoiceDialog
import app.minimpos.app.feature.settings.TerminalSetupActions
import app.minimpos.app.feature.transfer.CodeControls
import app.minimpos.app.feature.transfer.ImportUiState
import app.minimpos.app.feature.transfer.TransferImportScreen
import app.minimpos.app.feature.transfer.TransferImportViewModel
import app.minimpos.app.terminal.ReceiptBusiness
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.catalogue.Catalogue
import app.minimpos.core.catalogue.CatalogueTaxRate
import app.minimpos.core.codec.QrChunks
import app.minimpos.core.codec.Transfer
import app.minimpos.core.codec.TransferCodec
import kotlinx.coroutines.cancel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/**
 * Golden images of Settings and its picker dialogs in the simulator on a regular phone and, for the dialogs, the AMS1; see
 * `docs/ui/SCREENSHOT_TESTING.md`. A change to how they look shows up as a difference.
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

    @get:Rule(order = 3)
    val largeTextScreenshot = ScreenshotRule(compose, ScreenshotTolerance.SCREEN, fontScale = 1.5f)

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
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `whole-number price error on the smallest screen`() {
        env.updateSettings { it.copy(payment = it.payment.copy(currencyCode = "JPY")) }
        compose.setContent { MiniMposApp(container) }
        waitForTag("settings")
        compose.openProducts()
        waitForTag("addFab")
        compose.onNodeWithTag("addFab").performClick()
        waitForTag("productPrice")
        compose.onNodeWithTag("productPrice").performTextInput("1.5")
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

    @Test
    fun `products list with add button`() {
        compose.setContent { MiniMposApp(container) }
        waitForTag("settings")
        compose.openProducts()
        waitForTag("addFab")
        screenshot.capture()
    }

    @Test
    fun `new admin PIN`() {
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) { SetPinScreen(onDone = {}, onCancel = {}) }
            }
        }
        waitForTag("pinDots")
        screenshot.capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `new admin PIN on the smallest screen`() {
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) { SetPinScreen(onDone = {}, onCancel = {}) }
            }
        }
        waitForTag("pinDots")
        screenshot.capture()
    }

    @Test
    fun `receipt business import review`() {
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    BusinessReviewScreen(ReceiptBusiness("Corner Cafe", "1 Example Street", "555 0100"), true) { _, _ -> }
                }
            }
        }
        waitForTag("confirmReceiptBusiness")
        screenshot.capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w1000dp-h1280dp-xhdpi")
    fun `tablet settings two panes`() {
        openSection("section_security")
        waitForTag("setPin")
        screenshot.capture()
    }

    @Test
    fun `integration support details`() {
        compose.setContent {
            MiniMposTheme {
                Column {
                    IntegrationTroubleshooting(TerminalState(), TerminalSetupActions(), onRefresh = {}, showDetails = true)
                }
            }
        }
        waitForTag("shareTapReport")
        screenshot.capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `settings with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        openSettings()
        largeTextScreenshot.capture()
    }

    @Test
    fun `transfer code controls`() {
        screenshot.setContent { MiniMposTheme { CodeControls(0, 3, playing = false, onStep = {}, onTogglePlay = {}) } }
        screenshot.capture()
    }

    @Test
    fun `transfer import review`() {
        val vm = TransferImportViewModel("AUD", container.setupImport)
        try {
            compose.awaitCondition("scanner ready") { vm.state.value is ImportUiState.Scanning }
            val transfer =
                Transfer(
                    catalogue = Catalogue("AUD", listOf(CatalogueTaxRate("GST", 10_000)), emptyList(), emptyList()),
                    connection = """{"destination":"cloud","environment":"TEST","merchantAccount":"CornerCafe"}""",
                )
            QrChunks.split(TransferCodec.encode(transfer), "GOLD").forEach { vm.onCode(it.encode()) }
            compose.awaitCondition("transfer decoded") { vm.state.value is ImportUiState.Ready }
            val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.TransferImport))
            compose.setContent {
                MiniMposTheme {
                    CompositionLocalProvider(LocalAppContainer provides container) { TransferImportScreen(navigator, vm = vm) }
                }
            }
            waitForTag("connectionPreview")
            screenshot.capture()
        } finally {
            vm.viewModelScope.cancel()
        }
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
