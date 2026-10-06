package io.github.astiskala.minimpos.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access to the catalogue: tax rates, categories and products.
 *
 * Suspending functions run on Room's query executor and are main-safe. The [Flow] queries emit the current list and
 * again after every change to their table. Lists use the same order everywhere: by `sortOrder`, then as noted.
 */
@Dao
interface CatalogDao {
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
    /** Inserts a new sale. */
    @Insert
    suspend fun insert(sale: SaleEntity)

    /** Inserts the lines of a sale; the sale must already exist. */
    @Insert
    suspend fun insertLines(lines: List<SaleLineEntity>)

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

    /** Observes all sales (without lines), newest first. */
    @Query("SELECT * FROM sales ORDER BY createdAt DESC")
    fun sales(): Flow<List<SaleEntity>>

    /** Returns the sales still marked [SaleStatus.PENDING], in no particular order. */
    @Query("SELECT * FROM sales WHERE status = 'PENDING'")
    suspend fun pendingSales(): List<SaleEntity>

    /** Returns the sales whose capture is still marked [CaptureStatus.PENDING], in no particular order. */
    @Query("SELECT * FROM sales WHERE captureStatus = 'PENDING'")
    suspend fun pendingCaptures(): List<SaleEntity>

    /** Deletes the sales created before [before] (epoch milliseconds), with their lines; returns how many sales. */
    @Query("DELETE FROM sales WHERE createdAt < :before")
    suspend fun deleteSalesBefore(before: Long): Int

    /** Deletes sale [id] and its lines, after the history module checked retention eligibility; returns the row count. */
    @Query("DELETE FROM sales WHERE id = :id")
    suspend fun deleteSale(id: String): Int

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

    /** Observes all refunds, newest first. */
    @Query("SELECT * FROM refunds ORDER BY createdAt DESC")
    fun refunds(): Flow<List<RefundEntity>>

    /** Observes the refunds of the local sale [saleId], newest first. */
    @Query("SELECT * FROM refunds WHERE saleId = :saleId ORDER BY createdAt DESC")
    fun refundsForSale(saleId: String): Flow<List<RefundEntity>>

    /** Returns the refunds still marked [RefundStatus.PENDING], in no particular order. */
    @Query("SELECT * FROM refunds WHERE status = 'PENDING'")
    suspend fun pendingRefunds(): List<RefundEntity>

    /** Deletes the refunds created before [before] (epoch milliseconds) and returns how many were deleted. */
    @Query("DELETE FROM refunds WHERE createdAt < :before")
    suspend fun deleteRefundsBefore(before: Long): Int

    /** Deletes refund [id], after the history module checked retention eligibility; returns the row count. */
    @Query("DELETE FROM refunds WHERE id = :id")
    suspend fun deleteRefund(id: String): Int

    /** Deletes every refund. */
    @Query("DELETE FROM refunds")
    suspend fun deleteAllRefunds()
}
