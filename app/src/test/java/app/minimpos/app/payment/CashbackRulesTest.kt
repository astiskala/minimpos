package app.minimpos.app.payment

import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.settings.CashbackCard
import app.minimpos.app.data.settings.CashbackSettings
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardProvider
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.voucher.MealVoucherScheme
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.MexicanPayment
import app.minimpos.terminal.client.RecurringModel
import app.minimpos.terminal.client.ScannedPayment
import app.minimpos.terminal.client.TerminalTipOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class CashbackRulesTest {
    private val context = PaymentContext("TERMINAL", "AMS1-1", "POS", "Merchant", "TEST")
    private val facts =
        WalletAvailability(
            context = context,
            identity = "checked",
            cashbackDestination = true,
            checked = true,
            storeId = "ST1",
            storeCountry = "AU",
        )
    private val configured =
        CashbackSettings(
            enabled = true,
            standalone = true,
            card = CashbackCard.EFTPOS,
            limitMinor = 5000,
            context = context,
            currency = "AUD",
            storeId = "ST1",
            storeCountry = "AU",
        )
    private val totals = Cart().addCustom("Goods", 1000, AppliedTax("Tax", 10_000), "goods").totals(TaxMode.INCLUSIVE)
    private val checkout =
        Checkout(
            form = CheckoutForm(cashBackMinor = 500, cashbackConfirmation = configured),
            payment = PaymentSettings(cashback = configured, allowPartialAuthorisation = false),
            totals = totals,
            currency = CurrencySpec.of("AUD"),
            cashbackAvailable = true,
            managerConfigured = true,
            mexicanContext = context,
            mexicanIdentity = "checked",
        )

    @Test
    fun `cash request constructors reject every unsupported financial combination even when UI is bypassed`() {
        val start = checkNotNull(checkout.paymentStart(Instant.EPOCH, ZoneOffset.UTC))
        val gift = checkNotNull(PresentedGiftCard.of(GiftCardSetting(GiftCardProvider.SVS), GiftCardEntry.SWIPED))
        val invalid =
            listOf<() -> PaymentStart>(
                { start.copy(cashBackMinor = 0) },
                { start.copy(cashBackMinor = -1) },
                { start.copy(cashbackConfiguration = null) },
                { start.copy(kind = SaleKind.PRE_AUTHORISATION) },
                { start.copy(tipOnReceipt = true) },
                { start.copy(terminalTip = TerminalTipOptions()) },
                { start.copy(askGiving = true) },
                { start.copy(audioGuidance = true) },
                { start.copy(allowPartialAuthorisation = true) },
                { start.copy(tokenization = TokenizationRequest(RecurringModel.CARD_ON_FILE), shopperReference = "buyer") },
                { start.copy(scannedPayment = ScannedPayment("payme", "123456")) },
                { start.copy(giftCard = gift) },
                { start.copy(mealVoucher = MealVoucherScheme.BRAZIL_PAT) },
                { start.copy(mexicanPayment = MexicanPayment.DEBIT) },
                { start.copy(existingSaleId = "earlier") },
                { start.copy(allocation = TenderAllocation.Share(0, 2, 1000)) },
                { start.copy(cardEntryOverride = CardEntryOverride.MOTO) },
                { start.copy(walletContext = null) },
                { start.copy(walletSetupIdentity = null) },
                { start.copy(requestedAmountMinor = 1000) },
            )
        invalid.forEach { build -> assertThrows(IllegalArgumentException::class.java) { build() } }
        assertThat(start.amount.amountMinor).isEqualTo(1500)
        assertThat(checkout.requestedCardMinor).isEqualTo(1500)
        assertThat(checkout.copy(form = checkout.form.copy(cashBackMinor = -1)).requestedCardMinor).isEqualTo(1000)
        assertThat(checkout.copy(form = checkout.form.copy(cashBackMinor = Long.MAX_VALUE)).requestedCardMinor).isEqualTo(1000)
    }

    @Test
    fun `explicit enablement never overrides documented card geography or currency and simulator facts remain demonstrations`() {
        val nz = facts.copy(storeCountry = "NZ")
        assertThat(Cashback.ready(configured.copy(storeCountry = "NZ"), nz, "AUD", false)).isFalse()
        assertThat(Cashback.ready(configured.copy(currency = "EUR"), facts, "EUR", false)).isFalse()
        val german = configured.copy(card = CashbackCard.GIROCARD, storeCountry = "DE", currency = "CHF")
        assertThat(Cashback.ready(german, facts.copy(storeCountry = "DE"), "CHF", false)).isFalse()
        val us = configured.copy(card = CashbackCard.VISA, storeCountry = "US")
        assertThat(Cashback.ready(us, facts.copy(storeCountry = "US"), "AUD", false)).isFalse()
        val demo = facts.copy(simulated = true, storeId = null, storeCountry = null)
        assertThat(Cashback.ready(configured, demo, "AUD", true)).isTrue()
        val giro = configured.copy(card = CashbackCard.GIROCARD, currency = "EUR")
        assertThat(Cashback.ready(giro, demo, "EUR", false)).isTrue()
        val visa = configured.copy(card = CashbackCard.VISA)
        assertThat(Cashback.ready(visa, demo, "AUD", false)).isTrue()
        assertThat(
            Cashback.ready(configured, facts.copy(problem = SetupProblem.MANAGER_APPROVAL), "AUD", false),
        ).isFalse()
    }

    @Test
    fun `store or program changes need new enablement and currency changes cannot inherit a cash limit`() {
        val same = configured.confirmedFor(context, "ST1", "AU", "AUD")
        assertThat(same).isEqualTo(configured)
        val moved = configured.confirmedFor(context, "ST2", "AU", "AUD")
        assertThat(moved.enabled).isFalse()
        assertThat(moved.standalone).isFalse()
        val currency = configured.confirmedFor(context, "ST1", "AU", "EUR")
        assertThat(currency.limitMinor).isEqualTo(0)
        assertThat(currency.enabled).isFalse()
        assertThat(configured.selecting(CashbackCard.EFTPOS)).isEqualTo(configured)
        assertThat(configured.selecting(CashbackCard.VISA).enabled).isFalse()
        assertThat(configured.selecting(CashbackCard.VISA).standalone).isFalse()
    }

    @Test
    fun `send-time bounds need the configured program currency and original context`() {
        assertThat(configured.permits(500, 1500, "AUD", context)).isTrue()
        assertThat(configured.permits(500, 500, "AUD", context)).isTrue()
        listOf(
            configured.copy(enabled = false),
            configured.copy(card = null),
            configured.copy(limitMinor = 499),
            configured.copy(currency = "EUR"),
            configured.copy(context = context.copy(merchantAccount = "Other")),
        ).forEach { assertThat(it.permits(500, 1500, "AUD", context)).isFalse() }
        assertThat(configured.copy(standalone = false).permits(500, 500, "AUD", context)).isFalse()
        assertThat(configured.copy(card = CashbackCard.VISA).permits(500, 500, "AUD", context)).isFalse()
    }

    @Test
    fun `market table distinguishes purchase and standalone and needs exact verified store confirmations`() {
        assertThat(Cashback.ready(CashbackSettings(), facts, "AUD", false)).isFalse()
        assertThat(Cashback.ready(configured, facts, "AUD", false)).isTrue()
        assertThat(Cashback.ready(configured.copy(enabled = false), facts, "AUD", true)).isTrue()
        assertThat(Cashback.ready(configured.copy(standalone = false), facts, "AUD", true)).isFalse()
        listOf(
            facts.copy(storeId = null),
            facts.copy(storeId = "OTHER"),
            facts.copy(storeCountry = "NZ"),
            facts.copy(checked = false),
            facts.copy(identity = null),
            facts.copy(context = null),
            facts.copy(cashbackDestination = false),
            facts.copy(context = context.copy(merchantAccount = "Other")),
        ).forEach { assertThat(Cashback.ready(configured, it, "AUD", false)).isFalse() }
        assertThat(Cashback.ready(configured, facts, "EUR", false)).isFalse()
        assertThat(Cashback.ready(configured.copy(limitMinor = 0), facts, "AUD", false)).isFalse()
        assertThat(Cashback.ready(configured.copy(card = null), facts, "AUD", false)).isFalse()
        listOf(CashbackCard.GIROCARD, CashbackCard.VISA, CashbackCard.MASTERCARD).forEach { card ->
            val european = configured.copy(card = card, currency = "EUR", storeCountry = "DE")
            assertThat(Cashback.ready(european, facts.copy(storeCountry = "DE"), "EUR", false)).isTrue()
            assertThat(Cashback.ready(european, facts.copy(storeCountry = "DE"), "EUR", true)).isFalse()
            assertThat(Cashback.ready(european, facts.copy(storeCountry = "US"), "EUR", false)).isFalse()
        }
    }

    @Test
    fun `checkout preserves selected cash and blocks every incompatible tender without silently discarding it`() {
        val now = Instant.parse("2026-10-26T10:00:00Z")
        assertThat(checkout.canPay).isTrue()
        val start = checkNotNull(checkout.paymentStart(now, ZoneOffset.UTC))
        assertThat(start.requestedAmountMinor).isEqualTo(1500)
        assertThat(start.totals.amounts.gross).isEqualTo(1000)
        assertThat(start.cashBackMinor).isEqualTo(500)
        assertThat(checkout.ceremony(Tender.TERMINAL).managerApproval).isTrue()
        assertThat(checkout.canSplit).isFalse()
        assertThat(checkout.canSendLink).isFalse()
        assertThat(checkout.offersScanWallet).isFalse()
        listOf(
            checkout.copy(managerConfigured = false),
            checkout.copy(cashbackAvailable = false),
            checkout.copy(kind = SaleKind.PRE_AUTHORISATION),
            checkout.copy(form = checkout.form.copy(cashBackMinor = 5001)),
            checkout.copy(form = checkout.form.copy(cashBackMinor = -1)),
            checkout.copy(form = checkout.form.copy(cashbackConfirmation = null)),
            checkout.copy(form = checkout.form.copy(audioGuidance = true)),
            checkout.copy(form = checkout.form.copy(mexicanPayment = MexicanPayment.DEBIT)),
            checkout.copy(form = checkout.form.copy(cardEntryOverride = CardEntryOverride.MOTO)),
            checkout.copy(form = checkout.form.copy(askGiving = true)),
            checkout.copy(form = checkout.form.copy(askGratuity = true)),
            checkout.copy(form = checkout.form.copy(tipOnReceipt = true)),
            checkout.copy(form = checkout.form.copy(tokenize = true)),
            checkout.copy(payment = checkout.payment.copy(allowPartialAuthorisation = true)),
        ).forEach { blocked ->
            assertThat(blocked.canPay).isFalse()
            assertThat(blocked.paymentStart(now, ZoneOffset.UTC)).isNull()
            assertThat(blocked.form.cashBackMinor).isNotEqualTo(0)
        }
    }
}
