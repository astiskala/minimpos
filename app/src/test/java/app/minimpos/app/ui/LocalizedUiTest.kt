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
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.feature.outcomeNote
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
