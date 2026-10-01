package io.minimpos.core.refund

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A sold line as far as refunds are concerned.
 *
 * @property lineId the stored sale line's id, the key of a refund selection.
 * @property quantity the number of units sold.
 * @property refundedQuantity the number of units already refunded by earlier refunds.
 * @property gross the tax-inclusive amount charged for the whole line, in minor units.
 */
data class RefundableLine(
    val lineId: Long,
    val quantity: Int,
    val refundedQuantity: Int,
    val gross: Long,
) {
    /** Units that can still be refunded; never negative. */
    val remainingQuantity: Int get() = (quantity - refundedQuantity).coerceAtLeast(0)
}

/** Works out how much to refund when a shopper returns some items of a sale, possibly over several refunds. */
object RefundCalculator {
    /**
     * Amount to refund, in minor units, for [selection] (line id to quantity). Each line is apportioned cumulatively,
     * so refunding every unit of a line over several refunds always totals exactly the line's gross, even when the
     * per-unit amount is fractional. The result never exceeds [remainingRefundable] (what the sale still has left
     * after earlier refunds, including amount-only ones); if the selection covers everything still refundable, the
     * full remaining amount is returned so rounding never strands a cent. Quantities are clamped to each line's
     * remaining units, and unknown line ids are ignored; an empty selection gives 0.
     */
    fun amountFor(
        lines: List<RefundableLine>,
        selection: Map<Long, Int>,
        remainingRefundable: Long,
    ): Long {
        var total = 0L
        var anyRequested = false
        var coversEverything = true
        for (line in lines) {
            val requested = (selection[line.lineId] ?: 0).coerceIn(0, line.remainingQuantity)
            if (requested < line.remainingQuantity) coversEverything = false
            if (requested == 0) continue
            anyRequested = true
            total += lineAmount(line, requested)
        }
        val cap = remainingRefundable.coerceAtLeast(0)
        return when {
            !anyRequested -> 0
            coversEverything -> cap
            else -> total.coerceIn(0, cap)
        }
    }

    /**
     * The apportioned amount, in minor units, for refunding [quantity] more units of [line] (clamped to what is left):
     * the line's share for all units refunded after this one minus its share before, each rounded half up.
     */
    fun lineAmount(
        line: RefundableLine,
        quantity: Int,
    ): Long {
        val units = quantity.coerceIn(0, line.remainingQuantity)
        if (units == 0) return 0
        return share(line, line.refundedQuantity + units) - share(line, line.refundedQuantity)
    }

    private fun share(
        line: RefundableLine,
        units: Int,
    ): Long =
        BigDecimal
            .valueOf(line.gross)
            .multiply(BigDecimal.valueOf(units.toLong()))
            .divide(BigDecimal.valueOf(line.quantity.toLong()), 0, RoundingMode.HALF_UP)
            .longValueExact()
}
