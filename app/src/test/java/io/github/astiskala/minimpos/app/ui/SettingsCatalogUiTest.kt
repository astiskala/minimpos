package io.github.astiskala.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.astiskala.minimpos.app.GST_RATES
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        compose.onNodeWithTag("back").performClick()
        waitForTag("products")
        compose.onNodeWithTag("products").performClick()
        waitForTag("tab_1")
        compose.onNodeWithTag("tab_2").assertDoesNotExist()
    }
}
