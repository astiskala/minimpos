package app.minimpos.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Every [SaleEntity] column for history and maintenance reads, with receipt and adjustment payloads left null. */
private const val SALE_HISTORY_COLUMNS = """
    id, sample, createdAt, currency, taxMode, netMinor, taxMinor, totalMinor, status,
    merchantReference, customerReference, shopperReference, shopperEmail, requestedWallet,
    tokenizationRequested, storedPaymentMethodId, serviceId, poiId, poiTransactionId, poiTimestamp,
    pspReference, paymentBrand, maskedPan, entryMode, authCode, message, NULL AS customerReceiptJson,
    NULL AS cashierReceiptJson, signatureRequired, refundedMinor, emailedTo, errorCondition,
    refusalReason, kind, tipOnReceipt, audioGuidance, tipMinor, authorisedMinor, adjustment, NULL AS
    adjustAuthorisationData, capturedMinor, captureStatus, modificationMessage, paymentMethodVariant,
    holdCancelled, paymentLink, paymentLinkId, paymentLinkUrl, paymentLinkExpiresAt, reason,
    modificationReason, context, linkLocale, linkCountry, linkRecurringModel, adjustmentKey,
    adjustmentAmountMinor, adjustmentPending, adjustmentStartedAt, processedAt, captureStartedAt,
    captureProcessedAt, poiReconciliationId, shopperRating, paidMinor, donationMinor, offlineMode,
    unconfirmedBatchCount, savedCard, chargedCardId, chargeRecurringModel, busyServiceId
"""

/** Every [RefundEntity] column for history and maintenance reads, with receipt and line payloads left null. */
private const val REFUND_HISTORY_COLUMNS = """
    id, saleId, createdAt, merchantReference, originalTransactionId, originalTimestamp,
    originalReference, currency, amountMinor, full, status, serviceId, pspReference, message, NULL AS
    customerReceiptJson, NULL AS linesJson, cancellation, reason, context, processedAt,
    poiReconciliationId, checkoutRefund
"""

/**
 * Room access to the catalogue: tax rates, categories and products.
 *
 * Suspending functions run on Room's query executor and are main-safe. The [Flow] queries emit the current list and
 * again after every change to their table. Lists use the same order everywhere: by `sortOrder`, then as noted.
 */
@Dao
interface CatalogDao {
    /** Whether any tracked catalog samples remain, without materializing merchant rows. */
    @Query("SELECT EXISTS(SELECT 1 FROM products WHERE sample = 1) OR EXISTS(SELECT 1 FROM categories WHERE sample = 1)")
    suspend fun hasSamples(): Boolean

    /** Removes only tracked sample products. */
    @Query("DELETE FROM products WHERE sample = 1")
    suspend fun deleteSampleProducts()

    /** Keeps sample categories referenced by merchant products, releasing their sample identity. */
    @Query("UPDATE categories SET sample = 0 WHERE sample = 1 AND id IN (SELECT categoryId FROM products)")
    suspend fun retainSampleCategories()

    /** Removes only unreferenced sample categories after their products were removed. */
    @Query("DELETE FROM categories WHERE sample = 1 AND id NOT IN (SELECT categoryId FROM products WHERE categoryId IS NOT NULL)")
    suspend fun deleteSampleCategories()

    /** Observes all tax rates by sort order, then highest rate first, then name. */
    @Query("SELECT * FROM tax_rates ORDER BY sortOrder, rateMilliPercent DESC, name")
    fun taxRates(): Flow<List<TaxRateEntity>>

    /** Returns all tax rates once, ordered as [taxRates]. */
    @Query("SELECT * FROM tax_rates ORDER BY sortOrder, rateMilliPercent DESC, name")
    suspend fun taxRatesOnce(): List<TaxRateEntity>

    /** Observes all categories by sort order, then name. */
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun categories(): Flow<List<CategoryEntity>>

    /** Returns all categories once, ordered as [categories]. */
    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun categoriesOnce(): List<CategoryEntity>

    /** Observes all products by sort order, then name ignoring case. */
    @Query("SELECT * FROM products ORDER BY sortOrder, name COLLATE NOCASE")
    fun products(): Flow<List<ProductEntity>>

    /** Returns all products once, ordered as [products]. */
    @Query("SELECT * FROM products ORDER BY sortOrder, name COLLATE NOCASE")
    suspend fun productsOnce(): List<ProductEntity>

    /** Returns the product with row ID [id], or null if there is none. */
    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun product(id: Long): ProductEntity?

    /** Returns the product whose unique SKU equals [sku] exactly, or null. */
    @Query("SELECT * FROM products WHERE sku = :sku LIMIT 1")
    suspend fun productBySku(sku: String): ProductEntity?

