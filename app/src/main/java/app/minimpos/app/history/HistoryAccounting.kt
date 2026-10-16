package app.minimpos.app.history

import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.core.money.CurrencySpec
import app.minimpos.terminal.client.TerminalTotalsOutcome
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

/**
 * Comparison of local Mini mPOS accounting and terminal-reported totals for one [currency].
 *
 * @property currency ISO 4217 currency code.
 * @property localSalesMinor Local approved terminal sales in minor units.
 * @property terminalSalesMinor Terminal-reported debit total in minor units.
 * @property localRefundsMinor Local accepted terminal refunds in minor units.
 * @property terminalRefundsMinor Terminal-reported credit/reversal total in minor units.
 */
data class CurrencyReconciliation(
    val currency: String,
    val localSalesMinor: Long = 0,
    val terminalSalesMinor: Long = 0,
    val localRefundsMinor: Long = 0,
    val terminalRefundsMinor: Long = 0,
) {
    /** Whether local sales and refunds both match the terminal's reported totals. */
    val matches: Boolean get() = localSalesMinor == terminalSalesMinor && localRefundsMinor == terminalRefundsMinor
}

/**
 * Comparison between Mini mPOS local records and a terminal `GetTotals` or `SaleReconciliation` answer.
 *
 * @property poiId Terminal POIID compared.
 * @property poiReconciliationId Terminal reconciliation period ID (`POIReconciliationID`), if reported.
 * @property closedPeriod True when the reconciliation period was closed (`SaleReconciliation`).
 * @property currencies Per-currency comparison rows, sorted by currency code.
 * @property unresolvedCount Unresolved terminal operations for [poiId] that may still affect totals.
 * @property offlineSaleCount Number of offline-approved sales in the compared scope.
 * @property unconfirmedBatchCount Highest `unconfirmedBatchCount` reported on offline sales for [poiId].
 */
