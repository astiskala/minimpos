package app.minimpos.app.history

import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.client.TerminalCurrencyTotals
import app.minimpos.terminal.client.TerminalTotalsOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryAggregationTest {
    private val date = LocalDate.parse("2026-10-05")
    private val at = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
    private val sale =
        SaleEntity(
            "sale",
            createdAt = at,
            currency = "USD",
            taxMode = "INCLUSIVE",
            netMinor = 100,
            taxMinor = 0,
            totalMinor = 100,
            status = SaleStatus.APPROVED,
            merchantReference = "sale",
            context = PaymentContext("TERMINAL", "terminal", "POS", "merchant", "LIVE"),
        )

    @Test
    fun `report batches retain all environments and undated activity outside the requested day`() {
        val rows =
            listOf(
                sale,
                sale.copy(id = "tip", tipMinor = 20),
                sale.copy(id = "online", createdAt = 0, paymentLink = true),
                sale.copy(
                    id = "capture",
                    createdAt = 0,
                    tipOnReceipt = true,
                    captureStatus = CaptureStatus.REQUESTED,
                    capturedMinor = 120,
                    tipMinor = 20,
                ),
                sale.copy(id = "euro", currency = "EUR"),
                sale.copy(id = "demo", sample = true),
                sale.copy(id = "unknown", status = SaleStatus.UNKNOWN),
            ).map { HistoryItem.Sale(it) }
        val expected = HistoryAccounting.report(rows, date, ZoneOffset.UTC, at)
        for (size in 1..rows.size) {
            val accumulator = HistoryAccounting.ReportAccumulator(date, ZoneOffset.UTC, at)
            rows.chunked(size).forEach(accumulator::add)
            assertThat(accumulator.report()).isEqualTo(expected)
        }
        val live = expected.sections.single { it.scope == HistoryScope.LIVE }
        assertThat(live.online.getValue("USD").salesMinor).isEqualTo(100)
        assertThat(live.undated.getValue("USD").salesMinor).isEqualTo(120)
    }

    @Test
    fun `period evidence in the last batch controls earlier batches and remote totals count once`() {
        val outcome = TerminalTotalsOutcome.Answered("period", listOf(TerminalCurrencyTotals("USD", 200, 2, 0, 0)), false)
        val rows =
            listOf(
                sale.copy(id = "no-period"),
                sale.copy(id = "included-old", createdAt = 0, poiReconciliationId = "period"),
                sale.copy(id = "wrong-period", poiReconciliationId = "other"),
                sale.copy(id = "euro", currency = "EUR", poiReconciliationId = "period"),
                sale.copy(id = "included", poiReconciliationId = "period"),
            ).map { HistoryItem.Sale(it) }
        val accumulator = HistoryAccounting.ReconciliationAccumulator("terminal", outcome, date, ZoneOffset.UTC)
        rows.forEach { accumulator.add(listOf(it)) }
        val report = accumulator.report()
        assertThat(report).isEqualTo(HistoryAccounting.compareReconciliation(rows, "terminal", outcome, date, ZoneOffset.UTC))
        assertThat(report.currencies.single { it.currency == "USD" }.localSalesMinor).isEqualTo(200)
        assertThat(report.currencies.single { it.currency == "USD" }.terminalSalesMinor).isEqualTo(200)
        assertThat(report.currencies.single { it.currency == "EUR" }.localSalesMinor).isEqualTo(100)
        val empty = HistoryAccounting.ReconciliationAccumulator("terminal", outcome, date, ZoneOffset.UTC).report()
        assertThat(empty.currencies.single().terminalSalesMinor).isEqualTo(200)
    }
}
