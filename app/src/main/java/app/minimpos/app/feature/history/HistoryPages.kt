package app.minimpos.app.feature.history

import app.minimpos.app.data.db.HistoryKey
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.repo.HistoryBatch
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.history.DayTotals
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.HistoryDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Bounded row pages with independently streamed, whole-day summaries; all monetary meaning stays in accounting. */
internal class HistoryPages(
    private val history: HistoryRepository.Reader,
    /** Pure accounting function; tests count evaluations without relying on timing. */
    private val accounting: (List<HistoryItem>, ZoneId) -> List<HistoryDay> = HistoryAccounting::days,
) {
    private var cachedFor: Pair<Any, ZoneId>? = null
    private val summaries = linkedMapOf<LocalDate, DayTotals>()

    /** Reads up to 100 entries across seven matching dates, preserving global search and complete filtered-day totals. */
    suspend fun read(
        before: HistoryKey?,
        filter: HistoryFilter,
        search: HistorySearch,
        zone: ZoneId,
        cacheToken: Any = Any(),
    ): Pair<List<HistoryDay>, Boolean> {
        val identity = cacheToken to zone
        if (cachedFor != identity) {
            summaries.clear()
            cachedFor = identity
        }
        val rows = mutableListOf<HistoryDay>()
        val dates = mutableSetOf<LocalDate>()
        var cursor = before
        var older = false
        while (true) {
            val batch = history.page(cursor)
            val matches = BatchMatches(batch, filter, search, zone, accounting)
            for (key in batch.keys) {
                val day = matches.find(key)
                val full = rows.size == PAGE_ROWS || (day?.date !in dates && dates.size == PAGE_DAYS)
                if (day != null && full) {
                    older = true
                    break
                }
                if (day != null) {
                    rows += day
                    dates += day.date
                }
            }
            if (older || batch.keys.isEmpty()) break
            cursor = batch.keys.last()
        }
        val days =
            rows.groupBy { it.date }.map { (date, entries) ->
                HistoryDay(
                    date,
                    cachedSummary(date, filter, search, zone),
                    entries.flatMap { it.items },
                    entries.flatMap { it.captures }.toSet(),
                    entries.flatMap { it.times.entries }.associate { it.toPair() },
                )
            }
        return days to older
    }

    private suspend fun cachedSummary(
        date: LocalDate,
        filter: HistoryFilter,
        search: HistorySearch,
        zone: ZoneId,
    ): DayTotals =
        summaries[date] ?: summary(date, filter, search, zone).also {
            summaries[date] = it
            if (summaries.size > PAGE_DAYS) summaries.remove(summaries.keys.first())
        }

    private suspend fun summary(
        date: LocalDate,
        filter: HistoryFilter,
        search: HistorySearch,
        zone: ZoneId,
    ): DayTotals {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        var cursor =
            HistoryKey(
                "",
                date
                    .plusDays(1)
                    .atStartOfDay(zone)
                    .toInstant()
                    .toEpochMilli(),
                false,
            )
        var totals = DayTotals()
        while (true) {
            val batch = history.page(cursor, start)
            val matches = BatchMatches(batch, filter, search, zone, accounting)
            if (batch.keys.isEmpty()) return totals
            batch.keys.forEach { key ->
                matches.find(key)?.takeIf { it.date == date }?.let {
                    totals += it.totals
                }
            }
            cursor = batch.keys.last()
        }
    }

    private class BatchMatches(
        private val batch: HistoryBatch,
        private val filter: HistoryFilter,
        private val search: HistorySearch,
        private val zone: ZoneId,
        private val accounting: (List<HistoryItem>, ZoneId) -> List<HistoryDay>,
    ) {
        private val days = mutableMapOf<String, List<HistoryDay>>()

        fun find(key: HistoryKey): HistoryDay? {
            val calculated =
                days.getOrPut(key.id) {
                    val item = batch.item(key) ?: return@getOrPut emptyList()
                    val sale =
                        when (item) {
                            is HistoryItem.Sale -> item.sale
                            is HistoryItem.Refund -> item.refund.saleId?.let(batch.sales::get)
                            is HistoryItem.GiftCard -> null
                        }
                    val preAuths = sale?.takeIf { it.kind == SaleKind.PRE_AUTHORISATION }?.let { setOf(it.id) }.orEmpty()
                    if (!filter.matches(item, preAuths) || !search.matches(item, sale)) {
                        emptyList()
                    } else {
                        accounting(listOf(item), zone)
                    }
                }
            val date = Instant.ofEpochMilli(key.at).atZone(zone).toLocalDate()
            // A record/day admits only its latest activity, including when candidates straddle batches.
            return calculated.firstOrNull { it.date == date && it.times[key.id] == key.at }
        }
    }

    private companion object {
        const val PAGE_ROWS = 100
        const val PAGE_DAYS = 7
    }
}
