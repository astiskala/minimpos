package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.db.TenderKind
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.repo.TenderAllocationJson
import app.minimpos.app.data.repo.TenderBalance
import app.minimpos.app.data.repo.recordedEnvironment
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.RefundChoice
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.receipt.ReceiptElement
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

/** Split sales across shares, items and cash, and their returns to every original tender, on the simulator. */
@RunWith(RobolectricTestRunner::class)
class SplitTenderTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val session get() = container.session(SaleKind.SALE)

    @After
    fun tearDown() = env.close()

    /** Rings up two lattes (9.00 together) and a tea (4.00): 13.00 in all. */
    private fun ringUp() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        val (tax, latte, tea) =
            await {
                val taxId = container.catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
                val rate = TaxRateEntity(taxId, "GST", 10_000)
                val latteId = container.catalog.saveProduct(ProductEntity(name = "Latte", priceMinor = 450, taxRateId = taxId))
                val teaId = container.catalog.saveProduct(ProductEntity(name = "Tea", priceMinor = 400, taxRateId = taxId))
                Triple(rate, container.catalog.product(latteId)!!, container.catalog.product(teaId)!!)
            }
        session.addProduct(latte, tax)
        session.addProduct(latte, tax)
        session.addProduct(tea, tax)
    }

    private fun checkout(): Checkout =
        await {
            session
                .checkout(container.settingsState, container.terminalStatus.state, currency = container::currency)
                .first { it.canPay && it.totals.amounts.gross == 1300L }
        }

    private fun pay(start: PaymentStart): String {
        val id = checkNotNull(container.payments.start(start))
        await { container.payments.state.first { it == TransactionState.Finished(id) } }
        container.payments.acknowledge()
        return id
    }

    private fun record(id: String) = await { checkNotNull(container.sales.get(id)) }

    @Test
    fun `equal shares freeze the sale, store each tender before sending and finish in cash with change`() {
        ringUp()
        val share = TenderAllocation.Share(0, 2, 1300)
        val start = checkNotNull(session.splitStart(checkout(), Instant.now(), ZoneOffset.UTC, 650, share))
        assertThat(start.requestedAmountMinor).isEqualTo(650)
        assertThat(start.terminalTip).isNull()
        // A share that does not match its split is refused before anything is stored.
        assertThat(session.splitStart(checkout(), Instant.now(), ZoneOffset.UTC, 651, share)).isNull()
        val id = pay(start)
        val first = record(id)
        assertThat(first.sale.isPartiallyPaid).isTrue()
        assertThat(first.sale.paidMinor).isEqualTo(650)
        assertThat(first.sortedLines.map { it.name }).containsExactly("Latte", "Tea").inOrder()
        val card = first.tenders.single()
        assertThat(card.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(card.serviceId).isEqualTo(first.sale.serviceId)
        assertThat(card.requestedMinor).isEqualTo(650)
        assertThat(card.context).isNotNull()
        assertThat(TenderAllocationJson.decode(card.allocationJson)).isEqualTo(share)
        // The cart was paid into a stored sale, so ringing up starts afresh.
        assertThat(session.cart.value.lines).isEmpty()

        val next = TenderAllocation.Share(1, 2, 1300)
        assertThat(await { container.cashTenders.payExisting(id, 650, next, 1000, reviewedPaidMinor = 600) }).isFalse()
        assertThat(await { container.cashTenders.payExisting(id, 700, null, 1000, reviewedPaidMinor = 650) }).isFalse()
        assertThat(await { container.cashTenders.payExisting(id, 650, next, 600, reviewedPaidMinor = 650) }).isFalse()
        assertThat(await { container.cashTenders.payExisting(id, 650, next, 1000, reviewedPaidMinor = 650) }).isTrue()
        val paid = record(id)
        assertThat(paid.sale.isPartiallyPaid).isFalse()
        assertThat(paid.sale.amountMinor).isEqualTo(1300)
        val cash = paid.tenders.last()
        assertThat(cash.kind).isEqualTo(TenderKind.CASH)
        assertThat(cash.cashReceivedMinor).isEqualTo(1000)
        assertThat(cash.changeMinor).isEqualTo(350)
        assertThat(cash.environment).isEqualTo("SIMULATOR")
        assertThat(await { container.cashTenders.payExisting(id, 1, null, 1, reviewedPaidMinor = 1300) }).isFalse()

        val rows =
            container.receiptFactory
                .sale(
                    paid,
                    container.settingsState.value.receipt,
                ).elements
                .filterIsInstance<ReceiptElement.Row>()
        assertThat(rows.map { it.left }).containsAtLeast("Cash", "Cash received", "Change")
        val totals = HistoryAccounting.totals(HistoryAccounting.activities(listOf(HistoryItem.Sale(paid.sale, paid.tenders))))
        assertThat(totals.getValue("AUD").salesMinor).isEqualTo(1300)
        assertThat(totals.getValue("AUD").cashSalesMinor).isEqualTo(650)
    }

    @Test
    fun `items paid in cash first are never offered again and the rest goes to a card`() {
        ringUp()
        val balance = SaleBook.splitBalance(checkout().totals, manualCapture = false)
        val lattes = checkNotNull(balance.itemsFor(mapOf(0 to 2)))
        val start = checkNotNull(session.splitStart(checkout(), Instant.now(), ZoneOffset.UTC, 900, lattes))
        assertThat(start.allowPartialAuthorisation).isFalse()
        val id = checkNotNull(await { container.cashTenders.payNew(start, 900, createdAt = 10) })
        val sale = record(id)
        assertThat(sale.sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(sale.sale.paidMinor).isEqualTo(900)
        assertThat(sale.sale.tokenizationRequested).isFalse()
        val stored = sale.tenders.single()
        assertThat(stored.kind).isEqualTo(TenderKind.CASH)
        assertThat(TenderAllocationJson.decode(stored.allocationJson)).isEqualTo(lattes)
        assertThat(session.cart.value.lines).isEmpty()

        // The same lattes cannot be paid twice, by card or by cash.
        assertThat(Checkout.remainingPayment(sale.sale, 900, lattes)).isNull()
        assertThat(await { container.cashTenders.payExisting(id, 400, lattes, 400, reviewedPaidMinor = 900) }).isFalse()
        assertThat(container.payments.start(checkNotNull(Checkout.remainingPayment(sale.sale, 400, lattes)))).isNotNull()
        await { container.payments.state.first { it is TransactionState.Finished } }
        container.payments.acknowledge()
        assertThat(record(id).sale.paidMinor).isEqualTo(900)
        assertThat(record(id).tenders).hasSize(1)
        // A refused start leaves the sale as it was rather than unknown.
        assertThat(record(id).sale.status).isEqualTo(SaleStatus.APPROVED)

        val stored2 = record(id)
        val tea = checkNotNull(TenderBalance.of(stored2.sale, stored2.lines, stored2.tenders).itemsFor(mapOf(1 to 1)))
        pay(checkNotNull(Checkout.remainingPayment(stored2.sale, 400, tea)))
        val paid = record(id)
        assertThat(paid.sale.isPartiallyPaid).isFalse()
        assertThat(paid.tenders.map { it.kind }).containsExactly(TenderKind.CASH, TenderKind.CARD).inOrder()
    }

    @Test
    fun `a partial approval of a share leaves its remainder to the last share`() {
        ringUp()
        // 22.78 in two shares of 11.39, whose suffix the simulator approves in part.
        session.addCustom("Extra", 978, TaxRateEntity(1, "Zero", 0))
        val current =
            await {
                session.checkout(container.settingsState, currency = container::currency).first {
                    it.totals.amounts.gross ==
                        2278L
                }
            }
        val id = pay(checkNotNull(session.splitStart(current, Instant.now(), ZoneOffset.UTC, 1139, TenderAllocation.Share(0, 2, 2278))))
        val sale = record(id)
        assertThat(sale.sale.paidMinor).isLessThan(1139)
        val balance = TenderBalance.of(sale.sale, sale.lines, sale.tenders)
        val last = checkNotNull(balance.nextShare)
        assertThat(balance.amountFor(last, 0)).isEqualTo(2278 - sale.sale.paidMinor)
    }

    @Test
    fun `an interrupted card tender becomes unknown and blocks further tenders until resolved`() {
        ringUp()
        val id = pay(checkNotNull(session.splitStart(checkout(), Instant.now(), ZoneOffset.UTC, 500, null)))
        val sale = record(id).sale
        val opened = SaleEvent.TenderOpened("interrupted-service", 300, sale.paidMinor)
        assertThat(await { container.sales.openTender(id, opened, null, 20) }).isTrue()
        assertThat(await { container.sales.openTender(id, opened.copy(serviceId = "second"), null, 21) }).isFalse()
        await { container.history.settleInterrupted() }
        val interrupted = record(id)
        assertThat(interrupted.sale.status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(interrupted.tenders.last().status).isEqualTo(SaleStatus.UNKNOWN)
        assertThat(interrupted.tenders.last().serviceId).isEqualTo("interrupted-service")
        assertThat(await { container.cashTenders.payExisting(id, 100, null, 100, reviewedPaidMinor = sale.paidMinor) }).isFalse()
        assertThat(RefundablePayment.check(interrupted)).isInstanceOf(Refundability.NotRefundable::class.java)
    }

    @Test
    fun `returns propose every original tender and hand back cash last with a configured Manager PIN`() {
        ringUp()
        val id = pay(checkNotNull(session.splitStart(checkout(), Instant.now(), ZoneOffset.UTC, 500, null)))
        assertThat(await { container.cashTenders.payExisting(id, 800, null, 800, reviewedPaidMinor = 500) }).isTrue()
        val payment = (RefundablePayment.check(record(id)) as Refundability.Refundable).payment
        val allocation = payment.proposal(RefundChoice.Everything)
        assertThat(allocation.values).containsExactly(500L, 800L).inOrder()

        await { container.pinManager.setPin("1357") }
        env.updateSettings {
            it.copy(
                security =
                    it.security.copy(
                        managerRequirements = mapOf(ManagerAction.REFERENCED_REFUND.name to false),
                    ),
            )
        }
        // Public card refunds remain available while manager-only cash returns need a configured PIN.
        assertThat(container.storedPaymentActions.refund(payment, RefundChoice.Everything, allocation)).isEqualTo(RefundStarted.LEGS)
        val blocked = await { container.refundLegs.state.first { it?.finished == true } }!!
        assertThat(blocked.legs.map { it.cash }).containsExactly(false, true).inOrder()
        assertThat(blocked.legs.first().status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(blocked.legs.last().skipped).isEqualTo(RefundLegSkip.MANAGER_APPROVAL)
        container.refundLegs.acknowledge()

        await { container.managerPin.setPin("2468") }
        container.managerLock.unlock()
        val rest = (RefundablePayment.check(record(id)) as Refundability.Refundable).payment
        assertThat(rest.remainingMinor).isEqualTo(800)
        val cashOnly = rest.proposal(RefundChoice.Everything)
        assertThat(cashOnly.filterValues { it > 0 }.values).containsExactly(800L)
        assertThat(container.storedPaymentActions.refund(rest, RefundChoice.Everything, cashOnly)).isEqualTo(RefundStarted.LEGS)
        val done = await { container.refundLegs.state.first { it?.finished == true } }!!
        assertThat(done.legs.single().status).isEqualTo(RefundStatus.REQUESTED)
        val refunded = record(id)
        assertThat(refunded.sale.refundedMinor).isEqualTo(1300)
        val cashReturn = refunded.refunds.single { it.cash }
        assertThat(cashReturn.originalTransactionId).isEqualTo(refunded.tenders.last().id)
        assertThat(cashReturn.tenderId).isEqualTo(refunded.tenders.last().id)
        assertThat(RefundablePayment.check(refunded)).isInstanceOf(Refundability.NotRefundable::class.java)
        // A stale review of a balance already returned is refused without storing anything.
        container.refundLegs.acknowledge()
        assertThat(container.storedPaymentActions.refund(rest, RefundChoice.Everything, cashOnly)).isEqualTo(RefundStarted.LEGS)
        val stale = await { container.refundLegs.state.first { it?.finished == true } }!!
        assertThat(stale.legs.single().skipped).isEqualTo(RefundLegSkip.BALANCE_CHANGED)
        assertThat(record(id).refunds.count { it.cash }).isEqualTo(1)
        val report = HistoryAccounting.totals(HistoryAccounting.activities(refunded.refunds.map { HistoryItem.Refund(it) }))
        assertThat(report.getValue("AUD").cashRefundsMinor).isEqualTo(800)
        val environments =
            await {
                container.history.reader.fold(emptyMap<String, String?>()) { known, items ->
                    known + items.associate { it.id to it.recordedEnvironment }
                }
            }
        assertThat(environments[cashReturn.id]).isEqualTo("SIMULATOR")
        val batch = await { container.history.reader.page() }
        val key = batch.keys.first { it.id == cashReturn.id }
        assertThat(batch.item(key)?.recordedEnvironment).isEqualTo("SIMULATOR")
    }
}
