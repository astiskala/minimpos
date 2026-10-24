package app.minimpos.app.data.repo

import androidx.room.withTransaction
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.core.payment.PaymentContext
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
    private val observations = RecordObservations(source = dao::observeRefund)

    /**
     * Inserts a pending [refund] within current balances. A changed [reviewedRefunds] snapshot records a known-not-sent
     * refusal; null is for legacy/foreign callers, which still receive balance checks. Returns whether it may be sent.
     */
    suspend fun create(
        refund: RefundEntity,
        reviewedRefunds: Map<String, RefundStatus>? = null,
    ) = db.withTransaction {
        val valid = refund.saleId?.let { sales.get(it)?.allows(refund, reviewedRefunds) } ?: (refund.saleId == null)
        val stored =
            if (valid) {
                refund
            } else {
                refund.copy(
                    status = RefundStatus.FAILED,
                    reason = StoredReason.NotDone(Failure.NotSetUp(SetupProblem.REFUND_BALANCE)),
                )
            }
        dao.insert(stored)
        stored.status == RefundStatus.PENDING
    }

    private fun SaleWithLines.allows(
        refund: RefundEntity,
        reviewedRefunds: Map<String, RefundStatus>?,
    ): Boolean {
        if (reviewedRefunds != null &&
            refunds.filter { it.status != RefundStatus.FAILED }.associate { it.id to it.status } != reviewedRefunds
        ) {
            return false
        }
        if (refund.cancellation) return true
        val prior = refunds.filter { it.status != RefundStatus.FAILED && !it.cancellation }
        val pending =
            prior.filter { it.status != RefundStatus.REQUESTED }.fold(
                0L,
            ) { sum, priorRefund -> Math.addExact(sum, priorRefund.amountMinor) }
        val remaining = sale.amountMinor - (sale.cashBackMinor ?: 0) - sale.refundedMinor - pending
        if (refund.amountMinor !in 1..remaining || (tenders.size > 1 && refund.tenderId == null)) return false
        val id = refund.tenderId ?: return true
        val tender = tenders.firstOrNull { it.id == id } ?: return false
        return tender.allows(refund, prior)
    }

    private fun SaleTenderEntity.allows(
        refund: RefundEntity,
        prior: List<RefundEntity>,
    ): Boolean {
        if (refund.originalTransactionId != poiTransactionId) return false
        val reserved =
            prior
                .filter {
                    it.originalTransactionId == poiTransactionId
                }.fold(0L) { sum, priorRefund -> Math.addExact(sum, priorRefund.amountMinor) }
        val balance = (authorisedMinor ?: 0) - (cashBackMinor ?: 0) - reserved
        return refund.amountMinor in 1..balance && (!refund.full || (reserved == 0L && refund.amountMinor == authorisedMinor))
    }

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
    fun observe(id: String): Flow<RefundEntity?> = observations.observe(id)

    /** Observes the refunds (and cancellations) made from this terminal against sale [saleId], newest first. */
    fun forSale(saleId: String): Flow<List<RefundEntity>> = dao.refundsForSale(saleId)

    /**
     * Stores how refund [id] ended: its [status], why it was not accepted (the terminal's [message], or the app's
     * [reason]) and, when the terminal answered, its [details] (PSP reference and the customer's card receipt; without
     * an answer the stored ones are kept; a Checkout refund's own [pspReference] is kept too). When it was accepted
     * ([RefundStatus.REQUESTED]) it is applied to its local sale ([SaleRepository.applyRefund]), so the same items or
     * amount cannot be refunded twice from this terminal. This is the only change to a refund after [create]. Reads and
     * writes in one transaction; returns false only when a healthy read proves the refund no longer exists.
     */
    suspend fun settle(
        id: String,
        status: RefundStatus,
        message: String?,
        details: TransactionDetails?,
        reason: StoredReason? = null,
        pspReference: String? = null,
    ): Boolean =
        db.withTransaction {
            val stored = dao.refund(id) ?: return@withTransaction false
            if (stored.status == RefundStatus.REQUESTED && status != RefundStatus.REQUESTED) return@withTransaction true
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
                    pspReference = details?.pspReference ?: pspReference ?: stored.pspReference,
                    poiReconciliationId = details?.poiReconciliationId ?: stored.poiReconciliationId,
                    customerReceiptJson = details?.let { ReceiptLinesJson.encodeFields(it.customerReceipt) } ?: stored.customerReceiptJson,
                )
            dao.update(refund)
            if (stored.status != RefundStatus.REQUESTED) sales.applyRefund(refund)
            true
        }
}
