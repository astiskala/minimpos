package app.minimpos.app.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.items
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.started
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class PinAccessFlowTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    private lateinit var back: OnBackPressedDispatcher

    @Before
    fun setUp() {
        env.useSimulator { it.copy(receipt = it.receipt.copy(autoPrint = false)) }
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MiniMposApp(container)
        }
        waitForTag("settings")
    }

    /** The system Back gesture or key, as opposed to the app bar's arrow. */
    private fun pressSystemBack() {
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun `system Back on the new PIN pad cancels it like the arrow and stays in Security`() {
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_security")
        compose.onNodeWithTag("section_security").performScrollTo().performClick()
        waitForTag("setPin")
        compose.onNodeWithTag("setPin").performClick()
        waitForTag("pin_1")
        pressSystemBack()
        waitForTag("setPin")
        compose.onNodeWithTag("pin_1").assertDoesNotExist()
    }

    @Test
    fun `system Back on Manager approval cancels the action and stays on the sale`() {
        val start =
            PaymentStart(
                Cart().addCustom("Item", 200, AppliedTax("No tax", 0), "a").totals(TaxMode.INCLUSIVE),
                CurrencySpec("AUD", 2),
                "held-sale",
                null,
                null,
                null,
                kind = SaleKind.PRE_AUTHORISATION,
            )
        val saleId = container.payments.started(start)
        await { container.payments.state.first { it == TransactionState.Finished(saleId) } }
        await {
            container.pinManager.setPin("1357")
            container.managerPin.setPin("2468")
        }
        compose.onNodeWithTag("history").performClick()
        waitForText("held-sale")
        compose.onNodeWithText("held-sale").performClick()
        waitForTag("cancelPreAuth")
        compose.onNodeWithTag("cancelPreAuth").performScrollTo().performClick()
        waitForTag("pin_2")
        pressSystemBack()
        waitForTag("cancelPreAuth")
        compose.onNodeWithTag("pin_2").assertDoesNotExist()
        compose.onNodeWithTag("confirm").assertDoesNotExist()
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun waitForText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), 15_000)

    @Test
    fun `manager approval remains valid while an authorized refund starts processing`() {
        val start =
            PaymentStart(
                Cart().addCustom("Item", 200, AppliedTax("No tax", 0), "a").totals(TaxMode.INCLUSIVE),
                CurrencySpec("AUD", 2),
                "manager-refund",
                null,
                null,
                null,
            )
        val saleId = container.payments.started(start)
        await { container.payments.state.first { it == TransactionState.Finished(saleId) } }
        await {
            container.pinManager.setPin("1357")
            container.managerPin.setPin("2468")
        }
        compose.onNodeWithTag("history").performClick()
        waitForText("manager-refund")
        compose.onNodeWithText("manager-refund").performClick()
        waitForTag("detailRefund")
        compose.onNodeWithTag("detailRefund").performClick()
        waitForTag("pin_2")
        listOf(2, 4, 6, 8).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        waitForTag("startRefund")
        compose.onNodeWithTag("startRefund").performClick()
        waitForTag("confirm")
        compose.onNodeWithTag("confirm").performClick()
        waitForText("Refund requested")
        val refund = await { container.history.items().first() }.filterIsInstance<HistoryItem.Refund>().single().refund
        assertThat(refund.status).isEqualTo(RefundStatus.REQUESTED)
    }

    @Test
    fun `an idle settings screen locks without navigating`() {
        await { container.pinManager.setPin("1357") }
        container.sessionLock.unlock()
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_security")
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        compose.mainClock.advanceTimeBy(1_100)
        waitForTag("pin_1")
    }

    @Test
    fun `refund access uses the optional Manager PIN rather than the admin PIN`() {
        await {
            container.pinManager.setPin("1357")
            container.managerPin.setPin("2468")
        }
        compose.onNodeWithText("Refund").performClick()
        waitForText("Scan receipt")
        compose.onNodeWithText("Scan receipt").performClick()
        waitForTag("pin_1")
        compose.onNodeWithText("Enter the Manager PIN").assertIsDisplayed()
        listOf(1, 3, 5, 7).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        waitForText("Wrong PIN (attempts left: 4)")
        listOf(2, 4, 6, 8).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        waitForText("Scan receipt")
    }
}