    /** Returns the product of [kind] whose unique SKU equals [sku] exactly, or null. */
    @Query("SELECT * FROM products WHERE sku = :sku AND kind = :kind LIMIT 1")
    suspend fun productBySku(
        sku: String,
        kind: SaleKind,
    ): ProductEntity?

    /** Counts the products that use the tax rate [taxRateId]; such a rate cannot be deleted. */
    @Query("SELECT COUNT(*) FROM products WHERE taxRateId = :taxRateId")
    suspend fun productCountForTaxRate(taxRateId: Long): Int

    /** Counts all tax rates; 0 only before the defaults are seeded. */
    @Query("SELECT COUNT(*) FROM tax_rates")
    suspend fun taxRateCount(): Int

    /** Inserts [taxRate] (its `id` must be 0) and returns the generated row ID. */
    @Insert
    suspend fun insert(taxRate: TaxRateEntity): Long

    /** Inserts [category] (its `id` must be 0) and returns the generated row ID. */
    @Insert
    suspend fun insert(category: CategoryEntity): Long

    /** Inserts [product] (its `id` must be 0) and returns the generated row ID. */
    @Insert
    suspend fun insert(product: ProductEntity): Long

    /** Overwrites the tax rate with the same `id`; does nothing if there is none. */
    @Update
    suspend fun update(taxRate: TaxRateEntity)

    /** Overwrites the category with the same `id`; does nothing if there is none. */
    @Update
    suspend fun update(category: CategoryEntity)

    /** Overwrites the product with the same `id`; does nothing if there is none. */
    @Update
    suspend fun update(product: ProductEntity)

    /**
     * Deletes the tax rate with the same `id`.
     *
     * @throws android.database.sqlite.SQLiteConstraintException if products still use it (foreign key `RESTRICT`).
     */
    @Delete
    suspend fun delete(taxRate: TaxRateEntity)

    /** Deletes the category with the same `id`; its products become uncategorised. */
    @Delete
    suspend fun delete(category: CategoryEntity)

    /** Deletes the product with the same `id`. Past sale lines keep their copy of the product's details. */
    @Delete
    suspend fun delete(product: ProductEntity)

    /** Latest committed import receipt, or null before the first catalog transfer. */
    @Query("SELECT * FROM catalog_import WHERE id = 1")
    suspend fun importReceipt(): CatalogImportEntity?

    /** Records [receipt] inside the catalog import transaction, replacing only the previous recovery receipt. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rememberImport(receipt: CatalogImportEntity)

    /** Deletes every product. */
    @Query("DELETE FROM products")
    suspend fun deleteAllProducts()

    /** Deletes every category. */
    @Query("DELETE FROM categories")
    suspend fun deleteAllCategories()

    /** Deletes every tax rate; delete the products first, as they reference the rates. */
    @Query("DELETE FROM tax_rates")
    suspend fun deleteAllTaxRates()
}

/**
 * Room access to sales and their lines.
 *
 * Suspending functions run on Room's query executor and are main-safe. Queries returning [SaleWithLines] load the sale
 * and its lines in one transaction.
 */
@Dao
interface SaleDao {
    /** Age-qualified candidates in indexed creation order; accounting still decides deletion eligibility. */
    @Query(
        """
        SELECT $SALE_HISTORY_COLUMNS FROM sales
        WHERE createdAt >= :afterAt AND createdAt < :before
        AND (createdAt > :afterAt OR id > :afterId)
        AND (processedAt IS NULL OR processedAt < :before) AND (captureStartedAt IS NULL OR captureStartedAt < :before) AND (captureProcessedAt IS NULL OR captureProcessedAt < :before)
        ORDER BY createdAt, id LIMIT :limit
        """,
    )
    suspend fun retentionBatch(
        afterAt: Long,
        afterId: String,
        before: Long,
        limit: Int,
    ): List<SaleEntity>

    /** Indexed candidate activity times, newest first, bounded by [limit]; accounting owns their meaning. */
    @Query(
        """
        SELECT id, createdAt AS at, 0 AS refund FROM sales
        WHERE createdAt >= :afterAt AND createdAt <= :beforeAt
        AND (createdAt < :beforeAt OR id < :beforeId)
        UNION ALL
        SELECT id, processedAt AS at, 0 AS refund FROM sales
        WHERE processedAt >= :afterAt AND processedAt <= :beforeAt
        AND (processedAt < :beforeAt OR id < :beforeId)
        UNION ALL
        SELECT id, captureProcessedAt AS at, 0 AS refund FROM sales
        WHERE captureProcessedAt >= :afterAt AND captureProcessedAt <= :beforeAt
        AND (captureProcessedAt < :beforeAt OR id < :beforeId)
        UNION ALL
        SELECT id, captureStartedAt AS at, 0 AS refund FROM sales
        WHERE captureStartedAt >= :afterAt AND captureStartedAt <= :beforeAt
        AND (captureStartedAt < :beforeAt OR id < :beforeId)
        UNION ALL
        SELECT id, createdAt AS at, 1 AS refund FROM refunds
        WHERE createdAt >= :afterAt AND createdAt <= :beforeAt
        AND (createdAt < :beforeAt OR id < :beforeId)
        UNION ALL
        SELECT id, processedAt AS at, 1 AS refund FROM refunds
        WHERE processedAt >= :afterAt AND processedAt <= :beforeAt
        AND (processedAt < :beforeAt OR id < :beforeId)
        ORDER BY at DESC, id DESC LIMIT :limit
        """,
    )
    suspend fun historyKeys(
        beforeAt: Long,
        beforeId: String,
        afterAt: Long,
        limit: Int,
    ): List<HistoryKey>

