package app.minimpos.app.history

import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.after
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.client.TerminalCurrencyTotals
import app.minimpos.terminal.client.TerminalTotalsOutcome
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
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
    fun `daily refund fees are components of returns and historical allocations stay unknown`() {
        val returned =
            RefundEntity(
                "r",
                "sale",
                monday,
                "R",
                "T.P",
                "2026-10-05T12:00:00Z",
                null,
                "USD",
                100,
                false,
                RefundStatus.REQUESTED,
                surchargeMinor = 15,
                tipMinor = 0,
            )
        val totals =
            HistoryAccounting
                .totals(
                    HistoryAccounting.activities(
                        listOf(
                            HistoryItem.Refund(returned),
                            HistoryItem.Refund(returned.copy(id = "old", surchargeMinor = null)),
                        ),
                    ),
                ).getValue("USD")
        assertThat(totals.refundsMinor).isEqualTo(200)
        assertThat(totals.refundedSurchargesMinor).isEqualTo(15)
        assertThat(totals.unknownRefundSurchargeCount).isEqualTo(1)
        assertThat(totals.netMinor).isEqualTo(-200)
    }

    @Test
    fun `new tender fees follow their own processing day and terminal period without rewriting old totals`() {
        val first =
            SaleTenderEntity(
                "sale-1",
                "sale",
                1,
                1000,
                550,
                createdAt = monday,
                serviceId = "ONE",
                surchargeMinor = 50,
                tipMinor = 0,
                poiReconciliationId = "1",
            )
        val second =
            first.copy(
                id = "sale-2",
                sequence = 2,
                authorisedMinor = 600,
                createdAt = tuesday,
                serviceId = "TWO",
                tipMinor = 50,
                poiReconciliationId = "2",
            )
        val original = sale.copy(totalMinor = 1000, paidMinor = 1000, terminalPaidMinor = 1150, tipMinor = 50, surchargeMinor = 100)
        val item = HistoryItem.Sale(original, listOf(first, second))
        val days = HistoryAccounting.days(listOf(item), ZoneOffset.UTC)
        assertThat(days.map { it.totals.salesMinor.getValue("USD") }).containsExactly(600L, 550L).inOrder()
        assertThat(days.map { it.totals.surchargesMinor.getValue("USD") }).containsExactly(50L, 50L)
        val remote = TerminalTotalsOutcome.Answered("2", listOf(TerminalCurrencyTotals("USD", debitMinor = 600)), false)
        val comparison = HistoryAccounting.ReconciliationAccumulator("S1F2-1", remote, LocalDate.parse("2026-10-06"), ZoneOffset.UTC)
        comparison.add(listOf(item))
        assertThat(comparison.report().allMatch).isTrue()
        val historic = item.copy(tenders = listOf(first.copy(serviceId = null), second.copy(serviceId = null)))
        assertThat(
            HistoryAccounting
                .days(listOf(historic), ZoneOffset.UTC)
                .single()
                .totals.salesMinor
                .getValue("USD"),
        ).isEqualTo(1150)
    }

    @Test
    fun `surcharges are included once and missing fee history stays separately unknown`() {
        val charged = sale.copy(totalMinor = 1000, terminalPaidMinor = 1150, tipMinor = 100, surchargeMinor = 50)
        val totals =
            report(listOf(charged, sale.copy(id = "old")), "2026-10-05")
                .sections
                .single()
                .totals
                .getValue("USD")
        assertThat(totals.salesMinor).isEqualTo(11_150)
        assertThat(totals.tipsMinor).isEqualTo(100)
        assertThat(totals.surchargesMinor).isEqualTo(50)
        assertThat(totals.purchaseMinor).isEqualTo(1000)
        assertThat(totals.unknownSurchargeCount).isEqualTo(1)
        val accumulator = HistoryAccounting.ReportAccumulator(LocalDate.parse("2026-10-05"), ZoneOffset.UTC, tuesday)
        accumulator.add(listOf(HistoryItem.Sale(charged)))
        accumulator.add(listOf(HistoryItem.Sale(sale.copy(id = "old"))))
        assertThat(
            accumulator
                .report()
                .sections
                .single()
                .totals
                .getValue("USD"),
        ).isEqualTo(totals)
    }

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
        val unknown = pending.after(SaleEvent.CaptureAnswered(ModificationResult.Failed(Fault.TimedOut), monday))
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

    @Test
    fun `unresolvedTerminalItems selects only unresolved terminal sales and refunds for the matching terminal`() {
        val pendingSale = sale.copy(id = "pending-sale", status = SaleStatus.PENDING)
        val unknownSale = sale.copy(id = "unknown-sale", status = SaleStatus.UNKNOWN)
        val approvedSale = sale.copy(id = "approved-sale", status = SaleStatus.APPROVED)
        val linkSale = sale.copy(id = "link-sale", status = SaleStatus.PENDING, paymentLink = true)
        val otherTerminalSale =
            sale.copy(
                id = "other-terminal-sale",
                status = SaleStatus.UNKNOWN,
                context = PaymentContext("TERMINAL", "S1F2-2", "POS", "Merchant", "LIVE"),
            )
        val refundBase =
            RefundEntity(
                id = "pending-refund",
                saleId = sale.id,
                createdAt = monday,
                merchantReference = "R",
                originalTransactionId = "T",
                originalTimestamp = "2026-10-05T12:00:00Z",
                originalReference = "SALE",
                currency = "USD",
                amountMinor = 2000,
                full = false,
                status = RefundStatus.PENDING,
                context = sale.context,
            )
        val unknownRefund = refundBase.copy(id = "unknown-refund", status = RefundStatus.UNKNOWN)
        val requestedRefund = refundBase.copy(id = "requested-refund", status = RefundStatus.REQUESTED)
        val otherTerminalRefund =
            refundBase.copy(
                id = "other-refund",
                status = RefundStatus.UNKNOWN,
                context = PaymentContext("TERMINAL", "S1F2-2", "POS", "Merchant", "LIVE"),
            )
        val items =
            listOf(
                HistoryItem.Sale(pendingSale),
                HistoryItem.Sale(unknownSale),
                HistoryItem.Sale(approvedSale),
                HistoryItem.Sale(linkSale),
                HistoryItem.Sale(otherTerminalSale),
                HistoryItem.Refund(refundBase),
                HistoryItem.Refund(unknownRefund),
                HistoryItem.Refund(requestedRefund),
                HistoryItem.Refund(otherTerminalRefund),
            )

        assertThat(HistoryAccounting.unresolvedTerminalItems(items, "S1F2-1").map { it.id })
            .containsExactly("pending-sale", "unknown-sale", "pending-refund", "unknown-refund")
            .inOrder()
        assertThat(HistoryAccounting.unresolvedTerminalItems(items, "").map { it.id })
            .containsExactly(
                "pending-sale",
                "unknown-sale",
                "other-terminal-sale",
                "pending-refund",
                "unknown-refund",
                "other-refund",
            ).inOrder()
    }

    @Test
    fun `saved-card charges and their Checkout refunds are never unresolved terminal items`() {
        val refund =
            RefundEntity(
                id = "checkout-refund",
                saleId = sale.id,
                createdAt = monday,
                merchantReference = "R",
                originalTransactionId = "T",
                originalTimestamp = "2026-10-05T12:00:00Z",
                originalReference = "SALE",
                currency = "USD",
                amountMinor = 2000,
                full = false,
                status = RefundStatus.UNKNOWN,
                context = sale.context,
                checkoutRefund = true,
            )
        val items = listOf(HistoryItem.Sale(sale.copy(status = SaleStatus.UNKNOWN, savedCard = true)), HistoryItem.Refund(refund))
        assertThat(HistoryAccounting.unresolvedTerminalItems(items, "S1F2-1")).isEmpty()
        assertThat(HistoryAccounting.unresolvedTerminalItems(items, "")).isEmpty()
    }

    private fun reconciliationItems(): List<HistoryItem> {
        val simCtx = PaymentContext("SIMULATOR", "S1F2-1", "SIMULATOR", "", "SIMULATOR")
        val testCtx = PaymentContext("TERMINAL", "S1F2-1", "POS", "Merchant", "TEST")
        val otherPoiCtx = PaymentContext("TERMINAL", "S1F2-2", "POS", "Merchant", "LIVE")
        val saleUsd =
            sale.copy(
                id = "s-usd",
                totalMinor = 10_000,
                tipMinor = 1_500,
                offlineMode = "OFFLINE_EMV",
                unconfirmedBatchCount = 2,
                poiReconciliationId = "REC-1",
            )
        val refundUsd =
            RefundEntity(
                "r-usd",
                saleUsd.id,
                monday,
                "R-USD",
                "T",
                "2026-10-05T12:00:00Z",
                "SALE",
                "USD",
                2_000,
                false,
                RefundStatus.REQUESTED,
                context = sale.context,
                processedAt = monday,
                poiReconciliationId = "REC-1",
            )
        return listOf(
            HistoryItem.Sale(saleUsd),
            HistoryItem.Sale(sale.copy(id = "s-eur", currency = "EUR", totalMinor = 5_000, poiReconciliationId = "REC-1")),
            HistoryItem.Sale(sale.copy(id = "s-link", paymentLink = true, totalMinor = 9_999)),
            HistoryItem.Sale(sale.copy(id = "s-other-poi", totalMinor = 8_888, context = otherPoiCtx)),
            HistoryItem.Sale(sale.copy(id = "s-sim", processedAt = tuesday, totalMinor = 7_777, context = simCtx)),
            HistoryItem.Sale(sale.copy(id = "s-test", processedAt = tuesday, totalMinor = 6_666, context = testCtx)),
            HistoryItem.Sale(sale.copy(id = "s-unknown", processedAt = tuesday, totalMinor = 5_555, context = null)),
            HistoryItem.Sale(sale.copy(id = "s-tue", processedAt = tuesday, totalMinor = 4_444, poiReconciliationId = "REC-2")),
            HistoryItem.Refund(refundUsd),
            HistoryItem.Refund(refundUsd.copy(id = "r-sim", processedAt = tuesday, poiReconciliationId = "REC-2", context = simCtx)),
            HistoryItem.Refund(refundUsd.copy(id = "r-test", processedAt = tuesday, poiReconciliationId = "REC-2", context = testCtx)),
            HistoryItem.Refund(refundUsd.copy(id = "r-unknown", processedAt = tuesday, poiReconciliationId = "REC-2", context = null)),
            // A saved-card charge and its Checkout refund are not in the terminal's totals.
            HistoryItem.Sale(sale.copy(id = "s-card", savedCard = true, totalMinor = 3_333, poiReconciliationId = "REC-1")),
            HistoryItem.Refund(refundUsd.copy(id = "r-card", amountMinor = 1_111, checkoutRefund = true)),
        )
    }

    @Test
    fun `compareReconciliation compares terminal totals against matching local terminal activity`() {
        val items = reconciliationItems()
        val day = LocalDate.parse("2026-10-05")
        assertThat(HistoryAccounting.activities(items).map { it.scope to it.currency }).containsAtLeast(
            HistoryScope.LIVE to "USD",
            HistoryScope.DEMO to "USD",
            HistoryScope.TEST to "USD",
            HistoryScope.UNKNOWN to "USD",
        )
        val matchedOutcome =
            TerminalTotalsOutcome.Answered(
                poiReconciliationId = "REC-1",
                closedPeriod = true,
                totals =
                    listOf(
                        TerminalCurrencyTotals("USD", 11_500, 1, 2000, 1),
                        TerminalCurrencyTotals("EUR", 5000, 1, 0, 0),
                    ),
            )
        val matched = HistoryAccounting.compareReconciliation(items, "S1F2-1", matchedOutcome, day, ZoneOffset.UTC)
        assertThat(matched.poiReconciliationId).isEqualTo("REC-1")
        assertThat(matched.closedPeriod && matched.allMatch).isTrue()
        assertThat(matched.offlineSaleCount to matched.unconfirmedBatchCount).isEqualTo(1 to 2)
        val usdSummary = matched.currencies.first { it.currency == "USD" }
        assertThat(usdSummary.matches).isTrue()
        assertThat(usdSummary.localSalesMinor to usdSummary.terminalSalesMinor).isEqualTo(11_500L to 11_500L)
        assertThat(usdSummary.localRefundsMinor to usdSummary.terminalRefundsMinor).isEqualTo(2_000L to 2_000L)

        val mismatchedOutcome =
            matchedOutcome.copy(
                poiReconciliationId = null,
                closedPeriod = false,
                totals = listOf(TerminalCurrencyTotals("USD", 12_000, 2, 1500, 1)),
            )
        val mismatched = HistoryAccounting.compareReconciliation(items, "S1F2-1", mismatchedOutcome, day, ZoneOffset.UTC)
        assertThat(mismatched.allMatch).isFalse()
        assertThat(mismatched.currencies.first { it.currency == "USD" }.matches).isFalse()

        val emptyPoi =
            HistoryAccounting.compareReconciliation(
                items,
                "",
                matchedOutcome.copy(poiReconciliationId = null),
                day,
                ZoneOffset.UTC,
            )
        assertThat(emptyPoi.currencies.first { it.currency == "USD" }.localSalesMinor).isEqualTo(20_388L)
    }

    @Test
    fun `days group dated activity by processing day in the zone, newest first, with their totals`() {
        val captured =
            sale.copy(
                id = "tip",
                tipOnReceipt = true,
                tipMinor = 1_500,
                capturedMinor = 11_500,
                captureStatus = CaptureStatus.REQUESTED,
                captureProcessedAt = tuesday,
            )
        val link = sale.copy(id = "link", paymentLink = true, processedAt = null, createdAt = tuesday)
        val items = listOf(sale, captured, link).map { HistoryItem.Sale(it) }
        val days = HistoryAccounting.days(items, ZoneOffset.UTC)
        assertThat(days.map { it.date }).containsExactly(LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-05")).inOrder()
        val (tue, mon) = days
        assertThat(tue.totals.salesMinor).containsExactly("USD", 11_500L)
        assertThat(tue.totals.tipsMinor).containsExactly("USD", 1_500L)
        assertThat(tue.captures).containsExactly("tip")
        // The paid link's opening is dated, its undated online payment is not.
        assertThat(tue.items.map { it.id }).containsExactly("tip", "link")
        assertThat(mon.totals.salesMinor).containsExactly("USD", 10_000L)
        assertThat(mon.totals.preAuthsMinor).containsExactly("USD", 10_000L)
        assertThat(mon.totals.saleCount to mon.totals.preAuthCount).isEqualTo(1 to 1)
        assertThat(mon.times).containsExactly("sale", monday, "tip", monday)
        // A zone far enough east moves Monday noon UTC into Tuesday.
        assertThat(HistoryAccounting.days(listOf(HistoryItem.Sale(sale)), ZoneOffset.ofHours(13)).single().date)
            .isEqualTo(LocalDate.parse("2026-10-06"))
    }

    @Test
    fun `batches of a day add up to the whole day, in the same totals the daily report reads`() {
        val refund =
            RefundEntity(
                id = "r",
                saleId = "sale",
                createdAt = monday,
                processedAt = monday,
                merchantReference = "R",
                originalTransactionId = "T",
                originalTimestamp = "2026-10-05T12:00:00.000Z",
                originalReference = "SALE",
                currency = "USD",
                amountMinor = 2_500,
                full = false,
                status = RefundStatus.REQUESTED,
            )
        val items =
            listOf(
                HistoryItem.Sale(sale),
                HistoryItem.Sale(sale.copy(id = "eur", currency = "EUR", tipMinor = 100)),
                HistoryItem.Refund(refund),
                HistoryItem.Refund(refund.copy(id = "hold", cancellation = true)),
            )
        val whole = HistoryAccounting.days(items, ZoneOffset.UTC).single().totals
        val batches = items.map { HistoryAccounting.days(listOf(it), ZoneOffset.UTC).single().totals }
        assertThat(batches.reduce(DayTotals::plus)).isEqualTo(whole)
        assertThat(whole.currencies).isEqualTo(HistoryAccounting.totals(HistoryAccounting.activities(items)))
        assertThat(whole.salesMinor).containsExactly("EUR", 10_100L, "USD", 10_000L)
        assertThat(whole.tipsMinor).containsExactly("EUR", 100L)
        // A cancelled hold is not money returned, here or against the terminal's totals.
        assertThat(whole.refundsMinor).containsExactly("USD", 2_500L)
        assertThat(whole.refundCount).isEqualTo(1)
        val huge = CurrencyHistoryTotals(salesMinor = Long.MAX_VALUE)
        assertThrows(ArithmeticException::class.java) { huge + CurrencyHistoryTotals(salesMinor = 1) }
    }
}
