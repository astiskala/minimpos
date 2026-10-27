package app.minimpos.app.data.repo

import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.TenderKind
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.HistoryActivity
import app.minimpos.app.history.HistoryActivityKind
import app.minimpos.app.history.HistoryScope
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.split.CashPayment
import app.minimpos.terminal.client.TerminalCurrencyTotals
import app.minimpos.terminal.client.TerminalTotalsOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Plain JUnit: how a frozen sale total is divided among tenders, and the sale events that store them. */
class TenderBalanceTest {
    private val sale =
        SaleEntity(
            id = "s",
            createdAt = 1,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 909,
            taxMinor = 91,
            totalMinor = 1000,
            status = SaleStatus.APPROVED,
            merchantReference = "MP",
            paidMinor = 300,
            terminalPaidMinor = 300,
            surchargeMinor = 0,
            cashBackMinor = 0,
        )
    private val lines =
        listOf(
            SaleLineEntity(10, "s", 0, 1, "Latte", null, 300, 3, "GST", 10_000, 818, 82, 900),
            SaleLineEntity(11, "s", 1, null, "Water", null, 100, 1, "Free", 0, 100, 0, 100),
        )

    private fun tender(
        sequence: Int,
        allocation: TenderAllocation?,
        status: SaleStatus = SaleStatus.APPROVED,
    ) = SaleTenderEntity("s-$sequence", "s", sequence, 300, 300, status, allocationJson = TenderAllocationJson.encode(allocation))

    @Test
    fun `equal shares continue the split begun and the last share takes what is unpaid`() {
        val first = TenderAllocation.Share(0, 3, 1000)
        val balance = TenderBalance.of(sale, lines, listOf(tender(1, first)))
        val next = checkNotNull(balance.nextShare)
        assertThat(next).isEqualTo(TenderAllocation.Share(1, 3, 1000))
        assertThat(balance.amountFor(next, 0)).isEqualTo(333)
        assertThat(balance.accepts(333, next)).isTrue()
        assertThat(balance.accepts(334, next)).isFalse()
        assertThat(balance.accepts(333, TenderAllocation.Share(1, 4, 1000))).isFalse()
        assertThat(balance.sharesOpen).isTrue()
        assertThat(balance.itemsOpen).isFalse()
        val done = TenderBalance.of(sale.copy(paidMinor = 667), lines, listOf(tender(1, first), tender(2, next)))
        val last = checkNotNull(done.nextShare)
        assertThat(done.amountFor(last, 0)).isEqualTo(333)
        val all = TenderBalance.of(sale.copy(paidMinor = 1000), lines, listOf(tender(1, first), tender(2, next), tender(3, last)))
        assertThat(all.nextShare).isNull()
        assertThat(all.sharesOpen).isFalse()
        assertThat(all.accepts(1, null)).isFalse()
    }

    @Test
    fun `a new split starts its shares over the whole unpaid balance`() {
        val balance = TenderBalance.of(sale, lines, emptyList())
        assertThat(balance.accepts(350, TenderAllocation.Share(0, 2, 700))).isTrue()
        assertThat(balance.accepts(350, TenderAllocation.Share(0, 2, 1000))).isFalse()
        assertThat(balance.accepts(70, TenderAllocation.Share(0, 21, 700))).isFalse()
        assertThat(balance.accepts(700, null)).isTrue()
        assertThat(balance.accepts(701, null)).isFalse()
        assertThat(balance.accepts(0, null)).isFalse()
    }

