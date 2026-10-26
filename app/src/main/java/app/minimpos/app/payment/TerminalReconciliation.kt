package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.TerminalReconciliationReport
import app.minimpos.app.terminal.Attempt
import app.minimpos.app.terminal.TerminalGateway
import app.minimpos.app.terminal.TerminalState
import app.minimpos.core.access.ManagerAction
import app.minimpos.terminal.client.TerminalTotalsOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId

/** What one terminal reconciliation ended with, see [TerminalReconciliation.reconcile]. */
sealed interface ReconciliationOutcome {
    /**
     * The terminal answered; its totals are compared in [report], whether or not they match.
     */
    data class Reconciled(
        val report: TerminalReconciliationReport,
    ) : ReconciliationOutcome

    /**
     * No totals were compared, so nothing closed either.
     *
     * @property failure What must be entered first, or why the terminal could not be asked.
     */
    data class Failed(
        val failure: Failure,
    ) : ReconciliationOutcome
}

/**
 * One terminal reconciliation, run the way Home's Reconciliation screen offers it: every unresolved terminal operation
 * of the current terminal is checked again first ([FinancialOperations.recheck], the way it was sent), then the
 * terminal's current totals are read — or its period closed when asked — and compared against local accounting for the
 * day ([HistoryAccounting.ReconciliationAccumulator]). Closing a period keeps its original Manager approval and
 * checks it again immediately before sending, so expired approval sends nothing.
 *
 * @param history The retained activity the unresolved operations are read from and compared.
 * @param operations Asks the terminal again about unresolved operations, the way each was sent.
 * @param gateway Reads the terminal's totals or closes its reconciliation period.
 * @param terminal The current terminal, whose POIID scopes the unresolved operations and the comparison.
 * @param approval Manager approval of closing the period: taken when the reconciliation starts, checked again before
 *   sending.
 * @param zone The time zone the compared [LocalDate] is counted in.
 */
class TerminalReconciliation(
    private val history: HistoryRepository,
    private val operations: FinancialOperations,
    private val gateway: TerminalGateway,
    private val terminal: Flow<TerminalState>,
    private val approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /**
     * Reconciles the terminal's totals with local accounting for [date] ([ReconciliationOutcome.Reconciled]), or
     * returns why nothing could be compared or closed ([ReconciliationOutcome.Failed]). Call it when the operator starts
     * it; closing without Manager approval sends nothing and changes no sale.
     */
    suspend fun reconcile(
        date: LocalDate = LocalDate.now(zone()),
        closePeriod: Boolean = false,
    ): ReconciliationOutcome {
        val grant = approval.grant()
        val poiId = terminal.first().poiId.orEmpty()
        val unresolved =
            history.reader.fold(mutableListOf<StoredTransaction>()) { ids, batch ->
                ids.apply { addAll(HistoryAccounting.unresolvedTerminalItems(batch, poiId).map { it.storedTransaction() }) }
            }
        unresolved.forEach { operations.recheck(it) }
        return when (
            val attempt =
                gateway.totals(closePeriod) {
                    approval.forAction(if (closePeriod) ManagerAction.CLOSE_PERIOD else ManagerAction.TOTALS).allows(grant)
                }
        ) {
            is Attempt.NotSetUp -> {
                ReconciliationOutcome.Failed(Failure.NotSetUp(attempt.problem))
            }

            is Attempt.Made -> {
                when (val outcome = attempt.result) {
                    is TerminalTotalsOutcome.Failed -> {
                        ReconciliationOutcome.Failed(Failure.Remote(outcome.fault))
                    }

                    is TerminalTotalsOutcome.Answered -> {
                        ReconciliationOutcome.Reconciled(
                            history.reader
                                .fold(HistoryAccounting.ReconciliationAccumulator(poiId, outcome, date, zone())) { total, batch ->
                                    total.apply { add(batch) }
                                }.report(),
                        )
                    }
                }
            }
        }
    }

    private fun HistoryItem.storedTransaction(): StoredTransaction =
        when (this) {
            is HistoryItem.Sale -> StoredTransaction.Sale(id)
            is HistoryItem.Refund -> StoredTransaction.Refund(id)
            is HistoryItem.GiftCard -> StoredTransaction.GiftCard(id)
        }
}
