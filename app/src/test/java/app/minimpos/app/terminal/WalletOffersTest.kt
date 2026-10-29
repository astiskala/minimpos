package app.minimpos.app.terminal

import app.minimpos.core.payment.ScanWallet
import app.minimpos.terminal.transport.WalletMethod
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WalletOffersTest {
    @Test
    fun `ANY currencies permit wallets and gift cards without bypassing store restrictions`() {
        val wallet = WalletMethod("wechatpay_pos", true, true, "pos", setOf("ANY"), setOf("ANY"), setOf("ST1"))
        val gift = wallet.copy(type = "givex")
        val offers = WalletOffers.configured(listOf(wallet), "ST1", "AU")
        assertThat(WalletOffers.forCurrency(offers, "AUD")).containsExactly(ScanWallet.WECHAT_PAY)
        assertThat(WalletOffers.forCurrency(offers, "EUR")).containsExactly(ScanWallet.WECHAT_PAY)
        assertThat(WalletOffers.giftCards(listOf(gift), "ST1", "AU", "AUD")).containsExactly("givex")
        assertThat(WalletOffers.configured(listOf(wallet), "ST2", "AU")).isEmpty()
        assertThat(WalletOffers.giftCards(listOf(gift), "ST2", "AU", "AUD")).isEmpty()
    }

    @Test
    fun `only enabled allowed POS wallets in the current store country and currency are offered`() {
        val enabled = WalletMethod("wechatpay_pos", true, true, "pos", setOf("AUD"), setOf("AU"), setOf("ST1"))
        val methods =
            listOf(
                enabled,
                enabled.copy(type = "alipay", enabled = false),
                enabled.copy(type = "paypal_pos", allowed = false),
                enabled.copy(type = "dana", channel = "eCommerce"),
                enabled.copy(type = "kakaopay", storeIds = setOf("ST2")),
                enabled.copy(type = "gcash", countries = setOf("PH")),
                enabled.copy(type = "truemoney", currencies = setOf("THB")),
                enabled.copy(type = "unknown"),
                enabled.copy(type = "alipay_plus"),
            )
        val configured = WalletOffers.configured(methods, "ST1", "AU")
        assertThat(WalletOffers.forCurrency(configured, "AUD")).containsExactly(ScanWallet.WECHAT_PAY, ScanWallet.ALIPAY_PLUS)
        assertThat(WalletOffers.forCurrency(configured, "EUR")).isEmpty()
        assertThat(WalletOffers.nativeScanner("S1F2", "S1F2-123456789")).isFalse()
        assertThat(WalletOffers.nativeScanner("S1F2L", "S1F2-123456789")).isTrue()
        assertThat(WalletOffers.nativeScanner("", "S1E2L-123456789")).isTrue()
    }
}