    @Test
    fun `items allocated once are never offered or accepted again and refused tenders release them`() {
        val unpaid = sale.copy(paidMinor = 0, terminalPaidMinor = null)
        val empty = TenderBalance.of(unpaid, lines, emptyList())
        val latte = checkNotNull(empty.itemsFor(mapOf(0 to 2)))
        assertThat(latte.lines.single()).isEqualTo(AllocatedLine(0, 2, 600))
        assertThat(empty.accepts(600, latte)).isTrue()
        assertThat(empty.accepts(601, latte)).isFalse()
        assertThat(empty.itemsFor(emptyMap())).isNull()
        val paid = TenderBalance.of(unpaid.copy(paidMinor = 600), lines, listOf(tender(1, latte)))
        assertThat(paid.lines.first().allocatedQuantity).isEqualTo(2)
        assertThat(paid.accepts(600, latte)).isFalse()
        val rest = checkNotNull(paid.itemsFor(mapOf(0 to 5, 1 to 1)))
        assertThat(rest.lines).containsExactly(AllocatedLine(0, 1, 300), AllocatedLine(1, 1, 100))
        assertThat(paid.amountFor(rest, 0)).isEqualTo(400)
        val declined = TenderBalance.of(unpaid, lines, listOf(tender(1, latte, SaleStatus.DECLINED)))
        assertThat(declined.lines.first().allocatedQuantity).isEqualTo(0)
        assertThat(declined.itemsOpen).isTrue()
        val amountFirst = TenderBalance.of(sale, lines, listOf(tender(1, null)))
        assertThat(amountFirst.itemsOpen).isFalse()
        assertThat(amountFirst.accepts(300, rest)).isFalse()
        val preAuth = TenderBalance.of(unpaid.copy(kind = SaleKind.PRE_AUTHORISATION), lines, emptyList())
        assertThat(preAuth.itemsOpen).isFalse()
    }

    @Test
    fun `an unresolved tender blocks everything until it is resolved`() {
        val balance = TenderBalance.of(sale, lines, listOf(tender(1, null, SaleStatus.UNKNOWN)))
        assertThat(balance.unresolved).isTrue()
        assertThat(balance.accepts(100, null)).isFalse()
        assertThat(TenderBalance.of(sale, lines, listOf(tender(1, null, SaleStatus.PENDING))).accepts(100, null)).isFalse()
    }

    @Test
    fun `cash adds to the paid purchase without tip fee or cashback and stale reviews are refused`() {
        val cash = sale.after(SaleEvent.CashTendered(CashPayment(500, 2000), 300).stamped(5))
        assertThat(cash.paidMinor).isEqualTo(800)
        assertThat(cash.terminalPaidMinor).isEqualTo(800)
        assertThat(cash.surchargeMinor).isEqualTo(0)
        assertThat(cash.cashBackMinor).isEqualTo(0)
        assertThat(cash.amountMinor).isEqualTo(800)
        assertThat(cash.remainingMinor).isEqualTo(200)
        assertThrows(IllegalArgumentException::class.java) { sale.after(SaleEvent.CashTendered(CashPayment(500, 500), 200)) }
        assertThrows(IllegalArgumentException::class.java) { sale.after(SaleEvent.CashTendered(CashPayment(800, 800), 300)) }
        // A missing card fee keeps the aggregate unknown rather than becoming zero after cash.
        val unknownFee = sale.copy(surchargeMinor = null, terminalPaidMinor = null)
        assertThat(unknownFee.after(SaleEvent.CashTendered(CashPayment(100, 100), 300)).surchargeMinor).isNull()
        assertThat(sale.acceptsTender(700, 300)).isTrue()
        assertThat(sale.acceptsTender(700, 200)).isFalse()
        assertThat(sale.copy(surchargeMinor = null).acceptsTender(700, 300)).isFalse()
    }

    @Test
    fun `an opened tender freezes its own identity and amount`() {
        val opened = sale.after(SaleEvent.TenderOpened("svc", 400, 300))
        assertThat(opened.status).isEqualTo(SaleStatus.PENDING)
        assertThat(opened.serviceId).isEqualTo("svc")
        assertThat(opened.tenderRequestedMinor).isEqualTo(400)
        assertThrows(IllegalArgumentException::class.java) { sale.after(SaleEvent.TenderOpened("svc", 701, 300)) }
    }

