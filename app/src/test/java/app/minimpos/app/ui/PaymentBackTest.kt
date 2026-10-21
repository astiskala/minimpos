package app.minimpos.app.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.data.security.Secret
import app.minimpos.app.payment.TransactionState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** Back while a payment waits on the terminal asks before cancelling it, since a stray gesture must not stop it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class PaymentBackTest {
    private val answer = CompletableDeferred<Unit>()

    @get:Rule(order = 0)
    val env =
        TestEnvironment(
            FakeDevice(detectedPoiId = "S1F2-000158234567890"),
            FakeTerminal(passphrase = "correct horse battery staple", reply = { request ->
                if (request.saleToPOIRequest.paymentRequest != null) answer.await()
                null
            }),
        )

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @After
    fun answer() {
        answer.complete(Unit)
    }

    @Test
    fun `Back asks before cancelling a payment and keeping it waits on`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "mini-key")) }
        await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
        env.useCheckoutApi()
        await { container.catalog.seedDefaults(GST_RATES) }
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MiniMposApp(container)
        }
        compose.onNodeWithTag("newSale").performClick()
        waitFor(hasTestTag("addCustom"))
        listOf(1, 2, 5, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        compose.clickWhenEnabled("charge")
        waitFor(hasTestTag("pay"))
        compose.onNodeWithTag("pay").performClick()
        waitFor(hasTestTag("cancelPayment") and isEnabled())

        compose.runOnUiThread { back.onBackPressed() }
        waitFor(hasText("Cancel this payment?"))
        compose.onNodeWithText("Keep waiting").performClick()
        compose.onNodeWithTag("paymentWaiting").assertExists()
        assertThat((container.operations.currentPayment as TransactionState.Processing).cancelling).isFalse()

        compose.runOnUiThread { back.onBackPressed() }
        waitFor(hasText("Cancel this payment?"))
        compose.onNodeWithTag("confirm").performClick()
        waitFor(hasText("Canceling…"))
    }

    private fun waitFor(matcher: SemanticsMatcher) = compose.waitUntilAtLeastOneExists(matcher, 15_000)
}
