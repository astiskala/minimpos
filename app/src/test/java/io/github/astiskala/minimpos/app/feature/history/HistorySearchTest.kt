package io.github.astiskala.minimpos.app.feature.history

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.repo.HistoryItem
import io.github.astiskala.minimpos.core.payment.Wallet
import org.junit.Test

class HistorySearchTest {
    private val sale =
        SaleEntity(
            id = "s1",
            createdAt = 1,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 3091,
            taxMinor = 309,
            totalMinor = 3400,
            status = SaleStatus.APPROVED,
            merchantReference = "261001-072245-W2HT",
            customerReference = "CUST-1042",
            shopperReference = "CUST-1042",
            shopperEmail = "jo@example.com",
            pspReference = "QWERTY1234567890",
            paymentBrand = "visa",
            paymentMethodVariant = "visa_applepay",
            maskedPan = "476173 **** 0119",
            authCode = "654321",
            tipMinor = 500,
            tipOnReceipt = true,
        )
    private val refund =
        RefundEntity(
            id = "r1",
            saleId = "s1",
            createdAt = 2,
            merchantReference = "R-261001-083000-AB12",
            originalTransactionId = "TENDER.QWERTY1234567890",
            originalTimestamp = "2026-10-01T00:00:00Z",
            originalReference = "261001-072245-W2HT",
            currency = "AUD",
            amountMinor = 1100,
            full = false,
            status = RefundStatus.REQUESTED,
            pspReference = "REFUNDPSP0000001",
        )

    private fun finds(
        text: String,
        method: PaymentMethodFilter? = null,
    ) = HistorySearch(text, method).matches(HistoryItem.Sale(sale), sale)

    private fun findsRefund(
        text: String,
        method: PaymentMethodFilter? = null,
        withSale: Boolean = true,
    ) = HistorySearch(text, method).matches(HistoryItem.Refund(refund), sale.takeIf { withSale })

    @Test
    fun `matches references, shopper details, the card and its payment method, ignoring case`() {
        listOf(
            "w2ht",
            "qwerty123",
            "654321",
            "cust-1042",
            "JO@EXAMPLE",
            "0119",
            "visa",
            "apple",
            "applepay",
        ).forEach { assertThat(finds(it)).isTrue() }
        // Only the last four digits of the card number are searched, not its BIN.
        assertThat(finds("476173")).isFalse()
        assertThat(finds("mastercard")).isFalse()
    }

    @Test
    fun `an amount matches the amount as it stands or the bill before the tip`() {
        assertThat(sale.amountMinor).isEqualTo(3900)
        listOf("39", "39.00", "$39", "34.00", "34,00").forEach { assertThat(finds(it)).isTrue() }
        listOf("39.5", "3.40", "340").forEach { assertThat(finds(it)).isFalse() }
    }

    @Test
    fun `every word must match`() {
        assertThat(finds("visa 34")).isTrue()
        assertThat(finds("  visa   cust-1042 ")).isTrue()
        assertThat(finds("visa 35")).isFalse()
        assertThat(HistorySearch(" ").isActive).isFalse()
        assertThat(finds("")).isTrue()
    }

    @Test
    fun `refunds match on their own fields and through their sale, but not on the sale's amount`() {
        listOf("ab12", "refundpsp", "w2ht", "11", "11.00").forEach { assertThat(findsRefund(it)).isTrue() }
        listOf("cust-1042", "654321", "0119", "apple pay").forEach { assertThat(findsRefund(it)).isTrue() }
        assertThat(findsRefund("34.00")).isFalse()
        assertThat(findsRefund("cust-1042", withSale = false)).isFalse()
        assertThat(findsRefund("w2ht", withSale = false)).isTrue()
    }

    @Test
    fun `the payment method narrows sales and the refunds of those sales`() {
        val visa = PaymentMethodFilter.Brand("visa")
        val applePay = PaymentMethodFilter.InWallet(Wallet.APPLE_PAY)
        assertThat(finds("", visa)).isTrue()
        assertThat(finds("", applePay)).isTrue()
        assertThat(finds("", PaymentMethodFilter.Brand("mc"))).isFalse()
        assertThat(finds("", PaymentMethodFilter.InWallet(Wallet.GOOGLE_PAY))).isFalse()
        assertThat(finds("w2ht", visa)).isTrue()
        assertThat(finds("nothing", visa)).isFalse()
        assertThat(findsRefund("", applePay)).isTrue()
        assertThat(findsRefund("", applePay, withSale = false)).isFalse()
        assertThat(HistorySearch("", visa).isActive).isTrue()
    }

    @Test
    fun `offers the brands by name, then the wallets`() {
        val sales =
            listOf(
                sale,
                sale.copy(id = "s2", paymentBrand = "MC", paymentMethodVariant = "mc_googlepay"),
                sale.copy(id = "s3", paymentBrand = "amex", paymentMethodVariant = "amex"),
                sale.copy(id = "s4", paymentBrand = "mc", paymentMethodVariant = null),
                sale.copy(id = "s5", paymentBrand = null, paymentMethodVariant = null),
            )
        assertThat(PaymentMethodFilter.available(sales).map { it.label })
            .containsExactly("American Express", "Mastercard", "Visa", "Apple Pay", "Google Pay")
            .inOrder()
        assertThat(PaymentMethodFilter.Brand("mc").matches(sales[1])).isTrue()
    }
}
