package io.minimpos.app.payment

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.EmailCapture
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.Cart
import io.minimpos.core.cart.CartProduct
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.shopper.EmailReferenceMode
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
        assertThat(start.shopperReference).isEqualTo("CUST-1")
        assertThat(start.tokenization).isEqualTo(TokenizationRequest(RecurringModel.UNSCHEDULED_CARD_ON_FILE, includeEmail = true))
        assertThat(start.manualCapture).isFalse()
        // A typed reference is kept; without the switch the settings default (off for sales) applies.
        val typed =
            checkout(
                CheckoutForm(transactionReference = " ORDER-7 ", customerReference = "CUST-1"),
            ).paymentStart(now, ZoneOffset.UTC)!!
        assertThat(typed.merchantReference).isEqualTo("ORDER-7")
        assertThat(typed.tokenization).isNull()
        // The shopper reference goes with the payment even though the card is not saved.
        assertThat(typed.shopperReference).isEqualTo("CUST-1")
    }

    @Test
    fun `without a shopper reference source no card is saved and no customer reference asked for`() {
        val none = PaymentSettings(shopperReferenceSource = ShopperReferenceSource.NONE, preAuthTokenizeDefaultOn = true)
        val preAuth =
            checkout(CheckoutForm(customerReference = "CUST-1", email = "a@b.co", tokenize = true), none, SaleKind.PRE_AUTHORISATION)
        assertThat(preAuth.showCustomerReference).isFalse()
        assertThat(preAuth.canTokenize).isFalse()
        val start = preAuth.paymentStart(now, ZoneOffset.UTC)!!
        assertThat(start.tokenization).isNull()
        assertThat(start.shopperReference).isNull()
        assertThat(start.customerReference).isNull()
        assertThat(start.shopperEmail).isEqualTo("a@b.co")
    }

    @Test
    fun `with saving cards not offered the shopper reference is still sent, but no card is saved`() {
        val email =
            PaymentSettings(
                shopperReferenceSource = ShopperReferenceSource.EMAIL,
                emailReferenceMode = EmailReferenceMode.RAW,
                offerCardSaving = false,
                preAuthTokenizeDefaultOn = true,
            )
        val preAuth = checkout(CheckoutForm(email = " S@Example.com ", tokenize = true), email, SaleKind.PRE_AUTHORISATION)
        assertThat(preAuth.showEmail).isTrue()
        assertThat(preAuth.shopperReference).isEqualTo("s@example.com")
        assertThat(preAuth.canTokenize).isFalse()
        assertThat(preAuth.tokenize).isFalse()
        val start = preAuth.paymentStart(now, ZoneOffset.UTC)!!
        assertThat(start.shopperReference).isEqualTo("s@example.com")
        assertThat(start.tokenization).isNull()
        val customer = checkout(CheckoutForm(customerReference = "CUST-1"), PaymentSettings(offerCardSaving = false))
        assertThat(customer.showCustomerReference).isTrue()
        assertThat(customer.canTokenize).isFalse()
        assertThat(customer.paymentStart(now, ZoneOffset.UTC)!!.shopperReference).isEqualTo("CUST-1")
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
    fun `a payment link is offered for a sale that can be paid while links are available`() {
        val links = Checkout(CheckoutForm(customerReference = "CUST-1"), PaymentSettings(), totals, aud, linksAvailable = true)
        assertThat(links.canSendLink).isTrue()
        assertThat(links.copy(linksAvailable = false).canSendLink).isFalse()
        assertThat(links.copy(kind = SaleKind.PRE_AUTHORISATION).canSendLink).isFalse()
        assertThat(links.copy(form = CheckoutForm(customerReference = "ab")).canSendLink).isFalse()
        assertThat(links.copy(linksAvailable = false).linkStart(now, ZoneOffset.UTC)).isNull()
    }

    @Test
    fun `a link is never taken for a tip, keeps the shopper reference and works as long as set, at most 70 days`() {
        val start =
            Checkout(
                CheckoutForm(customerReference = "CUST-1", tipOnReceipt = true),
                PaymentSettings(linkExpiryHours = 48),
                totals,
                aud,
                printerAvailable = true,
                linksAvailable = true,
            ).linkStart(now, ZoneOffset.UTC)!!
        assertThat(start.payment.tipOnReceipt).isFalse()
        assertThat(start.payment.tokenization).isNull()
        assertThat(start.payment.shopperReference).isEqualTo("CUST-1")
        assertThat(start.expiresAt).isEqualTo(now.plusSeconds(48 * 3600))
        val longest =
            Checkout(
                CheckoutForm(),
                PaymentSettings(linkExpiryHours = 70 * 24),
                totals,
                aud,
                linksAvailable = true,
            ).linkStart(now, ZoneOffset.UTC)!!
        assertThat(longest.expiresAt).isEqualTo(now.plus(Checkout.MAX_LINK_LIFETIME))
        assertThat(longest.payment.shopperReference).isNull()
        val sale = start.pendingSale("s1", 5)
        assertThat(sale.paymentLink).isTrue()
        assertThat(sale.shopperReference).isEqualTo("CUST-1")
        assertThat(sale.paymentLinkExpiresAt).isEqualTo(start.expiresAt.toEpochMilli())
        assertThat(sale.tipOnReceipt).isFalse()
    }

    @Test
    fun `the link expiry is kept within Adyen's limits`() {
        assertThat(PaymentSettings(linkExpiryHours = 0).normalized().linkExpiryHours).isEqualTo(1)
        assertThat(PaymentSettings(linkExpiryHours = 99_999).normalized().linkExpiryHours).isEqualTo(1_680)
        assertThat(AppSettings(payment = PaymentSettings(linkExpiryHours = -5)).normalized().payment.linkExpiryHours).isEqualTo(1)
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
            assertThat(ready.linksAvailable).isFalse()
            assertThat(ready.kind).isEqualTo(SaleKind.SALE)
            assertThat(session.checkout(settings, flowOf(false), flowOf(true)) { CurrencySpec.of("AUD") }.first().canSendLink).isTrue()
        }
}
