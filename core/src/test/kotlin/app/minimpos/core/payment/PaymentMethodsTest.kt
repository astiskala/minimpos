package app.minimpos.core.payment

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaymentMethodsTest {
    @Test
    fun `names known brands and shows other codes in upper case`() {
        assertThat(PaymentMethods.brandName("mc")).isEqualTo("Mastercard")
        assertThat(PaymentMethods.brandName(" VISA ")).isEqualTo("Visa")
        assertThat(PaymentMethods.brandName("eftpos_australia")).isEqualTo("eftpos")
        assertThat(PaymentMethods.brandName("newscheme")).isEqualTo("NEWSCHEME")
        assertThat(PaymentMethods.normalizeBrand(" Amex ")).isEqualTo("amex")
    }

    @Test
    fun `scanned wallet routing brands have merchant readable names`() {
        assertThat(PaymentMethods.brandName("wechatpay_pos")).isEqualTo("WeChat Pay")
        assertThat(PaymentMethods.brandName("paypal_pos")).isEqualTo("PayPal")
        assertThat(PaymentMethods.brandName("alipay_plus_gcash")).isEqualTo("GCash")
    }

    @Test
    fun `wallet code validation preserves numeric strings and rejects unrelated QR payloads`() {
        assertThat(ScanWallet.WECHAT_PAY.accepts("133341022926803846")).isTrue()
        assertThat(ScanWallet.WECHAT_PAY.accepts("13334102292680384")).isFalse()
        assertThat(ScanWallet.PAYPAL.accepts("000190468703")).isTrue()
        assertThat(ScanWallet.ALIPAY.accepts("2".repeat(24))).isTrue()
        assertThat(ScanWallet.ALIPAY.accepts("2".repeat(25))).isFalse()
        assertThat(ScanWallet.PAYME.accepts("123456789012")).isTrue()
        assertThat(ScanWallet.PAYME.accepts("123".repeat(50))).isFalse()
        assertThat(ScanWallet.entries.all { !it.accepts("MPR1*demo") && !it.accepts("https://example.invalid/123") && !it.accepts("１２３４") })
            .isTrue()
        assertThat(ScanWallet.configured("alipay_plus")).isEqualTo(ScanWallet.ALIPAY_PLUS)
        assertThat(ScanWallet.configured("unknown")).isNull()
    }

    @Test
    fun `all wallet routes and returned variants retain their own names and valid offline codes`() {
        ScanWallet.entries.forEach { wallet ->
            assertThat(ScanWallet.configured(wallet.brand)).isEqualTo(wallet)
            assertThat(ScanWallet.reported(wallet.brand)).isEqualTo(wallet)
            assertThat(wallet.accepts(wallet.demoCode)).isTrue()
            assertThat(PaymentMethods.brandName(wallet.brand)).isEqualTo(wallet.displayName)
        }
        assertThat(ScanWallet.reported(null)).isNull()
        assertThat(ScanWallet.reported("unknown")).isNull()
        assertThat(ScanWallet.reported("alipay_plus_unknown")).isNull()
        assertThat(ScanWallet.reported("wechatpay")).isEqualTo(ScanWallet.WECHAT_PAY)
        assertThat(ScanWallet.reported("alipay_plus_alipay_cn")).isEqualTo(ScanWallet.ALIPAY)
        assertThat(ScanWallet.reported("alipay_plus_alipay_hk")).isEqualTo(ScanWallet.ALIPAY_HK)
        assertThat(ScanWallet.configured("payme")).isEqualTo(ScanWallet.PAYME)
        assertThat(ScanWallet.configured("paypal")).isEqualTo(ScanWallet.PAYPAL)
        assertThat(ScanWallet.configured("venmo")).isEqualTo(ScanWallet.VENMO)
    }

    @Test
    fun `finds the wallet at the end of the variant`() {
        assertThat(PaymentMethods.wallet("visa_applepay")).isEqualTo(Wallet.APPLE_PAY)
        assertThat(PaymentMethods.wallet("MC_GooglePay")).isEqualTo(Wallet.GOOGLE_PAY)
        assertThat(PaymentMethods.wallet("maestro_usa_samsungpay")).isEqualTo(Wallet.SAMSUNG_PAY)
        assertThat(PaymentMethods.wallet("visa")).isNull()
        assertThat(PaymentMethods.wallet("mc_debit")).isNull()
        assertThat(PaymentMethods.wallet("applepay")).isNull()
        assertThat(PaymentMethods.wallet(null)).isNull()
    }
}
