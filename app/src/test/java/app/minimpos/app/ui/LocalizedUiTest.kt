package app.minimpos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.CaptureStep
import app.minimpos.app.feature.modificationNote
import app.minimpos.app.feature.outcomeNote
import app.minimpos.app.feature.stillUnconfirmed
import app.minimpos.app.feature.text
import app.minimpos.app.feature.walletCheckFailed
import app.minimpos.app.payment.WalletScanProblem
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart
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
@Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
class LocalizedUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Before
    fun setUp() {
        val currency =
            if (env.context.resources.configuration.locales[0]
                    .country == "HK"
            ) {
                "HKD"
            } else {
                "JPY"
            }
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = currency)) }
        await { env.container.catalog.seedDefaults(env.container.starterTaxRates()) }
    }

    @Test
    fun `Japanese sale primary actions fit an AMS1`() = saleFits()

    @Test
    fun `why a stored payment failed is worded in the app's language when it is shown`() {
        val failed =
            SaleEntity(
                id = "s1",
                createdAt = 1,
                currency = "JPY",
                taxMode = "INCLUSIVE",
                netMinor = 1_000,
                taxMinor = 100,
                totalMinor = 1_100,
                status = SaleStatus.FAILED,
                merchantReference = "MP-1",
                reason = StoredReason.NotDone(Failure.NotSetUp(SetupProblem.POI_ID)),
            )
        val interrupted = failed.copy(reason = StoredReason.Interrupted, message = "EOF")
        compose.setContent {
            Column {
                Text(failed.outcomeNote().orEmpty(), Modifier.testTag("notSetUp"))
                Text(interrupted.outcomeNote().orEmpty(), Modifier.testTag("interrupted"))
            }
        }
        compose.onNodeWithTag("notSetUp").assertTextContains("端末ID", substring = true)
        compose.onNodeWithTag("interrupted").assertTextContains("${env.context.getString(R.string.payment_interrupted)} (EOF)")
    }

    private fun allFaults(said: ExternalText): List<Fault> =
        listOf(
            Fault.Unreachable("192.0.2.1", terminal = true),
            Fault.Unreachable("checkout-test.adyen.com", terminal = false),
            Fault.UnknownHost("pos.local", terminal = true),
            Fault.UnknownHost("checkout-test.adyen.com", terminal = false),
            Fault.Untrusted("192.0.2.1"),
            Fault.KeyRejected(said),
            Fault.TerminalRejected(said),
            Fault.TerminalOffline("S1F2-1", said),
            Fault.NotStarted,
            Fault.AppRefused(said),
            Fault.Unsupported,
            Fault.NoLateReply,
            Fault.NoRecord,
            Fault.RequestNotEncrypted,
            Fault.Credential(ApiKey.ADYEN),
            Fault.Credential(ApiKey.PAYMENTS_APP),
            Fault.Permission(ApiKey.ADYEN, "Role"),
            Fault.Permission(ApiKey.PAYMENTS_APP, null),
            Fault.NotFound("S1F2-1"),
            Fault.AdyenRejected(422, "101", said),
            Fault.ListTooLarge,
            Fault.TimedOut,
            Fault.ConnectionLost,
            Fault.ReplyUnverified,
            Fault.UnreadableReply(said),
            Fault.NoAnswerFromTerminal("S1F2-1", said),
            Fault.Abandoned,
            Fault.TerminalHttp(502),
            Fault.AdyenUnavailable(503, null, said),
            Fault.StillInProgress,
        ) + MalformedPart.entries.map { Fault.Malformed(it) }

    private fun allOutcomes(said: ExternalText): List<ActionOutcome> =
        listOf(
            ActionOutcome.Printed,
            ActionOutcome.Emailed("shopper@example.com"),
            ActionOutcome.AbortSent,
            ActionOutcome.LinkNotPaid,
            ActionOutcome.NotCaptured(Failure.Remote(Fault.TimedOut), CaptureStep.CAPTURE),
            ActionOutcome.NotCaptured(Failure.Remote(Fault.TimedOut), CaptureStep.ADJUSTMENT),
            ActionOutcome.CaptureUnconfirmed(Failure.Remote(Fault.TimedOut), CaptureStep.CAPTURE),
            ActionOutcome.CaptureUnconfirmed(Failure.Remote(Fault.TimedOut), CaptureStep.ADJUSTMENT),
            ActionOutcome.CaptureRefused(CaptureStep.TIP, 500, "JPY", said),
            ActionOutcome.CaptureRefused(CaptureStep.CAPTURE, 1_000, "JPY", null),
            ActionOutcome.CaptureNotAllowed,
            ActionOutcome.Connected(hasPrinter = true, globalStatus = "OK", setupOnly = false),
            ActionOutcome.Connected(hasPrinter = false, globalStatus = null, setupOnly = false),
            ActionOutcome.Connected(hasPrinter = false, globalStatus = null, setupOnly = true),
            ActionOutcome.ConnectionFailed(Failure.Remote(Fault.TimedOut)),
            ActionOutcome.ApiWorks,
            ActionOutcome.TapToPayReady("INST-1"),
            ActionOutcome.TapToPayRemoved,
            ActionOutcome.TestEmailSent("ops@example.com"),
            ActionOutcome.Missing,
            ActionOutcome.Unconfirmed(Failure.Remote(Fault.TimedOut)),
            ActionOutcome.Failed(Failure.Remote(Fault.TimedOut)),
        )

    private fun baseSale() =
        SaleEntity(
            id = "s",
            createdAt = 1,
            currency = "JPY",
            taxMode = "INCLUSIVE",
            netMinor = 1_000,
            taxMinor = 0,
            totalMinor = 1_000,
            status = SaleStatus.UNKNOWN,
            merchantReference = "MP",
        )

    @Test
    fun `words every outcome fault setup problem and stored note without blank strings`() {
        val said = ExternalText("Adyen said")
        val failures =
            SetupProblem.entries.map { Failure.NotSetUp(it) } +
                allFaults(said).map { Failure.Remote(it) } +
                DeviceFault.entries.map { Failure.Device(it) } +
                EmailFault.entries.map { Failure.Email(it, said) }
        val baseSale = baseSale()
        val baseRefund =
            RefundEntity("r", "s", 1, "MR", "TX", "2026-10-05T12:00:00Z", "MP", "JPY", 500, false, RefundStatus.UNKNOWN)
        val rendered = mutableListOf<String>()
        compose.setContent {
            allOutcomes(said).forEach { rendered += it.text() }
            failures.forEach { rendered += it.text() }
            WalletScanProblem.entries.forEach { rendered += it.text() }
            SetupProblem.entries.forEach { rendered += it.text() }
            rendered += walletCheckFailed(Failure.Remote(Fault.TimedOut))
            rendered += stillUnconfirmed(Failure.Remote(Fault.TimedOut))
            val timedOutUnconfirmed = StoredReason.Unconfirmed(Failure.Remote(Fault.TimedOut))
            rendered += checkNotNull(baseSale.copy(paymentLink = true, reason = timedOutUnconfirmed).outcomeNote())
            rendered += checkNotNull(baseSale.copy(reason = StoredReason.Unconfirmed(null)).outcomeNote())
            val timedOutNotDone = StoredReason.NotDone(Failure.Remote(Fault.TimedOut))
            rendered += checkNotNull(baseSale.copy(modificationReason = timedOutNotDone).modificationNote())
            rendered += checkNotNull(baseSale.copy(modificationReason = timedOutUnconfirmed).modificationNote())
            rendered += checkNotNull(baseSale.copy(modificationReason = StoredReason.Interrupted).modificationNote())
            rendered += checkNotNull(baseRefund.copy(reason = StoredReason.Interrupted).outcomeNote())
            assertThat(baseSale.copy(modificationReason = StoredReason.Unconfirmed(null)).modificationNote()).isNull()
        }
        assertThat(rendered).isNotEmpty()
        assertThat(rendered.all { it.isNotBlank() }).isTrue()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese sale primary actions fit an AMS1`() = saleFits()

    @Test
    fun `Japanese wallet checkout and demo scan fit AMS1`() = walletFits()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese wallet checkout and demo scan fit AMS1`() = walletFits()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese sale primary actions fit an AMS1`() = saleFits()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese wallet checkout and demo scan fit AMS1`() = walletFits()

    @Test
    @Config(qualifiers = "en-rHK-w320dp-h460dp-hdpi")
    fun `language can be overridden in Settings without losing the cart and reset to the device`() {
        await { env.container.session(SaleKind.SALE).addCustom("Merchant item", 1_200, TaxRateEntity(1, "No tax", 0)) }
        val original = await { env.container.settings.current() }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("appLanguage"), 15_000)
        compose.onNodeWithTag("appLanguage").performScrollTo().performClick()
        compose.onNodeWithText("繁體中文").assertIsDisplayed().performClick()
        compose.waitUntilAtLeastOneExists(
            hasText("語言"),
            15_000,
        )
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("settings").assertTextContains("設定")
        assertThat(
            env.container
                .session(SaleKind.SALE)
                .cart.value.lines
                .single()
                .name,
        ).isEqualTo("Merchant item")
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("appLanguage").performScrollTo().performClick()
        compose.onNodeWithText("跟隨裝置").performClick()
        compose.waitUntilAtLeastOneExists(
            hasText("Language"),
            15_000,
        )
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("settings").assertTextContains("Settings")
        assertThat(await { env.container.settings.current() }).isEqualTo(original)
    }

    private fun walletFits() {
        await {
            env.container.session(SaleKind.SALE).addCustom("Demo", 1_200, TaxRateEntity(1, "No tax", 0))
            env.container.walletDiscovery.refresh()
        }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("newSale").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("charge") and isEnabled(), 15_000)
        compose.onNodeWithTag("charge").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("scanWallet"), 15_000)
        compose.onNodeWithTag("scanWallet").assertIsDisplayed().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("wallet_pick_wechatpay_pos"), 15_000)
        compose.onNodeWithTag("wallet_pick_wechatpay_pos").performScrollTo().performClick()
        compose.onNodeWithTag("walletDemo").assertIsDisplayed()
        compose.onNodeWithText(env.context.getString(R.string.wallet_demo_note)).assertIsDisplayed()
    }

    private fun saleFits() {
        val context = env.context
        compose.setContent { MiniMposApp(env.container) }
        listOf("newSale", "refund", "history", "products", "settings").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("newSale").assertTextContains(context.getString(R.string.home_new_sale)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("addCustom"), 15_000)
        compose.onNodeWithText(context.getString(R.string.sale_custom_description)).performClick()
        compose.onNodeWithText(context.getString(R.string.sale_custom_default_name)).assertIsDisplayed()
        compose.onNodeWithTag("key_1").assertIsDisplayed().performClick()
        compose.onNodeWithTag("key_2").performClick()
        compose.onNodeWithTag("addCustom").assertIsDisplayed().performClick()
        assertThat(
            env.container
                .session(SaleKind.SALE)
                .cart.value.lines
                .single()
                .name,
        ).isEqualTo(context.getString(R.string.sale_custom_default_name))
        compose.onNodeWithTag("charge").assertIsDisplayed().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("pay"), 15_000)
        compose.onNodeWithTag("pay").assertIsDisplayed().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("newSaleAfter"), 15_000)
        compose.onNodeWithTag("resultStatus").assertTextContains(context.getString(R.string.status_approved))
        compose.onNodeWithTag("newSaleAfter").assertIsDisplayed()
        compose.onNodeWithTag("home").assertIsDisplayed()
    }
}
