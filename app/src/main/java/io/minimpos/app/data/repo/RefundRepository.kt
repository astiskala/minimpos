package io.minimpos.app.data.repo

import androidx.room.withTransaction
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.RefundStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * One item of an item refund, stored as JSON in `refunds.linesJson` (see [ReceiptLinesJson]) and printed on the refund
 * receipt. Amounts are in minor units of the refund's currency.
 *
 * @property lineId The [io.minimpos.app.data.db.SaleLineEntity.id] of the refunded line.
 * @property name Item name as sold.
 * @property quantity Units refunded.
 * @property unitPriceMinor Unit price as sold.
 * @property grossMinor The share of the line's gross total refunded for [quantity] units, apportioned by
 * [io.minimpos.core.refund.RefundCalculator.lineAmount] so that refunding every unit, over one or more refunds,
 * returns exactly the line's gross total.
 */
@Serializable
data class RefundedLine(
    val lineId: Long,
    val name: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val grossMinor: Long,
)

/**
 * Stored referenced refunds ([RefundEntity]).
 *
 * A refund is created as PENDING before the terminal is called and completed with its outcome afterwards (see
 * [io.minimpos.app.payment.RefundBook]). Completing an accepted refund also updates what has been refunded on the
 * local sale. All functions are main-safe (Room runs them on its own executor).
 */
class RefundRepository(
    private val db: AppDatabase,
) {
    private val dao = db.refundDao()
    private val saleDao = db.saleDao()

    /** Inserts a new [refund], normally PENDING. */
    suspend fun create(refund: RefundEntity) = dao.insert(refund)

    /** Returns the refund with ID [id], or null if there is none. */
    suspend fun get(id: String): RefundEntity? = dao.refund(id)

    /** Observes the refund with ID [id]; emits null while there is none. */
    fun observe(id: String): Flow<RefundEntity?> = dao.observeRefund(id)

    /**
     * Stores the refund result and, when it was accepted ([RefundStatus.REQUESTED]) for a local sale, records what has
     * been refunded so the same items or amount cannot be refunded twice from this terminal.
     *
     * A full refund marks every line and the whole total as refunded; a partial one adds its [RefundedLine] quantities
     * and its amount. Neither can exceed what was sold. Runs in one transaction.
     */
    suspend fun complete(refund: RefundEntity) =
        db.withTransaction {
            dao.update(refund)
            if (refund.status != RefundStatus.REQUESTED || refund.saleId == null) return@withTransaction
            val sale = saleDao.sale(refund.saleId) ?: return@withTransaction
            val refundedLines = ReceiptLinesJson.decodeRefunded(refund.linesJson).groupBy { it.lineId }
            val lines =
                sale.lines.map { line ->
                    val quantity =
                        if (refund.full) {
                            line.quantity
                        } else {
                            line.refundedQuantity +
                                refundedLines[line.id].orEmpty().sumOf { it.quantity }
                        }
                    line.copy(refundedQuantity = quantity.coerceAtMost(line.quantity))
                }
            saleDao.updateLines(lines)
            val refunded = if (refund.full) sale.sale.totalMinor else sale.sale.refundedMinor + refund.amountMinor
            saleDao.update(sale.sale.copy(refundedMinor = refunded.coerceAtMost(sale.sale.totalMinor)))
        }
}
