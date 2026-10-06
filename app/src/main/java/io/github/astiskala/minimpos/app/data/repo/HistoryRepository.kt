package io.github.astiskala.minimpos.app.data.repo

import androidx.room.withTransaction
import io.github.astiskala.minimpos.app.data.db.AppDatabase
import io.github.astiskala.minimpos.app.data.db.HistorySwitchEntity
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.StoredReason
import io.github.astiskala.minimpos.app.data.settings.StorageJson
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.serializer

/** One entry of the transaction history: a sale or a refund. */
sealed interface HistoryItem {
    /** The sale's or refund's ID; sale and refund IDs are both random UUIDs, so they do not collide. */
    val id: String

    /** When the transaction was started, in epoch milliseconds. */
    val createdAt: Long

    /**
     * A card payment.
     *
     * @property sale The stored sale, without its lines.
     */
    data class Sale(
        val sale: SaleEntity,
    ) : HistoryItem {
        override val id: String get() = sale.id
        override val createdAt: Long get() = sale.createdAt
    }

    /**
     * A referenced refund.
     *
     * @property refund The stored refund.
     */
    data class Refund(
        val refund: RefundEntity,
    ) : HistoryItem {
        override val id: String get() = refund.id
        override val createdAt: Long get() = refund.createdAt
    }
}

/**
 * The transaction history as a whole: sales and refunds together, and the housekeeping that applies to both tables
 * (settling interrupted transactions, retention and clearing). All functions are main-safe (Room runs them on its own
 * executor).
 */
class HistoryRepository(
    private val db: AppDatabase,
) {
    private val saleDao = db.saleDao()
    private val refundDao = db.refundDao()

    /** Observes all sales and refunds merged into one list, newest first. */
    fun items(): Flow<List<HistoryItem>> =
        combine(saleDao.sales(), refundDao.refunds()) { sales, refunds ->
            (sales.map { HistoryItem.Sale(it) } + refunds.map { HistoryItem.Refund(it) }).sortedByDescending { it.createdAt }
        }

    /** Reads one transactionally consistent snapshot of all retained records for report delivery. Main-safe. */
    suspend fun snapshot(): List<HistoryItem> =
        db.withTransaction {
            (saleDao.sales().first().map { HistoryItem.Sale(it) } + refundDao.refunds().first().map { HistoryItem.Refund(it) })
                .sortedByDescending { it.createdAt }
        }

    /**
     * Marks sales and refunds that never got an answer (for example because the app was killed mid-payment) as UNKNOWN,
     * so staff can check them from history, and captures that were being requested as UNKNOWN, so they can be sent
     * again; all for [StoredReason.Interrupted] (a sale's [SaleEvent.Interrupted]). Only safe at startup, before any
     * payment, refund or capture starts, because it treats every PENDING row as interrupted.
     */
    suspend fun settleInterrupted() {
        db.withTransaction {
            (saleDao.pendingSales() + saleDao.pendingCaptures()).distinctBy { it.id }.forEach {
                saleDao.update(it.after(SaleEvent.Interrupted))
            }
            refundDao.pendingRefunds().forEach {
                refundDao.update(it.copy(status = RefundStatus.UNKNOWN, message = null, reason = StoredReason.Interrupted))
            }
        }
    }

    /**
     * Deletes eligible settled sales and refunds older than [retentionDays] days before [now] (epoch milliseconds).
     * Unresolved payments and sales referenced by retained refunds remain. Returns the deleted record count;
     * [retentionDays] of 0 or less keeps everything.
     */
    suspend fun prune(
        retentionDays: Int,
        now: Long,
    ): Int {
        if (retentionDays <= 0) return 0
        val before = now - retentionDays * DAY_MILLIS
        return deleteSettledBefore(before)
    }

    /** Deletes settled history only; active or unresolved payments and their related refunds remain. The catalogue is kept. */
    suspend fun clear() {
        deleteSettledBefore(Long.MAX_VALUE)
    }

    /** Permanently purges every local operation, including unresolved ones, only after explicit switch confirmation. */
    suspend fun purgeForEnvironmentSwitch(target: TerminalSettings) {
        db.withTransaction {
            val json = StorageJson.encodeToString(serializer<TerminalSettings>(), target)
            saleDao.rememberHistorySwitch(HistorySwitchEntity(terminalJson = json))
            refundDao.deleteAllRefunds()
            saleDao.deleteAllSales()
        }
    }

    /** Confirmed switch target awaiting durable settings, or null when no switch needs recovery. */
    val pendingSwitch: Flow<HistorySwitchEntity?> = saleDao.historySwitch()

    /** Whether a confirmed switch must finish before new financial operations. */
    val switchPending: Flow<Boolean> = pendingSwitch.map { it != null }

    /** Decodes the current non-secret switch target; malformed journals fail rather than being ignored. */
    suspend fun switchTarget(): TerminalSettings? =
        pendingSwitch.first()?.let {
            StorageJson.decodeFromString(serializer<TerminalSettings>(), it.terminalJson)
        }

    /** Finishes a confirmed switch once its destination settings were saved. */
    suspend fun finishSwitch() = saleDao.finishHistorySwitch()

    /** Whether tracked sample sales remain; called inside sample setup's database transaction. */
    internal suspend fun hasSamples(): Boolean = saleDao.sales().first().any { it.sample }

    /** Removes every seeded demo sale and its report activity; merchant-created refunds and sales remain. */
    internal suspend fun removeSamples() {
        saleDao
            .sales()
            .first()
            .filter { it.sample }
            .forEach { saleDao.deleteSale(it.id) }
    }

    private suspend fun deleteSettledBefore(before: Long): Int =
        db.withTransaction {
            val sales = saleDao.sales().first()
            val refunds = refundDao.refunds().first()
            val protectedSales = sales.filterNot { it.retentionEligible }.map { it.id }.toSet()
            val removableRefunds =
                refunds.filter {
                    maxOf(it.createdAt, it.processedAt ?: 0) < before && it.status in setOf(RefundStatus.REQUESTED, RefundStatus.FAILED) &&
                        it.saleId !in protectedSales
                }
            val retainedReferences = (refunds - removableRefunds.toSet()).mapNotNull { it.saleId }.toSet()
            val removableSales =
                sales.filter {
                    maxOf(
                        it.createdAt,
                        it.processedAt ?: 0,
                        it.captureStartedAt ?: 0,
                        it.captureProcessedAt ?: 0,
                    ) <
                        before &&
                        it.retentionEligible && it.id !in retainedReferences
                }
            removableRefunds.sumOf { refundDao.deleteRefund(it.id) } + removableSales.sumOf { saleDao.deleteSale(it.id) }
        }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
