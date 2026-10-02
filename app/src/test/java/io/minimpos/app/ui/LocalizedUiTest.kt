package io.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.minimpos.app.MiniMposApp
import io.minimpos.app.R
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
class LocalizedUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Before
    fun setUp() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "JPY")) }
        await { env.container.catalog.seedDefaults(env.container.starterTaxRates()) }
    }

    @Test
    fun `Japanese sale primary actions fit an AMS1`() = saleFits()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese sale primary actions fit an AMS1`() = saleFits()

    private fun saleFits() {
        val context = env.context
        compose.setContent { MiniMposApp(env.container) }
        listOf("newSale", "refund", "history", "products", "settings").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("newSale").assertTextContains(context.getString(R.string.home_new_sale)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("addCustom"), 15_000)
        compose.onNodeWithTag("key_1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("key_2").performClick()
        compose.onNodeWithTag("addCustom").assertIsDisplayed().performClick()
        compose.onNodeWithTag("charge").assertIsDisplayed().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("pay"), 15_000)
        compose.onNodeWithTag("pay").assertIsDisplayed().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("newSaleAfter"), 15_000)
        compose.onNodeWithTag("resultStatus").assertTextContains(context.getString(R.string.status_approved))
        compose.onNodeWithTag("newSaleAfter").assertIsDisplayed()
        compose.onNodeWithTag("home").assertIsDisplayed()
    }
}
