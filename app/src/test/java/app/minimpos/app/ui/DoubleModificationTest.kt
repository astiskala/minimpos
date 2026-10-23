package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.items
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** Adyen's Checkout API for captures and adjustments: accepts each one and counts what was sent. */
private class CountingModifications : PaymentModifications {
    val captures = AtomicInteger()
    val adjustments = AtomicInteger()

    override suspend fun capture(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult = ModificationResult.Received("CAP-${captures.incrementAndGet()}")

    override suspend fun updateAmount(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        adjustAuthorisationData: String?,
        idempotencyKey: String,
    ): ModificationResult {
        val count = adjustments.incrementAndGet()
        return if (adjustAuthorisationData ==
            null
        ) {
            ModificationResult.Received("ADJ-$count")
        } else {
            ModificationResult.Authorised("ADJ-$count", "BQABAQ$count")
        }
    }

    override suspend fun verify(): Fault? = null
}

/**
 * Captures, adjustments and tips sent through the Checkout API after two taps that both arrive before the next frame:
 * one request each. Payments run on this device's terminal, so the API is real rather than simulated.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class DoubleModificationTest {
    private val terminal = FakeTerminal()
    private val modifications = CountingModifications()

    @get:Rule(order = 0)
    val env =
        TestEnvironment(device = FakeDevice(detectedPoiId = "S1F2-000158213605014"), terminal = terminal, modifications = modifications)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.useCheckoutApi()
        env.updateSettings {
            it.copy(
                terminal = it.terminal.copy(keyIdentifier = "key"),
                payment = it.payment.copy(currencyCode = "AUD"),
                receipt = it.receipt.copy(autoPrint = false),
            )
        }
        await {
            container.secrets.set(Secret.TERMINAL_PASSPHRASE, terminal.passphrase)
            container.terminalStatus.check()
            container.catalog.seedDefaults(GST_RATES)
        }
        compose.setContent { MiniMposApp(container) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    /** Two complete taps in one input batch, so both are handled before the screen can change. */
    private fun doubleTap(tag: String) =
        compose.onNodeWithTag(tag).performTouchInput {
            click()
            click()
        }

    /** Waits until queued captures and adjustments have run: [app.minimpos.app.payment.Captures] runs one at a time. */
    private fun modificationsSettled() = await { container.captures.retryCapture("none") }

    private fun ringUp(
        tile: String,
        vararg digits: Int,
    ) {
        compose.onNodeWithTag(tile).performClick()
        waitForTag("addCustom")
        digits.forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
    }

    private fun preAuthorisationInHistory() {
        ringUp("preAuth", 2, 0, 0, 0, 0)
        waitForTag("pay")
        compose.onNodeWithTag("pay").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Pre-authorized"), 15_000)
        compose.onNodeWithTag("home").performClick()
        waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Pre-authorized"), 15_000)
        compose.onNodeWithText("Pre-authorized", useUnmergedTree = true).performClick()
        waitForTag("capture")
    }

    @Test
    fun `a double tap on the capture confirmation sends one capture`() {
        preAuthorisationInHistory()
        compose.onNodeWithTag("capture").performScrollTo().performClick()
        waitForTag("submitCapture")
        compose.onNodeWithTag("submitCapture").performClick()
        waitForTag("confirm")
        doubleTap("confirm")
        compose.waitUntilAtLeastOneExists(hasTestTag("detailStatus") and hasText("Capture requested"), 15_000)
        modificationsSettled()
        assertThat(modifications.captures.get()).isEqualTo(1)
        assertThat(modifications.adjustments.get()).isEqualTo(0)
    }

    @Test
    fun `a double tap on the adjustment confirmation sends one adjustment`() {
        preAuthorisationInHistory()
        compose.onNodeWithTag("adjust").performScrollTo().performClick()
        waitForTag("submitCapture")
        compose.onNodeWithTag("submitCapture").performClick()
        waitForTag("confirm")
        doubleTap("confirm")
        waitForTag("capture")
        modificationsSettled()
        assertThat(modifications.adjustments.get()).isEqualTo(1)
        assertThat(modifications.captures.get()).isEqualTo(0)
    }

    @Test
    fun `a double tap on the tip confirmation sends one capture`() {
        env.updateSettings { it.copy(payment = it.payment.copy(receiptTipping = ReceiptTipping.DEFAULT_ON)) }
        ringUp("newSale", 2, 5, 0, 0)
        compose.clickWhenEnabled("charge")
        waitForTag("pay")
        compose.onNodeWithTag("pay").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Awaiting tip"), 15_000)
        compose.onNodeWithTag("enterTip").performScrollTo().performClick()
        waitForTag("addTip")
        listOf(3, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addTip").performClick()
        waitForTag("confirm")
        doubleTap("confirm")
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Capture requested"), 15_000)
        modificationsSettled()
        assertThat(modifications.captures.get()).isEqualTo(1)
        val sale = await { container.history.items().first() }.filterIsInstance<HistoryItem.Sale>().single()
        assertThat(await { container.sales.get(sale.id)!! }.sale.tipMinor).isEqualTo(300)
    }
}
