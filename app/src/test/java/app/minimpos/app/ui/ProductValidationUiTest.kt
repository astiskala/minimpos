package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.core.money.CurrencySpec
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Invalid prices explain the currency's precision without changing which prices the editor accepts. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ProductValidationUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `whole-number currency explains invalid prices`() = checkPrice("JPY", "1.5", "12")

    @Test
    fun `three-decimal currency explains invalid prices`() = checkPrice("BHD", "1.2345", "1.234")

    @Test
    fun `zero is explained and valid decimal comma clears the error`() = checkPrice("AUD", "0", "4,50")

    @Test
    fun `overflow explains that the price must be smaller`() = checkPrice("AUD", "1e17", "4.50", R.string.products_price_too_large)

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese validation fits with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        checkPrice("JPY", "-1", "12")
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese three-decimal validation fits`() = checkPrice("BHD", "1.2345", "1.234")

    private fun checkPrice(
        currency: String,
        invalid: String,
        valid: String,
        error: Int? = null,
    ) {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = currency)) }
        await { env.container.catalog.seedDefaults(GST_RATES) }
        compose.setContent { MiniMposApp(env.container) }
        waitForTag("settings")
        compose.openProducts()
        waitForTag("addFab")
        compose.onNodeWithTag("addFab").performClick()
        waitForTag("productName")
        compose.onNodeWithTag("productName").performTextInput("Coffee")
        compose.onNodeWithTag("productPrice").performTextInput(invalid)
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("productPriceError"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        val decimals = CurrencySpec.of(currency).fractionDigits
        val message =
            if (error != null) {
                env.context.getString(error)
            } else if (decimals == 0) {
                env.context.getString(R.string.products_price_whole_invalid)
            } else {
                env.context.resources.getQuantityString(R.plurals.products_price_invalid, decimals, decimals)
            }
        compose
            .onNodeWithTag("productPriceError", useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextEquals(message)
        compose.onNodeWithTag("productPrice").performTextReplacement(valid)
        compose.onNodeWithTag("productPriceError", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("saveProduct").assertIsEnabled()
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)
}
