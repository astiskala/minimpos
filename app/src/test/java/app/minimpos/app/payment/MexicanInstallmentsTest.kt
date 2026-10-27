package app.minimpos.app.payment

import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.mexicanPlan
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.client.MexicanPayment
import app.minimpos.terminal.simulator.SimulatedOutcome
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class MexicanInstallmentsTest {
    @get:Rule
    val env = TestEnvironment()

    private fun request(choice: MexicanPayment): PaymentStart =
        await {
            env.container.walletDiscovery.refresh()
            val available = env.container.walletDiscovery.state.value
            checkNotNull(
                Checkout(
                    form = CheckoutForm(mexicanPayment = choice),
                    payment = PaymentSettings(mexicanInstallments = true, allowPartialAuthorisation = false),
                    totals = Cart().addCustom("Item", 1200, AppliedTax("Zero", 0), "item").totals(TaxMode.INCLUSIVE),
                    currency = CurrencySpec.of("MXN"),
                    mexicanRoutingAvailable = available.mexicanRouting,
                    mexicanContext = available.context,
                    mexicanIdentity = available.identity,
                ).paymentStart(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC),
            )
        }

    @Test
    fun `a refused installment preserves returned plan facts without recording money collected`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.DECLINE)) }
        val id = checkNotNull(env.container.payments.start(request(MexicanPayment.MONTHS_6)))
        await {
            env.container.payments.state
                .first { it == TransactionState.Finished(id) }
        }
        val record = await { checkNotNull(env.container.sales.get(id)) }
        assertThat(record.sale.status).isEqualTo(SaleStatus.DECLINED)
        assertThat(record.sale.paidMinor).isEqualTo(0)
        val tender = record.tenders.single()
        assertThat(tender.mexicanPayment).isEqualTo("MONTHS_6")
        assertThat(tender.returnedPaymentType).isEqualTo("Instalment")
        assertThat(tender.returnedInstallments).isEqualTo("6")
        assertThat(tender.authorisedMinor).isNull()
        assertThat(env.context.mexicanPlan(tender).last()).contains("6")
    }

    @Test
    fun `request is durable on its tender before sending and recovered answer stays separate`() {
        env.useSimulator()
        val sales = env.container.sales
        val book = SaleBook(sales)
        val start = request(MexicanPayment.MONTHS_9)
        await { assertThat(book.open("mx", start, "MX9", 100)).isTrue() }
        val pending = await { checkNotNull(sales.get("mx")).tenders.single() }
        assertThat(pending.status).isEqualTo(SaleStatus.PENDING)
        assertThat(pending.mexicanPayment).isEqualTo("MONTHS_9")
        assertThat(pending.returnedInstallments).isNull()
        assertThat(env.context.mexicanPlan(pending).last()).contains(env.context.getString(R.string.mexican_unreported))
        val operation = book.operation(start) as TerminalOperation.Pay
        assertThat(operation.params.mexicanPayment).isEqualTo(MexicanPayment.MONTHS_9)
        assertThat(operation.expected).isEqualTo(start.walletContext)
        assertThat(operation.setupIdentity).isEqualTo(start.walletSetupIdentity)
    }

    @Test
    fun `restart rechecks the original tender without a new payment and preserves plan through setup mismatch`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.TIMEOUT)) }
        val start = request(MexicanPayment.MONTHS_12)
        val book = SaleBook(env.container.sales)
        val unanswered =
            object : TransactionBook<PaymentStart> by book {
                override suspend fun settle(
                    id: String,
                    settlement: Settlement,
                ): Boolean = book.settle(id, settlement.copy(status = SettlementStatus.UNKNOWN, details = null))
            }
        val first = TransactionLifecycle(env.scope, env.container.gateway, unanswered, newId = { "recover-mx" })
        val id = checkNotNull(first.start(start))
        await { first.state.first { it == TransactionState.Finished(id) } }
        val original = await { checkNotNull(env.container.sales.get(id)).tenders.single() }
        assertThat(original.status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(original.mexicanPayment).isEqualTo("MONTHS_12")
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD)) }
        val recreated = TransactionLifecycle(env.scope, env.container.gateway, book)
        await { recreated.recheck(id) }
        assertThat(
            await {
                env.container.sales
                    .get(id)!!
                    .tenders
                    .single()
                    .status
            },
        ).isEqualTo(SaleStatus.UNKNOWN)
        env.useSimulator()
        await { assertThat(recreated.recheck(id)).isEqualTo(Recheck.Settled) }
        val settled = await { checkNotNull(env.container.sales.get(id)).tenders.single() }
        assertThat(settled.serviceId).isEqualTo(original.serviceId)
        assertThat(settled.context).isEqualTo(original.context)
        assertThat(settled.mexicanPayment).isEqualTo("MONTHS_12")
        assertThat(settled.returnedInstallments).isEqualTo("12")
        assertThat(settled.returnedPaymentType).isEqualTo("Instalment")
        assertThat(settled.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(env.context.mexicanPlan(settled).last()).contains("12")
    }

    @Test
    fun `changed discovery identity refuses sending instead of dropping the selected plan`() {
        env.useSimulator()
        val start = request(MexicanPayment.DEBIT).copy(walletSetupIdentity = "stale")
        val id = checkNotNull(env.container.payments.start(start))
        await {
            env.container.payments.state
                .first { it == TransactionState.Finished(id) }
        }
        val record = await { checkNotNull(env.container.sales.get(id)) }
        assertThat(record.sale.status).isEqualTo(SaleStatus.FAILED)
        assertThat(record.tenders.single().mexicanPayment).isEqualTo("DEBIT")
        assertThat(record.sale.poiTransactionId).isNull()
    }
}
