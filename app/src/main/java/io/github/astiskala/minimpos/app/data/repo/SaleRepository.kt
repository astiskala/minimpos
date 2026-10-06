package io.github.astiskala.minimpos.app.data.repo

import androidx.room.withTransaction
import io.github.astiskala.minimpos.app.data.db.AppDatabase
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleLineEntity
import io.github.astiskala.minimpos.app.data.db.SaleWithLines
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Stored card payments ([SaleEntity] with its [SaleLineEntity] items), and every change to them after they were opened.
 *
 * A sale is created as PENDING before the terminal is called ([createPending]); after that it only changes through
 * [record], which applies a [SaleEvent] (what happened to it, which decides the fields written), and [applyRefund] for
 * an accepted refund or cancellation, which also changes its lines. These are the only writes to one sale and its
 * lines. Each reads and writes the sale in one transaction and does nothing when the sale no longer exists (for
 * example after pruning).
 * Refunds are stored (and listed per sale) by [RefundRepository]; the history list and the housekeeping of whole tables
 * (settling interrupted transactions at startup, pruning and clearing) are in [HistoryRepository]. All functions are
 * main-safe (Room runs them on its own executor).
 */
class SaleRepository(
    private val db: AppDatabase,
    /** Epoch milliseconds stamped at persistence; tests can supply a fixed clock. */
    private val now: () -> Long = System::currentTimeMillis,
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

    /** Records [event] for sale [id] (see [SaleEvent]), without writing or invalidating observers when nothing changes. */
    suspend fun record(
        id: String,
        event: SaleEvent,
    ) {
        db.withTransaction {
            dao.sale(id)?.sale?.let { sale ->
                val stamped =
                    when (event) {
                        is SaleEvent.Settled -> {
                            event.copy(
                                processedAt =
                                    event.processedAt ?: event.details?.poiTimestamp?.let {
                                        runCatching {
                                            Instant
                                                .parse(it)
                                                .toEpochMilli()
                                        }.getOrNull()
                                    } ?: now(),
                            )
                        }

                        is SaleEvent.CaptureSending -> {
                            event.copy(startedAt = event.startedAt ?: now())
                        }

                        is SaleEvent.CaptureAnswered -> {
                            event.copy(processedAt = event.processedAt ?: now())
                        }

                        is SaleEvent.Sending, is SaleEvent.LinkAnswered, is SaleEvent.NotSent, is SaleEvent.NotSetUp,
                        is SaleEvent.OutcomeUnknown, is SaleEvent.Emailed, is SaleEvent.AdjustmentAnswered,
                        is SaleEvent.ModificationNotSetUp, SaleEvent.Interrupted, is SaleEvent.ContextRecorded,
                        is SaleEvent.AdjustmentSending,
                        -> {
                            event
                        }
                    }
                val updated = sale.after(stamped)
                if (updated != sale) dao.update(updated)
            }
        }
    }

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
}
