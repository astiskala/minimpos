package io.minimpos.app.data.repo

import androidx.room.withTransaction
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleWithLines
import kotlinx.coroutines.flow.Flow

/**
 * Stored card payments ([SaleEntity] with its [SaleLineEntity] items).
 *
 * A sale is created as PENDING before the terminal is called and then updated as the payment progresses (see
 * [io.minimpos.app.payment.SaleBook]). Refunds are recorded through [RefundRepository]; the history list and
 * its housekeeping are in [HistoryRepository]. All functions are main-safe (Room runs them on its own executor).
 */
class SaleRepository(
    private val db: AppDatabase,
) {
    private val dao = db.saleDao()
    private val refundDao = db.refundDao()

    /** Inserts a new [sale] (normally PENDING) with its [lines] in one transaction. */
    suspend fun createPending(
        sale: SaleEntity,
        lines: List<SaleLineEntity>,
    ) = db.withTransaction {
        dao.insert(sale)
        dao.insertLines(lines)
    }

    /** Overwrites the stored sale with the same ID; its lines are not touched. */
    suspend fun update(sale: SaleEntity) = dao.update(sale)

    /** Returns the sale with ID [id] and its lines, or null if there is none (for example after pruning). */
    suspend fun get(id: String): SaleWithLines? = dao.sale(id)

    /** Observes the sale with ID [id] and its lines; emits null while there is none. */
    fun observe(id: String): Flow<SaleWithLines?> = dao.observeSale(id)

    /**
     * Returns the sale whose POITransactionID is [transactionId], or null. Used to link a scanned refund QR code to a
     * payment taken on this terminal.
     */
    suspend fun findByTransactionId(transactionId: String): SaleWithLines? = dao.saleByTransactionId(transactionId)

    /** Records that the receipt of sale [saleId] was emailed to [email]; does nothing if the sale no longer exists. */
    suspend fun markEmailed(
        saleId: String,
        email: String,
    ) {
        dao.sale(saleId)?.let { dao.update(it.sale.copy(emailedTo = email)) }
    }

    /** Observes the refunds made from this terminal against sale [saleId], newest first. */
    fun refundsForSale(saleId: String): Flow<List<RefundEntity>> = refundDao.refundsForSale(saleId)
}