    /** All split tenders belonging to a bounded history-deletion batch. */
    @Query("SELECT * FROM sale_tenders WHERE saleId IN (:ids)")
    suspend fun historyTenders(ids: List<String>): List<SaleTenderEntity>

    /** One representative per distinct method; the covering index avoids materializing the full sales table. */
    @Query("SELECT MIN(id) FROM sales GROUP BY paymentBrand, paymentMethodVariant, requestedWallet")
    suspend fun historyMethodIds(): List<String>

    /** Token-bearing sales for one shopper, newest first, with bounded keyset paging and no receipt bodies. */
    @Query(
        """
        SELECT $SALE_HISTORY_COLUMNS FROM sales
        WHERE shopperReference = :shopper AND storedPaymentMethodId IS NOT NULL
        AND createdAt <= :beforeAt AND (createdAt < :beforeAt OR id < :beforeId)
        ORDER BY createdAt DESC, id DESC LIMIT :limit
        """,
    )
    suspend fun shopperSales(
        shopper: String,
        beforeAt: Long,
        beforeId: String,
        limit: Int,
    ): List<SaleEntity>

    /** Lightweight originals of refunds in one history batch; receipt payloads are omitted. */
    @Query(
        """
        SELECT $SALE_HISTORY_COLUMNS FROM sales
        WHERE id IN (:ids)
        """,
    )
    suspend fun historyParents(ids: List<String>): List<SaleEntity>

    /**
     * Whether any sale or refund exists, re-emitted after every write to either table, so history readers refresh once
     * per transaction without loading transaction bodies.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM sales) OR EXISTS(SELECT 1 FROM refunds)")
    fun historyChanges(): Flow<Boolean>

    /** Lightweight records after [afterId] in primary-key order, at most [limit]; receipt payloads are omitted. */
    @Query(
        """
        SELECT $SALE_HISTORY_COLUMNS FROM sales
        WHERE id > :afterId ORDER BY id LIMIT :limit
        """,
    )
    suspend fun historyBatch(
        afterId: String,
        limit: Int,
    ): List<SaleEntity>

    /** Deletes the eligible [ids] selected transactionally by history housekeeping, returning the deleted count. */
    @Query("DELETE FROM sales WHERE id IN (:ids)")
    suspend fun deleteHistory(ids: List<String>): Int

    /** Observes a confirmed purge still awaiting its settings write. */
    @Query("SELECT * FROM history_switch WHERE id = 1")
    fun historySwitch(): Flow<HistorySwitchEntity?>

    /** Stores the confirmed non-secret switch target in the same transaction as history deletion. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rememberHistorySwitch(switch: HistorySwitchEntity)

    /** Clears the journal only after destination settings were saved. */
    @Query("DELETE FROM history_switch")
    suspend fun finishHistorySwitch()

    /** Inserts a new sale. */
    @Insert
    suspend fun insert(sale: SaleEntity)

    /** Inserts the lines of a sale; the sale must already exist. */
    @Insert
    suspend fun insertLines(lines: List<SaleLineEntity>)

    /** Inserts or replaces a recorded payment tender for an existing sale. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTender(tender: SaleTenderEntity)

    /** Overwrites the sale with the same `id`; its lines are not touched. */
    @Update
    suspend fun update(sale: SaleEntity)

    /** Overwrites the given lines, matched by `id`. */
    @Update
    suspend fun updateLines(lines: List<SaleLineEntity>)

    /** Returns the sale with ID [id] and its lines, or null if there is none. */
    @Transaction
    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun sale(id: String): SaleWithLines?

    /** Observes the sale with ID [id] and its lines; emits null while there is none. */
    @Transaction
    @Query("SELECT * FROM sales WHERE id = :id")
    fun observeSale(id: String): Flow<SaleWithLines?>

