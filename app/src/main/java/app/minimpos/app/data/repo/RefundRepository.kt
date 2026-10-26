package app.minimpos.app.data.repo

import androidx.room.withTransaction
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundCancelStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.db.TenderKind
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
 * Stored refunds ([RefundEntity]): referenced refunds, cancellations of held payments and unreferenced card refunds,
 * with any cancellation of a delayed unreferenced refund.
 *
 * A refund is created as PENDING before the terminal is called ([create]) and settled with its outcome afterwards
 * ([settle], see [app.minimpos.app.payment.RefundBook]); the only other writes are the cancellation of a delayed
 * unreferenced refund ([cancelSending], [cancelAnswered]). What an accepted refund
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

    /**
     * Records cash handed back for a cash tender ([RefundEntity.cash]), confirmed by the operator, without a Terminal
     * API call: within current balances it is stored accepted at [now] and applied to its sale in one transaction.
     * A changed [reviewedRefunds] snapshot or balance stores nothing and returns false.
     */
    suspend fun recordCashReturn(
        refund: RefundEntity,
        reviewedRefunds: Map<String, RefundStatus>?,
    ): Boolean =
        db.withTransaction {
            val saleId = refund.saleId
            require(refund.cash && saleId != null && refund.tenderId != null) { "Cash returns belong to a cash tender" }
            val record = sales.get(saleId) ?: return@withTransaction false
            if (!record.allows(refund, reviewedRefunds)) return@withTransaction false
            val stored = refund.copy(status = RefundStatus.REQUESTED, processedAt = now())
            dao.insert(stored)
            sales.applyRefund(stored)
            true
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
        val approved = tenders.filter { it.approved }
        if (refund.amountMinor !in 1..remaining || (approved.size > 1 && refund.tenderId == null)) return false
        val id = refund.tenderId ?: return !refund.cash
        val tender = approved.firstOrNull { it.id == id } ?: return false
        return tender.allows(refund, prior)
    }

    private fun SaleTenderEntity.allows(
        refund: RefundEntity,
        prior: List<RefundEntity>,
    ): Boolean {
        val cashTender = kind == TenderKind.CASH
        if (refund.cash != cashTender) return false
        if (refund.originalTransactionId != (if (cashTender) id else poiTransactionId)) return false
        val reserved =
            prior
                .filter { it.returnsTo(this) }
                .fold(0L) { sum, priorRefund -> Math.addExact(sum, priorRefund.amountMinor) }
        val balance = (authorisedMinor ?: 0) - (cashBackMinor ?: 0) - reserved
        return refund.amountMinor in 1..balance &&
            (!refund.full || (!cashTender && reserved == 0L && refund.amountMinor == authorisedMinor))
    }

    /**
     * Records that cancelling the delayed unreferenced refund [id] is being sent at [startedAt] (epoch ms), before it
     * is sent; an unresolved cancellation keeps its first time. Returns the refund as stored, or null when it is not an
     * accepted unreferenced refund whose cancellation may be sent now.
     */
    suspend fun cancelSending(
        id: String,
        startedAt: Long,
    ): RefundEntity? =
        db.withTransaction {
            val stored = dao.refund(id) ?: return@withTransaction null
            val cancel = stored.cancelStatus
            if (!stored.unreferenced || stored.status != RefundStatus.REQUESTED || stored.pspReference == null) return@withTransaction null
            if (cancel == RefundCancelStatus.REQUESTED) return@withTransaction null
            val sending =
                stored.copy(
                    cancelStatus = RefundCancelStatus.SENDING,
                    cancelStartedAt = if (cancel?.unresolved == true) stored.cancelStartedAt else startedAt,
                    cancelReason = null,
                )
            dao.update(sending)
            sending
        }

    /**
     * Stores how cancelling refund [id] was answered: [status], the app's [reason] when it was not received and Adyen's
     * [pspReference] of the cancellation request. A received cancellation is never overwritten.
     */
    suspend fun cancelAnswered(
        id: String,
        status: RefundCancelStatus,
        reason: StoredReason?,
        pspReference: String?,
    ) = db.withTransaction {
        val stored = dao.refund(id) ?: return@withTransaction
        if (stored.cancelStatus == RefundCancelStatus.REQUESTED) return@withTransaction
        dao.update(
            stored.copy(cancelStatus = status, cancelReason = reason, cancelPspReference = pspReference ?: stored.cancelPspReference),
        )
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
     * amount cannot be refunded twice from this terminal. Apart from cancellations, the only change after [create]. Reads and
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
                    acquirerResponseCode = stored.acquirerAnswer(details),
                )
            dao.update(refund)
            if (stored.status != RefundStatus.REQUESTED) sales.applyRefund(refund)
            true
        }
}

/** An unreferenced refund's synchronous acquirer answer from [details], else the stored one; null for referenced refunds. */
internal fun RefundEntity.acquirerAnswer(details: TransactionDetails?): String? =
    details?.acquirerResponseCode?.takeIf { unreferenced } ?: acquirerResponseCode

/**
 * Whether this refund leg returns money to [tender]: it names the tender, or (recorded before refunds named their
 * tender) it reverses the tender's card transaction.
 */
fun RefundEntity.returnsTo(tender: SaleTenderEntity): Boolean =
    tenderId == tender.id || (tenderId == null && tender.kind == TenderKind.CARD && originalTransactionId == tender.poiTransactionId)
