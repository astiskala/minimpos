package app.minimpos.app.refund

import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A retained transaction's dated activity, not a second financial operation. */
data class HistoryActivity(
    /** Original sale or refund opened by tapping this activity. */
    val item: HistoryItem,
    /** Activity time in epoch milliseconds; null for an unavailable processing date. */
    val at: Long?,
    /** How this activity contributes to the summary. */
    val kind: HistoryActivityKind,
    /** Amount in the original transaction's currency minor units. */
    val amountMinor: Long = 0,
    /** Tip included in [amountMinor], never added to it again. */
    val tipMinor: Long = 0,
) {
    /** ISO 4217 currency code, unchanged from the original operation. */
    val currency: String get() =
        when (item) {
            is HistoryItem.Sale -> item.sale.currency
            is HistoryItem.Refund -> item.refund.currency
        }

    /** Original account environment, or demo when no real money moved. */
    val scope: HistoryScope get() {
        val context =
            when (item) {
                is HistoryItem.Sale -> item.sale.context
                is HistoryItem.Refund -> item.refund.context
            }
        return when {
            (item is HistoryItem.Sale && item.sale.sample) || context?.historyEnvironment == "SIMULATOR" -> HistoryScope.DEMO
            context?.historyEnvironment == "LIVE" -> HistoryScope.LIVE
            context?.historyEnvironment == "TEST" -> HistoryScope.TEST
            else -> HistoryScope.UNKNOWN
        }
    }
}

/** Monetary meaning of an activity; opened entries carry no amount. */
enum class HistoryActivityKind {
    /** Original attempt, retained even when declined or cancelled. */
    OPENED,

    /** Approved ordinary sale. */
    SALE,

    /** Accepted manual capture, including its tip. */
    CAPTURE,

    /** Original approved hold, unaffected by later capture or cancellation. */
    HELD,

    /** Accepted refund, excluding hold cancellation. */
    REFUND,

    /** Operation whose answer is missing or needs attention. */
    ISSUE,

    /** Paid online with no actual payment date available. */
    ONLINE,
}

/** Distinct report sections prevent real, test and demo amounts from mixing. */
enum class HistoryScope {
    /** Real payments. */
    LIVE,

    /** Adyen TEST activity. */
    TEST,

    /** Simulator and seeded sample activity. */
    DEMO,

    /** Original environment was not recorded. */
    UNKNOWN,
}

/** Totals in one currency, counting retained activity only. */
data class CurrencyHistoryTotals(
    /** Approved sales and accepted captures, including tips, in minor units. */
    val salesMinor: Long = 0,
    /** Number of sales and captures. */
    val saleCount: Int = 0,
    /** Tips already included in [salesMinor], in minor units. */
    val tipsMinor: Long = 0,
    /** Accepted refund requests, in minor units; not final settlement. */
    val refundsMinor: Long = 0,
    /** Number of accepted refund requests. */
    val refundCount: Int = 0,
    /** Newly approved holds, in minor units; not outstanding balance. */
    val heldMinor: Long = 0,
    /** Number of new holds. */
    val holdCount: Int = 0,
    /** Number of unresolved operations requiring follow-up. */
    val unresolvedCount: Int = 0,
) {
    /** Local sales minus accepted refund requests, in minor units. */
    val netMinor: Long get() = salesMinor - refundsMinor
}

/** One environment's dated totals and separately disclosed undated activity. */
data class HistoryReportSection(
    /** Original payment environment or demo identity. */
    val scope: HistoryScope,
    /** Processing-day totals by currency code. */
    val totals: Map<String, CurrencyHistoryTotals>,
    /** Paid links with unavailable payment dates, across retained history. */
    val online: Map<String, CurrencyHistoryTotals>,
    /** Accepted captures with unavailable processing dates, across retained history. */
    val undated: Map<String, CurrencyHistoryTotals>,
)

/** Repeatable local summary, never a terminal reconciliation or frozen day close. */
data class HistoryReport(
    /** Device-local day summarized. */
    val date: LocalDate,
    /** Time zone used consistently for every dated activity. */
    val zone: ZoneId,
    /** Generation time in epoch milliseconds. */
    val generatedAt: Long,
    /** Distinct environment sections with separate currency totals. */
    val sections: List<HistoryReportSection>,
)

/** Pure processing-day accounting shared by filtered History and unfiltered reports; no clock or storage access. */
object HistoryAccounting {
    /** Whether [item] has any issue activity; multiple unresolved operations still select its record only once. */
    fun needsAttention(item: HistoryItem): Boolean =
        when (item) {
            is HistoryItem.Sale -> item.sale.hasOperationIssue() || item.sale.hasCaptureIssue()
            is HistoryItem.Refund -> item.refund.status.unresolved
        }

    private fun SaleEntity.hasOperationIssue(): Boolean = status == SaleStatus.PENDING || status == SaleStatus.UNKNOWN || adjustmentPending

