package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.cashAwaitingHandover
import app.minimpos.app.data.repo.retentionEligible
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.data.security.SessionLock
import app.minimpos.app.data.settings.CashbackCard
import app.minimpos.app.data.settings.CashbackSettings
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.RefundChoice
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.client.TerminalCurrencyTotals
import app.minimpos.terminal.client.TerminalTotalsOutcome
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
class CashbackPaymentsTest {
    @get:Rule val env = TestEnvironment()
    private val container get() = env.container
    internal val book get() = SaleBook(container.sales)
    private var time = 1000L
    private var configured = true
    private val lock = SessionLock { time }.apply { unlock() }
    private val approval = ManagerApproval({ configured }, lock) { 60_000 }

    private fun start(purchase: Long = 1000): PaymentStart {
        env.useSimulator()
        await { container.walletDiscovery.refresh() }
        val facts = container.walletDiscovery.state.value
        env.updateSettings {
            it.copy(
                payment =
                    it.payment.copy(
                        cashback =
                            CashbackSettings(
                                enabled = true,
                                standalone = true,
                                card = CashbackCard.EFTPOS,
                                limitMinor = 5000,
                                currency = "AUD",
                                context = facts.context,
                            ),
                    ),
            )
        }
        val cart = if (purchase == 0L) Cart() else Cart().addCustom("Goods", purchase, AppliedTax("Tax", 10_000), "g")
        return PaymentStart(
            cart.totals(TaxMode.INCLUSIVE),
            CurrencySpec.of("AUD"),
            "Cash",
            null,
            null,
            null,
            requestedAmountMinor = purchase + 500,
            cashBackMinor = 500,
            cashbackConfiguration = container.settingsState.value.payment.cashback,
            walletContext = facts.context,
            walletSetupIdentity = facts.identity,
        )
    }

    private fun runner(store: TransactionBook<PaymentStart> = book) =
        TransactionLifecycle(env.scope, container.gateway, store, approval = approval)

    @Test
    fun `terminal reconciliation includes the card cash while operator handover never changes card totals`() {
        val runner = runner()
        val id = checkNotNull(runner.start(start()))
        await { runner.state.first { it == TransactionState.Finished(id) } }

        fun reading(): Long {
            val record = await { checkNotNull(container.sales.get(id)) }
            val date = Instant.ofEpochMilli(checkNotNull(record.sale.processedAt)).atZone(ZoneOffset.UTC).toLocalDate()
            val remote =
                TerminalTotalsOutcome.Answered(
                    poiReconciliationId = null,
                    totals = listOf(TerminalCurrencyTotals("AUD", 1500, 1, 0, 0)),
                    closedPeriod = false,
                )
            return HistoryAccounting
                .ReconciliationAccumulator("", remote, date, ZoneOffset.UTC)
                .apply {
                    add(listOf(HistoryItem.Sale(record.sale, record.tenders)))
                }.report()
                .currencies
                .single()
                .localSalesMinor
        }
        assertThat(reading()).isEqualTo(1500)
        await { assertThat(CashTenders(container.sales, { "SIMULATOR" }, approval = approval).handOverCashback(id)).isTrue() }
        assertThat(reading()).isEqualTo(1500)
    }

    @Test
    fun `changed cash enablement program or limit after review is checked again by the send owner`() {
        val request = start()
        val original = checkNotNull(request.cashbackConfiguration)
        listOf(original.copy(enabled = false), original.copy(card = CashbackCard.VISA), original.copy(limitMinor = 6000))
            .forEach { changed ->
                env.updateSettings { it.copy(payment = it.payment.copy(cashback = changed)) }
                val runner = runner()
                val id = checkNotNull(runner.start(request))
                await { runner.state.first { it == TransactionState.Finished(id) } }
                assertThat(await { checkNotNull(container.sales.get(id)).sale.status }).isEqualTo(SaleStatus.FAILED)
            }
    }

