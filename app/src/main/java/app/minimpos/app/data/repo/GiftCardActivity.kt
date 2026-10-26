package app.minimpos.app.data.repo

import app.minimpos.app.data.db.GiftCardOperationEntity
import app.minimpos.app.data.db.GiftCardStage
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.core.giftcard.GiftCardOperation
import app.minimpos.core.giftcard.GiftCardProvider

/** What staff may do next with a stored-value activity. */
enum class GiftCardAction {
    /** Ask the terminal again about a stage whose outcome is unknown, with its original message category. */
    CHECK_AGAIN,

    /** Activate or load again after the funding was confirmed and no value was delivered yet. */
    CONTINUE,

    /** Refund the funding card payment because no value was delivered (or the load was undone). */
    REFUND_FUNDING,

    /** Record that funds received outside the app were given back because no value was delivered. */
    RETURN_FUNDS,

    /** Confirm the cash of an approved cash-out was handed to the shopper. */
    CONFIRM_HANDOVER,

    /** Undo the activity's load: the card's most recent load within 24 hours. */
    UNDO_LOAD,
}

/**
 * One stored-value activity read from its stages: what it is, how far it got and what it still needs. The only reading
 * of gift-card stages; screens, history, recovery and housekeeping never rederive it. Pure and immutable.
 *
 * @property stages Every stage of the activity, oldest first; never empty.
 */
