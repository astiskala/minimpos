package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
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
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.sale.SaleResultScreen
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.transport.TerminalEnvironment
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
     * Shows the result screen of a stored sale with fixed facts. A payment made through the simulator would give the
     * sale a new reference, time and card each run, which no golden could hold.
     */
    private fun showResult(
        status: SaleStatus,
        refusalReason: String? = null,
    ) {
        val result = Route.SaleResult("sale")
        val approved = status == SaleStatus.APPROVED
        await {
            container.database.saleDao().insert(
                SaleEntity(
                    id = "sale",
                    createdAt = SALE_TIME,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 1136,
                    taxMinor = 114,
                    totalMinor = 1250,
                    status = status,
                    merchantReference = "261030-120000-ABCDE",
                    context = PaymentContext("SIMULATOR", "SIM", "POS", "", null, simulated = true),
                    pspReference = "PSP0000000000001".takeIf { approved },
                    paymentBrand = "mc".takeIf { approved },
                    maskedPan = "555555 **** 4444".takeIf { approved },
                    refusalReason = refusalReason,
                ),
            )
        }
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, result))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    SaleResultScreen(result.saleId, navigator)
                }
            }
        }
        waitForTag("resultStatus")
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
        showResult(SaleStatus.APPROVED)
        screenshot.capture()
    }

    @Test
    @Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
    fun `result approved on a regular phone`() {
        showResult(SaleStatus.APPROVED)
        screenshot.capture()
    }

    @Test
    fun `result declined`() {
        showResult(SaleStatus.DECLINED, refusalReason = "Not enough balance")
        screenshot.capture()
    }

    @Test
    fun `result unknown`() {
        showResult(SaleStatus.UNKNOWN)
        screenshot.capture()
    }

    private companion object {
        /** 30 Oct 2026, 12:00 UTC. */
        const val SALE_TIME = 1_793_361_600_000L
    }
}