    /** Returns the sale with the terminal's POITransactionID [transactionId] and its lines, or null. */
    @Transaction
    @Query("SELECT * FROM sales WHERE poiTransactionId = :transactionId LIMIT 1")
    suspend fun saleByTransactionId(transactionId: String): SaleWithLines?

    /** Whether any tracked sample history remains, without loading sales or receipts. */
    @Query("SELECT EXISTS(SELECT 1 FROM sales WHERE sample = 1)")
    suspend fun hasSamples(): Boolean

    /** Only tracked sample headers, omitting receipt payloads, for atomic sample removal. */
    @Query(
        """
        SELECT $SALE_HISTORY_COLUMNS FROM sales
        WHERE sample = 1
        """,
    )
    suspend fun sampleSales(): List<SaleEntity>

    /** One sale header without loading its lines or tenders. */
    @Query("SELECT * FROM sales WHERE id = :id")
    suspend fun header(id: String): SaleEntity?

    /** Number of retained tenders, read inside the settlement transaction before appending one. */
    @Query("SELECT COUNT(*) FROM sale_tenders WHERE saleId = :saleId")
    suspend fun tenderCount(saleId: String): Int

    /** Returns the sales still marked [SaleStatus.PENDING], in no particular order. */
    @Query("SELECT * FROM sales WHERE status = 'PENDING'")
    suspend fun pendingSales(): List<SaleEntity>

    /** Returns the sales whose capture is still marked [CaptureStatus.PENDING], in no particular order. */
    @Query("SELECT * FROM sales WHERE captureStatus = 'PENDING'")
    suspend fun pendingCaptures(): List<SaleEntity>

    /** Deletes every sale and, by cascade, every sale line. */
    @Query("DELETE FROM sales")
    suspend fun deleteAllSales()
}

/**
 * Room access to refunds.
 *
 * Suspending functions run on Room's query executor and are main-safe.
 */
@Dao
interface RefundDao {
    /** Age-qualified candidates in indexed creation order; accounting still decides deletion eligibility. */
    @Query(
        """
        SELECT $REFUND_HISTORY_COLUMNS FROM refunds
        WHERE createdAt >= :afterAt AND createdAt < :before
        AND (createdAt > :afterAt OR id > :afterId)
        AND (processedAt IS NULL OR processedAt < :before)
        ORDER BY createdAt, id LIMIT :limit
        """,
    )
    suspend fun retentionBatch(
        afterAt: Long,
        afterId: String,
        before: Long,
        limit: Int,
    ): List<RefundEntity>

    /** Original sales still referenced by any retained refund among [saleIds]. */
    @Query("SELECT DISTINCT saleId FROM refunds WHERE saleId IN (:saleIds)")
    suspend fun referencedSales(saleIds: List<String>): List<String>

    /** Lightweight refunds with these [ids]; receipt payloads are omitted. */
    @Query(
        """
        SELECT $REFUND_HISTORY_COLUMNS FROM refunds WHERE id IN (:ids)
        """,
    )
    suspend fun historyRecords(ids: List<String>): List<RefundEntity>

    /** Lightweight records after [afterId] in primary-key order, at most [limit]; receipt payloads are omitted. */
    @Query(
        """
        SELECT $REFUND_HISTORY_COLUMNS FROM refunds WHERE id > :afterId ORDER BY id LIMIT :limit
        """,
    )
    suspend fun historyBatch(
        afterId: String,
        limit: Int,
    ): List<RefundEntity>

    /** Deletes the eligible [ids] selected transactionally by history housekeeping, returning the deleted count. */
    @Query("DELETE FROM refunds WHERE id IN (:ids)")
    suspend fun deleteHistory(ids: List<String>): Int

    /** Inserts a new refund. */
    @Insert
    suspend fun insert(refund: RefundEntity)

    /** Overwrites the refund with the same `id`. */
    @Update
    suspend fun update(refund: RefundEntity)

    /** Returns the refund with ID [id], or null if there is none. */
    @Query("SELECT * FROM refunds WHERE id = :id")
    suspend fun refund(id: String): RefundEntity?

    /** Observes the refund with ID [id]; emits null while there is none. */
    @Query("SELECT * FROM refunds WHERE id = :id")
    fun observeRefund(id: String): Flow<RefundEntity?>

    /** Observes the refunds of the local sale [saleId], newest first. */
    @Query("SELECT * FROM refunds WHERE saleId = :saleId ORDER BY createdAt DESC")
    fun refundsForSale(saleId: String): Flow<List<RefundEntity>>

    /** Returns the refunds still marked [RefundStatus.PENDING], in no particular order. */
    @Query("SELECT * FROM refunds WHERE status = 'PENDING'")
    suspend fun pendingRefunds(): List<RefundEntity>

    /** Deletes every refund. */
    @Query("DELETE FROM refunds")
    suspend fun deleteAllRefunds()
}
