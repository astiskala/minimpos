package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.history.RefundablePayment
import app.minimpos.app.items
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.payment.PaymentContext
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Charge saved card from Home: reference, card, its own cart, then the charge, all against the simulator. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class SavedCardFlowTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private val container = env.container

    private fun useSimulator(source: ShopperReferenceSource) =
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD", shopperReferenceSource = source)) }

    /** A simulated payment earlier saved card TOKEN1 under CUST-001, as the simulator remembers saved cards. */
    private fun seedSavedCard() =
        await {
            container.database.saleDao().insert(
                SaleEntity(
                    id = "saved",
                    createdAt = 1,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 1_000,
                    taxMinor = 0,
                    totalMinor = 1_000,
                    status = SaleStatus.APPROVED,
                    merchantReference = "earlier",
                    shopperReference = "CUST-001",
                    storedPaymentMethodId = "TOKEN1",
                    paymentBrand = "visa",
                    maskedPan = "411111******1111",
                    context = PaymentContext("SIMULATOR", "SIM", "POS", "", null, simulated = true),
                ),
            )
        }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    @Test
    fun `finds the shopper's saved card, rings up the sale apart from New sale and charges it`() {
        useSimulator(ShopperReferenceSource.CUSTOMER_REFERENCE)
        await { container.catalog.seedDefaults(GST_RATES) }
        seedSavedCard()
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("savedCard").performClick()
        waitForTag("savedCardReference")
        compose.onNodeWithTag("savedCardReference").performTextInput("CUST-001")
        compose.clickWhenEnabled("findCards")
        waitForTag("savedCard_TOKEN1")
        compose.onNodeWithText("VISA •••• 1111").assertTextContains("VISA •••• 1111")
        compose.onNodeWithTag("chargeToken_TOKEN1").performClick()
        waitForTag("addCustom")
        listOf(1, 2, 5, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        waitForTag("charge")
        compose.clickWhenEnabled("charge")
        waitForTag("chargeSavedCard")
        compose.onNodeWithTag("chargeSavedCard").assertTextContains("Charge $12.50")
        compose.onNodeWithTag("chargeSavedCard").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Approved"), 15_000)

        val sale =
            await {
                container.history
                    .items()
                    .first { items -> items.any { it is HistoryItem.Sale && it.sale.savedCard } }
                    .filterIsInstance<HistoryItem.Sale>()
                    .single { it.sale.savedCard }
                    .sale
            }
        assertThat(sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(sale.chargedCardId).isEqualTo("TOKEN1")
        assertThat(sale.shopperReference).isEqualTo("CUST-001")
        assertThat(sale.totalMinor).isEqualTo(1_250)
        // Its receipt's refund QR code carries the Checkout transaction ID, so only this device's history refunds it.
        val qr = checkNotNull(RefundablePayment.qrCode(checkNotNull(await { container.sales.get(sale.id) })))
        assertThat(RefundablePayment.isCheckoutTransaction(checkNotNull(RefundQrPayload.decode(qr)).transactionId)).isTrue()
        // The saved-card cart is cleared once charged; New sale's cart was never touched.
        assertThat(container.savedCardSession.cart.value.lines).isEmpty()
    }

    @Test
    fun `without a shopper reference source the lookup says what to set up`() {
        useSimulator(ShopperReferenceSource.NONE)
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("savedCard").performClick()
        waitForTag("findCards")
        compose.onNodeWithTag("findCards").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("blockedReason").assertTextContains(env.context.getString(R.string.setup_shopper_reference))
    }
}