    private fun SaleEntity.hasCaptureIssue(): Boolean {
        val standing = standing
        return standing == PaymentStanding.CAPTURE_FAILED || standing == PaymentStanding.CAPTURE_UNKNOWN ||
            standing == PaymentStanding.CAPTURE_SENDING
    }

    /** Original attempts plus monetary activities; missing capture/link dates stay null rather than being guessed. */
    fun activities(items: List<HistoryItem>): List<HistoryActivity> =
        items.flatMap { item ->
            buildList {
                add(HistoryActivity(item, item.createdAt, HistoryActivityKind.OPENED))
                when (item) {
                    is HistoryItem.Sale -> {
                        saleActivities(item).forEach(::add)
                    }

                    is HistoryItem.Refund -> {
                        val refund = item.refund
                        if (refund.status == RefundStatus.REQUESTED && !refund.cancellation) {
                            add(
                                HistoryActivity(
                                    item = item,
                                    at = refund.processedAt ?: refund.createdAt,
                                    kind = HistoryActivityKind.REFUND,
                                    amountMinor = refund.amountMinor,
                                ),
                            )
                        }
                        if (refund.status.unresolved) {
                            add(HistoryActivity(item, refund.createdAt, HistoryActivityKind.ISSUE))
                        }
                    }
                }
            }
        }

    private fun saleActivities(item: HistoryItem.Sale): List<HistoryActivity> {
        val sale = item.sale
        return buildList {
            if (sale.status == SaleStatus.APPROVED) {
                when {
                    sale.paymentLink -> {
                        add(HistoryActivity(item, null, HistoryActivityKind.ONLINE, sale.amountMinor))
                    }

                    sale.manualCapture -> {
                        add(HistoryActivity(item, sale.processedAt ?: sale.createdAt, HistoryActivityKind.HELD, sale.totalMinor))
                        if (sale.standing.charged) {
                            add(
                                HistoryActivity(
                                    item,
                                    sale.captureProcessedAt,
                                    HistoryActivityKind.CAPTURE,
                                    sale.amountMinor,
                                    sale.tipMinor ?: 0,
                                ),
                            )
                        }
                    }

                    else -> {
                        add(HistoryActivity(item, sale.processedAt ?: sale.createdAt, HistoryActivityKind.SALE, sale.amountMinor))
                    }
                }
            }
            if (sale.hasOperationIssue()) {
                add(HistoryActivity(item, sale.createdAt, HistoryActivityKind.ISSUE))
            }
            if (sale.hasCaptureIssue()) {
                add(HistoryActivity(item, sale.captureStartedAt ?: sale.createdAt, HistoryActivityKind.ISSUE))
            }
        }
    }

    /** Totals by currency; opened entries contribute nothing, online amounts are used only in the undated section. */
    fun totals(activities: List<HistoryActivity>): Map<String, CurrencyHistoryTotals> =
        activities.filter { it.kind != HistoryActivityKind.OPENED }.groupBy { it.currency }.toSortedMap().mapValues { (_, entries) ->
            val sales =
                entries.filter {
                    it.kind in setOf(HistoryActivityKind.SALE, HistoryActivityKind.CAPTURE, HistoryActivityKind.ONLINE)
                }
            val refunds = entries.filter { it.kind == HistoryActivityKind.REFUND }
            val holds = entries.filter { it.kind == HistoryActivityKind.HELD }
            CurrencyHistoryTotals(
                salesMinor = sales.sumOf { it.amountMinor },
                saleCount = sales.size,
                tipsMinor = sales.sumOf { it.tipMinor },
                refundsMinor = refunds.sumOf { it.amountMinor },
                refundCount = refunds.size,
                heldMinor = holds.sumOf { it.amountMinor },
                holdCount = holds.size,
                unresolvedCount = entries.count { it.kind == HistoryActivityKind.ISSUE },
            )
        }

    /** Whole-day report from [items]; callers pass all retained records, never History's filtered list. */
    fun report(
        items: List<HistoryItem>,
        date: LocalDate,
        zone: ZoneId,
        generatedAt: Long,
    ): HistoryReport {
        val sections =
            activities(items)
                .groupBy { it.scope }
                .mapNotNull { (scope, entries) ->
                    val dated = entries.filter { it.at?.let { at -> Instant.ofEpochMilli(at).atZone(zone).toLocalDate() } == date }
                    val online = entries.filter { it.kind == HistoryActivityKind.ONLINE }
                    val undated = entries.filter { it.kind == HistoryActivityKind.CAPTURE && it.at == null }
                    if (dated.isEmpty() && online.isEmpty() && undated.isEmpty()) {
                        null
                    } else {
                        HistoryReportSection(scope, totals(dated), totals(online), totals(undated))
                    }
                }.sortedBy { it.scope.ordinal }
        return HistoryReport(date, zone, generatedAt, sections)
    }
}
