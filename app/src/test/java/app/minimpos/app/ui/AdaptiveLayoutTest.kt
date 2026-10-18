package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.started
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** Layouts that follow the window: History and Settings beside what they open when it is wide, and the app's language. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w1280dp-h800dp-mdpi")
class AdaptiveLayoutTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useSimulator { it.copy(receipt = it.receipt.copy(autoPrint = false)) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    /** An approved sale of $2.00 with [reference]; returns its stored time. */
    private fun approvedSale(reference: String): Long {
        val start =
            PaymentStart(
                Cart().addCustom("Item", 200, AppliedTax("No tax", 0), "a").totals(TaxMode.INCLUSIVE),
                CurrencySpec("AUD", 2),
                reference,
                null,
                null,
                null,
            )
        val saleId = container.payments.started(start)
        await { container.payments.state.first { it == TransactionState.Finished(saleId) } }
        container.operations.acknowledgePayment()
        return checkNotNull(await { container.sales.get(saleId) }).sale.createdAt
    }

    @Test
    fun `in an expanded window History stays beside the sale it opened and its Back leaves both`() {
        approvedSale("first-sale")
        approvedSale("second-sale")
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("history").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Choose a transaction to see it here."), 15_000)
        compose.waitUntilAtLeastOneExists(hasText("first-sale"), 15_000)
        compose.onNodeWithText("first-sale").performClick()
        waitForTag("detailStatus")
        // The list is still there: the next choice replaces the detail beside it.
        compose.onNodeWithText("second-sale").assertIsDisplayed().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("first-sale").fetchSemanticsNodes().size == 1 }
        compose.onAllNodesWithTag("back").onFirst().performClick()
        waitForTag("newSale")
        compose.onNodeWithTag("historyList").assertDoesNotExist()
    }

    @Test
    fun `in an expanded window Settings stays beside the section it opened`() {
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_tax")
        compose.onNodeWithTag("section_tax").performScrollTo().performClick()
        waitForTag("chargeTax")
        compose.onNodeWithTag("section_terminal").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
    fun `history times follow the app's language rather than the device's`() {
        val at = Instant.ofEpochMilli(approvedSale("ja-sale")).atZone(ZoneId.systemDefault())
        env.updateSettings { it.copy(languageTag = "ja") }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("history").performClick()
        compose.waitUntilAtLeastOneExists(hasText("ja-sale"), 15_000)
        val short = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
        // The device is Australian English ("3:05 pm"); Japanese has no am/pm ("15:05").
        compose.onNodeWithText(short.withLocale(Locale.forLanguageTag("ja-AU")).format(at), substring = true).assertExists()
        compose.onNodeWithText(short.withLocale(Locale.forLanguageTag("en-AU")).format(at), substring = true).assertDoesNotExist()
    }
}
