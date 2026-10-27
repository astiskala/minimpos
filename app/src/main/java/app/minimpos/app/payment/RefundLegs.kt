package app.minimpos.app.payment

import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.RefundStart
import app.minimpos.core.access.ManagerAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import kotlin.uuid.Uuid

/** Why a leg of a multi-leg return was not attempted; nothing was stored or sent for it. */
enum class RefundLegSkip {
    /** An earlier leg was not accepted, so later legs wait for staff review. */
    EARLIER_LEG,

    /** Cash returns follow the configured Manager permission and recheck approval. */
    MANAGER_APPROVAL,

    /** The sale's balances or returns changed since the review; review the return again. */
    BALANCE_CHANGED,

    /** Another refund was already running. */
    BUSY,
}

/**
 * One leg of a multi-leg return as it stands.
 *
 * @property amount What it returns, as requested.
 * @property cash Whether it hands cash back locally rather than reversing a card.
 * @property refundId The stored refund once persisted; null while waiting or when skipped.
 * @property status Its stored outcome once known; null while waiting, being sent or skipped.
 * @property skipped Why it was not attempted; null otherwise.
 */
data class RefundLegState(
    val amount: OperationAmount,
    val cash: Boolean,
    val refundId: String? = null,
    val status: RefundStatus? = null,
    val skipped: RefundLegSkip? = null,
)

/**
 * The multi-leg return running or just finished.
 *
 * @property legs Its legs in sending order: cards first, then cash.
 * @property finished Whether every leg was attempted or skipped.
 */
data class RefundRun(
    val legs: List<RefundLegState>,
    val finished: Boolean = false,
)

/**
 * Returns one sale's refund across its original tenders, one independently persisted leg at a time: each card leg is
 * a terminal reversal of its own transaction in its original context through [FinancialOperations], waited for until
 * stored, and cash legs are handed back locally only after every card leg was accepted. A leg that is not accepted
 * (refused, failed or unknown) stops the rest, so staff review the sale again; pending and unknown legs keep reserving
 * their tender's balance. Each leg carries the review snapshot plus the legs this run stored before it, so a change by
 * anyone else refuses it as stale. The Manager approval taken when staff confirmed the return covers every leg; cash
 * legs follow their configured Manager permissions. Process death leaves stored legs to their usual recovery and starts nothing.
 *
 * @param scope Application scope the run survives navigation in.
 * @param operations Sends card legs and follows their progress.
 * @param records Records cash legs and reads stored outcomes.
 * @param approval Manager approval held by the run.
 * @param clock Stamps cash legs.
 * @param newId Generates cash leg IDs.
 */
class RefundLegs(
    private val scope: CoroutineScope,
    private val operations: FinancialOperations,
    private val records: RefundRepository,
    private val approval: ManagerApproval,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { Uuid.random().toString() },
) {
    /** The run being followed, or null when none was started or the last one was acknowledged. */
    val state: StateFlow<RefundRun?>
        field = MutableStateFlow<RefundRun?>(null)

    init {
        // Cash legs and the gaps between terminal legs hold history and setup steady until the whole run finishes.
        operations.include(OperationKind({ state.value?.finished == false }))
    }

    /**
     * Starts returning [legs] (requests from one review) in the background; false, with nothing stored or sent, when a
     * run or another refund is already in progress or there is nothing to return.
     */
    @Synchronized
    fun start(legs: List<RefundStart>): Boolean {
        if (legs.isEmpty() || state.value?.finished == false || operations.currentRefund is TransactionState.Processing) return false
        val ordered = legs.sortedBy { it.cash }
        val grant = approval.grant()
        state.value = RefundRun(ordered.map { RefundLegState(OperationAmount(it.amountMinor, it.currency), it.cash) })
        scope.launch { run(ordered, grant) }
        return true
    }

    /** Leaves a finished run, so the next one can start. */
    fun acknowledge() {
        state.update { it?.takeUnless { run -> run.finished } }
    }

    private suspend fun run(
        legs: List<RefundStart>,
        grant: ManagerApproval.Grant,
    ) {
        val reviewed = legs.first().reviewedRefunds?.toMutableMap()
        var stop: RefundLegSkip? = null
        // Ends the run even when a leg fails unexpectedly, so later returns are not refused as already running.
        try {
            legs.forEachIndexed { index, leg ->
                if (stop != null) {
                    update(index) { it.copy(skipped = RefundLegSkip.EARLIER_LEG) }
                    return@forEachIndexed
                }
                val request = leg.copy(reviewedRefunds = reviewed?.toMap())
                val (id, status, skip) = if (leg.cash) cash(request, grant) else card(request, grant)
                update(index) { it.copy(refundId = id, status = status, skipped = skip) }
                if (id != null && status != null && status != RefundStatus.FAILED) reviewed?.put(id, status)
                if (status != RefundStatus.REQUESTED) stop = skip ?: RefundLegSkip.EARLIER_LEG
            }
        } finally {
            state.update { it?.copy(finished = true) }
        }
    }

    private suspend fun card(
        request: RefundStart,
        grant: ManagerApproval.Grant,
    ): Triple<String?, RefundStatus?, RefundLegSkip?> {
        val id = operations.refundLeg(request, grant) ?: return Triple(null, null, RefundLegSkip.BUSY)
        operations.refundProgress.first { it is TransactionState.Finished && it.id == id }
        val status = records.get(id)?.status
        operations.acknowledgeRefund()
        return Triple(id, status, null)
    }

    private suspend fun cash(
        request: RefundStart,
        grant: ManagerApproval.Grant,
    ): Triple<String?, RefundStatus?, RefundLegSkip?> {
        if (!approval
                .forAction(
                    ManagerAction.CASH_REFUND,
                ).allows(grant)
        ) {
            return Triple(null, null, RefundLegSkip.MANAGER_APPROVAL)
        }
        val id = newId()
        val recorded = records.recordCashReturn(request.pendingRefund(id, clock.millis()), request.reviewedRefunds)
        return if (recorded) Triple(id, RefundStatus.REQUESTED, null) else Triple(null, null, RefundLegSkip.BALANCE_CHANGED)
    }

    private fun update(
        index: Int,
        transform: (RefundLegState) -> RefundLegState,
    ) = state.update { run -> run?.copy(legs = run.legs.mapIndexed { i, leg -> if (i == index) transform(leg) else leg }) }
}
