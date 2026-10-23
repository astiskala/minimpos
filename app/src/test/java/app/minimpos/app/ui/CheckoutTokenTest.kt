package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.items
import app.minimpos.core.payment.PaymentContext
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A New sale's checkout offers Charge token once the entered shopper reference has tokens saved. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class CheckoutTokenTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(payment = it.payment.copy(currencyCode = "AUD", shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE))
        }
        await { container.catalog.seedDefaults(GST_RATES) }
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
        compose.setContent { MiniMposApp(container) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun checkoutWith(reference: String) {
        compose.onNodeWithTag("newSale").performClick()
        waitForTag("addCustom")
        listOf(1, 2, 5, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        waitForTag("charge")
        compose.clickWhenEnabled("charge")
        waitForTag("customerReference")
        compose.onNodeWithTag("customerReference").performTextInput(reference)
    }

    @Test
    fun `a shopper with a saved token can be charged from checkout and the New sale cart is cleared`() {
        checkoutWith("CUST-001")
        waitForTag("chargeToken")
        compose.onNodeWithTag("chargeToken").performClick()
        waitForTag("token_TOKEN1")
        compose.onNodeWithTag("token_TOKEN1").performClick()
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
        assertThat(sale.totalMinor).isEqualTo(1_250)
        compose.awaitCondition("New sale cleared") {
            container
                .session(SaleKind.SALE)
                .cart.value.lines
                .isEmpty()
        }
    }

    @Test
    fun `a shopper without tokens is not offered a token charge`() {
        checkoutWith("CUST-404")
        waitForTag("pay")
        // Unlike a shopper with tokens, nothing appears once the lookup has had time to answer.
        val appeared = runCatching { compose.waitUntilAtLeastOneExists(hasTestTag("chargeToken"), 3_000) }.isSuccess
        assertThat(appeared).isFalse()
    }
}
