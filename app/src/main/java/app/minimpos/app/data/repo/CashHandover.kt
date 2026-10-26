package app.minimpos.app.data.repo

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus

/**
 * The durable cash payout reading shared by card cashback and gift-card cash-out. Approval and physical handover are
 * independent: unknown amounts cannot be handed over, and a recorded handover never becomes due again on recovery.
 * @property requestedMinor Maximum cash requested, in the original currency's minor units.
 * @property approvedMinor Terminal-approved cash; null means unreported, never the requested amount.
 * @property chargedMinor Cash funded within the card charge after merchandise, or stored value removed; null means unreported.
 * @property recordedAt Operator handover recording time, epoch milliseconds; null until explicitly recorded.
 */
data class CashHandover(
    val requestedMinor: Long,
    val approvedMinor: Long?,
    val chargedMinor: Long?,
    val recordedAt: Long?,
) {
    /** Whether staff must still hand over cash or investigate its unreported amount. */
    val needsAttention: Boolean get() = recordedAt == null && (approvedMinor == null || approvedMinor != 0L)

    /** Whether the known approved cash is within the original request and charge, and no handover was recorded. */
    val canRecord: Boolean get() {
        val cash = approvedMinor ?: return false
        val charged = chargedMinor ?: return false
        return needsAttention && cash in 1..requestedMinor && cash <= charged
    }
}

/** Card cashback's durable handover reading; null before approval and for ordinary sales. */
val SaleEntity.cashHandover: CashHandover? get() =
    requestedCashBackMinor?.takeIf { status == SaleStatus.APPROVED }?.let {
        CashHandover(it, cashBackMinor, terminalPaidMinor?.let { charged -> (charged - totalMinor).coerceAtLeast(0) }, cashHandedOverAt)
    }
