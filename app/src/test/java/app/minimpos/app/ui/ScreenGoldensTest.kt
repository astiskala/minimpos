package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.ScreenshotRule
import app.minimpos.app.ScreenshotTolerance
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.sale.SaleResultScreen
import app.minimpos.app.items
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/**
 * Golden images of whole screens in the simulator with synthetic data, on the AMS1 (`w320dp-h460dp-hdpi`, the smallest
 * supported screen) and a regular phone; see `docs/ui/SCREENSHOT_TESTING.md`. Screens are compared with a small tolerance.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ScreenGoldensTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule(order = 2)
    val screenshot = ScreenshotRule(compose, ScreenshotTolerance.SCREEN)

    private val container = env.container
    private var resultToShow by mutableStateOf<Pair<Route.SaleResult, Navigator>?>(null)

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(currencyCode = "AUD", shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE),
                receipt = it.receipt.copy(businessName = "Corner Cafe", autoPrint = false),
            )
        }
        await { container.catalog.seedDefaults(GST_RATES) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    /** The custom item keypad opens straight away, since there are no products. */
    private fun enterAmount() {
        compose.onNodeWithTag("newSale").performClick()
        waitForTag("addCustom")
        listOf(1, 2, 5, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
    }

    /**
     * Pays 12.50 through the simulator in the app, as a shopper's payment would go, and waits for the result screen.
     * Then it replaces what differs from run to run (the reference, time, card and Adyen references) with fixed values
     * in the stored sale, which the open screen follows. A [lost] reply leaves a sale whose outcome the app does not
     * know, so the answer's facts are cleared too.
     */
    private fun payWith(
        outcome: SimulatedOutcome,
        lost: Boolean = false,
    ) {
        env.updateSettings { it.copy(simulator = it.simulator.copy(outcome = outcome)) }
        enterAmount()
        compose.onNodeWithTag("addCustom").performClick()
        compose.clickWhenEnabled("charge")
        waitForTag("pay")
        compose.onNodeWithTag("pay").performClick()
        waitForTag("resultStatus")
        val stored = await { container.history.items().first { it.isNotEmpty() } }.single()
        val sale = await { container.sales.get(stored.id)!! }.sale
        val answered = outcome == SimulatedOutcome.APPROVE && !lost
        await {
            container.database.saleDao().update(
                sale.copy(
                    createdAt = SALE_TIME,
                    merchantReference = "261030-120000-ABCDE",
                    status = if (lost) SaleStatus.UNKNOWN else sale.status,
                    pspReference = "PSP0000000000001".takeIf { answered },
                    paymentBrand = "mc".takeIf { answered },
                    maskedPan = "555555 **** 4444".takeIf { answered },
                    authCode = "123456".takeIf { answered },
                    paymentMethodVariant = null,
                    poiTransactionId = null,
                    poiTimestamp = null,
                    customerReceiptJson = null,
                    cashierReceiptJson = null,
                ),
            )
        }
        val result = Route.SaleResult(sale.id)
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, result))
        resultToShow = result to navigator
        compose.waitForIdle()
        waitForTag("resultStatus")
    }

    /** The app, until [payWith] has fixed the sale's facts; then that sale's result screen, which reads them. */
    private fun showApp() =
        compose.setContent {
            val shown = resultToShow
            if (shown == null) {
                MiniMposApp(container)
            } else {
                MiniMposTheme {
                    CompositionLocalProvider(LocalAppContainer provides container) { SaleResultScreen(shown.first.saleId, shown.second) }
                }
            }
        }

    @Test
    fun home() {
        compose.setContent { MiniMposApp(container) }
        waitForTag("newSale")
        screenshot.capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
    fun `home on a regular phone`() {
        compose.setContent { MiniMposApp(container) }
        waitForTag("newSale")
        screenshot.capture()
    }

    @Test
    fun `home when setup is required`() {
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST, host = "192.0.2.10"))
        }
        container.start()
        compose.setContent { MiniMposApp(container) }
        waitForTag("terminalSetup")
        screenshot.capture()
    }

    @Test
    fun sale() {
        compose.setContent { MiniMposApp(container) }
        enterAmount()
        screenshot.capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
    fun `sale on a regular phone`() {
        compose.setContent { MiniMposApp(container) }
        enterAmount()
        screenshot.capture()
    }

    @Test
    fun `result approved`() {
        showApp()
        payWith(SimulatedOutcome.APPROVE)
        screenshot.capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
    fun `result approved on a regular phone`() {
        showApp()
        payWith(SimulatedOutcome.APPROVE)
        screenshot.capture()
    }

    @Test
    fun `result declined`() {
        showApp()
        payWith(SimulatedOutcome.DECLINE)
        screenshot.capture()
    }

    @Test
    fun `result unknown`() {
        showApp()
        payWith(SimulatedOutcome.APPROVE, lost = true)
        screenshot.capture()
    }

    private companion object {
        /** 30 Oct 2026, 12:00 UTC. */
        const val SALE_TIME = 1_793_361_600_000L
    }
}
