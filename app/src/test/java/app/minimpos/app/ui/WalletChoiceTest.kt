package app.minimpos.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.feature.sale.WalletChoice
import app.minimpos.app.feature.sale.logoUrl
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.payment.ScanWallet
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class WalletChoiceTest {
    @get:Rule
    val compose = createRecordingComposeRule()

    @Test
    fun `wallet artwork uses only public Adyen CDN paths for known brands`() {
        ScanWallet.entries.forEach { wallet ->
            assertThat(wallet.logoUrl).isEqualTo(
                "https://checkoutshopper-test.cdn.adyen.com/checkoutshopper/images/logos/${wallet.brand}.svg",
            )
        }
    }

    @Test
    fun `a wallet remains named and selectable when its logo cannot load`() {
        var selected = false
        compose.setContent { MiniMposTheme { WalletChoice(ScanWallet.WECHAT_PAY, { selected = true }) } }
        compose.onNodeWithTag("wallet_logo_wechatpay_pos", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("WeChat Pay").assertIsDisplayed().performClick()
        assertThat(selected).isTrue()
    }
}