    @Test
    fun `allocations round trip and unreadable values decode as an amount`() {
        val items = TenderAllocation.Items(listOf(AllocatedLine(0, 1, 300)))
        assertThat(TenderAllocationJson.decode(TenderAllocationJson.encode(items))).isEqualTo(items)
        val share = TenderAllocation.Share(1, 2, 900)
        assertThat(TenderAllocationJson.decode(TenderAllocationJson.encode(share))).isEqualTo(share)
        assertThat(TenderAllocationJson.encode(null)).isNull()
        assertThat(TenderAllocationJson.decode("{broken")).isNull()
        assertThat(TenderAllocationJson.decode(null)).isNull()
        assertThat(SaleTenderEntity("c", "s", 1, 1, kind = TenderKind.CASH).approved).isTrue()
    }
}

/** Plain JUnit: cash in local reports, never in terminal totals or their comparison. */
class CashAccountingTest {
    private val context = PaymentContext("TERMINAL", "P1", "S", "M", "TEST")
    private val sale =
        SaleEntity(
            id = "s",
            createdAt = 1_000,
            currency = "USD",
            taxMode = "INCLUSIVE",
            netMinor = 1000,
            taxMinor = 0,
            totalMinor = 1000,
            status = SaleStatus.APPROVED,
            merchantReference = "MP",
            paidMinor = 1000,
            terminalPaidMinor = 1000,
            surchargeMinor = 0,
            poiId = "P1",
            context = context,
        )
    private val card =
        SaleTenderEntity("s-1", "s", 1, 600, 600, createdAt = 1_000, serviceId = "svc", surchargeMinor = 0, poiReconciliationId = "2")
    private val cash =
        SaleTenderEntity(
            "s-2",
            "s",
            2,
            400,
            400,
            createdAt = 1_000,
            surchargeMinor = 0,
            kind = TenderKind.CASH,
            cashReceivedMinor = 500,
            changeMinor = 100,
            environment = "TEST",
        )
    private val declined = SaleTenderEntity("s-3", "s", 3, 400, null, SaleStatus.DECLINED, createdAt = 1_000, serviceId = "x")

    @Test
    fun `cash is separated in local totals and excluded from the terminal comparison`() {
        val item = HistoryItem.Sale(sale, listOf(card, declined, cash))
        val returned =
            RefundEntity(
                "r",
                "s",
                2_000,
                "R",
                "s-2",
                "1970-01-01T00:00:01Z",
                "MP",
                "USD",
                100,
                false,
                RefundStatus.REQUESTED,
                cash = true,
                tenderId = "s-2",
                processedAt = 2_000,
            )
        val totals =
            HistoryAccounting
                .totals(HistoryAccounting.activities(listOf(item, HistoryItem.Refund(returned))))
                .getValue("USD")
        assertThat(totals.salesMinor).isEqualTo(1000)
        assertThat(totals.cashSalesMinor).isEqualTo(400)
        assertThat(totals.refundsMinor).isEqualTo(100)
        assertThat(totals.cashRefundsMinor).isEqualTo(100)
        val remote =
            TerminalTotalsOutcome.Answered(
                "2",
                listOf(TerminalCurrencyTotals("USD", debitMinor = 600)),
                false,
            )
        val comparison =
            HistoryAccounting.ReconciliationAccumulator(
                "P1",
                remote,
                LocalDate.of(1970, 1, 1),
                ZoneOffset.UTC,
            )
        comparison.add(listOf(item, HistoryItem.Refund(returned)))
        val report = comparison.report()
        assertThat(report.currencies.single().localSalesMinor).isEqualTo(600)
        assertThat(report.currencies.single().localRefundsMinor).isEqualTo(0)
        assertThat(report.allMatch).isTrue()
        val cashOnly = HistoryItem.Sale(sale.copy(context = null, poiId = null), listOf(cash.copy(authorisedMinor = 1000)))
        assertThat(cashOnly.recordedEnvironment).isEqualTo("TEST")
        assertThat(HistoryActivity(cashOnly, 1, HistoryActivityKind.SALE).scope)
            .isEqualTo(HistoryScope.TEST)
    }
}
