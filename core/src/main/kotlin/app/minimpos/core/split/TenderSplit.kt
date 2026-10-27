package app.minimpos.core.split

import app.minimpos.core.refund.RefundCalculator
import app.minimpos.core.refund.RefundableLine

/**
 * A frozen sale line as far as allocating it to tenders is concerned.
 *
 * @property lineId The stored sale line's ID, the key of an item selection.
 * @property quantity Units sold.
 * @property allocatedQuantity Units already allocated to tenders that are approved, pending or unknown.
 * @property grossMinor The line's tax-inclusive amount as sold, in minor units; tax is never recalculated.
 */
data class AllocatableLine(
    val lineId: Long,
    val quantity: Int,
    val allocatedQuantity: Int,
    val grossMinor: Long,
) {
    /** Units not yet allocated to any tender; never negative. */
    val remainingQuantity: Int get() = (quantity - allocatedQuantity).coerceAtLeast(0)

    internal val refundable: RefundableLine get() = RefundableLine(lineId, quantity, allocatedQuantity, grossMinor)
}

/**
 * Cash handed over for one cash tender: what the tender applies to the sale and what the shopper gave.
 *
 * @property amountMinor Amount applied to the sale, in minor units; positive.
 * @property receivedMinor Cash received from the shopper, in minor units; at least [amountMinor].
 * @throws IllegalArgumentException if [amountMinor] is not positive or less cash was received than it applies.
 */
data class CashPayment(
    val amountMinor: Long,
    val receivedMinor: Long,
) {
    init {
        require(amountMinor > 0) { "Cash tender must apply a positive amount" }
        require(receivedMinor >= amountMinor) { "Received cash must cover the tender" }
    }

    /** Change handed back, in minor units; never negative. */
    val changeMinor: Long get() = Math.subtractExact(receivedMinor, amountMinor)
}

/**
 * Pure allocation of one sale's frozen purchase total across tenders, in minor units of its currency. Sale-level
 * pricing and tax are never recalculated: items carry the gross amounts they were sold for, apportioned like refunds,
 * and every minor-unit remainder is placed deterministically.
 */
object TenderSplit {
    /** The most equal shares one sale can be divided into. */
    const val MAX_SHARES = 20

    /**
     * [baseMinor] divided into [count] equal shares, largest first: the first `baseMinor % count` shares carry one
     * extra minor unit, so the shares always total [baseMinor] exactly.
     *
     * @throws IllegalArgumentException if [count] is outside 2..[MAX_SHARES] or [baseMinor] is negative.
     */
    fun equalShares(
        baseMinor: Long,
        count: Int,
    ): List<Long> {
        require(count in 2..MAX_SHARES) { "Shares must be between 2 and $MAX_SHARES" }
        require(baseMinor >= 0) { "Share base must not be negative" }
        val share = baseMinor / count
        val extra = baseMinor % count
        return List(count) { index -> if (index < extra) share + 1 else share }
    }

    /**
     * What share [index] (zero-based) of [count] equal shares of [baseMinor] collects while [unpaidMinor] is still due:
     * its share capped at the unpaid balance, except the last share, which takes whatever is unpaid so an earlier
     * partial approval never strands a remainder. Zero when nothing is unpaid or every share was collected.
     */
    fun shareAmount(
        baseMinor: Long,
        count: Int,
        index: Int,
        unpaidMinor: Long,
    ): Long {
        val shares = equalShares(baseMinor, count)
        val unpaid = unpaidMinor.coerceAtLeast(0)
        return when {
            index !in shares.indices -> 0
            index == shares.lastIndex -> unpaid
            else -> minOf(shares[index], unpaid)
        }
    }

    /**
     * Amount allocated to the units of [selection] (line ID to quantity), each line apportioned cumulatively over
     * earlier allocations exactly as [RefundCalculator] apportions returns, so allocating every unit totals the line's
     * gross. Selecting every remaining unit takes the whole [unpaidMinor]; the result never exceeds it. Quantities are
     * clamped to each line's remaining units and unknown lines are ignored.
     */
    fun itemsAmount(
        lines: List<AllocatableLine>,
        selection: Map<Long, Int>,
        unpaidMinor: Long,
    ): Long = RefundCalculator.amountFor(lines.map { it.refundable }, selection, unpaidMinor)

    /** Gross apportioned to [quantity] more units of [line], over its earlier allocations. */
    fun lineAmount(
        line: AllocatableLine,
        quantity: Int,
    ): Long = RefundCalculator.lineAmount(line.refundable, quantity)
}
