package io.minimpos.app.data.repo

import androidx.room.withTransaction
import io.minimpos.app.data.db.AdjustmentStatus
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.RefundStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.terminal.checkout.PaymentLink
import io.minimpos.terminal.client.TransactionDetails
import kotlinx.coroutines.flow.Flow

/**
 * Stored card payments ([SaleEntity] with its [SaleLineEntity] items), and every change to them after they were opened.
 *
 * A sale is created as PENDING before the terminal is called ([createPending]); after that it only changes through the
 * named transitions here, which are the only writes to one sale and its lines: [markSending] and [settle] for the
 * payment itself (only [settle] for one paid through a payment link, with the link), then, for a payment taken with
 * manual capture, [recordAdjustment], [recordCapture] and [modificationFailed]; [applyRefund] for an accepted refund
 * or cancellation; [markEmailed]. Each transition reads and writes the sale in one transaction and does nothing when
 * the sale no longer exists (for example after pruning).
 * Refunds are stored (and listed per sale) by [RefundRepository]; the history list and the housekeeping of whole tables
 * (settling interrupted transactions at startup, pruning and clearing) are in [HistoryRepository]. All functions are
 * main-safe (Room runs them on its own executor).
 */
class SaleRepository(
    private val db: AppDatabase,
) {
    private val dao = db.saleDao()

    /** Inserts a new [sale] (normally PENDING) with its [lines] in one transaction. */
    suspend fun createPending(
        sale: SaleEntity,
        lines: List<SaleLineEntity>,
    ) = db.withTransaction {
        dao.insert(sale)
        dao.insertLines(lines)
    }

    /** Returns the sale with ID [id] and its lines, or null if there is none (for example after pruning). */
    suspend fun get(id: String): SaleWithLines? = dao.sale(id)

    /** Observes the sale with ID [id] and its lines; emits null while there is none. */
    fun observe(id: String): Flow<SaleWithLines?> = dao.observeSale(id)

    /**
     * Returns the sale whose POITransactionID is [transactionId], or null. Used to link a scanned refund QR code to a
     * payment taken on this terminal.
     */
    suspend fun findByTransactionId(transactionId: String): SaleWithLines? = dao.saleByTransactionId(transactionId)

    /** Records that the receipt of sale [saleId] was emailed to [email]. */
    suspend fun markEmailed(
        saleId: String,
        email: String,
    ) = change(saleId) { it.copy(emailedTo = email) }

    /** Records that sale [id] is being sent to the terminal [poiId]. */
    suspend fun markSending(
        id: String,
        poiId: String,
    ) = change(id) { it.copy(poiId = poiId) }

    /**
     * Stores how the payment [id] ended (or, for one paid through a payment link, where it stands: awaiting its payment
     * too): its [status], the [message] shown when it did not succeed, when the terminal answered its [details]
     * (transaction, card, receipts, saved card and the blob for synchronous adjustments) and, when Adyen answered about
     * the sale's payment link, the [link]'s ID, address and expiry (an expiry Adyen did not send keeps the one chosen
     * when the sale was opened).
     */
    suspend fun settle(
        id: String,
        status: SaleStatus,
        message: String?,
        details: TransactionDetails?,
        link: PaymentLink? = null,
    ) = change(id) { sale -> settled(sale, status, message, details).withLink(link) }

    private fun SaleEntity.withLink(link: PaymentLink?): SaleEntity =
        link?.let {
            copy(
                paymentLinkId = it.id,
                paymentLinkUrl = it.url,
                paymentLinkExpiresAt = it.expiresAt?.toEpochMilli() ?: paymentLinkExpiresAt,
            )
        } ?: this

    private fun settled(
        sale: SaleEntity,
        status: SaleStatus,
        message: String?,
        details: TransactionDetails?,
    ): SaleEntity =
        if (details == null) {
            sale.copy(status = status, message = message)
        } else {
            sale.copy(
                status = status,
                poiTransactionId = details.poiTransactionId,
                poiTimestamp = details.poiTimestamp,
                pspReference = details.pspReference,
                paymentBrand = details.paymentBrand,
                paymentMethodVariant = details.paymentMethodVariant,
                maskedPan = details.maskedPan,
                entryMode = details.entryMode,
                authCode = details.approvalCode,
                message = message,
                errorCondition = details.errorCondition,
                refusalReason = details.refusalReason,
                storedPaymentMethodId = details.tokenization?.storedPaymentMethodId,
                customerReceiptJson = ReceiptLinesJson.encodeFields(details.customerReceipt),
                cashierReceiptJson = ReceiptLinesJson.encodeFields(details.cashierReceipt),
                signatureRequired = details.signatureRequired,
                adjustAuthorisationData = details.adjustAuthorisationData,
            )
        }

    /**
     * Records an adjustment of what sale [id] holds to [amountMinor] that went through: [AdjustmentStatus.AUTHORISED]
     * with Adyen's new [adjustAuthorisationData] blob, or [AdjustmentStatus.REQUESTED] (asynchronous; the blob is kept).
     */
    suspend fun recordAdjustment(
        id: String,
        amountMinor: Long,
        status: AdjustmentStatus,
        adjustAuthorisationData: String? = null,
    ) = change(id) { sale ->
        sale.copy(
            authorisedMinor = amountMinor,
            adjustment = status,
            adjustAuthorisationData = if (status == AdjustmentStatus.AUTHORISED) adjustAuthorisationData else sale.adjustAuthorisationData,
            modificationMessage = null,
        )
    }

    /**
     * Records where the capture of sale [id] stands: its [status], and why it did not go through ([message]; null when
     * it did). [CaptureStatus.PENDING] just before it is sent to Adyen, and [CaptureStatus.MANUAL] when staff capture it
     * in the Customer Area, also record the amount captured ([amountMinor]) and the tip entered for it ([tipMinor]);
     * null keeps the stored ones, as when Adyen's answer is recorded.
     */
    suspend fun recordCapture(
        id: String,
        status: CaptureStatus,
        amountMinor: Long? = null,
        tipMinor: Long? = null,
        message: String? = null,
    ) = change(id) {
        it.copy(
            captureStatus = status,
            capturedMinor = amountMinor ?: it.capturedMinor,
            tipMinor = tipMinor ?: it.tipMinor,
            modificationMessage = message,
        )
    }

    /** Records why the latest capture or adjustment of sale [id] did not go through, for display. */
    suspend fun modificationFailed(
        id: String,
        message: String,
    ) = change(id) { it.copy(modificationMessage = message) }

    /**
     * Applies the accepted [refund] (one that is [RefundStatus.REQUESTED]) to its local sale. A cancellation of a held
     * payment marks the sale [SaleEntity.holdCancelled] and refunds nothing. A full refund marks every line and the
     * sale's whole amount (with any tip, see [SaleEntity.amountMinor]) as refunded; a partial one adds its
     * [RefundedLine] quantities and its amount. Neither can exceed what was sold. Nothing happens for a refund that was
     * not accepted, has no local sale, or whose sale no longer exists. Called by [RefundRepository.settle] inside its
     * transaction.
     */
    suspend fun applyRefund(refund: RefundEntity) {
        val saleId = refund.saleId?.takeIf { refund.status == RefundStatus.REQUESTED } ?: return
        db.withTransaction {
            val record = dao.sale(saleId) ?: return@withTransaction
            val sale = record.sale
            if (refund.cancellation) {
                dao.update(sale.copy(holdCancelled = true))
                return@withTransaction
            }
            val refunded = ReceiptLinesJson.decodeRefunded(refund.linesJson).groupBy { it.lineId }
            dao.updateLines(
                record.lines.map { line ->
                    val added = refunded[line.id].orEmpty().sumOf { it.quantity }
                    line.copy(
                        refundedQuantity = if (refund.full) line.quantity else (line.refundedQuantity + added).coerceAtMost(line.quantity),
                    )
                },
            )
            val amount = sale.amountMinor
            val total = if (refund.full) amount else sale.refundedMinor + refund.amountMinor
            dao.update(sale.copy(refundedMinor = total.coerceAtMost(amount)))
        }
    }

    private suspend fun change(
        id: String,
        transform: (SaleEntity) -> SaleEntity,
    ) {
        db.withTransaction { dao.sale(id)?.let { dao.update(transform(it.sale)) } }
    }
}
