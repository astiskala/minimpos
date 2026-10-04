package io.github.astiskala.minimpos.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeLinkApi
import io.github.astiskala.minimpos.app.GST_RATES
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.terminal.checkout.PaymentLinkStatus
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Payment links on a phone, from checkout to the paid sale in history, and their setting. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class PaymentLinkFlowTest {
    private val links = FakeLinkApi()

    @get:Rule(order = 0)
    val env = TestEnvironment(links = links)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        await { container.catalog.seedDefaults(GST_RATES) }
        compose.setContent { MiniMposApp(container) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun waitForText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), 15_000)

    /** There are no products in these tests, so a new sale opens the custom item keypad straight away. */
    private fun ringUpCustomAmount(vararg digits: Int) {
        compose.onNodeWithTag("newSale").performClick()
        waitForTag("addCustom")
        digits.forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
    }

    @Test
    fun `sends a payment link instead of the terminal, shares it, and sees it paid`() {
        env.useLinks()
        ringUpCustomAmount(1, 2, 5, 0)
        compose.onNodeWithTag("charge").performClick()
        waitForTag("sendLink")
        compose.onNodeWithTag("sendLink").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("linkStatus") and hasText("Awaiting payment"), 15_000)
        compose.onNodeWithTag("linkUrl").assertTextEquals(FakeLinkApi.URL)
        compose.onNodeWithTag("linkQr").assertExists()
        compose.onNodeWithTag("shareLink").performScrollTo().performClick()
        val application = ApplicationProvider.getApplicationContext<Application>()
        var chooser: Intent? = null
        compose.awaitCondition("the share sheet opens") {
            chooser = shadowOf(application).nextStartedActivity
            chooser != null
        }
        val opened = checkNotNull(chooser)
        assertThat(opened.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(IntentCompat.getParcelableExtra(opened, Intent.EXTRA_INTENT, Intent::class.java)!!.getStringExtra(Intent.EXTRA_TEXT))
            .contains(FakeLinkApi.URL)
        assertThat(
            container
                .session(SaleKind.SALE)
                .cart.value.lines,
        ).isEmpty()

        links.status = PaymentLinkStatus.COMPLETED
        compose.onNodeWithTag("checkLink").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("linkStatus") and hasText("Approved"), 15_000)
        compose.onNodeWithText("Paid online with the payment link", substring = true).assertExists()
        compose.onNodeWithTag("shareReceipt").performScrollTo().assertIsDisplayed()

        // History shows it as a sale, with the link it was paid through.
        compose.onNodeWithTag("home").performClick()
        waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        waitForText("Approved")
        compose.onNodeWithText("Approved", useUnmergedTree = true).performClick()
        waitForText(FakeLinkApi.URL)
        compose.onNodeWithTag("showLink").assertDoesNotExist()
        compose.onNodeWithTag("detailRefund").assertDoesNotExist()
    }

    @Test
    fun `payment link settings offer expiry without a redundant API setup warning`() {
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_payments")
        compose.onNodeWithTag("section_payments").performClick()
        compose.onNodeWithTag("paymentLinks").assertDoesNotExist()
        // The screen recomposes with the stored settings a moment after they change.
        waitForTag("linkExpiry")
        compose.onNodeWithTag("linkExpiry").performScrollTo().assertIsDisplayed()
        // Payments go to the simulator, which has no payment links.
        compose.onNodeWithTag("linksNeedApi").assertDoesNotExist()
        compose.onNodeWithText("Payment links are not available with the simulator.", substring = true).assertExists()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("back").performClick()
        ringUpCustomAmount(5, 0, 0)
        compose.onNodeWithTag("charge").performClick()
        waitForTag("pay")
        compose.onNodeWithTag("sendLink").assertDoesNotExist()
    }
}
