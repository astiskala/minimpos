package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import app.minimpos.app.FakeLinkApi
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/**
 * Two taps that both arrive before the next frame (a slow terminal, or two fingers) must not start a financial
 * operation twice or stop the app: each financial action handles the second tap as already done.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class DoubleActivationTest {
    private val links = FakeLinkApi()

    @get:Rule(order = 0)
    val env = TestEnvironment(links = links)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(currencyCode = "AUD"),
                receipt = it.receipt.copy(autoPrint = false),
                // Each payment is still running when the second tap is handled.
                simulator = it.simulator.copy(delayMillis = 500),
            )
        }
        await { container.catalog.seedDefaults(GST_RATES) }
        compose.setContent { MiniMposApp(container) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun waitForText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), 15_000)

    /** Two complete taps in one input batch, so both are handled before the screen can change. */
    private fun doubleTap(tag: String) =
        compose.onNodeWithTag(tag).performTouchInput {
            click()
            click()
        }

    private fun history() = await { container.history.items().first() }

    private fun sales() = history().filterIsInstance<HistoryItem.Sale>()

    private fun refunds() = history().filterIsInstance<HistoryItem.Refund>()

    /** There are no products in these tests, so a new sale (or pre-authorisation) opens the custom item keypad. */
    private fun ringUp(
        tile: String,
        vararg digits: Int,
    ) {
        compose.onNodeWithTag(tile).performClick()
        waitForTag("addCustom")
        digits.forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
    }

    private fun checkout(vararg digits: Int) {
        ringUp("newSale", *digits)
        compose.clickWhenEnabled("charge")
        waitForTag("pay")
    }

    private fun openInHistory(text: String) {
        compose.onNodeWithTag("home").performClick()
        waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        waitForText(text)
        compose.onNodeWithText(text, useUnmergedTree = true).performClick()
    }

    private fun preAuthorisationInHistory() {
        ringUp("preAuth", 2, 0, 0, 0, 0)
        waitForTag("pay")
        compose.onNodeWithTag("pay").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Pre-authorized"), 15_000)
        openInHistory("Pre-authorized")
        waitForTag("capture")
    }

    @Test
    fun `a double tap on Pay starts one payment`() {
        checkout(1, 2, 5, 0)
        doubleTap("pay")
        waitForText("Approved")
        assertThat(sales().single().sale.status).isEqualTo(SaleStatus.APPROVED)
    }

    @Test
    fun `double taps through the MOTO warning and Manager approval start one payment`() {
        env.updateSettings { it.copy(payment = it.payment.copy(allowMoto = true)) }
        await { container.managerPin.setPin("2468") }
        checkout(1, 2, 5, 0)
        compose.onNodeWithTag("motoSwitch").performScrollTo().performClick()
        doubleTap("pay")
        waitForTag("confirm")
        doubleTap("confirm")
        waitForTag("pin_2")
        listOf(2, 4, 6, 8).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        doubleTap("pin_OK")
        waitForText("Approved")
        assertThat(sales().single().sale.status).isEqualTo(SaleStatus.APPROVED)
    }

    @Test
    fun `a double tap on Pay remaining collects one more tender`() {
        // Adyen's test amounts ending in 139 are approved in part when partial authorisation is allowed.
        checkout(1, 3, 9)
        compose.onNodeWithTag("pay").performClick()
        waitForTag("payRemaining")
        doubleTap("payRemaining")
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Approved"), 15_000)
        val sale = sales().single()
        val record = await { container.sales.get(sale.id)!! }
        assertThat(record.tenders).hasSize(2)
        assertThat(record.sale.isPartiallyPaid).isFalse()
    }

    @Test
    fun `a double tap on Create payment link creates one link`() {
        env.useLinks()
        checkout(1, 2, 5, 0)
        doubleTap("sendLink")
        compose.waitUntilAtLeastOneExists(hasTestTag("linkStatus") and hasText("Awaiting payment"), 15_000)
        compose.awaitCondition("every link that was started is stored") { sales().none { it.sale.status == SaleStatus.PENDING } }
        assertThat(sales()).hasSize(1)
        assertThat(links.created).hasSize(1)
    }

    @Test
    fun `a double tap on the refund confirmation starts one refund`() {
        checkout(4, 0, 0)
        compose.onNodeWithTag("pay").performClick()
        waitForText("Approved")
        openInHistory("$4.00")
        waitForTag("detailRefund")
        compose.onNodeWithTag("detailRefund").performClick()
        waitForTag("startRefund")
        compose.onNodeWithTag("option_FULL").performClick()
        compose.onNodeWithTag("startRefund").performClick()
        waitForTag("confirm")
        doubleTap("confirm")
        waitForText("Refund requested")
        assertThat(refunds().single().refund.full).isTrue()
    }

    @Test
    fun `a double tap on the cancellation confirmation starts one cancellation`() {
        preAuthorisationInHistory()
        compose.onNodeWithTag("cancelPreAuth").performScrollTo().performClick()
        waitForTag("confirm")
        doubleTap("confirm")
        waitForText("Cancellation requested")
        assertThat(refunds().single().refund.cancellation).isTrue()
    }
}
