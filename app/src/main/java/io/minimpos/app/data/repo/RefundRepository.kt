package io.minimpos.app.data.repo

import androidx.room.withTransaction
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.RefundStatus
import io.minimpos.terminal.client.TransactionDetails
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
 * Stored referenced refunds ([RefundEntity]), including cancellations of held payments.
 *
 * A refund is created as PENDING before the terminal is called ([create]) and settled with its outcome afterwards
 * ([settle], see [io.minimpos.app.payment.RefundBook]); there is no other write to a refund. What an accepted refund
 * does to its local sale is the sale's own transition, [SaleRepository.applyRefund], run in the same transaction. All
 * functions are main-safe (Room runs them on its own executor).
 *
 * @param db The database.
 * @param sales Records accepted refunds on their sales.
 */
class RefundRepository(
    private val db: AppDatabase,
    private val sales: SaleRepository,
) {
    private val dao = db.refundDao()

    /** Inserts a new [refund], normally PENDING. */
    suspend fun create(refund: RefundEntity) = dao.insert(refund)

    /** Returns the refund with ID [id], or null if there is none. */
    suspend fun get(id: String): RefundEntity? = dao.refund(id)

    /** Observes the refund with ID [id]; emits null while there is none. */
    fun observe(id: String): Flow<RefundEntity?> = dao.observeRefund(id)

    /** Observes the refunds (and cancellations) made from this terminal against sale [saleId], newest first. */
    fun forSale(saleId: String): Flow<List<RefundEntity>> = dao.refundsForSale(saleId)

    /**
     * Stores how refund [id] ended: its [status], the [message] shown when it was not accepted and, when the terminal
     * answered, its [details] (PSP reference and the customer's card receipt; without an answer the stored ones are
     * kept). When it was accepted ([RefundStatus.REQUESTED]) it is applied to its local sale
     * ([SaleRepository.applyRefund]), so the same items or amount cannot be refunded twice from this terminal. This is
     * the only change to a refund after [create]. Reads and writes in one transaction; does nothing when the refund no
     * longer exists.
     */
    suspend fun settle(
        id: String,
        status: RefundStatus,
        message: String?,
        details: TransactionDetails?,
    ) = db.withTransaction {
        val stored = dao.refund(id) ?: return@withTransaction
        val refund =
            stored.copy(
                status = status,
                message = message,
                pspReference = details?.pspReference ?: stored.pspReference,
                customerReceiptJson = details?.let { ReceiptLinesJson.encodeFields(it.customerReceipt) } ?: stored.customerReceiptJson,
            )
        dao.update(refund)
        sales.applyRefund(refund)
    }
}
