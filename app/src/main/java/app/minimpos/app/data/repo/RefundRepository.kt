package app.minimpos.app.data.repo

import androidx.room.withTransaction
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.StoredReason
import app.minimpos.core.money.PaymentContext
import app.minimpos.terminal.client.TransactionDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * One item of an item refund, stored as JSON in `refunds.linesJson` (see [ReceiptLinesJson]) and printed on the refund
 * receipt. Amounts are in minor units of the refund's currency.
 *
 * @property lineId The [app.minimpos.app.data.db.SaleLineEntity.id] of the refunded line.
 * @property name Item name as sold.
 * @property quantity Units refunded.
 * @property unitPriceMinor Unit price as sold.
 * @property grossMinor The share of the line's gross total refunded for [quantity] units, apportioned by
 * [app.minimpos.core.refund.RefundCalculator.lineAmount] so that refunding every unit, over one or more refunds,
 * returns exactly the line's gross total.
 * @property taxRateMilliPercent Applied rate as sold, in thousandths of a percent; 0 for items with no tax.
 */
@Serializable
data class RefundedLine(
    val lineId: Long,
    val name: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val grossMinor: Long,
    val taxRateMilliPercent: Int = 0,
)

/**
 * Stored referenced refunds ([RefundEntity]), including cancellations of held payments.
 *
 * A refund is created as PENDING before the terminal is called ([create]) and settled with its outcome afterwards
 * ([settle], see [app.minimpos.app.payment.RefundBook]); there is no other write to a refund. What an accepted refund
 * does to its local sale is the sale's own transition, [SaleRepository.applyRefund], run in the same transaction. All
 * functions are main-safe (Room runs them on its own executor).
 *
 * @param db The database.
 * @param sales Records accepted refunds on their sales.
 * @param now Epoch milliseconds used when acceptance has no terminal timestamp.
 */
class RefundRepository(
    private val db: AppDatabase,
    private val sales: SaleRepository,
    /** Epoch milliseconds used when the terminal provides no acceptance timestamp. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.refundDao()

    /** Inserts a new [refund], normally PENDING. */
    suspend fun create(refund: RefundEntity) = dao.insert(refund)

    /** Returns the refund with ID [id], or null if there is none. */
    suspend fun get(id: String): RefundEntity? = dao.refund(id)

    /** Records the actual connection before sending and its certificate environment after answering. */
    suspend fun recordContext(
        id: String,
        context: PaymentContext,
    ) = db.withTransaction {
        dao.refund(id)?.let { dao.update(it.copy(context = context)) }
        Unit
    }

    /** Observes the refund with ID [id]; emits null while there is none. */
    fun observe(id: String): Flow<RefundEntity?> = dao.observeRefund(id)

    /** Observes the refunds (and cancellations) made from this terminal against sale [saleId], newest first. */
    fun forSale(saleId: String): Flow<List<RefundEntity>> = dao.refundsForSale(saleId)

    /**
     * Stores how refund [id] ended: its [status], why it was not accepted (the terminal's [message], or the app's
     * [reason]) and, when the terminal answered, its [details] (PSP reference and the customer's card receipt; without
     * an answer the stored ones are kept). When it was accepted ([RefundStatus.REQUESTED]) it is applied to its local
     * sale ([SaleRepository.applyRefund]), so the same items or amount cannot be refunded twice from this terminal. This
     * is the only change to a refund after [create]. Reads and writes in one transaction; does nothing when the refund no
     * longer exists.
     */
    suspend fun settle(
        id: String,
        status: RefundStatus,
        message: String?,
        details: TransactionDetails?,
        reason: StoredReason? = null,
    ) = db.withTransaction {
        val stored = dao.refund(id) ?: return@withTransaction
        if (stored.status == RefundStatus.REQUESTED && status != RefundStatus.REQUESTED) return@withTransaction
        val refund =
            stored.copy(
                status = status,
                processedAt =
                    stored.processedAt ?: if (status == RefundStatus.REQUESTED) {
                        details?.poiTimestamp?.let {
                            runCatching {
                                Instant
                                    .parse(it)
                                    .toEpochMilli()
                            }.getOrNull()
                        } ?: now()
                    } else {
                        null
                    },
                message = message,
                reason = reason,
                pspReference = details?.pspReference ?: stored.pspReference,
                customerReceiptJson = details?.let { ReceiptLinesJson.encodeFields(it.customerReceipt) } ?: stored.customerReceiptJson,
            )
        dao.update(refund)
        if (stored.status != RefundStatus.REQUESTED) sales.applyRefund(refund)
    }
}
