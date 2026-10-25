package app.minimpos.app.payment

import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.TenderKind
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.TenderBalance
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.feature.mealVoucherFacts
import app.minimpos.app.history.RefundInvalidReason
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartProduct
import app.minimpos.core.cart.CartTotals
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.voucher.MealVoucherScheme
import app.minimpos.terminal.simulator.SimulatedOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class MealVouchersTest {
    @get:Rule
    val env = TestEnvironment()

    private val monday = LocalDate.of(2026, 10, 26)
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val zero = AppliedTax("Zero", 0)

    private fun totals(
        eligibleMinor: Long,
        customMinor: Long = 0,
    ): CartTotals {
        var cart = Cart().addProduct(CartProduct(1, "Meal", null, eligibleMinor, zero, mealVoucherEligible = true)) { "meal" }
        if (customMinor > 0) cart = cart.addCustom("Custom", customMinor, zero, "custom")
        return cart.totals(TaxMode.INCLUSIVE)
    }

    private fun availability(accountSelection: Boolean = true): MealVoucherAvailability =
        await {
            env.container.walletDiscovery.refresh()
            val discovered = env.container.walletDiscovery.state.value
            MealVoucherAvailability(
                configured = true,
                schemes = discovered.mealVoucherSchemes("EUR") + discovered.mealVoucherSchemes("BRL"),
                context = discovered.context,
                identity = discovered.identity,
                simulated = discovered.simulated,
                accountSelection = accountSelection,
            )
        }

    private fun checkout(
        totals: CartTotals,
        currency: String,
    ) = Checkout(
        payment = PaymentSettings(mealVouchers = true, mealVoucherAccountSelection = true),
        totals = totals,
        currency = CurrencySpec.of(currency),
    )

    private fun voucherStart(
        totals: CartTotals,
        currency: String,
        scheme: MealVoucherScheme,
        amountMinor: Long,
        available: MealVoucherAvailability = availability(),
    ): PaymentStart? {
        val checkout = checkout(totals, currency)
        val reading = Checkout.mealVoucher(available, scheme, SaleBook.splitBalance(totals, false), monday, null)
        return Checkout.mealVoucherStart(checkout.splitStart(now, ZoneOffset.UTC, amountMinor, null), reading, available, amountMinor)
    }

    private fun finish(start: PaymentStart): String {
        val id = checkNotNull(env.container.payments.start(start))
        await {
            env.container.payments.state
                .first { it == TransactionState.Finished(id) }
        }
        return id
    }

    @Test
    fun `an app-selected Brazilian voucher stores its request before sending and its reported facts after`() {
        env.useSimulator()
        val start = checkNotNull(voucherStart(totals(3_000), "BRL", MealVoucherScheme.BRAZIL_PAT, 3_000))
        assertThat(start.allowPartialAuthorisation).isFalse()
        val book = SaleBook(env.container.sales)
        val operation = book.operation(start) as TerminalOperation.Pay
        assertThat(operation.params.mealVoucherAccount).isTrue()
        assertThat(operation.expected).isEqualTo(start.walletContext)
        await { assertThat(book.open("brv", start, "BRV", 100)).isTrue() }
        val pending = await { checkNotNull(env.container.sales.get("brv")).tenders.single() }
        assertThat(pending.status).isEqualTo(SaleStatus.PENDING)
        assertThat(pending.mealVoucher).isEqualTo("BRAZIL_PAT")
        assertThat(pending.fundingSource).isNull()

        val id = finish(start)
        val record = await { checkNotNull(env.container.sales.get(id)) }
        val tender = record.tenders.single()
        assertThat(record.sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(tender.mealVoucher).isEqualTo("BRAZIL_PAT")
        assertThat(tender.fundingSource).isEqualTo("PREPAID")
        assertThat(tender.paymentMethodVariant).isEqualTo("elo_voucher_br")
        val facts = env.context.mealVoucherFacts(tender)
        assertThat(facts.first()).contains(env.context.getString(R.string.voucher_scheme_brazil))
        assertThat(facts.last()).contains("elo_voucher_br · PREPAID")
        val refundable = RefundablePayment.check(record) as Refundability.Refundable
        assertThat(refundable.payment.remainingMinor).isEqualTo(3_000)
        assertThat(refundable.payment.legs.none { it.cash }).isTrue()
    }

    @Test
    fun `a mixed European basket pays only eligible items by voucher, keeps a partial approval and the rest goes elsewhere`() {
        env.useSimulator()
        val basket = totals(eligibleMinor = 2_139, customMinor = 1_000)
        assertThat(voucherStart(basket, "EUR", MealVoucherScheme.EUROPE_CARD_SCHEME, 3_139)).isNull()
        assertThat(voucherStart(basket, "EUR", MealVoucherScheme.EUROPE_CARD_SCHEME, 2_140)).isNull()
        val start = checkNotNull(voucherStart(basket, "EUR", MealVoucherScheme.EUROPE_CARD_SCHEME, 2_139))
        assertThat(start.allowPartialAuthorisation).isTrue()
        val id = finish(start)
        val record = await { checkNotNull(env.container.sales.get(id)) }
        assertThat(record.sale.isPartiallyPaid).isTrue()
        val voucher = record.tenders.single()
        assertThat(voucher.mealVoucher).isEqualTo("EUROPE_CARD_SCHEME")
        assertThat(voucher.authorisedMinor).isEqualTo(1_070)
        assertThat(record.lines.single { it.productId == 1L }.mealVoucherEligible).isTrue()
        assertThat(record.lines.single { it.productId == null }.mealVoucherEligible).isFalse()

        val balance = TenderBalance.of(record.sale, record.lines, record.tenders)
        assertThat(balance.eligibleMinor).isEqualTo(2_139)
        assertThat(balance.voucherMinor).isEqualTo(1_070)
        assertThat(balance.voucherLimitMinor(MealVoucherScheme.EUROPE_CARD_SCHEME)).isEqualTo(1_069)
        // The stored check refuses more voucher value than the eligible items still allow.
        val excessive = SaleEvent.TenderOpened("MORE", 1_070, record.sale.paidMinor)
        await {
            assertThat(
                env.container.sales.openTender(id, excessive, null, 200, mealVoucher = MealVoucherScheme.EUROPE_CARD_SCHEME),
            ).isFalse()
        }
        val remaining = record.sale.remainingMinor
        await { assertThat(env.container.cashTenders.payExisting(id, remaining, null, remaining, record.sale.paidMinor)).isTrue() }
        val paid = await { checkNotNull(env.container.sales.get(id)) }
        assertThat(paid.sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(paid.tenders.map { it.kind to it.mealVoucher }).containsExactly(
            TenderKind.CARD to "EUROPE_CARD_SCHEME",
            TenderKind.CASH to null,
        )
        // Voucher value returns to the voucher; cash returns only what cash paid.
        val refundable = RefundablePayment.check(paid) as Refundability.Refundable
        val legs = refundable.payment.legs
        assertThat(legs.map { it.cash to it.remainingMinor }).containsExactly(false to 1_070L, true to remaining).inOrder()
    }

    @Test
    fun `Titres-restaurant are limited, closed on Sundays and never refunded through the app`() {
        env.useSimulator()
        val basket = totals(eligibleMinor = 4_000, customMinor = 500)
        val available = availability()
        val balance = SaleBook.splitBalance(basket, false)
        val sunday = Checkout.mealVoucher(available, MealVoucherScheme.FRANCE_TITRES_RESTAURANT, balance, monday.minusDays(1), null)
        assertThat(sunday.blocked).isEqualTo(MealVoucherBlock.CLOSED_TODAY)
        val reading = Checkout.mealVoucher(available, MealVoucherScheme.FRANCE_TITRES_RESTAURANT, balance, monday, null)
        assertThat(reading.blocked).isNull()
        assertThat(reading.limitMinor).isEqualTo(2_500)
        assertThat(voucherStart(basket, "EUR", MealVoucherScheme.FRANCE_TITRES_RESTAURANT, 2_501)).isNull()
        val start = checkNotNull(voucherStart(basket, "EUR", MealVoucherScheme.FRANCE_TITRES_RESTAURANT, 2_500))
        assertThat(start.allowPartialAuthorisation).isFalse()
        val id = finish(start)
        val record = await { checkNotNull(env.container.sales.get(id)) }
        assertThat(record.tenders.single().authorisedMinor).isEqualTo(2_500)
        assertThat(env.context.mealVoucherFacts(record.tenders.single()))
            .contains(env.context.getString(R.string.voucher_not_refundable))
        assertThat((RefundablePayment.check(record) as Refundability.NotRefundable).reason)
            .isEqualTo(RefundInvalidReason.VOUCHER_NOT_REFUNDABLE)
        val remaining = record.sale.remainingMinor
        await { assertThat(env.container.cashTenders.payExisting(id, remaining, null, remaining, record.sale.paidMinor)).isTrue() }
        val paid = await { checkNotNull(env.container.sales.get(id)) }
        val refundable = RefundablePayment.check(paid) as Refundability.Refundable
        // Only the cash tender can be returned: voucher value is never converted into cash.
        assertThat(refundable.payment.remainingMinor).isEqualTo(remaining)
        assertThat(refundable.payment.legs.map { it.cash }).containsExactly(true)
        assertThat(paid.tenders.filter(RefundablePayment.Companion::keepsVoucherValue).map { it.authorisedMinor }).containsExactly(2_500L)
    }

    @Test
    fun `unavailable vouchers are explained before payment in order`() {
        env.useSimulator()
        val basket = totals(1_000)
        val balance = SaleBook.splitBalance(basket, false)
        val available = availability()

        fun block(
            availability: MealVoucherAvailability,
            scheme: MealVoucherScheme? = MealVoucherScheme.EUROPE_CARD_SCHEME,
            context: PaymentContext? = null,
            totals: CartTotals = basket,
        ) = Checkout.mealVoucher(availability, scheme, SaleBook.splitBalance(totals, false), monday, context).blocked
        assertThat(block(available.copy(configured = false))).isEqualTo(MealVoucherBlock.NOT_CONFIGURED)
        assertThat(block(available.copy(schemes = emptyList()))).isEqualTo(MealVoucherBlock.NO_MARKET)
        assertThat(block(available.copy(identity = null))).isEqualTo(MealVoucherBlock.NO_MARKET)
        assertThat(
            block(available.copy(accountSelection = false), MealVoucherScheme.BRAZIL_PAT),
        ).isEqualTo(MealVoucherBlock.TERMINAL_SELECTS)
        assertThat(
            block(available, context = checkNotNull(available.context).copy(poiId = "OTHER")),
        ).isEqualTo(MealVoucherBlock.CHANGED_SETUP)
        assertThat(block(available, totals = Cart().addCustom("Custom", 900, zero, "c").totals(TaxMode.INCLUSIVE)))
            .isEqualTo(MealVoucherBlock.NOTHING_ELIGIBLE)
        assertThat(Checkout.mealVoucher(available, null, balance, monday, null).blocked).isNull()
        // Real setups use only the verified store country's market; the simulator offers a demo choice by currency.
        assertThat(available.simulated).isTrue()
        assertThat(
            env.container.walletDiscovery.state.value
                .copy(simulated = false, storeCountry = "CH")
                .mealVoucherSchemes("EUR"),
        ).isEmpty()
        assertThat(
            env.container.walletDiscovery.state.value
                .copy(simulated = false, storeCountry = "ES")
                .mealVoucherSchemes("EUR"),
        ).containsExactly(MealVoucherScheme.EUROPE_CARD_SCHEME)
        assertThat(
            env.container.walletDiscovery.state.value
                .copy(simulated = false, storeCountry = "ES")
                .mealVoucherSchemes("BRL"),
        ).isEmpty()
    }

    @Test
    fun `an unknown voucher result is recovered with its original identity and request facts`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.TIMEOUT)) }
        val start = checkNotNull(voucherStart(totals(1_500), "BRL", MealVoucherScheme.BRAZIL_PAT, 1_500))
        val book = SaleBook(env.container.sales)
        val unanswered =
            object : TransactionBook<PaymentStart> by book {
                override suspend fun settle(
                    id: String,
                    settlement: Settlement,
                ): Boolean = book.settle(id, settlement.copy(status = SettlementStatus.UNKNOWN, details = null))
            }
        val first = TransactionLifecycle(env.scope, env.container.gateway, unanswered, newId = { "recover-voucher" })
        val id = checkNotNull(first.start(start))
        await { first.state.first { it == TransactionState.Finished(id) } }
        val original = await { checkNotNull(env.container.sales.get(id)).tenders.single() }
        assertThat(original.status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(original.mealVoucher).isEqualTo("BRAZIL_PAT")
        val recreated = TransactionLifecycle(env.scope, env.container.gateway, book)
        await { assertThat(recreated.recheck(id)).isEqualTo(Recheck.Settled) }
        val settled = await { checkNotNull(env.container.sales.get(id)).tenders.single() }
        assertThat(settled.serviceId).isEqualTo(original.serviceId)
        assertThat(settled.context).isEqualTo(original.context)
        assertThat(settled.mealVoucher).isEqualTo("BRAZIL_PAT")
        assertThat(settled.fundingSource).isEqualTo("PREPAID")
        assertThat(settled.status).isEqualTo(SaleStatus.APPROVED)
    }

    @Test
    fun `a declined voucher or changed setup collects nothing and keeps the selected voucher`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.DECLINE)) }
        val declined = finish(checkNotNull(voucherStart(totals(1_200), "BRL", MealVoucherScheme.BRAZIL_PAT, 1_200)))
        val refused = await { checkNotNull(env.container.sales.get(declined)) }
        assertThat(refused.sale.status).isEqualTo(SaleStatus.DECLINED)
        assertThat(refused.sale.paidMinor).isEqualTo(0)
        assertThat(refused.tenders.single().mealVoucher).isEqualTo("BRAZIL_PAT")

        env.useSimulator()
        val stale =
            checkNotNull(
                voucherStart(totals(1_200), "BRL", MealVoucherScheme.BRAZIL_PAT, 1_200),
            ).copy(walletSetupIdentity = "stale")
        val id = finish(stale)
        val record = await { checkNotNull(env.container.sales.get(id)) }
        assertThat(record.sale.status).isEqualTo(SaleStatus.FAILED)
        assertThat(record.tenders.single().mealVoucher).isEqualTo("BRAZIL_PAT")
        assertThat(record.sale.poiTransactionId).isNull()
    }
}