    @Test
    fun `expiry between persisting and sending refuses cash and a terminal denial never offers handover`() {
        val request = start()
        val delayed =
            object : TransactionBook<PaymentStart> by book {
                override suspend fun open(
                    id: String,
                    request: PaymentStart,
                    serviceId: String,
                    createdAt: Long,
                ): Boolean {
                    val opened = book.open(id, request, serviceId, createdAt)
                    time += 61_000
                    return opened
                }
            }
        val expired = runner(delayed)
        val denied = checkNotNull(expired.start(request))
        await { expired.state.first { it == TransactionState.Finished(denied) } }
        assertThat(await { checkNotNull(container.sales.get(denied)).sale.status }).isEqualTo(SaleStatus.FAILED)
        lock.unlock()
        env.updateSettings { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.DECLINE)) }
        val runner = runner()
        val id = checkNotNull(runner.start(request))
        await { runner.state.first { it == TransactionState.Finished(id) } }
        val declined = await { checkNotNull(container.sales.get(id)) }
        assertThat(declined.sale.status).isEqualTo(SaleStatus.DECLINED)
        assertThat(declined.sale.cashAwaitingHandover).isFalse()
        assertThat(declined.sale.cashHandedOverAt).isNull()
    }

    @Test
    fun `purchase cash handover survives restart and reports independent money`() {
        val request = start()
        val runner = runner()
        val id = checkNotNull(runner.start(request))
        assertThat(runner.start(request)).isNull()
        await { runner.state.first { it == TransactionState.Finished(id) } }
        var record = await { checkNotNull(container.sales.get(id)) }
        assertThat(record.sale.totalMinor).isEqualTo(1000)
        assertThat(record.sale.paidMinor).isEqualTo(1000)
        assertThat(record.sale.cashBackMinor).isEqualTo(500)
        assertThat(record.sale.amountMinor).isEqualTo(1500)
        assertThat(record.tenders.single().requestedCashBackMinor).isEqualTo(500)
        assertThat(record.sale.cashAwaitingHandover).isTrue()
        val refundable = RefundablePayment.check(record) as Refundability.Refundable
        assertThat(refundable.payment.remainingMinor).isEqualTo(1000)
        val refund =
            checkNotNull(
                refundable.payment.legs
                    .single()
                    .request(RefundChoice.Everything, "", Instant.now(), ZoneOffset.UTC),
            )
        assertThat(refund.full).isFalse()
        assertThat(refund.amountMinor).isEqualTo(1000)

        await {
            container.history.settleInterrupted()
            container.history.clear()
        }
        record = await { checkNotNull(container.sales.get(id)) }
        assertThat(record.sale.cashAwaitingHandover).isTrue()
        val cash = CashTenders(container.sales, { "SIMULATOR" }, approval = approval)
        await {
            assertThat(cash.handOverCashback("missing")).isFalse()
            assertThat(CashTenders(container.sales, { "SIMULATOR" }).handOverCashback(id)).isFalse()
        }
        time += 61_000
        await { assertThat(cash.handOverCashback(id)).isFalse() }
        lock.unlock()

        await {
            assertThat(cash.handOverCashback(id)).isTrue()
            assertThat(cash.handOverCashback(id)).isFalse()
        }
        record = await { checkNotNull(container.sales.get(id)) }
        assertThat(record.sale.retentionEligible).isTrue()
        val date = Instant.ofEpochMilli(checkNotNull(record.sale.processedAt)).atZone(ZoneOffset.UTC).toLocalDate()
        val report = HistoryAccounting.report(listOf(HistoryItem.Sale(record.sale, record.tenders)), date, ZoneOffset.UTC, 0)
        val total =
            report.sections
                .single()
                .totals
                .getValue("AUD")
        assertThat(total.salesMinor).isEqualTo(1000)
        assertThat(total.cashbackMinor).isEqualTo(500)
        assertThat(total.cashHandedOverMinor).isEqualTo(500)
    }

    @Test
    fun `standalone cash-out excludes cash and fees from merchandise and refunds`() {
        val runner = runner()
        val cashout = checkNotNull(runner.start(start(0)))
        await { runner.state.first { it == TransactionState.Finished(cashout) } }
        val standalone = await { checkNotNull(container.sales.get(cashout)) }
        assertThat(standalone.sale.totalMinor).isEqualTo(0)
        assertThat(standalone.sale.taxMinor).isEqualTo(0)
        val fees = standalone.tenders.map { it.copy(authorisedMinor = 520, surchargeMinor = 20) }
        val withFee = HistoryItem.Sale(standalone.sale.copy(terminalPaidMinor = 520, surchargeMinor = 20), fees)
        val feeTotals = HistoryAccounting.totals(HistoryAccounting.activities(listOf(withFee))).getValue("AUD")
        assertThat(feeTotals.salesMinor).isEqualTo(0)
        assertThat(feeTotals.purchaseMinor).isEqualTo(0)
        assertThat(feeTotals.surchargesMinor).isEqualTo(20)
        val feeRecord = standalone.copy(sale = withFee.sale, tenders = fees)
        assertThat(RefundablePayment.check(feeRecord)).isInstanceOf(Refundability.NotRefundable::class.java)
        assertThat(RefundablePayment.fromTender(feeRecord, fees.single())).isNull()
        assertThat(RefundablePayment.qrCode(feeRecord)).isNull()

        assertThat((RefundablePayment.check(standalone) as? Refundability.Refundable)?.payment?.remainingMinor ?: 0).isEqualTo(0)
    }

    @Test
    fun `unreported and inconsistent cashback cannot enlarge merchandise refund balances`() {
        val runner = runner()
        val id = checkNotNull(runner.start(start()))
        await { runner.state.first { it == TransactionState.Finished(id) } }
        val original = await { checkNotNull(container.sales.get(id)) }
        listOf(null, -1L, 501L).forEach { reported ->
            val sale = original.copy(sale = original.sale.copy(cashBackMinor = reported))
            assertThat(RefundablePayment.check(sale)).isInstanceOf(Refundability.NotRefundable::class.java)
            val tender = original.tenders.single().copy(cashBackMinor = reported)
            assertThat(RefundablePayment.fromTender(original, tender)).isNull()
        }
        val noCharge = original.copy(sale = original.sale.copy(terminalPaidMinor = null))
        assertThat(RefundablePayment.check(noCharge)).isInstanceOf(Refundability.NotRefundable::class.java)
    }

    @Test
    fun `missing answer recovers original cash request and handover remains awaiting after repeated status checks`() {
        val request = start()
        val unanswered =
            object : TransactionBook<PaymentStart> by book {
                override suspend fun settle(
                    id: String,
                    settlement: Settlement,
                ): Boolean = book.settle(id, settlement.copy(status = SettlementStatus.UNKNOWN, details = null))
            }
        val first = runner(unanswered)
        val id = checkNotNull(first.start(request))
        await { first.state.first { it == TransactionState.Finished(id) } }
        val original = await { checkNotNull(container.sales.get(id)) }
        assertThat(original.sale.status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(original.sale.requestedCashBackMinor).isEqualTo(500)
        val recreated = runner()
        await { assertThat(recreated.recheck(id)).isEqualTo(Recheck.Settled) }
        val resolved = await { checkNotNull(container.sales.get(id)) }
        assertThat(resolved.tenders.single().serviceId).isEqualTo(original.tenders.single().serviceId)
        assertThat(resolved.sale.cashAwaitingHandover).isTrue()
        await { assertThat(recreated.recheck(id)).isEqualTo(Recheck.Settled) }
        assertThat(await { checkNotNull(container.sales.get(id)).sale.cashHandedOverAt }).isNull()
    }

    @Test
    fun `cash requests require configured unexpired approval and changed setup never sends`() {
        val request = start()
        configured = false
        val runner = runner()
        var id = checkNotNull(runner.start(request))
        await { runner.state.first { it == TransactionState.Finished(id) } }
        assertThat(await { checkNotNull(container.sales.get(id)).sale.status }).isEqualTo(SaleStatus.FAILED)
        runner.acknowledge()
        configured = true
        lock.lock()
        id = checkNotNull(runner.start(request))
        await { runner.state.first { it == TransactionState.Finished(id) } }
        assertThat(await { checkNotNull(container.sales.get(id)).sale.status }).isEqualTo(SaleStatus.FAILED)
        runner.acknowledge()
        lock.unlock()
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "CHANGED")) }
        id = checkNotNull(runner.start(request))
        await { runner.state.first { it == TransactionState.Finished(id) } }
        assertThat(await { checkNotNull(container.sales.get(id)).sale.status }).isEqualTo(SaleStatus.FAILED)
    }
}
