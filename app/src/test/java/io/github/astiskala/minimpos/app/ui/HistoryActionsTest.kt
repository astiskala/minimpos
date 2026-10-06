package io.github.astiskala.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.astiskala.minimpos.app.GST_RATES
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class HistoryActionsTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(payment = it.payment.copy(currencyCode = "AUD"), receipt = it.receipt.copy(autoPrint = false))
        }
        await {
            container.catalog.seedDefaults(GST_RATES)
            val rate =
                container.catalog.taxRates
                    .first()
                    .first()
            container.catalog.saveProduct(
                ProductEntity(name = "Catering deposit", priceMinor = 20_000, taxRateId = rate.id, kind = SaleKind.PRE_AUTHORISATION),
            )
        }
        compose.setContent { MiniMposApp(container) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun openPreAuthorisation() {
        compose.onNodeWithTag("preAuth").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Catering deposit"), 15_000)
        compose.onNodeWithText("Catering deposit").performClick()
        waitForTag("pay")
        compose.onNodeWithTag("pay").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Pre-authorized"), 15_000)
        compose.onNodeWithTag("home").performClick()
        waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Pre-authorized"), 15_000)
        compose.onNodeWithText("Pre-authorized", useUnmergedTree = true).performClick()
        waitForTag("adjust")
    }

    @Test
    fun `adjusting from history opens the held amount and returns to the same detail`() {
        openPreAuthorisation()
        compose
            .onNodeWithTag("adjust")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        waitForTag("submitCapture")
        compose.onNodeWithTag("submitCapture").assertIsDisplayed().assertTextEquals("Hold $200.00")
        compose.onNodeWithTag("back").performClick()
        waitForTag("capture")
        compose.onNodeWithTag("capture").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the merchant copy action prints a merchant receipt from history`() {
        openPreAuthorisation()
        compose
            .onNodeWithText("Print merchant copy")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        waitForTag("virtualPrinter")
        compose.waitUntilAtLeastOneExists(hasText("MERCHANT COPY"), 15_000)
        compose.onAllNodesWithText("MERCHANT COPY").assertCountEquals(2)
        compose.runOnIdle { container.virtualPrinter.clear() }
        compose.onNodeWithTag("virtualPrinter").assertDoesNotExist()
        compose.onNodeWithTag("cancelPreAuth").performScrollTo().assertIsDisplayed()
    }
}
