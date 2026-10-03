package io.github.astiskala.minimpos.core.payment

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