data class TerminalReconciliationReport(
    val poiId: String,
    val poiReconciliationId: String?,
    val closedPeriod: Boolean,
    val currencies: List<CurrencyReconciliation>,
    val unresolvedCount: Int = 0,
    val offlineSaleCount: Int = 0,
    val unconfirmedBatchCount: Int = 0,
) {
    /** True when every currency matches and no terminal operations remain unresolved. */
    val allMatch: Boolean get() = currencies.all { it.matches } && unresolvedCount == 0
}

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
                        add(
                            HistoryActivity(
                                item,
                                sale.processedAt ?: sale.createdAt,
                                HistoryActivityKind.SALE,
                                sale.amountMinor,
                                sale.tipMinor ?: 0,
                            ),
                        )
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

    /**
     * Compares terminal-reported [outcome] against local terminal operations in [items] for [poiId] (matching
     * [TerminalTotalsOutcome.Answered.poiReconciliationId] when stored, otherwise [date] in [zone]). Excludes payment
     * links, manual-capture holds, hold cancellations and sample records, which do not appear in terminal debit/credit totals.
     */
    fun compareReconciliation(
        items: List<HistoryItem>,
        poiId: String,
        outcome: TerminalTotalsOutcome.Answered,
        date: LocalDate,
        zone: ZoneId,
    ): TerminalReconciliationReport {
        val terminalItems = items.filter { matchesTerminal(it, poiId) }
        val unresolved = terminalItems.count(::isUnresolvedTerminalItem)
        val scoped = filterReconciliationScope(terminalItems, outcome.poiReconciliationId, date, zone)
        val (localSales, localRefunds) = sumReconciliationAmounts(scoped)
        val remoteByCurrency = outcome.totals.associateBy { it.currency.uppercase() }
        val currencies = (localSales.keys + localRefunds.keys + remoteByCurrency.keys).toSortedSet()
        val comparisons =
            currencies.map { code ->
                val spec = CurrencySpec.of(code)
                val remote = remoteByCurrency[code]
                CurrencyReconciliation(
                    currency = code,
                    localSalesMinor = localSales[code] ?: 0L,
                    terminalSalesMinor = remote?.let { spec.toMinor(it.debitAmount) } ?: 0L,
                    localRefundsMinor = localRefunds[code] ?: 0L,
                    terminalRefundsMinor = remote?.let { spec.toMinor(it.creditAmount) } ?: 0L,
                )
            }
        return TerminalReconciliationReport(
            poiId = poiId,
            poiReconciliationId = outcome.poiReconciliationId,
            closedPeriod = outcome.closedPeriod,
            currencies = comparisons,
            unresolvedCount = unresolved,
            offlineSaleCount =
                scoped.count {
                    it is HistoryItem.Sale && it.sale.status == SaleStatus.APPROVED && it.sale.offlineMode != null
                },
            unconfirmedBatchCount =
                scoped
                    .filterIsInstance<HistoryItem.Sale>()
                    .mapNotNull { it.sale.unconfirmedBatchCount }
                    .maxOrNull() ?: 0,
        )
    }

    /** Terminal items in [items] for [poiId] whose terminal outcome is still unresolved (`PENDING` or `UNKNOWN`). */
    fun unresolvedTerminalItems(
        items: List<HistoryItem>,
        poiId: String,
    ): List<HistoryItem> = items.filter { matchesTerminal(it, poiId) && isUnresolvedTerminalItem(it) }

    private fun matchesTerminal(
        item: HistoryItem,
        poiId: String,
    ): Boolean =
        when (item) {
            is HistoryItem.Sale -> {
                val s = item.sale
                !s.sample && !s.paymentLink && (poiId.isBlank() || s.poiId == poiId || s.context?.poiId == poiId)
            }

            is HistoryItem.Refund -> {
                val r = item.refund
                poiId.isBlank() || r.context?.poiId == poiId
            }
        }

    private fun isUnresolvedTerminalItem(item: HistoryItem): Boolean =
        when (item) {
            is HistoryItem.Sale -> item.sale.status == SaleStatus.PENDING || item.sale.status == SaleStatus.UNKNOWN
            is HistoryItem.Refund -> item.refund.status.unresolved
        }

    private fun filterReconciliationScope(
        terminalItems: List<HistoryItem>,
        poiReconciliationId: String?,
        date: LocalDate,
        zone: ZoneId,
    ): List<HistoryItem> {
        val hasPeriodIds =
            poiReconciliationId != null &&
                terminalItems.any { item ->
                    when (item) {
                        is HistoryItem.Sale -> item.sale.poiReconciliationId != null
                        is HistoryItem.Refund -> item.refund.poiReconciliationId != null
                    }
                }
        return terminalItems.filter { item ->
            if (hasPeriodIds) {
                when (item) {
                    is HistoryItem.Sale -> item.sale.poiReconciliationId == poiReconciliationId
                    is HistoryItem.Refund -> item.refund.poiReconciliationId == poiReconciliationId
                }
            } else {
                val at =
                    when (item) {
                        is HistoryItem.Sale -> item.sale.processedAt ?: item.sale.createdAt
                        is HistoryItem.Refund -> item.refund.processedAt ?: item.refund.createdAt
                    }
                Instant.ofEpochMilli(at).atZone(zone).toLocalDate() == date
            }
        }
    }

    private fun sumReconciliationAmounts(scoped: List<HistoryItem>): Pair<Map<String, Long>, Map<String, Long>> {
        val localSales = mutableMapOf<String, Long>()
        val localRefunds = mutableMapOf<String, Long>()
        scoped.forEach { item ->
            when (item) {
                is HistoryItem.Sale -> {
                    val s = item.sale
                    if (s.status == SaleStatus.APPROVED && !s.manualCapture) {
                        localSales[s.currency] = (localSales[s.currency] ?: 0L) + s.amountMinor
                    }
                }

                is HistoryItem.Refund -> {
                    val r = item.refund
                    if (r.status == RefundStatus.REQUESTED && !r.cancellation) {
                        localRefunds[r.currency] = (localRefunds[r.currency] ?: 0L) + r.amountMinor
                    }
                }
            }
        }
        return localSales to localRefunds
    }
}
