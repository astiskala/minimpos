package io.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.minimpos.app.FakeDevice
import io.minimpos.app.FakeTerminal
import io.minimpos.app.MiniMposApp
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.awaitCondition
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.PrinterMode
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app on an Adyen AMS1: a 4" 480×800 px screen, of which the status bar and Adyen's navigation bar leave about
 * 320×460 dp. The main flows must work without scrolling to their primary action; some are also tried on the P630's
 * 320×456 dp and the S1F2's 360×568 dp.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SmallScreenTest {
    private val terminal = FakeTerminal(passphrase = "correct horse battery staple")

    @get:Rule(order = 0)
    val env = TestEnvironment(FakeDevice(detectedPoiId = "AMS1-000168223606144"), terminal)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.updateSettings { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        await { container.catalog.seedDefaults("GST", "GST-free") }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun waitForText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text, substring = true), 15_000)

    private fun configureKey() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "mini-key")) }
        await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
    }

    @Test
    fun `home guides the terminal setup, which only asks for the shared key`() {
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("terminalSetup").assertIsDisplayed().performClick()
        waitForTag("keyIdentifier")
        // On the terminal itself its ID, address and environment are known, so they are not asked for.
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("poiId").assertDoesNotExist()
        compose.onNodeWithText("Environment").assertDoesNotExist()
        compose.onNodeWithTag("connectionStatus").assertTextContains("Checking", substring = true)

        compose.onNodeWithTag("keyIdentifier").performTextInput("mini-key")
        compose.awaitCondition("Saving the key identifier") { container.settingsState.value.terminal.keyIdentifier == "mini-key" }
        compose.onNodeWithTag("passphrase").performTextInput("wrong passphrase")
        compose
            .onNodeWithTag("testConnection")
            .performScrollTo()
            .assertTextContains("Save and test")
            .performClick()
        waitForText("Could not connect")
        compose.onNodeWithTag("connectionResult").assertTextContains("shared key", substring = true)
        compose.onNodeWithTag("connectionResultOk").performClick()
        compose.onNodeWithTag("connectionStatus").assertTextContains("Not connected", substring = true)

        // The keyboard's Done key saves and tests too, and the result shows however far the screen is scrolled.
        compose.onNodeWithTag("passphrase").performTextInput("correct horse battery staple")
        compose.onNodeWithTag("passphrase").performImeAction()
        waitForText("Connected (OK")
        compose.onNodeWithTag("connectionResultOk").performClick()
        compose.onNodeWithTag("connectionStatus").assertTextContains("AMS1-000168223606144", substring = true)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery staple")
        compose.onNodeWithText("Saved", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("testConnection").assertTextContains("Test connection")
        assertThat(terminal.hosts.distinct()).containsExactly("localhost")

        compose.onNodeWithTag("back").performClick()
        waitForTag("newSale")
        compose.onNodeWithTag("terminalSetup").assertDoesNotExist()
    }

    @Test
    fun `a sale fits the screen from home to the result`() = saleFitsTheScreen()

    /** The P630: 320×480 px at mdpi, less the status bar (it has keys instead of a navigation bar). */
    @Test
    @Config(qualifiers = "en-rAU-w320dp-h456dp-mdpi")
    fun `a sale fits the P630 screen`() = saleFitsTheScreen()

    /** The S1F2: 720×1280 px at xhdpi, less both bars; the medium sizes still need the compact layouts. */
    @Test
    @Config(qualifiers = "en-rAU-w360dp-h568dp-xhdpi")
    fun `a sale fits the S1F2 screen`() = saleFitsTheScreen()

    @Test
    fun `the search field only takes room once asked for`() {
        configureKey()
        await {
            val rate =
                container.catalog.taxRates
                    .first()
                    .first()
            (1..9).forEach {
                container.catalog.saveProduct(
                    ProductEntity(name = "Product $it", priceMinor = 100L * it, taxRateId = rate.id),
                )
            }
        }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("newSale").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Product 1"), 15_000)
        compose.onNodeWithTag("search").assertDoesNotExist()

        compose.onNodeWithTag("openSearch").performClick()
        compose.onNodeWithTag("search").assertIsDisplayed().performTextInput("Product 3")
        compose.onNodeWithText("Product 1").assertDoesNotExist()
        compose.onNode(hasText("Product 3") and hasTestTag("search").not()).assertIsDisplayed()

        // Closing the search clears it too.
        compose.onNodeWithTag("openSearch").performClick()
        compose.onNodeWithTag("search").assertDoesNotExist()
        compose.onNodeWithText("Product 1").assertIsDisplayed()
    }

    private fun saleFitsTheScreen() {
        configureKey()
        env.updateSettings { it.copy(terminal = it.terminal.copy(environment = TerminalEnvironment.TEST)) }
        await { container.terminalStatus.state.first { it.environment == TerminalEnvironment.TEST } }
        compose.setContent { MiniMposApp(container) }
        listOf("newSale", "refund", "history", "products", "settings").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        compose.onNodeWithTag("terminalSetup").assertDoesNotExist()
        // A test terminal already says TEST in its own status bar, so the app adds no banner.
        compose.onNodeWithTag("modeBanner").assertDoesNotExist()

        compose.onNodeWithTag("newSale").performClick()
        waitForTag("addCustom")
        // The custom item keypad fits without scrolling.
        compose.onNodeWithTag("addCustom").assertIsDisplayed()
        compose.onNodeWithTag("key_00").assertIsDisplayed()
        listOf(1, 2, 5, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        compose.onNodeWithTag("charge").assertIsDisplayed().performClick()

        // Pay stays in reach below the checkout form.
        waitForTag("pay")
        compose
            .onNodeWithTag("pay")
            .assertIsDisplayed()
            .assertTextContains("Pay $12.50")
            .performClick()
        waitForTag("newSaleAfter")
        compose.onNodeWithTag("newSaleAfter").assertIsDisplayed()
        compose.onNodeWithTag("home").assertIsDisplayed()
        compose.onNodeWithTag("resultStatus").assertTextContains("Approved")

        val sale = await { container.history.items().first { it.isNotEmpty() } }.single()
        val record = await { container.sales.get(sale.id)!! }
        assertThat(record.sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(record.sale.poiId).isEqualTo("AMS1-000168223606144")
    }

    @Test
    fun `a pre-authorisation fits the screen from home to its cancellation`() = preAuthFitsTheScreen()

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h456dp-mdpi")
    fun `a pre-authorisation fits the P630 screen`() = preAuthFitsTheScreen()

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h568dp-xhdpi")
    fun `a pre-authorisation fits the S1F2 screen`() = preAuthFitsTheScreen()

    private fun preAuthFitsTheScreen() {
        configureKey()
        await {
            val rate =
                container.catalog.taxRates
                    .first()
                    .first()
            container.catalog.saveProduct(ProductEntity(name = "Flat white", priceMinor = 450, taxRateId = rate.id))
            container.catalog.saveProduct(
                ProductEntity(name = "Catering deposit", priceMinor = 20_000, taxRateId = rate.id, kind = SaleKind.PRE_AUTHORISATION),
            )
        }
        compose.setContent { MiniMposApp(container) }
        // The big tile is split in two, and every tile still fits.
        waitForTag("preAuth")
        listOf("newSale", "preAuth", "refund", "history", "products", "settings").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }

        compose.onNodeWithTag("preAuth").performClick()
        waitForText("Catering deposit")
        // Tapping the product goes straight to checkout.
        compose.onNodeWithText("Catering deposit").performClick()
        waitForTag("pay")
        compose
            .onNodeWithTag("pay")
            .assertIsDisplayed()
            .assertTextContains("Pre-authorize $200.00")
            .performClick()
        waitForTag("newSaleAfter")
        compose.onNodeWithTag("newSaleAfter").assertIsDisplayed()
        compose.onNodeWithTag("home").assertIsDisplayed()
        compose.onNodeWithTag("resultStatus").assertTextContains("Pre-authorized")

        val preAuth = await { container.history.items().first { it.isNotEmpty() } }.single()
        compose.onNodeWithTag("home").performClick()
        waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        waitForText("Pre-authorized")
        compose.onNodeWithText("Pre-authorized", useUnmergedTree = true).performClick()
        waitForTag("cancelPreAuth")
        compose.onNodeWithTag("cancelPreAuth").assertIsDisplayed()
        assertThat(await { container.sales.get(preAuth.id)!! }.sale.kind).isEqualTo(SaleKind.PRE_AUTHORISATION)
    }

    @Test
    fun `a tip on the receipt fits the screen, and is recorded for the Customer Area without an API`() = tipFitsTheScreen()

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h456dp-mdpi")
    fun `a tip on the receipt fits the P630 screen`() = tipFitsTheScreen()

    private fun tipFitsTheScreen() {
        configureKey()
        // The AMS1 has no printer of its own; tipping on the receipt needs one.
        env.updateSettings { it.copy(receipt = it.receipt.copy(printerMode = PrinterMode.ON)) }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("newSale").performClick()
        waitForTag("addCustom")
        listOf(4, 0, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        compose.onNodeWithTag("charge").performClick()
        waitForTag("pay")
        compose.onNodeWithTag("tipOnReceipt").performScrollTo().performClick()
        compose.onNodeWithTag("pay").assertIsDisplayed().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Awaiting tip"), 15_000)
        compose.onNodeWithTag("newSaleAfter").assertIsDisplayed()

        compose.onNodeWithTag("enterTip").performScrollTo().performClick()
        waitForTag("addTip")
        // The keypad and both actions fit without scrolling.
        listOf("addTip", "noTip", "key_00", "tipInput_TOTAL").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        listOf(6, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addTip").performClick()
        compose.onNodeWithText("Capture $46.00 in your Customer Area afterwards", substring = true).assertExists()
        compose.onNodeWithTag("confirm").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Capture in Customer Area"), 15_000)
        compose.onNodeWithTag("newSaleAfter").assertIsDisplayed()
        val sale = await { container.history.items().first { it.isNotEmpty() } }.single()
        assertThat(await { container.sales.get(sale.id)!! }.sale.captureStatus).isEqualTo(CaptureStatus.MANUAL)
    }

    @Test
    fun `the Checkout API is set up under Terminal settings, and says what is still missing`() {
        configureKey()
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("merchantAccount")
        compose.onNodeWithTag("merchantAccount").performScrollTo().performTextInput("HarbourCoffeeCOM")
        compose.awaitCondition(
            "Saving the merchant account",
        ) { container.settingsState.value.terminal.merchantAccount == "HarbourCoffeeCOM" }
        compose.onNodeWithTag("apiKey").performScrollTo().performTextInput("AQE-secret")
        compose
            .onNodeWithTag("testApi")
            .performScrollTo()
            .assertTextContains("Save and test API key")
            .performClick()
        // The terminal's environment is not known until a connection has been made over TLS.
        compose.waitUntilAtLeastOneExists(
            hasTestTag("apiResult") and hasText("Test the connection to the terminal first", substring = true),
            15_000,
        )
        assertThat(await { container.secrets.get(Secret.CHECKOUT_API_KEY) }).isEqualTo("AQE-secret")
        compose.onNodeWithTag("livePrefix").assertExists()
        compose.onNodeWithTag("forgetApiKey").performScrollTo().performClick()
        compose.awaitCondition("Removing the API key") { await { container.secrets.get(Secret.CHECKOUT_API_KEY) } == null }
    }

    @Test
    fun `the PIN pad fits the screen`() = pinPadFitsTheScreen()

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h456dp-mdpi")
    fun `the PIN pad fits the P630 screen`() = pinPadFitsTheScreen()

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h568dp-xhdpi")
    fun `the PIN pad fits the S1F2 screen`() = pinPadFitsTheScreen()

    private fun pinPadFitsTheScreen() {
        configureKey()
        await { container.pinManager.setPin("1357") }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("pin_OK")
        listOf("pin_1", "pin_0", "pin_<", "pin_OK", "pinMessage").forEach { compose.onNodeWithTag(it).assertIsDisplayed() }
        listOf(1, 3, 5, 7).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").assertTextContains("Checking", substring = true)
    }
}
