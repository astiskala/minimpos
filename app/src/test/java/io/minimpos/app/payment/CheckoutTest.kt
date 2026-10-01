package io.minimpos.app.payment

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.EmailCapture
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.Cart
import io.minimpos.core.cart.CartProduct
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.tax.TaxMode
import io.minimpos.terminal.client.RecurringModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

/** What a checkout makes of the entries and the settings, without a view model. */
class CheckoutTest {
    private val now = Instant.parse("2026-09-30T14:58:11Z")
    private val aud = CurrencySpec("AUD", 2)
    private val totals =
        Cart()
            .addProduct(CartProduct(1, "Latte", null, 450, AppliedTax("GST", 10_000))) { "a" }
            .totals(TaxMode.INCLUSIVE)

    private fun checkout(
        form: CheckoutForm = CheckoutForm(),
        payment: PaymentSettings = PaymentSettings(),
        kind: SaleKind = SaleKind.SALE,
        printer: Boolean = true,
    ) = Checkout(form, payment, totals, aud, kind, printer)

    @Test
    fun `a blank reference is generated with the prefix, and the card is saved under the customer reference`() {
        val start =
            checkout(
                CheckoutForm(customerReference = " CUST-1 ", email = "a@b.co", tokenize = true),
                PaymentSettings(referencePrefix = "MP"),
            ).paymentStart(now, ZoneOffset.UTC)!!
        assertThat(start.merchantReference).matches("MP-260930-145811-[0-9A-Z]{4}")
        assertThat(start.customerReference).isEqualTo("CUST-1")
        assertThat(start.shopperEmail).isEqualTo("a@b.co")
        assertThat(
            start.tokenization,
        ).isEqualTo(TokenizationRequest("CUST-1", RecurringModel.UNSCHEDULED_CARD_ON_FILE, includeEmail = true))
        assertThat(start.manualCapture).isFalse()
        // A typed reference is kept; without the switch the settings default (off for sales) applies.
        val typed =
            checkout(
                CheckoutForm(transactionReference = " ORDER-7 ", customerReference = "CUST-1"),
            ).paymentStart(now, ZoneOffset.UTC)!!
        assertThat(typed.merchantReference).isEqualTo("ORDER-7")
        assertThat(typed.tokenization).isNull()
    }

    @Test
    fun `invalid entries or an empty cart start nothing`() {
        assertThat(checkout(CheckoutForm(customerReference = "ab")).paymentStart(now, ZoneOffset.UTC)).isNull()
        assertThat(checkout(CheckoutForm(transactionReference = "x".repeat(Checkout.MAX_REFERENCE_LENGTH + 1))).canPay).isFalse()
        assertThat(checkout(CheckoutForm(email = "bad"), PaymentSettings(emailCapture = EmailCapture.BOTH)).canPay)
            .isFalse()
        assertThat(Checkout().paymentStart(now, ZoneOffset.UTC)).isNull()
    }

    @Test
    fun `tipping on the receipt needs a sale and a printer, and is taken with manual capture`() {
        val tip = checkout(CheckoutForm(tipOnReceipt = true))
        assertThat(tip.paymentStart(now, ZoneOffset.UTC)!!.manualCapture).isTrue()
        assertThat(checkout(CheckoutForm(tipOnReceipt = true), printer = false).tipOnReceipt).isFalse()
        val preAuth = checkout(CheckoutForm(tipOnReceipt = true, customerReference = "CUST-1"), kind = SaleKind.PRE_AUTHORISATION)
        assertThat(preAuth.canTipOnReceipt).isFalse()
        val start = preAuth.paymentStart(now, ZoneOffset.UTC)!!
        assertThat(start.tipOnReceipt).isFalse()
        assertThat(start.manualCapture).isTrue()
        // Pre-authorisations save the card by default.
        assertThat(start.tokenization).isNotNull()
        assertThrows(IllegalArgumentException::class.java) { start.copy(tipOnReceipt = true) }
    }

    @Test
    fun `the session's checkout follows its cart, form, settings and printer`() =
        runBlocking {
            val session = SaleSession { "line" }
            val settings = MutableStateFlow(AppSettings(payment = PaymentSettings(taxMode = TaxMode.EXCLUSIVE)))
            val checkouts = session.checkout(settings, flowOf(false)) { CurrencySpec.of("AUD") }
            assertThat(checkouts.first().canPay).isFalse()
            session.addProduct(ProductEntity(1, "Tea", 400, 1), TaxRateEntity(1, "GST", 10_000))
            session.updateForm { it.copy(customerReference = "CUST-9") }
            val ready = checkouts.first()
            assertThat(ready.totals.amounts.gross).isEqualTo(440)
            assertThat(ready.customerReference).isEqualTo("CUST-9")
            assertThat(ready.printerAvailable).isFalse()
            assertThat(ready.kind).isEqualTo(SaleKind.SALE)
        }
}
