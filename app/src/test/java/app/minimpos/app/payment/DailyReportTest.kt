package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.receipt.ActionResult
import app.minimpos.app.refund.HistoryAccounting
import app.minimpos.core.money.PaymentContext
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import app.minimpos.terminal.client.PrintJob
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class DailyReportTest {
    @get:Rule val env = TestEnvironment()
    private val container get() = env.container
    private val at = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
    private val sale =
        SaleEntity(
            id = "demo",
            sample = true,
            createdAt = at,
            processedAt = at,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 1000,
            taxMinor = 0,
            totalMinor = 1000,
            status = SaleStatus.APPROVED,
            merchantReference = "PRIVATE-REFERENCE",
            shopperEmail = "shopper@example.invalid",
            maskedPan = "4111 **** 1111",
            context = PaymentContext("SIMULATOR", "SIM", "POS", "", null, simulated = true),
        )

    @Test
    fun `report prints demo totals without card data shopper identity transaction list or refund QR`() =
        await {
            env.useSimulator()
            container.sales.createPending(sale, emptyList())
            val date = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
            val result = container.receipts.printReport(date)
            assertThat(result).isEqualTo(ActionResult.Success)
            val jobs = container.virtualPrinter.jobs.value
            assertThat(jobs).hasSize(1)
            assertThat(jobs.single()).isInstanceOf(PrintJob.Text::class.java)
            val text = jobs.single().toString()
            assertThat(text).contains("Local daily summary")
            assertThat(text).contains("DEMO")
            assertThat(text).contains("$10.00")
            assertThat(text).doesNotContain("PRIVATE-REFERENCE")
            assertThat(text).doesNotContain("shopper@example.invalid")
            assertThat(text).doesNotContain("4111")
        }

    @Test
    fun `report text discloses undated online payments and excludes them from net`() {
        val link = sale.copy(paymentLink = true)
        val report = HistoryAccounting.report(listOf(HistoryItem.Sale(link)), LocalDate.parse("2026-10-06"), ZoneOffset.UTC, at)
        val document = container.receiptFactory.dailyReport(report, container.settingsState.value.receipt)
        val text = PlainTextReceiptRenderer(48).render(document)
        assertThat(text).contains("Online payments")
        assertThat(text.replace(Regex("\\s+"), " ")).contains("Excluded from this day")
        assertThat(report.sections.single().totals).isEmpty()
        assertThat(
            report.sections
                .single()
                .online
                .getValue("AUD")
                .salesMinor,
        ).isEqualTo(1000)
    }

    @Test
    fun `printer refusal surfaces failure and stops offering report printing`() =
        await {
            env.useSimulator { it.copy(simulator = it.simulator.copy(hasPrinter = false)) }
            val offered = container.receipts.canPrintReports.first()
            assertThat(offered).isFalse()
            val result = container.receipts.printReport(LocalDate.now())
            assertThat(result).isInstanceOf(ActionResult.Failure::class.java)
            assertThat(container.virtualPrinter.jobs.value).isEmpty()
        }
}
