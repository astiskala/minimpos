package app.minimpos.app.refund

import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.after
import app.minimpos.core.money.PaymentContext
import app.minimpos.terminal.checkout.ModificationResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class HistoryAccountingTest {
    private val monday = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
    private val tuesday = monday + 86_400_000
    private val sale =
        SaleEntity(
            id = "sale",
            createdAt = monday,
            processedAt = monday,
            currency = "USD",
            taxMode = "INCLUSIVE",
            netMinor = 10_000,
            taxMinor = 0,
            totalMinor = 10_000,
            status = SaleStatus.APPROVED,
            merchantReference = "SALE",
            context = PaymentContext("TERMINAL", "S1F2-1", "POS", "Merchant", "LIVE"),
        )

    private fun report(
        sales: List<SaleEntity>,
        date: String,
        zone: ZoneId = ZoneOffset.UTC,
    ) = HistoryAccounting.report(sales.map { HistoryItem.Sale(it) }, LocalDate.parse(date), zone, tuesday)

    @Test
    fun `receipt tip bill and tip move to capture day without erasing original hold`() {
        val captured =
            sale.copy(
                tipOnReceipt = true,
                tipMinor = 1500,
                capturedMinor = 11_500,
                captureStatus = CaptureStatus.REQUESTED,
                captureProcessedAt = tuesday,
            )
        val mon =
            report(listOf(captured), "2026-10-05")
                .sections
                .single()
                .totals
                .getValue("USD")
        assertThat(mon.salesMinor).isEqualTo(0)
        assertThat(mon.heldMinor).isEqualTo(10_000)
        val tue =
            report(listOf(captured), "2026-10-06")
                .sections
                .single()
                .totals
                .getValue("USD")
        assertThat(tue.salesMinor).isEqualTo(11_500)
        assertThat(tue.saleCount).isEqualTo(1)
        assertThat(tue.tipsMinor).isEqualTo(1500)
        assertThat(tue.netMinor).isEqualTo(11_500)
    }

    @Test
    fun `cancelled holds retain original authorized activity`() {
        val cancelled = sale.copy(tipOnReceipt = true, holdCancelled = true)
        val totals =
            report(listOf(cancelled), "2026-10-05")
                .sections
                .single()
                .totals
                .getValue("USD")
        assertThat(totals.heldMinor).isEqualTo(10_000)
        assertThat(totals.saleCount).isEqualTo(0)
    }

    @Test
    fun `unknown and failed captures never count as sales and expose issues on capture day`() {
        listOf(CaptureStatus.UNKNOWN, CaptureStatus.FAILED, CaptureStatus.PENDING).forEach { status ->
            val pending =
                sale.copy(
                    tipOnReceipt = true,
                    capturedMinor = 11_500,
                    tipMinor = 1500,
                    captureStatus = status,
                    captureStartedAt = tuesday,
                )
            val totals =
                report(listOf(pending), "2026-10-06")
                    .sections
                    .single()
                    .totals
                    .getValue("USD")
            assertThat(totals.salesMinor).isEqualTo(0)
            assertThat(totals.unresolvedCount).isEqualTo(1)
        }
    }

    @Test
    fun `retry keeps first send timestamp and repeated acceptance cannot move accounting day`() {
        val pending = sale.copy(tipOnReceipt = true).after(SaleEvent.CaptureSending(11_500, 1500, monday))
        val unknown = pending.after(SaleEvent.CaptureAnswered(ModificationResult.Unknown("timeout"), monday))
        val retried = unknown.after(SaleEvent.CaptureSending(11_500, startedAt = tuesday))
        assertThat(retried.captureStartedAt).isEqualTo(monday)
        val accepted = retried.after(SaleEvent.CaptureAnswered(ModificationResult.Received("PSP"), tuesday))
        val duplicate = accepted.after(SaleEvent.CaptureAnswered(ModificationResult.Received("PSP"), tuesday + 86_400_000))
        assertThat(duplicate).isEqualTo(accepted)
        assertThat(
            report(listOf(duplicate), "2026-10-06")
                .sections
                .single()
                .totals
                .getValue("USD")
                .saleCount,
        ).isEqualTo(1)
    }

    @Test
    fun `paid links and undated captures never enter dated net`() {
        val link = sale.copy(id = "link", paymentLink = true)
        val capture = sale.copy(tipOnReceipt = true, captureStatus = CaptureStatus.REQUESTED, capturedMinor = 12_000)
        val section = report(listOf(link, capture), "2026-10-05").sections.single()
        assertThat(section.totals.getValue("USD").netMinor).isEqualTo(0)
        assertThat(section.online.getValue("USD").salesMinor).isEqualTo(10_000)
        assertThat(section.undated.getValue("USD").salesMinor).isEqualTo(12_000)
    }

    @Test
    fun `currencies and seeded demo amounts stay separate from real payments`() {
        val demo = sale.copy(id = "demo", sample = true)
        val yen = sale.copy(id = "yen", currency = "JPY", totalMinor = 123)
        val sections = report(listOf(sale, demo, yen), "2026-10-05").sections
        assertThat(sections.map { it.scope }).containsExactly(HistoryScope.LIVE, HistoryScope.DEMO).inOrder()
        assertThat(
            sections
                .first()
                .totals
                .getValue("USD")
                .salesMinor,
        ).isEqualTo(10_000)
        assertThat(
            sections
                .first()
                .totals
                .getValue("JPY")
                .salesMinor,
        ).isEqualTo(123)
        assertThat(
            sections
                .last()
                .totals
                .getValue("USD")
                .salesMinor,
        ).isEqualTo(10_000)
    }

    @Test
    fun `refund acceptance day not original sale day reduces net and cancellations do not`() {
        val refund =
            RefundEntity(
                id = "r",
                saleId = sale.id,
                createdAt = monday,
                processedAt = tuesday,
                merchantReference = "R",
                originalTransactionId = "T",
                originalTimestamp = "2026-10-05T12:00:00Z",
                originalReference = "SALE",
                currency = "USD",
                amountMinor = 2000,
                full = false,
                status = RefundStatus.REQUESTED,
                context = sale.context,
            )
        val items =
            listOf(HistoryItem.Sale(sale), HistoryItem.Refund(refund), HistoryItem.Refund(refund.copy(id = "c", cancellation = true)))
        val report = HistoryAccounting.report(items, LocalDate.parse("2026-10-06"), ZoneOffset.UTC, tuesday)
        assertThat(
            report.sections
                .single()
                .totals
                .getValue("USD")
                .netMinor,
        ).isEqualTo(-2000)
        assertThat(
            report.sections
                .single()
                .totals
                .getValue("USD")
                .refundCount,
        ).isEqualTo(1)
    }

    @Test
    fun `only pending and unknown refunds are unresolved`() {
        assertThat(RefundStatus.entries.filter { it.unresolved }).containsExactly(RefundStatus.PENDING, RefundStatus.UNKNOWN)
    }

    @Test
    fun `issue eligibility shares activity meaning without collapsing unresolved operation counts`() {
        val unresolved = HistoryItem.Sale(sale.copy(tipOnReceipt = true, captureStatus = CaptureStatus.UNKNOWN, adjustmentPending = true))
        assertThat(HistoryAccounting.needsAttention(unresolved)).isTrue()
        assertThat(HistoryAccounting.activities(listOf(unresolved)).count { it.kind == HistoryActivityKind.ISSUE }).isEqualTo(2)
        val resolved = HistoryItem.Sale(unresolved.sale.copy(captureStatus = CaptureStatus.REQUESTED, adjustmentPending = false))
        assertThat(HistoryAccounting.needsAttention(resolved)).isFalse()
        assertThat(HistoryAccounting.activities(listOf(resolved)).none { it.kind == HistoryActivityKind.ISSUE }).isTrue()
    }

    @Test
    fun `capture issue eligibility follows payment standing rather than raw capture status`() {
        val pending = sale.copy(tipOnReceipt = true, captureStatus = CaptureStatus.UNKNOWN)
        listOf(
            pending.copy(tipOnReceipt = false),
            pending.copy(holdCancelled = true),
            pending.copy(status = SaleStatus.DECLINED),
        ).forEach { record ->
            val item = HistoryItem.Sale(record)
            assertThat(HistoryAccounting.needsAttention(item)).isFalse()
            assertThat(HistoryAccounting.activities(listOf(item)).none { it.kind == HistoryActivityKind.ISSUE }).isTrue()
        }
    }

    @Test
    fun `timezone defines processing day across midnight`() {
        val nearMidnight = sale.copy(processedAt = Instant.parse("2026-10-06T00:30:00Z").toEpochMilli())
        val report = report(listOf(nearMidnight), "2026-10-05", ZoneId.of("America/Los_Angeles"))
        assertThat(
            report.sections
                .single()
                .totals
                .getValue("USD")
                .salesMinor,
        ).isEqualTo(10_000)
    }
}
