package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleKind
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class SaleEntryTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Before
    fun setUp() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        await { env.container.catalog.seedDefaults(GST_RATES) }
        compose.setContent { MiniMposApp(env.container) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    @Test
    fun `opening an empty pre-authorisation catalogue from Home offers a custom item despite an unfinished amount`() {
        compose.onNodeWithTag("preAuth").performClick()
        waitForTag("addCustom")
        listOf(7, 5, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        waitForTag("pay")
        compose.onNodeWithTag("back").performClick()
        waitForTag("customItem")
        compose.onNodeWithTag("addCustom").assertDoesNotExist()
        compose.onNodeWithTag("back").performClick()
        waitForTag("preAuth")

        compose.onNodeWithTag("preAuth").performClick()
        waitForTag("addCustom")
        // Offering a new amount must not silently discard the unfinished one.
        compose.onNodeWithText("Cancel").performClick()
        assertThat(
            env.container
                .session(SaleKind.PRE_AUTHORISATION)
                .cart.value.lines
                .single()
                .unitPrice,
        ).isEqualTo(7_500)
        compose.onNodeWithTag("customItem").performClick()
        waitForTag("addCustom")
        listOf(2, 5, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        waitForTag("pay")
        compose.onNodeWithTag("pay").assertTextEquals("Pre-authorize $25.00")
        assertThat(
            env.container
                .session(SaleKind.PRE_AUTHORISATION)
                .cart.value.lines,
        ).hasSize(1)
    }
}
