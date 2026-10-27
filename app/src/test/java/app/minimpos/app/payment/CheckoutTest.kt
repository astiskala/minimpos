package app.minimpos.app.payment

import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.EmailCapture
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.PricingChange
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.terminal.TerminalState
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartProduct
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.payment.ScanWallet
import app.minimpos.core.shopper.EmailReferenceMode
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.MexicanPayment
import app.minimpos.terminal.client.RecurringModel
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TerminalTipSuggestions
import com.google.common.truth.Truth.assertThat
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
    private val audioSettings = AppSettings(payment = PaymentSettings(audioGuidance = true))

    private fun checkout(
        form: CheckoutForm = CheckoutForm(),
        payment: PaymentSettings =
            PaymentSettings(
                shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE,
                receiptTipping = ReceiptTipping.DEFAULT_OFF,
            ),
        kind: SaleKind = SaleKind.SALE,
        printer: Boolean = true,
    ) = Checkout(form, payment, totals, aud, kind, printer)

    @Test
    fun `Mexican routing needs configured verified facts and never drops selected options for other tenders`() {
        val context = PaymentContext("TERMINAL", "AMS1-123", "POS", "Merchant", "TEST")
        val ready =
            checkout(form = CheckoutForm(mexicanPayment = MexicanPayment.MONTHS_6, tokenize = false)).copy(
                currency = CurrencySpec.of("MXN"),
                payment = PaymentSettings(mexicanInstallments = true, allowPartialAuthorisation = false),
                mexicanRoutingAvailable = true,
                mexicanContext = context,
                mexicanIdentity = "verified",
            )
        assertThat(ready.canPay).isTrue()
        val start = checkNotNull(ready.paymentStart(now, ZoneOffset.UTC))
        assertThat(start.mexicanPayment).isEqualTo(MexicanPayment.MONTHS_6)
        assertThat(start.walletContext).isEqualTo(context)
        assertThat(start.walletSetupIdentity).isEqualTo("verified")
        assertThat(ready.canSplit).isFalse()
        assertThat(ready.copy(linksAvailable = true).canSendLink).isFalse()
        assertThat(ready.copy(wallets = listOf(ScanWallet.WECHAT_PAY)).canScanWallet).isFalse()
        listOf(
            ready.copy(mexicanRoutingAvailable = false),
            ready.copy(mexicanContext = null),
            ready.copy(mexicanIdentity = null),
            ready.copy(currency = aud),
            ready.copy(payment = ready.payment.copy(mexicanInstallments = false)),
            ready.copy(payment = ready.payment.copy(allowPartialAuthorisation = true)),
            ready.copy(kind = SaleKind.PRE_AUTHORISATION),
            ready.copy(
                form = ready.form.copy(audioGuidance = true),
                payment = ready.payment.copy(audioGuidance = true),
                audioGuidanceMinimumSoftware = "1.117",
                audioGuidanceTarget = "AMS1-123",
            ),
            ready.copy(form = ready.form.copy(tipOnReceipt = true)),
            ready.copy(form = ready.form.copy(askGratuity = true)),
            ready.copy(form = ready.form.copy(askGiving = true)),
            ready.copy(payment = ready.payment.copy(allowMke = true), form = ready.form.copy(cardEntryOverride = CardEntryOverride.MKE)),
        ).forEach { assertThat(it.paymentStart(now, ZoneOffset.UTC)).isNull() }
        assertThrows(IllegalArgumentException::class.java) { start.copy(requestedAmountMinor = 100) }
        assertThrows(IllegalArgumentException::class.java) { start.copy(allowPartialAuthorisation = true) }
    }

    @Test
    fun `busy payment runner restores audio choice without restoring stale checkout access`() =
        runBlocking {
            val session = SaleSession()
            session.addCustom("Item", 1200, TaxRateEntity(1, "Zero", 0))
            session.updateForm { it.copy(audioGuidance = true) }
            val terminal = flowOf(TerminalState(poiId = "AMS1-123"))
            val checkout = session.checkout(flowOf(audioSettings), terminal = terminal, currency = { aud }).first()
            val start = checkNotNull(session.paymentStart(checkout, now, ZoneOffset.UTC))
            session.updateForm(start) { it.copy(audioGuidance = true) }
            assertThat(session.form.value.audioGuidance).isTrue()
            assertThat(session.paymentStart(checkout, now, ZoneOffset.UTC)).isNull()
            session.complete(start)
            assertThat(session.cart.value.lines).hasSize(1)
            session.updateForm { it.copy(audioGuidance = false) }
            session.updateForm(start) { it.copy(audioGuidance = true) }
            assertThat(session.form.value.audioGuidance).isFalse()
        }

    @Test
    fun `audio guidance is offered only once enabled for a supported terminal and needs no confirmation`() {
        val chosen =
            checkout(form = CheckoutForm(audioGuidance = true))
                .copy(audioGuidanceMinimumSoftware = "1.117", audioGuidanceTarget = "AMS1-123")
        assertThat(chosen.offersAudioGuidance).isFalse()
        assertThat(checkNotNull(chosen.paymentStart(now, ZoneOffset.UTC)).audioGuidance).isFalse()
        val ready = chosen.copy(payment = chosen.payment.copy(audioGuidance = true))
        assertThat(ready.offersAudioGuidance).isTrue()
        assertThat(ready.copy(audioGuidanceMinimumSoftware = null).offersAudioGuidance).isFalse()
        assertThat(ready.copy(audioGuidanceMinimumSoftware = null, audioGuidanceSimulated = true).offersAudioGuidance).isTrue()
        assertThat(ready.copy(audioGuidanceTarget = null).offersAudioGuidance).isFalse()
        assertThat(ready.canPay).isTrue()
        val start = checkNotNull(ready.paymentStart(now, ZoneOffset.UTC))
        assertThat(start.audioGuidance).isTrue()
        assertThat(start.audioGuidancePoiId).isEqualTo("AMS1-123")
        assertThat(start.shopperFlow).isNull()
        val fullAmount =
            checkNotNull(
                ready
                    .copy(payment = ready.payment.copy(allowPartialAuthorisation = true))
                    .paymentStart(now, ZoneOffset.UTC),
            )
        assertThat(fullAmount.allowPartialAuthorisation).isFalse()
        assertThrows(IllegalArgumentException::class.java) { fullAmount.copy(allowPartialAuthorisation = true) }
        assertThat(
            checkNotNull(ready.copy(audioGuidanceTarget = "AMS1-other").paymentStart(now, ZoneOffset.UTC)).audioGuidancePoiId,
        ).isEqualTo("AMS1-other")
        assertThat(
            ready
                .copy(
                    form = ready.form.copy(askGratuity = true),
                    payment = ready.payment.copy(terminalTipping = ReceiptTipping.DEFAULT_ON),
                ).canPay,
        ).isFalse()
        assertThat(ready.copy(form = ready.form.copy(tipOnReceipt = true)).canPay).isFalse()
        assertThat(
            ready
                .copy(
                    form = ready.form.copy(askGiving = true),
                    payment = ready.payment.copy(askGiving = ReceiptTipping.DEFAULT_ON),
                ).canPay,
        ).isFalse()
        assertThat(
            ready
                .copy(
                    form = ready.form.copy(cardEntryOverride = CardEntryOverride.MKE),
                    payment = ready.payment.copy(allowMke = true),
                ).canPay,
        ).isFalse()
        assertThat(ready.copy(linksAvailable = true).canSendLink).isFalse()
        assertThat(ready.copy(wallets = listOf(ScanWallet.WECHAT_PAY)).canScanWallet).isFalse()
    }

    @Test
    fun `audio choice is consumed once and stale checkout cannot start another shopper`() =
        runBlocking {
            val session = SaleSession()
            session.addCustom("Item", 1200, TaxRateEntity(1, "Zero", 0))
            session.updateForm { it.copy(audioGuidance = true) }
            val terminal = flowOf(TerminalState(poiId = "AMS1-123"))
            val checkout = session.checkout(flowOf(audioSettings), terminal = terminal, currency = { aud }).first()
            val start = checkNotNull(session.paymentStart(checkout, now, ZoneOffset.UTC))
            assertThat(start.audioGuidance).isTrue()
            assertThat(session.form.value.audioGuidance).isFalse()
            assertThat(session.paymentStart(checkout, now, ZoneOffset.UTC)).isNull()
            session.complete(start)
            assertThat(session.cart.value.lines).isEmpty()
        }

    @Test
    fun `each tender's ceremony says what it drops, then the special entry warning, then Manager approval`() {
        val giving = PaymentSettings(receiptTipping = ReceiptTipping.DEFAULT_ON, askGiving = ReceiptTipping.DEFAULT_ON)
        val tipped = checkout(payment = giving.copy(askGiving = ReceiptTipping.DISABLED))
        assertThat(tipped.ceremony(Tender.LINK)).isEqualTo(TenderCeremony(setOf(DroppedOption.TIP), false, false))
        assertThat(tipped.ceremony(Tender.WALLET).drops).containsExactly(DroppedOption.TIP)
        assertThat(tipped.ceremony(Tender.TOKEN)).isEqualTo(TenderCeremony(setOf(DroppedOption.TIP), false, true))
        assertThat(tipped.ceremony(Tender.TERMINAL)).isEqualTo(TenderCeremony(emptySet(), false, false))

        // A sale inviting a donation loses it on a link, a wallet or a token too, which the operator confirms.
        val donating = checkout(payment = giving.copy(receiptTipping = ReceiptTipping.DISABLED))
        assertThat(donating.ceremony(Tender.LINK).drops).containsExactly(DroppedOption.DONATION)
        assertThat(
            donating
                .copy(linksAvailable = true)
                .linkStart(Instant.EPOCH, ZoneOffset.UTC)
                ?.payment
                ?.askGiving,
        ).isFalse()
        assertThat(donating.paymentStart(Instant.EPOCH, ZoneOffset.UTC)?.askGiving).isTrue()

        val moto = checkout(form = CheckoutForm(cardEntryOverride = CardEntryOverride.MOTO), payment = PaymentSettings(allowMoto = true))
        assertThat(moto.ceremony(Tender.TERMINAL)).isEqualTo(TenderCeremony(emptySet(), specialEntryWarning = true, managerApproval = true))
        assertThat(checkout().ceremony(Tender.TOKEN)).isEqualTo(TenderCeremony(emptySet(), false, true))
    }

    @Test
    fun `remaining tender uses stored sale facts without repeating card consent tips or shopper prompts`() {
        val sale =
            SaleEntity(
                id = "partial",
                createdAt = 1,
                currency = "AUD",
                taxMode = "INCLUSIVE",
                netMinor = 1000,
                taxMinor = 0,
                totalMinor = 1000,
                paidMinor = 400,
                status = SaleStatus.APPROVED,
                merchantReference = "ORIGINAL",
                customerReference = "CUSTOMER",
                shopperEmail = "shopper@example.invalid",
                shopperReference = "SHOPPER",
            )
        val start = checkNotNull(Checkout.remainingPayment(sale))
        assertThat(start.requestedAmountMinor).isEqualTo(600)
        assertThat(start.existingSaleId).isEqualTo(sale.id)
        assertThat(start.currency).isEqualTo(aud)
        assertThat(start.merchantReference).isEqualTo(sale.merchantReference)
        assertThat(start.customerReference).isEqualTo(sale.customerReference)
        assertThat(start.shopperEmail).isEqualTo(sale.shopperEmail)
        assertThat(start.shopperReference).isEqualTo(sale.shopperReference)
        assertThat(start.allowPartialAuthorisation).isTrue()
        assertThat(start.tokenization).isNull()
        assertThat(start.terminalTip).isNull()
        assertThat(start.shopperFlow).isNull()
        assertThat(start.tipOnReceipt).isFalse()
        assertThat(start.askGiving).isFalse()
        listOf(
            sale.copy(paidMinor = 1000),
            sale.copy(paidMinor = 0),
            sale.copy(kind = SaleKind.PRE_AUTHORISATION),
            sale.copy(tipOnReceipt = true),
            sale.copy(paymentLink = true),
            sale.copy(holdCancelled = true),
            sale.copy(status = SaleStatus.UNKNOWN),
        ).forEach { assertThat(Checkout.remainingPayment(it)).isNull() }
    }

    @Test
    fun `scanned wallet sales keep saving consent but remove receipt tipping without changing the form`() {
        val state =
            checkout(
                form = CheckoutForm(customerReference = "CUST-1", tokenize = true, tipOnReceipt = true),
                payment = PaymentSettings(offerCardSaving = true, receiptTipping = ReceiptTipping.DEFAULT_ON),
            ).copy(wallets = listOf(ScanWallet.WECHAT_PAY))
        val start = checkNotNull(state.walletStart(now, ZoneOffset.UTC))
        assertThat(start.manualCapture).isFalse()
        assertThat(start.tokenization).isNotNull()
        assertThat(state.form.tipOnReceipt).isTrue()
        assertThat(state.copy(kind = SaleKind.PRE_AUTHORISATION).walletStart(now, ZoneOffset.UTC)).isNull()
        assertThat(state.copy(wallets = emptyList()).canScanWallet).isFalse()
    }

    @Test
    fun `completing a payment clears only its originating session revision`() =
        runBlocking {
            val session = SaleSession()
            val checkouts = session.checkout(flowOf(AppSettings())) { aud }
            session.addProduct(ProductEntity(1, "Latte", 450, 1), TaxRateEntity(1, "GST", 10_000))
            val start = checkNotNull(session.paymentStart(checkouts.first(), now, ZoneOffset.UTC))
            assertThat(start.sessionRevision).isNotNull()
            session.updateForm { it.copy(email = "new@example.com") }
            session.complete(start)
            assertThat(session.cart.value.lines).hasSize(1)
            assertThat(session.form.value.email).isEqualTo("new@example.com")
            session.complete(checkNotNull(session.paymentStart(checkouts.first(), now, ZoneOffset.UTC)))
            assertThat(session.cart.value.lines).isEmpty()
        }

    @Test
    fun `session starts reject a stale displayed checkout for payments links and saved cards`() =
        runBlocking {
            val session = SaleSession()
            val terminal = flowOf(TerminalState(printerAvailable = true, paymentLinks = true))
            val settings =
                flowOf(
                    AppSettings(
                        payment = PaymentSettings(receiptTipping = ReceiptTipping.DEFAULT_ON),
                    ),
                )
            val checkouts = session.checkout(settings, terminal) { aud }
            val card = StoredCard("card-1", "visa", "1111", recurringModels = setOf("UnscheduledCardOnFile"))
            session.addProduct(ProductEntity(1, "Latte", 450, 1), TaxRateEntity(1, "GST", 10_000))
            session.updateForm { it.copy(customerReference = "CUST-1") }
            val displayed = checkouts.first()
            session.updateForm { it.copy(email = "new@example.com") }
            assertThat(session.paymentStart(displayed, now, ZoneOffset.UTC)).isNull()
            assertThat(session.linkStart(displayed, now, ZoneOffset.UTC)).isNull()
            assertThat(session.savedCardStart(displayed, card, now, ZoneOffset.UTC)).isNull()
            val current = checkouts.first()
            assertThat(current.tipOnReceipt).isTrue()
            val payment = checkNotNull(session.paymentStart(current, now, ZoneOffset.UTC))
            val link = checkNotNull(session.linkStart(current, now, ZoneOffset.UTC))
            val savedCard = checkNotNull(session.savedCardStart(current, card, now, ZoneOffset.UTC, fromCheckout = true))
            assertThat(payment.shopperEmail).isEqualTo("new@example.com")
            assertThat(link.payment.sessionRevision).isEqualTo(payment.sessionRevision)
            assertThat(savedCard.payment.manualCapture).isFalse()
            assertThat(savedCard.fromCheckout).isTrue()
            session.complete(link.payment)
            assertThat(session.cart.value.lines).isEmpty()
            assertThat(session.form.value).isEqualTo(CheckoutForm())
        }

    @Test
    fun `checkout follows pricing journal readiness without losing its session contents`() =
        runBlocking {
            val session = SaleSession()
            session.addProduct(ProductEntity(1, "Tea", 450, 1), TaxRateEntity(1, "GST", 10_000))
            session.updateForm { it.copy(email = "sam@example.com") }
            val settings = MutableStateFlow(AppSettings())
            val checkouts = session.checkout(settings, flowOf(TerminalState(paymentLinks = true))) { aud }
            settings.value = settings.value.copy(pricingChange = PricingChange("AUD", "JPY", PaymentSettings(), emptyMap()))
            val blocked = checkouts.first()
            assertThat(session.paymentStart(blocked, now, ZoneOffset.UTC)).isNull()
            assertThat(session.linkStart(blocked, now, ZoneOffset.UTC)).isNull()
            assertThat(session.cart.value.lines).hasSize(1)
            assertThat(session.form.value.email).isEqualTo("sam@example.com")
            settings.value = settings.value.copy(pricingChange = null)
            assertThat(session.paymentStart(checkouts.first(), now, ZoneOffset.UTC)).isNotNull()
        }

    @Test
    fun `unrelated starts never clear a session and each payment kind keeps its own snapshot`() =
        runBlocking {
            val sale = SaleSession()
            val preAuth = SaleSession(SaleKind.PRE_AUTHORISATION)
            listOf(sale, preAuth).forEach { it.addProduct(ProductEntity(1, "Tea", 450, 1), TaxRateEntity(1, "GST", 10_000)) }
            val checkout = preAuth.checkout(flowOf(AppSettings()), flowOf(TerminalState(paymentLinks = true))) { aud }.first()
            assertThat(sale.paymentStart(checkout, now, ZoneOffset.UTC)).isNull()
            assertThat(preAuth.linkStart(checkout, now, ZoneOffset.UTC)).isNull()
            val start = checkNotNull(preAuth.paymentStart(checkout, now, ZoneOffset.UTC))
            sale.complete(start)
            sale.complete(start.copy(kind = SaleKind.SALE, sessionRevision = null))
            assertThat(sale.cart.value.lines).hasSize(1)
            preAuth.complete(start)
            assertThat(preAuth.cart.value.lines).isEmpty()
        }

    @Test
    fun `a blank reference is generated with the prefix, and the card is saved under the customer reference`() {
        val start =
            checkout(
                CheckoutForm(customerReference = " CUST-1 ", email = "a@b.co", tokenize = true),
                PaymentSettings(referencePrefix = "MP", shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE),
            ).paymentStart(now, ZoneOffset.UTC)!!
        assertThat(start.merchantReference).matches("MP-260930-145811-[0-9A-Z]{4}")
        assertThat(start.customerReference).isEqualTo("CUST-1")
        assertThat(start.shopperEmail).isEqualTo("a@b.co")
        assertThat(start.shopperReference).isEqualTo("CUST-1")
        assertThat(start.tokenization).isEqualTo(TokenizationRequest(RecurringModel.UNSCHEDULED_CARD_ON_FILE))
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
        val customer =
            checkout(
                CheckoutForm(customerReference = "CUST-1"),
                PaymentSettings(offerCardSaving = false, shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE),
            )
        assertThat(customer.showCustomerReference).isTrue()
        assertThat(customer.canTokenize).isFalse()
        assertThat(customer.paymentStart(now, ZoneOffset.UTC)!!.shopperReference).isEqualTo("CUST-1")
    }

    @Test
    fun `invalid entries or an empty cart start nothing`() {
        assertThat(checkout(CheckoutForm(customerReference = "ab")).paymentStart(now, ZoneOffset.UTC)).isNull()
        assertThat(checkout(CheckoutForm(transactionReference = "x".repeat(Checkout.MAX_REFERENCE_LENGTH + 1))).canPay).isFalse()
        assertThat(checkout(CheckoutForm(email = "bad"), PaymentSettings(emailCapture = EmailCapture.BEFORE_PAYMENT)).canPay)
            .isFalse()
        assertThat(Checkout().paymentStart(now, ZoneOffset.UTC)).isNull()
    }

    @Test
    fun `receipt tipping has three modes and disabled ignores an earlier operator choice`() {
        ReceiptTipping.entries.forEach { mode ->
            val checkout = checkout(payment = PaymentSettings(receiptTipping = mode))
            assertThat(checkout.canTipOnReceipt).isEqualTo(mode != ReceiptTipping.DISABLED)
            assertThat(checkout.tipOnReceipt).isEqualTo(mode == ReceiptTipping.DEFAULT_ON)
            assertThat(checkout.copy(form = CheckoutForm(tipOnReceipt = true)).tipOnReceipt)
                .isEqualTo(mode != ReceiptTipping.DISABLED)
            assertThat(checkout.copy(form = CheckoutForm(tipOnReceipt = false)).tipOnReceipt).isFalse()
            assertThat(checkout.copy(printerAvailable = false).canTipOnReceipt).isFalse()
            assertThat(checkout.copy(kind = SaleKind.PRE_AUTHORISATION).canTipOnReceipt).isFalse()
        }
        assertThat(checkout(payment = PaymentSettings()).canTipOnReceipt).isFalse()
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
        val links =
            Checkout(
                CheckoutForm(customerReference = "CUST-1"),
                PaymentSettings(shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE),
                totals,
                aud,
                linksAvailable = true,
            )
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
                PaymentSettings(linkExpiryHours = 48, shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE),
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
            val settings =
                MutableStateFlow(
                    AppSettings(
                        payment =
                            PaymentSettings(
                                taxMode = TaxMode.EXCLUSIVE,
                                shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE,
                            ),
                    ),
                )
            val checkouts = session.checkout(settings) { CurrencySpec.of("AUD") }
            assertThat(checkouts.first().canPay).isFalse()
            session.addProduct(ProductEntity(1, "Tea", 400, 1), TaxRateEntity(1, "GST", 10_000))
            session.updateForm { it.copy(customerReference = "CUST-9") }
            val ready = checkouts.first()
            assertThat(ready.totals.amounts.gross).isEqualTo(440)
            assertThat(ready.customerReference).isEqualTo("CUST-9")
            assertThat(ready.printerAvailable).isFalse()
            assertThat(ready.linksAvailable).isFalse()
            assertThat(ready.kind).isEqualTo(SaleKind.SALE)
            assertThat(
                session
                    .checkout(settings, flowOf(TerminalState(paymentLinks = true))) { CurrencySpec.of("AUD") }
                    .first()
                    .canSendLink,
            ).isTrue()
        }

    @Test
    fun `terminal tipping supports dynamic basket thresholds pre-tax amounts and mutual exclusion with receipt tipping`() {
        val smallCart =
            Cart()
                .addProduct(CartProduct(1, "Coffee", null, 600, AppliedTax("GST", 10_000))) { "c1" }
                .totals(TaxMode.INCLUSIVE, chargeTax = true)
        val largeCart =
            Cart()
                .addProduct(CartProduct(2, "Meal", null, 11_000, AppliedTax("GST", 10_000))) { "c2" }
                .totals(TaxMode.INCLUSIVE, chargeTax = true)
        val settings =
            PaymentSettings(
                terminalTipping = ReceiptTipping.DEFAULT_ON,
                receiptTipping = ReceiptTipping.DEFAULT_ON,
                dynamicTipOverride = true,
                lowBasketThresholdMinor = 1_000,
                fixedTipAmountsMinor = listOf(100, 200, 300),
                tipPercentages = listOf(10, 15, 20),
                tipOnPreTax = true,
            )
        val smallCheckout = Checkout(CheckoutForm(), settings, smallCart, aud, printerAvailable = true)
        assertThat(smallCheckout.askGratuity).isTrue()
        assertThat(smallCheckout.tipOnReceipt).isFalse()
        assertThat(smallCheckout.terminalTipOptions()).isEqualTo(
            TerminalTipOptions(
                suggestions = TerminalTipSuggestions.FixedAmounts(listOf(100, 200, 300), allowCustom = true),
                amountToTipOnMinor = smallCart.amounts.net,
            ),
        )

        val largeCheckout = Checkout(CheckoutForm(), settings, largeCart, aud, printerAvailable = true)
        assertThat(largeCheckout.terminalTipOptions()).isEqualTo(
            TerminalTipOptions(
                suggestions = TerminalTipSuggestions.Percentages(listOf(10, 15, 20), allowCustom = true),
                amountToTipOnMinor = 10_000,
            ),
        )

        val switchedToReceipt = largeCheckout.copy(form = CheckoutForm(tipOnReceipt = true, askGratuity = false))
        assertThat(switchedToReceipt.tipOnReceipt).isTrue()
        assertThat(switchedToReceipt.askGratuity).isFalse()
        assertThat(switchedToReceipt.terminalTipOptions()).isNull()
    }

    @Test
    fun `the terminal asks for an email only when the settings allow it and none was entered`() {
        val texts = ShopperPromptTexts("Order", "Total", "Confirm", "OK", "Cancel", "Your email", "Rate us")
        val checkout =
            Checkout(
                payment = PaymentSettings(collectEmailOnTerminal = true),
                shopperDisplayAvailable = true,
                shopperTexts = texts,
            )
        // The switch alone does not ask: emailed receipts must be ready (AppSettings.asksEmailOnTerminal).
        assertThat(checkout.shopperSessionFlow("REF")?.emailPrompt).isNull()
        assertThat(checkout.copy(emailOnTerminal = true).shopperSessionFlow("REF")?.emailPrompt).isEqualTo("Your email")
        val entered = checkout.copy(emailOnTerminal = true, form = CheckoutForm(email = "shopper@example.com"))
        assertThat(entered.shopperSessionFlow("REF")?.emailPrompt).isNull()
    }
}