data class GiftCardActivity(
    val stages: List<GiftCardOperationEntity>,
) {
    init {
        require(stages.isNotEmpty() && stages.all { it.groupId == stages.first().groupId }) { "One activity's stages" }
    }

    /** The activity's ID. */
    val groupId: String get() = stages.first().groupId

    /** When it began, epoch milliseconds. */
    val createdAt: Long get() = stages.first().createdAt

    /** Its currency. */
    val currency: String get() = stages.first().currency

    /** The funding stage; null for an activity without funding. */
    val funding: GiftCardOperationEntity? get() =
        stages.firstOrNull {
            it.stage == GiftCardStage.FUNDING_CARD ||
                it.stage == GiftCardStage.FUNDING_EXTERNAL
        }

    /** What it set out to do: the funded value stage, else its first stage. */
    val kind: GiftCardStage get() = funding?.funds ?: stages.first().stage

    /** The latest activation or load attempt; null before one was stored. */
    val value: GiftCardOperationEntity? get() = stages.lastOrNull { it.stage == GiftCardStage.ACTIVATE || it.stage == GiftCardStage.LOAD }

    /** The main value change of an activity without funding: undo, deactivation, cash-out or refund; else [value]. */
    val change: GiftCardOperationEntity? get() =
        stages.firstOrNull { it.stage.changesValue && it.stage != GiftCardStage.UNDO_LOAD }
            ?: value

    /** The provider of its card; null when no stage names one. */
    val provider: GiftCardProvider? get() =
        stages.firstNotNullOfOrNull { stage ->
            GiftCardProvider.entries.firstOrNull {
                it.name ==
                    stage.provider
            }
        }

    /** The card's last four digits, as last reported; null when unknown. */
    val cardLastFour: String? get() = stages.lastOrNull { it.cardLastFour != null }?.cardLastFour

    /** The card's balance as last reported; null when unreported. */
    val balanceMinor: Long? get() = stages.lastOrNull { it.balanceMinor != null && it.status == GiftCardStatus.SUCCEEDED }?.balanceMinor

    private val undo: GiftCardOperationEntity? get() = stages.lastOrNull { it.stage == GiftCardStage.UNDO_LOAD && !it.status.notDone }

    /** Whether the load was undone. */
    val undone: Boolean get() = undo?.status == GiftCardStatus.SUCCEEDED

    /** Whether the activation or load delivered its value and it was not undone. */
    val delivered: Boolean get() = value?.status == GiftCardStatus.SUCCEEDED && !undone

    /** Whether any stage still needs a status check or a confirmation. */
    val unresolved: Boolean get() = stages.any { it.status.unresolved }

    private val repaired: Boolean
        get() =
            stages.any {
                (it.stage == GiftCardStage.FUNDING_REFUND || it.stage == GiftCardStage.FUNDING_RETURNED) && !it.status.notDone
            }

    private val funded: Boolean get() = funding?.status == GiftCardStatus.SUCCEEDED && funding?.movedMinor?.let { it > 0 } == true

    /** Whether confirmed funding has neither delivered value nor been repaired: a visible repair is needed. */
    val needsRepair: Boolean get() = funded && !delivered && !repaired && !unresolved

    /** Cash-out handover, read through the same durable payout rules as card cashback. */
    val cashHandover: CashHandover? get() =
        stages.firstOrNull { it.stage == GiftCardStage.CASH_HANDOVER }?.let {
            CashHandover(
                it.amountMinor,
                it.amountMinor,
                it.amountMinor,
                (it.processedAt ?: it.createdAt).takeIf { _ -> it.status == GiftCardStatus.SUCCEEDED },
            )
        }

    /** The checks, confirmations and repairs it still needs, whatever the time. */
    val attention: Set<GiftCardAction>
        get() =
            buildSet {
                if (stages.any { it.status == GiftCardStatus.UNKNOWN && it.stage.sent }) add(GiftCardAction.CHECK_AGAIN)
                if (cashHandover?.needsAttention == true) {
                    add(GiftCardAction.CONFIRM_HANDOVER)
                }
                if (needsRepair) {
                    if (value == null || value?.status?.notDone == true) add(GiftCardAction.CONTINUE)
                    add(if (funding?.stage == GiftCardStage.FUNDING_CARD) GiftCardAction.REFUND_FUNDING else GiftCardAction.RETURN_FUNDS)
                }
            }

    /** Whether it still needs staff: a check, a confirmation or a repair; retention keeps it. */
    val needsAttention: Boolean get() = attention.isNotEmpty()

    /**
     * What staff may do now: [attention], plus undoing its load while [now] (epoch milliseconds) is within the undo
     * window and no later load of the same card appears among [others] (other activities known locally).
     */
    fun actions(
        now: Long,
        others: List<GiftCardActivity> = emptyList(),
    ): Set<GiftCardAction> = if (undoable(now, others)) attention + GiftCardAction.UNDO_LOAD else attention

    private fun undoable(
        now: Long,
        others: List<GiftCardActivity>,
    ): Boolean {
        val load = undoableLoad(now) ?: return false
        val sameCard = others.filter { it.groupId != groupId && it.provider == provider && it.cardLastFour == cardLastFour }
        return sameCard.none { it.loadedAfter(load.createdAt) }
    }

    /** This activity's load while it may still be undone at [now]: delivered, referenced, within the window. */
    private fun undoableLoad(now: Long): GiftCardOperationEntity? {
        val load = value?.takeIf { it.stage == GiftCardStage.LOAD && delivered && undo == null && !unresolved } ?: return null
        val fresh = load.processedAt?.let { now - it <= GiftCardProvider.UNDO_WINDOW_MILLIS } == true
        val referenced = load.poiTransactionId != null && load.poiTimestamp != null
        return load.takeIf { fresh && referenced && provider?.supports(GiftCardOperation.UNDO_LOAD) == true }
    }

    /** Whether a later activation or load of its card was stored after [at] (epoch milliseconds) and may have happened. */
    private fun loadedAfter(at: Long): Boolean =
        stages.any { (it.stage == GiftCardStage.LOAD || it.stage == GiftCardStage.ACTIVATE) && !it.status.notDone && it.createdAt > at }

    /**
     * What it moved, in minor units of [currency], for local accounting: never merchandise.
     *
     * @property valueAddedMinor Value activated, loaded or refunded onto cards, less loads undone.
     * @property valueRemovedMinor Value paid out by cash-out.
     * @property cardFundingMinor Collected by funding card payments.
     * @property externalFundingMinor Confirmed received outside the app.
     * @property fundingReturnedMinor Funding refunded to its card or given back.
     * @property cashHandedOverMinor Cash handed to shoppers for cash-outs.
     */
    data class Totals(
        val valueAddedMinor: Long = 0,
        val valueRemovedMinor: Long = 0,
        val cardFundingMinor: Long = 0,
        val externalFundingMinor: Long = 0,
        val fundingReturnedMinor: Long = 0,
        val cashHandedOverMinor: Long = 0,
    ) {
        /** These totals plus [other], with checked arithmetic. */
        operator fun plus(other: Totals): Totals =
            Totals(
                Math.addExact(valueAddedMinor, other.valueAddedMinor),
                Math.addExact(valueRemovedMinor, other.valueRemovedMinor),
                Math.addExact(cardFundingMinor, other.cardFundingMinor),
                Math.addExact(externalFundingMinor, other.externalFundingMinor),
                Math.addExact(fundingReturnedMinor, other.fundingReturnedMinor),
                Math.addExact(cashHandedOverMinor, other.cashHandedOverMinor),
            )
    }

    /** What [stage] moved as [Totals]; unresolved and refused stages move nothing. */
    fun totalsOf(stage: GiftCardOperationEntity): Totals {
        val moved = stage.movedMinor
        return when (stage.stage) {
            GiftCardStage.ACTIVATE, GiftCardStage.LOAD, GiftCardStage.REFUND -> Totals(valueAddedMinor = moved)
            GiftCardStage.UNDO_LOAD -> Totals(valueAddedMinor = -moved)
            GiftCardStage.CASH_OUT -> Totals(valueRemovedMinor = moved)
            GiftCardStage.FUNDING_CARD -> Totals(cardFundingMinor = moved)
            GiftCardStage.FUNDING_EXTERNAL -> Totals(externalFundingMinor = moved)
            GiftCardStage.FUNDING_REFUND, GiftCardStage.FUNDING_RETURNED -> Totals(fundingReturnedMinor = moved)
            GiftCardStage.CASH_HANDOVER -> Totals(cashHandedOverMinor = moved)
            GiftCardStage.DEACTIVATE -> Totals()
        }
    }

    /** Every stage's [totalsOf] together. */
    val totals: Totals get() = stages.fold(Totals()) { sum, stage -> sum + totalsOf(stage) }

    /** Grouping stored stages into activities. */
    companion object {
        /**
         * [stages] of any activities, given oldest first in the order they were stored, grouped into activities, newest
         * activity first. Stages stored within one millisecond keep their stored order.
         */
        fun of(stages: List<GiftCardOperationEntity>): List<GiftCardActivity> =
            stages
                .groupBy { it.groupId }
                .values
                .map { group -> GiftCardActivity(group.sortedBy { it.createdAt }) }
                .sortedWith(compareByDescending<GiftCardActivity> { it.createdAt }.thenByDescending { it.groupId })
    }
}
