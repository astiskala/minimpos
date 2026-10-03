package io.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import io.minimpos.app.MiniMposApp
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.db.RefundStatus
import io.minimpos.app.data.repo.HistoryItem
import io.minimpos.app.payment.PaymentStart
import io.minimpos.app.payment.TransactionState
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.Cart
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.tax.TaxMode
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

    @Before
    fun setUp() {
        env.useSimulator { it.copy(receipt = it.receipt.copy(autoPrint = false)) }
        compose.setContent { MiniMposApp(container) }
        waitForTag("settings")
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
        val saleId = container.payments.start(start)
        await { container.payments.state.first { it == TransactionState.Finished(saleId) } }
        await { container.managerPin.setPin("2468") }
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
