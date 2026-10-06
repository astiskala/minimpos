package io.github.astiskala.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.GST_RATES
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.settings.EmailCapture
import io.github.astiskala.minimpos.app.data.settings.ReceiptTipping
import io.github.astiskala.minimpos.app.data.settings.ShopperReferenceSource
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

/** The shopper reference and saving cards: their settings and what checkout asks for and sends. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class ShopperReferenceFlowTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(payment = it.payment.copy(currencyCode = "AUD", shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE))
        }
        await { container.catalog.seedDefaults(GST_RATES) }
        compose.setContent { MiniMposApp(container) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun waitForText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), 15_000)

    private fun awaitSetting(
        description: String,
        condition: () -> Boolean,
    ) = compose.awaitCondition("Saving $description", condition)

    /** There are no products in these tests, so a new sale opens the custom item keypad straight away. */
    private fun ringUpCustomAmount(vararg digits: Int) {
        compose.onNodeWithTag("newSale").performClick()
        waitForTag("addCustom")
        digits.forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
    }

    private fun openPaymentSettings() {
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_payments")
        compose.onNodeWithTag("section_payments").performClick()
    }

    private fun useEmailAsShopperReference() =
        env.updateSettings {
            it.copy(
                payment = it.payment.copy(shopperReferenceSource = ShopperReferenceSource.EMAIL, emailCapture = EmailCapture.AFTER_PAYMENT),
            )
        }

    @Test
    fun `checkout option labels toggle their switches`() {
        env.updateSettings { it.copy(payment = it.payment.copy(receiptTipping = ReceiptTipping.DEFAULT_OFF)) }
        ringUpCustomAmount(5, 0, 0)
        compose.onNodeWithTag("charge").performClick()
        waitForTag("pay")
        compose.onNodeWithTag("customerReference").performTextInput("CUST-1042")
        compose.onNodeWithText("Save card for future payments").performScrollTo().performClick()
        compose.onNodeWithTag("tokenize").assertIsOn()
        compose.onNodeWithText("Tip on the receipt").performScrollTo().performClick()
        compose.onNodeWithTag("tipOnReceipt").assertIsOn()
    }

    @Test
    fun `with the email as shopper reference checkout asks for the email and no customer reference`() {
        useEmailAsShopperReference()
        ringUpCustomAmount(5, 0, 0)
        compose.onNodeWithTag("charge").performClick()
        waitForTag("pay")
        compose.onNodeWithTag("email").assertExists()
        compose.onNodeWithTag("customerReference").assertDoesNotExist()
    }

    @Test
    fun `with the email as shopper reference it is always asked for before payment`() {
        useEmailAsShopperReference()
        openPaymentSettings()
        waitForTag("emailCapture")
        // There is no separate customer reference to ask for.
        compose.onNodeWithTag("referenceSource").assertTextContains("No separate customer reference", substring = true)
        compose.onNodeWithTag("emailCapture").performScrollTo().assertTextContains("Before payment", substring = true)
        compose.onNodeWithTag("emailCapture").assertTextContains("the email is the shopper reference", substring = true)
        compose.onNodeWithTag("emailCapture").performClick()
        waitForTag("emailCapture_BEFORE_PAYMENT")
        compose.onNodeWithTag("emailCapture_OFF").assertDoesNotExist()
        compose.onNodeWithTag("emailCapture_AFTER_PAYMENT").assertDoesNotExist()
        compose.onNodeWithTag("emailCapture_BEFORE_PAYMENT").performClick()
        awaitSetting("Before payment") { container.settingsState.value.payment.emailCapture == EmailCapture.BEFORE_PAYMENT }

        // Back to the customer reference as shopper reference: checkout asks for it again.
        compose.onNodeWithTag("referenceSource").performScrollTo().performClick()
        waitForTag("referenceSource_CUSTOMER_REFERENCE")
        compose.onNodeWithTag("referenceSource_CUSTOMER_REFERENCE").performClick()
        awaitSetting("Customer reference") {
            container.settingsState.value.payment.shopperReferenceSource == ShopperReferenceSource.CUSTOMER_REFERENCE
        }
        compose.onNodeWithTag("referenceSource").assertTextContains("Checkout asks for a customer reference", substring = true)
        compose.onNodeWithText("No separate customer reference", substring = true).assertDoesNotExist()
    }

    @Test
    fun `switching off saving cards keeps the shopper reference but hides the saved card settings`() {
        openPaymentSettings()
        waitForTag("offerCardSaving")
        compose.onNodeWithTag("tokenizeDefault").assertExists()
        compose.onNodeWithTag("offerCardSaving").performScrollTo().performClick()
        awaitSetting("Offer to save cards") { !container.settingsState.value.payment.offerCardSaving }
        compose.onNodeWithTag("tokenizeDefault").assertDoesNotExist()
        compose.onNodeWithTag("preAuthTokenizeDefault").assertDoesNotExist()
        compose.onNodeWithTag("referenceSource").assertTextContains("as the shopper reference", substring = true)

        // Without a shopper reference there is nothing to save cards under, so the switch goes too.
        compose.onNodeWithTag("referenceSource").performScrollTo().performClick()
        waitForTag("referenceSource_NONE")
        compose.onNodeWithTag("referenceSource_NONE").performClick()
        awaitSetting("None") { container.settingsState.value.payment.shopperReferenceSource == ShopperReferenceSource.NONE }
        compose.onNodeWithTag("offerCardSaving").assertDoesNotExist()
        compose.onNodeWithTag("referenceSource").assertTextContains("cards cannot be saved", substring = true)
    }

    @Test
    fun `with saving cards not offered checkout still sends the customer reference as shopper reference`() {
        env.updateSettings { it.copy(payment = it.payment.copy(offerCardSaving = false)) }
        ringUpCustomAmount(5, 0, 0)
        compose.onNodeWithTag("charge").performClick()
        waitForTag("pay")
        compose.onNodeWithTag("customerReference").performTextInput("CUST-7")
        compose.onNodeWithTag("tokenize").assertDoesNotExist()
        compose.onNodeWithTag("pay").performClick()
        waitForText("Approved")
        val sale = await { container.history.items().first { it.isNotEmpty() } }.single()
        val record = await { container.sales.get(sale.id)!! }.sale
        assertThat(record.shopperReference).isEqualTo("CUST-7")
        assertThat(record.tokenizationRequested).isFalse()
        assertThat(record.storedPaymentMethodId).isNull()
    }
}
