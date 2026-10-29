package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.FakeStoreDetails
import app.minimpos.app.FakeTerminal
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.terminal.transport.MerchantLookup
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class MerchantCurrencyUiTest {
    private val stores = FakeStoreDetails(merchantAnswer = MerchantLookup.Found("USD Shop", "JPY"))

    @get:Rule(order = 0)
    val env = TestEnvironment(stores = stores, terminal = FakeTerminal(passphrase = "passphrase"))

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `settlement offer needs pricing confirmation and cancellation preserves product prices`() = reviewPricing()

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `Japanese settlement offer opens pricing review on AMS1`() = reviewPricing()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese settlement offer opens pricing review on AMS1`() = reviewPricing()

    @Test
    @Config(qualifiers = "en-rAU-w360dp-h640dp-xhdpi")
    fun `settlement offer opens pricing review on S1F2`() = reviewPricing()

    private fun reviewPricing() {
        val container = env.container
        env.useLinks()
        env.updateSettings { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        val productId =
            await {
                val tax = container.catalog.saveTaxRate(TaxRateEntity(name = "Tax", rateMilliPercent = 0))
                container.catalog.saveProduct(ProductEntity(name = "Tea", priceMinor = 450, taxRateId = tax))
            }
        container.start()
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("section_terminal"), 15_000)
        compose.onNodeWithTag("section_terminal").performClick()
        compose.onNodeWithTag("useMerchantCurrency").assertDoesNotExist()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("section_payments").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("useMerchantCurrency"), 15_000)
        compose
            .onNodeWithTag("useMerchantCurrency")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirm"), 15_000)
        assertUnchanged(productId)
        compose.onNodeWithText(env.context.getString(R.string.action_cancel)).performClick()
        assertUnchanged(productId)
        // Canceling pricing review keeps the offer available without changing setup.
        compose.waitUntilAtLeastOneExists(hasTestTag("useMerchantCurrency"), 15_000)
        compose.onNodeWithTag("useMerchantCurrency").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirm"), 15_000)
        compose.onNodeWithTag("confirm").performClick()
        compose.awaitCondition(
            "currency changes after pricing confirmation",
        ) { container.settingsState.value.payment.currencyCode == "JPY" }
        assertThat(await { container.catalog.product(productId) }!!.priceMinor).isEqualTo(5)
        compose.onNodeWithTag("useMerchantCurrency").assertDoesNotExist()
    }

    private fun assertUnchanged(productId: Long) {
        assertThat(env.container.settingsState.value.payment.currencyCode).isEqualTo("AUD")
        assertThat(await { env.container.catalog.product(productId) }!!.priceMinor).isEqualTo(450)
    }
}
