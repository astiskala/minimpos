package app.minimpos.app.data.repo

import androidx.room.withTransaction
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.HistoryKey
import app.minimpos.app.data.db.HistorySwitchEntity
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.settings.StorageJson
import app.minimpos.app.data.settings.TerminalSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
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
     * @property tenders Lightweight original tenders without receipt bodies.
     */
    data class Sale(
        val sale: SaleEntity,
        val tenders: List<SaleTenderEntity> = emptyList(),
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
    /** Dispatcher for mapping and sorting observed history outside Room's query executor. */
    cpu: CoroutineDispatcher = Dispatchers.Default,
    /** Receives successfully deleted records after commit, for process-only recovery and simulator cleanup. */
    private val onRemoved: (List<HistoryItem>, List<SaleTenderEntity>) -> Unit = { _, _ -> },
    /** Clears process bookkeeping only after an explicitly confirmed full purge commits. */
    private val onPurged: () -> Unit = {},
) {
    private val saleDao = db.saleDao()
    private val refundDao = db.refundDao()

    /** Whether any history exists, emitted again after each write to either table, without materializing records. */
    val changes: Flow<Boolean> = saleDao.historyChanges()

    /** Bounded, lightweight history access for lists and searches. */
    val reader = Reader(db, cpu)

    /**
     * Marks sales and refunds that never got an answer (for example because the app was killed mid-payment) as UNKNOWN,
     * so staff can check them from history, together with the tender rows they were sending, and captures that were
     * being requested as UNKNOWN, so they can be sent again; all for [StoredReason.Interrupted] (a sale's
     * [SaleEvent.Interrupted]). Only safe at startup, before any payment, refund or capture starts, because it treats
     * every PENDING row as interrupted.
     */
    suspend fun settleInterrupted() {
        db.withTransaction {
            (saleDao.pendingSales() + saleDao.pendingCaptures()).distinctBy { it.id }.forEach {
                saleDao.update(it.after(SaleEvent.Interrupted))
            }
            saleDao.interruptPendingTenders()
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
        onPurged()
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
    internal suspend fun hasSamples(): Boolean = saleDao.hasSamples()

    /** Removes samples and [removeCatalog] atomically, releasing process state only after the outer transaction commits. */
    internal suspend fun removeSamples(removeCatalog: suspend () -> Unit) {
        val removed =
            db.withTransaction {
                val records = saleDao.sampleSales()
                val tenders =
                    records.chunked(BATCH_SIZE).flatMap { chunk ->
                        val ids = chunk.map { it.id }
                        saleDao.historyTenders(ids).also { saleDao.deleteHistory(ids) }
                    }
                removeCatalog()
                records.map { HistoryItem.Sale(it) } to tenders
            }
        onRemoved(removed.first, removed.second)
    }

    private suspend fun deleteSettledBefore(before: Long): Int = deleteRefundsBefore(before) + deleteSalesBefore(before)

    // The retention batch queries already enforce the age cut-off; only eligibility is decided here.
    private suspend fun deleteRefundsBefore(before: Long): Int {
        var deleted = 0
        keysetBatches<RefundEntity> { last ->
            val (records, removed, erased) =
                db.withTransaction {
                    val records = refundDao.retentionBatch(last?.createdAt ?: Long.MIN_VALUE, last?.id.orEmpty(), before, BATCH_SIZE)
                    val parents = saleDao.historyParents(records.mapNotNull { it.saleId }.distinct()).associateBy { it.id }
                    val eligible =
                        records.filter {
                            !it.status.unresolved && it.saleId?.let { id -> parents[id]?.retentionEligible } != false
                        }
                    Triple(records, refundDao.deleteHistory(eligible.map { it.id }), eligible.map { HistoryItem.Refund(it) })
                }
            onRemoved(erased, emptyList())
            deleted += removed
            records
        }
        return deleted
    }

    private suspend fun deleteSalesBefore(before: Long): Int {
        var deleted = 0
        keysetBatches<SaleEntity> { last ->
            val (records, removed, erased) =
                db.withTransaction {
                    val records = saleDao.retentionBatch(last?.createdAt ?: Long.MIN_VALUE, last?.id.orEmpty(), before, BATCH_SIZE)
                    val referenced = refundDao.referencedSales(records.map { it.id }).toSet()
                    val eligible = records.filter { it.retentionEligible && it.id !in referenced }
                    val ids = eligible.map { it.id }
                    val tenders = saleDao.historyTenders(ids)
                    Triple(records, saleDao.deleteHistory(ids), eligible.map { HistoryItem.Sale(it) } to tenders)
                }
            onRemoved(erased.first, erased.second)
            deleted += removed
            records
        }
        return deleted
    }

    /** Lightweight bounded history reads; financial meaning and filtering remain with their pure owners. */
    class Reader internal constructor(
        private val db: AppDatabase,
        private val cpu: CoroutineDispatcher,
    ) {
        private val saleDao = db.saleDao()
        private val refundDao = db.refundDao()

        /**
         * Reads at most 256 indexed candidates before [before] and on/after [afterAt] (epoch milliseconds).
         * Null [before] starts at the newest candidate. Receipts are omitted; parent sales accompany refunds for searching.
         */
        suspend fun page(
            before: HistoryKey? = null,
            afterAt: Long = Long.MIN_VALUE,
        ): HistoryBatch =
            db.withTransaction {
                // UNION ALL merges covering indexes without a temporary sort. Drop duplicate timestamps within this bounded batch.
                val keys = saleDao.historyKeys(before?.at ?: Long.MAX_VALUE, before?.id ?: "\uffff", afterAt, BATCH_SIZE).distinct()
                val refunds = refundDao.historyRecords(keys.filter { it.refund }.map { it.id })
                val sales = saleDao.historyParents(keys.filterNot { it.refund }.map { it.id } + refunds.mapNotNull { it.saleId })
                val tenders = saleDao.historyTenders(sales.map { it.id }).groupBy { it.saleId }
                HistoryBatch(keys, sales.associateBy { it.id }, refunds.associateBy { it.id }, tenders)
            }

        /**
         * Folds every retained record once in batches of at most 256, omitting receipt bodies.
         * [reduce] must not retain batches or perform side effects. One read transaction preserves snapshot consistency;
         * cancellation is checked between batches. Work runs outside Main on the injected CPU/Room executors.
         */
        suspend fun <T> fold(
            initial: T,
            reduce: (T, List<HistoryItem>) -> T,
        ): T =
            withContext(cpu) {
                db.withTransaction {
                    var result = initial
                    keysetBatches<SaleEntity> { last ->
                        currentCoroutineContext().ensureActive()
                        saleDao.historyBatch(last?.id.orEmpty(), BATCH_SIZE).also { batch ->
                            val tenders = saleDao.historyTenders(batch.map { it.id }).groupBy { it.saleId }
                            result = reduce(result, batch.map { HistoryItem.Sale(it, tenders[it.id].orEmpty()) })
                        }
                    }
                    keysetBatches<RefundEntity> { last ->
                        currentCoroutineContext().ensureActive()
                        refundDao.historyBatch(last?.id.orEmpty(), BATCH_SIZE).also { batch ->
                            result = reduce(result, batch.map { HistoryItem.Refund(it) })
                        }
                    }
                    result
                }
            }

        /** Folds only [shopper]'s token-bearing sales, newest first, without receipt payloads or unrelated history. */
        suspend fun <T> shopper(
            shopper: String,
            initial: T,
            reduce: (T, List<SaleEntity>) -> T,
        ): T =
            withContext(cpu) {
                db.withTransaction {
                    var result = initial
                    keysetBatches<SaleEntity> { last ->
                        currentCoroutineContext().ensureActive()
                        saleDao.shopperSales(shopper, last?.createdAt ?: Long.MAX_VALUE, last?.id ?: "\uffff", BATCH_SIZE).also {
                            result = reduce(result, it)
                        }
                    }
                    result
                }
            }

        /** Representative lightweight sales for every payment method present in retained history. */
        suspend fun methods(): List<SaleEntity> =
            withContext(cpu) {
                saleDao.historyMethodIds().chunked(BATCH_SIZE).flatMap { saleDao.historyParents(it) }
            }
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** Largest number of records any history read or deletion loads at once. */
internal const val BATCH_SIZE = 256

/**
 * Runs keyset-paged [next] until it returns fewer than [BATCH_SIZE] records. [next] receives the last record of the
 * previous batch, or null for the first batch, and returns its own batch.
 */
internal inline fun <E> keysetBatches(next: (last: E?) -> List<E>) {
    var last: E? = null
    do {
        val batch = next(last)
        last = batch.lastOrNull() ?: last
    } while (batch.size == BATCH_SIZE)
}

/**
 * One bounded batch of candidate activity and the records needed to interpret it.
 *
 * @property keys Candidates in descending timestamp and ID order.
 * @property sales Lightweight sales, including refund parents.
 * @property refunds Lightweight refunds.
 * @property tenders Lightweight original tenders keyed by sale ID.
 */
data class HistoryBatch(
    val keys: List<HistoryKey>,
    val sales: Map<String, SaleEntity>,
    val refunds: Map<String, RefundEntity>,
    val tenders: Map<String, List<SaleTenderEntity>> = emptyMap(),
) {
    /** Candidate's original record; null if a concurrent deletion removed it. */
    fun item(key: HistoryKey): HistoryItem? =
        if (key.refund) refunds[key.id]?.let(HistoryItem::Refund) else sales[key.id]?.let { HistoryItem.Sale(it, tenders[it.id].orEmpty()) }
}

/**
 * The history environment (LIVE, TEST or SIMULATOR) this record's money moved in: its original payment context, else
 * the environment its cash tenders were recorded in; null while unknown. Sample history is simulated.
 */
val HistoryItem.recordedEnvironment: String?
    get() =
        when (this) {
            is HistoryItem.Sale -> {
                if (sale.sample) {
                    "SIMULATOR"
                } else {
                    sale.context?.historyEnvironment
                        ?: tenders.firstNotNullOfOrNull { it.environment }
                }
            }

            is HistoryItem.Refund -> {
                refund.context?.historyEnvironment
            }
        }
