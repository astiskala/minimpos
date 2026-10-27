package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.TaxRateEntity
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class SettingsCatalogUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    @Test
    fun `Home opens main Settings directly and Products returns through Settings under the same admin unlock`() {
        await { env.container.pinManager.setPin("1357") }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("products").assertDoesNotExist()
        compose.onNodeWithTag("settings").assertIsDisplayed().performClick()
        waitForTag("pin_1")
        listOf(1, 3, 5, 7).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("products").performScrollTo().performClick()
        waitForTag("addFab")
        compose.onNodeWithTag("back").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("pin_1").assertDoesNotExist()
        compose.onNodeWithTag("back").performClick()
        waitForTag("newSale")
        compose.onNodeWithTag("settings").performClick()
        waitForTag("pin_1")
        assertThat(env.container.managerLock.unlocked.value).isFalse()
    }

    @Test
    fun `tax rates are listed and chosen in settings, not products`() {
        val container = env.container
        env.useSimulator()
        await { container.catalog.seedDefaults(GST_RATES) }
        val vat = await { container.catalog.saveTaxRate(TaxRateEntity(name = "VAT", rateMilliPercent = 25_500, sortOrder = 9)) }
        await { container.settings.update { it.copy(payment = it.payment.copy(defaultTaxRateId = vat)) } }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        compose.waitUntilAtLeastOneExists(hasText("VAT 25.5%"), 15_000)
        compose.onNodeWithTag("section_tax").performScrollTo().performClick()
        waitForTag("taxRow_$vat")
        compose.onNodeWithTag("taxRow_$vat").assertTextContains("25.5%", substring = true)
        compose.onNodeWithTag("taxRow_$vat").assertTextContains("Default", substring = true)
        compose.onNodeWithTag("addTaxRate").performScrollTo().assertIsDisplayed()

        // Tax rates are no longer edited under Products.
        compose.onNodeWithTag("back").performClick()
        waitForTag("section_tax")
        waitForTag("products")
        compose.onNodeWithTag("products").performClick()
        waitForTag("tab_1")
        compose.onNodeWithTag("tab_2").assertDoesNotExist()
    }
}
