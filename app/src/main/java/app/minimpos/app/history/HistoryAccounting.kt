package app.minimpos.app.history

import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.HistoryItem
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
 * Comparison between Mini mPOS local records and one terminal's `GetTotals` or `SaleReconciliation` answer.
 *
 * @property poiReconciliationId Terminal reconciliation period ID (`POIReconciliationID`), if reported.
 * @property closedPeriod True when the reconciliation period was closed (`SaleReconciliation`).
 * @property currencies Per-currency comparison rows, sorted by currency code.
 * @property unresolvedCount Unresolved operations on the compared terminal that may still affect totals.
 * @property offlineSaleCount Number of offline-approved sales in the compared scope.
 * @property unconfirmedBatchCount Highest `unconfirmedBatchCount` reported on the compared terminal's offline sales.
 */
data class TerminalReconciliationReport(
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

/**
 * Processing-day totals per currency for the activities the filter shows: approved sales, accepted captures with
 * their tips, accepted refunds and newly authorized holds. Later capture or cancellation does not erase original
 * hold activity. Manual-capture bills count as sales only on dated capture acceptance.
 *
 * @property salesMinor Approved sales' amounts (with tips, or what a pre-authorisation captured) by ISO 4217 currency
 *   code, in minor units.
 * @property saleCount Number of approved sales.
 * @property refundsMinor Accepted refunds' amounts by currency code, in minor units.
 * @property refundCount Number of accepted refunds.
 * @property preAuthsMinor Newly authorized manual-capture amounts by currency code, in minor units;
 *   unchanged by later capture or cancellation.
 * @property preAuthCount Number of newly authorized holds.
 * @property tipsMinor The tips included in [salesMinor], by currency code, in minor units; only currencies with tips.
 */
data class DayTotals(
    val salesMinor: Map<String, Long>,
    val saleCount: Int,
    val refundsMinor: Map<String, Long>,
    val refundCount: Int,
    val preAuthsMinor: Map<String, Long> = emptyMap(),
    val preAuthCount: Int = 0,
    val tipsMinor: Map<String, Long> = emptyMap(),
)

/**
 * One day of history, in the time zone it was grouped in (see [HistoryAccounting.days]).
 *
 * @property date The local date.
 * @property totals The day's totals.
 * @property items Original transactions with dated activity on this day, newest activity first.
 * @property captures Sale IDs whose accepted capture occurred on this day.
 * @property times Most recent activity per original transaction, in epoch milliseconds.
 */
data class HistoryDay(
    val date: LocalDate,
    val totals: DayTotals,
    val items: List<HistoryItem>,
    /** Sales whose accepted capture occurred on this day. */
    val captures: Set<String> = emptySet(),
    /** Most recent dated activity per original transaction, in epoch milliseconds. */
    val times: Map<String, Long> = emptyMap(),
)

/** Pure processing-day accounting shared by filtered History and unfiltered reports; no clock or storage access. */
object HistoryAccounting {
    /** Adds independent batches of one processing day's totals without retaining their transaction bodies. */
    fun merge(
        left: DayTotals,
        right: DayTotals,
    ): DayTotals {
        fun sum(
            a: Map<String, Long>,
            b: Map<String, Long>,
        ) = (a.keys + b.keys).associateWith { (a[it] ?: 0) + (b[it] ?: 0) }
        return DayTotals(
            sum(left.salesMinor, right.salesMinor),
            left.saleCount + right.saleCount,
            sum(left.refundsMinor, right.refundsMinor),
            left.refundCount + right.refundCount,
            sum(left.preAuthsMinor, right.preAuthsMinor),
            left.preAuthCount + right.preAuthCount,
            sum(left.tipsMinor, right.tipsMinor),
        )
    }

    /**
     * Bounded, pure whole-day report aggregation, including retained online and undated captures across every batch.
     * @param date Local processing date.
     * @param zone Time zone used for every batch.
     * @param generatedAt One generation time, in epoch milliseconds.
     */
    class ReportAccumulator(
        private val date: LocalDate,
        private val zone: ZoneId,
        private val generatedAt: Long,
    ) {
        private val sections = mutableMapOf<HistoryScope, HistoryReportSection>()

        /** Adds independent records exactly once, retaining only per-scope/currency totals. */
        fun add(items: List<HistoryItem>) {
            HistoryAccounting.report(items, date, zone, generatedAt).sections.forEach { section ->
                val previous = sections[section.scope]
                sections[section.scope] =
                    if (previous == null) {
                        section
                    } else {
                        HistoryReportSection(
                            section.scope,
                            mergeCurrencies(previous.totals, section.totals),
                            mergeCurrencies(previous.online, section.online),
                            mergeCurrencies(previous.undated, section.undated),
                        )
                    }
            }
        }

        /** Current summary; no original records are retained. */
        fun report(): HistoryReport = HistoryReport(date, zone, generatedAt, sections.values.sortedBy { it.scope.ordinal })

        private fun mergeCurrencies(
            left: Map<String, CurrencyHistoryTotals>,
            right: Map<String, CurrencyHistoryTotals>,
        ) = (left.keys + right.keys).sorted().associateWith { code ->
            val a = left[code] ?: CurrencyHistoryTotals()
            val b = right[code] ?: CurrencyHistoryTotals()
            CurrencyHistoryTotals(
                a.salesMinor + b.salesMinor,
                a.saleCount + b.saleCount,
                a.tipsMinor + b.tipsMinor,
                a.refundsMinor + b.refundsMinor,
                a.refundCount + b.refundCount,
                a.heldMinor + b.heldMinor,
                a.holdCount + b.holdCount,
                a.unresolvedCount + b.unresolvedCount,
            )
        }
    }

    /**
     * Pure reconciliation aggregation with both date and period candidates until all retained period evidence is known.
     * @param poiId Terminal identity; blank includes all terminal records.
     * @param outcome Authoritative terminal totals, included once in the final comparison.
     * @param date Fallback local processing date when no retained period IDs exist.
     * @param zone Time zone for the fallback date.
     */
    class ReconciliationAccumulator(
        private val poiId: String,
        private val outcome: TerminalTotalsOutcome.Answered,
        private val date: LocalDate,
        private val zone: ZoneId,
    ) {
        private var hasPeriods = false
        private var unresolved = 0
        private val day = ScopeTotals()
        private val period = ScopeTotals()

        /** Adds independent records exactly once without retaining their bodies. */
        fun add(items: List<HistoryItem>) {
            val relevant = items.filter { matchesTerminal(it, poiId) }
            hasPeriods = hasPeriods || (outcome.poiReconciliationId != null && relevant.any { periodId(it) != null })
            unresolved += relevant.count(::isUnresolvedTerminalItem)
            relevant.forEach { item ->
                if (periodId(item) == outcome.poiReconciliationId) period.add(item)
                if (Instant.ofEpochMilli(processedAt(item)).atZone(zone).toLocalDate() == date) day.add(item)
            }
        }

        /** Final comparison; period evidence from any batch controls every batch's scope. */
        fun report(): TerminalReconciliationReport {
            val scope = if (hasPeriods) period else day
            val remoteByCurrency = outcome.totals.associateBy { it.currency.uppercase() }
            val currencies = (scope.sales.keys + scope.refunds.keys + remoteByCurrency.keys).toSortedSet()
            return TerminalReconciliationReport(
                poiReconciliationId = outcome.poiReconciliationId,
                closedPeriod = outcome.closedPeriod,
                currencies =
                    currencies.map { code ->
                        val remote = remoteByCurrency[code]
                        CurrencyReconciliation(
                            currency = code,
                            localSalesMinor = scope.sales[code] ?: 0L,
                            terminalSalesMinor = remote?.debitMinor ?: 0L,
                            localRefundsMinor = scope.refunds[code] ?: 0L,
                            terminalRefundsMinor = remote?.creditMinor ?: 0L,
                        )
                    },
                unresolvedCount = unresolved,
                offlineSaleCount = scope.offlineSales,
                unconfirmedBatchCount = scope.unconfirmedBatches,
            )
        }

        private fun periodId(item: HistoryItem): String? =
            when (item) {
                is HistoryItem.Sale -> item.sale.poiReconciliationId
                is HistoryItem.Refund -> item.refund.poiReconciliationId
            }

        private fun processedAt(item: HistoryItem): Long =
            when (item) {
                is HistoryItem.Sale -> item.sale.processedAt ?: item.sale.createdAt
                is HistoryItem.Refund -> item.refund.processedAt ?: item.refund.createdAt
            }

        /** Local amounts by currency and counts for one candidate scope (the period, or the fallback date). */
        private class ScopeTotals {
            val sales = mutableMapOf<String, Long>()
            val refunds = mutableMapOf<String, Long>()
            var offlineSales = 0
            var unconfirmedBatches = 0

            fun add(item: HistoryItem) {
                when (item) {
                    is HistoryItem.Sale -> {
                        val s = item.sale
                        if (s.status == SaleStatus.APPROVED && !s.manualCapture) sales.merge(s.currency, s.amountMinor, Long::plus)
                        if (s.status == SaleStatus.APPROVED && s.offlineMode != null) offlineSales++
                        s.unconfirmedBatchCount?.let { unconfirmedBatches = maxOf(unconfirmedBatches, it) }
                    }

                    is HistoryItem.Refund -> {
                        val r = item.refund
                        if (r.status == RefundStatus.REQUESTED && !r.cancellation) refunds.merge(r.currency, r.amountMinor, Long::plus)
                    }
                }
            }
        }
    }

    /**
     * Whether [item] has any issue activity ([SaleEntity.issues], or an unresolved refund); multiple unresolved
     * operations still select its record only once.
     */
    fun needsAttention(item: HistoryItem): Boolean =
        when (item) {
            is HistoryItem.Sale -> item.sale.issues.isNotEmpty()
            is HistoryItem.Refund -> item.refund.status.unresolved
        }

    /**
     * [items] grouped into processing days in [zone], newest day first, each with its totals: what History lists for
     * the transactions a filter shows. Activities without a date (paid links, undated captures) are left out.
     */
    fun days(
        items: List<HistoryItem>,
        zone: ZoneId,
    ): List<HistoryDay> =
        activities(items)
            .filter { it.at != null }
            .groupBy { Instant.ofEpochMilli(checkNotNull(it.at)).atZone(zone).toLocalDate() }
            .map { (date, activities) ->
                val totals = totals(activities)
                val sorted = activities.sortedByDescending { it.at }
                HistoryDay(
                    date = date,
                    totals =
                        DayTotals(
                            salesMinor = totals.filterValues { it.saleCount > 0 }.mapValues { it.value.salesMinor },
                            saleCount = totals.values.sumOf { it.saleCount },
                            refundsMinor = totals.filterValues { it.refundCount > 0 }.mapValues { it.value.refundsMinor },
                            refundCount = totals.values.sumOf { it.refundCount },
                            preAuthsMinor = totals.filterValues { it.holdCount > 0 }.mapValues { it.value.heldMinor },
                            preAuthCount = totals.values.sumOf { it.holdCount },
                            tipsMinor = totals.filterValues { it.tipsMinor > 0 }.mapValues { it.value.tipsMinor },
                        ),
                    items = sorted.map { it.item }.distinctBy { it.id },
                    captures = activities.filter { it.kind == HistoryActivityKind.CAPTURE }.map { it.item.id }.toSet(),
                    times = sorted.distinctBy { it.item.id }.associate { it.item.id to checkNotNull(it.at) },
                )
            }.sortedByDescending { it.date }

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
            val issues = sale.issues
            if (PaymentIssue.OUTCOME in issues || PaymentIssue.ADJUSTMENT in issues) {
                add(HistoryActivity(item, sale.createdAt, HistoryActivityKind.ISSUE))
            }
            if (PaymentIssue.CAPTURE in issues) {
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
     * Terminal items in [items] for [poiId] whose terminal outcome is still unresolved (a sale's
     * [PaymentIssue.OUTCOME], or a pending or unknown refund); adjustments and captures go through the Checkout API and
     * are not terminal operations.
     */
    fun unresolvedTerminalItems(
        items: List<HistoryItem>,
        poiId: String,
    ): List<HistoryItem> = items.filter { matchesTerminal(it, poiId) && isUnresolvedTerminalItem(it) }

    /** Whether this record belongs to the requested terminal; blank [poiId] includes every terminal. */
    internal fun matchesTerminal(
        item: HistoryItem,
        poiId: String,
    ): Boolean =
        when (item) {
            is HistoryItem.Sale -> {
                val s = item.sale
                s.terminalOperation && (poiId.isBlank() || s.poiId == poiId || s.context?.poiId == poiId)
            }

            is HistoryItem.Refund -> {
                val r = item.refund
                r.terminalOperation && (poiId.isBlank() || r.context?.poiId == poiId)
            }
        }

    /** Whether the terminal outcome remains unresolved, excluding Checkout adjustments and captures. */
    internal fun isUnresolvedTerminalItem(item: HistoryItem): Boolean =
        when (item) {
            is HistoryItem.Sale -> PaymentIssue.OUTCOME in item.sale.issues
            is HistoryItem.Refund -> item.refund.status.unresolved
        }
}
