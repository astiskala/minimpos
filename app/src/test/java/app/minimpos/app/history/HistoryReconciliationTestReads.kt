package app.minimpos.app.history

import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.terminal.client.TerminalTotalsOutcome
import java.time.LocalDate
import java.time.ZoneId

/** Small-fixture convenience for the bounded production reconciliation accumulator. */
fun HistoryAccounting.compareReconciliation(
    items: List<HistoryItem>,
    poiId: String,
    outcome: TerminalTotalsOutcome.Answered,
    date: LocalDate,
    zone: ZoneId,
): TerminalReconciliationReport = HistoryAccounting.ReconciliationAccumulator(poiId, outcome, date, zone).apply { add(items) }.report()
