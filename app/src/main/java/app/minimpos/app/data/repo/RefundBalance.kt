package app.minimpos.app.data.repo

import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.TenderKind

/**
 * What a stored sale, and each tender of it, can still return: the one return-balance reading, which staff review
 * ([app.minimpos.app.history.RefundablePayment]) and the transaction storing a return ([RefundRepository]) both ask, so
 * a return offered for review is the return the write accepts. Accepted returns are applied to the sale; possibly sent
 * ones (pending or unknown) reserve their amount until resolved; failed returns and hold cancellations return nothing.
 * Amounts are minor units of the sale's currency. Pure.
 *
 * @param record The stored sale with its tenders and returns.
 */
class RefundBalance(
    private val record: SaleWithLines,
) {
    /** The returns that count against the sale: neither failed nor a hold cancellation. */
    val returns: List<RefundEntity> = record.refunds.filter { it.status != RefundStatus.FAILED && !it.cancellation }

    /** Accepted returns already applied to the sale. */
    val acceptedMinor: Long get() = record.sale.refundedMinor

    /** Possibly sent returns of the sale (pending or unknown), reserved until resolved. */
    val reservedMinor: Long = returns.filter { it.status.unresolved }.amount()

    /**
     * What the sale can still return: its amount less cash handed out, accepted and reserved returns; negative when
     * stored returns exceed it.
     */
    val remainingMinor: Long get() = record.sale.amountMinor - (record.sale.cashBackMinor ?: 0) - acceptedMinor - reservedMinor

    /** What [tender] of this sale can still return. */
    fun of(tender: SaleTenderEntity): TenderReturns = TenderReturns(tender, returns.filter { it.returnsTo(tender) })
}

/**
 * What one tender can still return, see [RefundBalance.of].
 *
 * @property returns The sale's returns to this tender that count against it.
 */
class TenderReturns internal constructor(
    private val tender: SaleTenderEntity,
    val returns: List<RefundEntity>,
) {
    /** Accepted returns to this tender. */
    val acceptedMinor: Long = returns.filter { it.status == RefundStatus.REQUESTED }.amount()

    /** Possibly sent returns to this tender, reserved until resolved. */
    val reservedMinor: Long = returns.filter { it.status != RefundStatus.REQUESTED }.amount()

    /** What it can still return: what it collected less cash handed out and every return to it; negative when overdrawn. */
    val remainingMinor: Long get() = (tender.authorisedMinor ?: 0) - (tender.cashBackMinor ?: 0) - acceptedMinor - reservedMinor

    /** Whether returning [minor] reverses everything this tender collected, nothing returned and no cash handed out yet. */
    fun whole(minor: Long): Boolean =
        acceptedMinor == 0L && reservedMinor == 0L && (tender.cashBackMinor ?: 0) == 0L && minor == tender.authorisedMinor
}

/**
 * Whether this refund leg returns money to [tender]: it names the tender, or (recorded before refunds named their
 * tender) it reverses the tender's card transaction.
 */
internal fun RefundEntity.returnsTo(tender: SaleTenderEntity): Boolean =
    tenderId == tender.id || (tenderId == null && tender.kind == TenderKind.CARD && originalTransactionId == tender.poiTransactionId)

/** What these returns add up to, in minor units. */
internal fun List<RefundEntity>.amount(): Long = fold(0L) { sum, refund -> Math.addExact(sum, refund.amountMinor) }
