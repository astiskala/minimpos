package io.minimpos.app.data.repo

import androidx.room.withTransaction
import io.minimpos.app.data.db.AppDatabase
import io.minimpos.app.data.db.CatalogDao
import io.minimpos.app.data.db.CategoryEntity
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.core.catalogue.Catalogue
import io.minimpos.core.catalogue.CatalogueCategory
import io.minimpos.core.catalogue.CatalogueProduct
import io.minimpos.core.catalogue.CatalogueTaxRate
import kotlinx.coroutines.flow.Flow

/** How an imported catalogue is combined with the one on the device. */
enum class ImportMode {
    /** Delete the current catalogue first. */
    REPLACE,

    /**
     * Keep the current catalogue: update products with the same SKU (or, without a SKU, the same name ignoring case)
     * and add the rest. Tax rates and categories that already exist are reused.
     */
    MERGE,
}

/**
 * What a catalogue import changed.
 *
 * @property productsAdded Products inserted.
 * @property productsUpdated Existing products overwritten (only in [ImportMode.MERGE]).
 * @property taxRatesAdded Tax rates inserted, including a 0% rate created for untaxed products.
 * @property categoriesAdded Categories inserted.
 */
data class ImportSummary(
    val productsAdded: Int,
    val productsUpdated: Int,
    val taxRatesAdded: Int,
    val categoriesAdded: Int,
)

/** The result of trying to delete a tax rate. */
sealed interface DeleteResult {
    /** The tax rate was deleted. */
    data object Deleted : DeleteResult

    /**
     * The tax rate was kept because products still use it.
     *
     * @property productCount How many products use it.
     */
    data class InUse(
        val productCount: Int,
    ) : DeleteResult
}

/**
 * The product catalogue: tax rates, categories and products, plus export and import of the compact catalogue format
 * shared by QR code (see [Catalogue]).
 *
 * All functions are main-safe (Room runs them on its own executor).
 */
class CatalogRepository(
    private val db: AppDatabase,
    /**
     * Name for the 0% rate created when an imported catalogue has untaxed products (possible in format v2 from older
     * builds) and no 0% rate exists.
     */
    private val zeroRateName: String = "Zero rated",
) {
    private val dao = db.catalogDao()

    /** All tax rates, ordered for display (see [CatalogDao.taxRates]). */
    val taxRates: Flow<List<TaxRateEntity>> = dao.taxRates()

    /** All categories, ordered for display. */
    val categories: Flow<List<CategoryEntity>> = dao.categories()

    /** All products, ordered for display. */
    val products: Flow<List<ProductEntity>> = dao.products()

    /**
     * Creates the starter tax rates on first launch, so products can be added straight away: [standardName] at 10% and
     * [zeroName] at 0%. Does nothing once any tax rate exists.
     */
    suspend fun seedDefaults(
        standardName: String,
        zeroName: String,
    ) {
        db.withTransaction {
            if (dao.taxRateCount() == 0) {
                dao.insert(TaxRateEntity(name = standardName, rateMilliPercent = DEFAULT_STANDARD_RATE, sortOrder = 0))
                dao.insert(TaxRateEntity(name = zeroName, rateMilliPercent = 0, sortOrder = 1))
            }
        }
    }

    /** Inserts [taxRate] when its ID is 0, otherwise updates it; returns its ID. */
    suspend fun saveTaxRate(taxRate: TaxRateEntity): Long =
        if (taxRate.id == 0L) {
            dao.insert(taxRate)
        } else {
            dao.update(taxRate)
            taxRate.id
        }

    /**
     * Deletes [taxRate] unless products use it. The caller is responsible for keeping at least one rate (Settings does
     * not offer to delete the last one).
     */
    suspend fun deleteTaxRate(taxRate: TaxRateEntity): DeleteResult {
        val used = dao.productCountForTaxRate(taxRate.id)
        if (used > 0) return DeleteResult.InUse(used)
        dao.delete(taxRate)
        return DeleteResult.Deleted
    }

    /** Inserts [category] when its ID is 0, otherwise updates it; returns its ID. */
    suspend fun saveCategory(category: CategoryEntity): Long =
        if (category.id == 0L) {
            dao.insert(category)
        } else {
            dao.update(category)
            category.id
        }

    /** Deletes [category]; its products stay and become uncategorised. */
    suspend fun deleteCategory(category: CategoryEntity) = dao.delete(category)

    /** Inserts [product] when its ID is 0, otherwise updates it; returns its ID. */
    suspend fun saveProduct(product: ProductEntity): Long =
        if (product.id == 0L) {
            dao.insert(product)
        } else {
            dao.update(product)
            product.id
        }

    /** Deletes [product]. Past sales keep their own copy of its details. */
    suspend fun deleteProduct(product: ProductEntity) = dao.delete(product)

    /** Returns the product with row ID [id], or null if there is none. */
    suspend fun product(id: Long): ProductEntity? = dao.product(id)

    /**
     * Returns a product with the scanned or typed [sku] (surrounding whitespace ignored), of [kind] when one is given
     * (else of either kind), or null.
     */
    suspend fun productBySku(
        sku: String,
        kind: SaleKind? = null,
    ): ProductEntity? = if (kind == null) dao.productBySku(sku.trim()) else dao.productBySku(sku.trim(), kind)

    /**
     * Snapshots the whole catalogue for sharing. Tax rates and categories are referenced by their index in the exported
     * lists, so row IDs never leave the device; [currencyCode] tells the importing device what the prices mean.
     */
    suspend fun export(currencyCode: String): Catalogue {
        val taxRates = dao.taxRatesOnce()
        val categories = dao.categoriesOnce()
        val taxIndex = taxRates.withIndex().associate { it.value.id to it.index }
        val categoryIndex = categories.withIndex().associate { it.value.id to it.index }
        return Catalogue(
            currencyCode = currencyCode,
            taxRates = taxRates.map { CatalogueTaxRate(it.name, it.rateMilliPercent) },
            categories = categories.map { CatalogueCategory(it.name) },
            products =
                dao.productsOnce().map {
                    CatalogueProduct(
                        it.name,
                        it.priceMinor,
                        taxIndex.getValue(it.taxRateId),
                        it.categoryId?.let(categoryIndex::get),
                        it.sku,
                        preAuthorisation = it.kind == SaleKind.PRE_AUTHORISATION,
                    )
                },
        )
    }

    /**
     * Imports [catalogue] in one transaction, so a failure leaves the current catalogue untouched.
     *
     * Tax rates are reused when one with the same name (ignoring case) and rate exists, categories when one with the same
     * name exists; the rest are added in the catalogue's order. Products are matched as described for [ImportMode.MERGE]
     * (in [ImportMode.REPLACE] everything was deleted first, so all are added); a matched product keeps its sort order and
     * takes the incoming kind (sale or pre-authorisation). Untaxed products from older catalogues get the first 0% rate,
     * which is created if there is none. The currency is not checked here; prices are taken as they are.
     */
    suspend fun import(
        catalogue: Catalogue,
        mode: ImportMode,
    ): ImportSummary =
        db.withTransaction {
            if (mode == ImportMode.REPLACE) {
                dao.deleteAllProducts()
                dao.deleteAllCategories()
                dao.deleteAllTaxRates()
            }
            val taxRates = importTaxRates(catalogue.taxRates)
            val categories = importCategories(catalogue.categories)
            val products = importProducts(catalogue.products, taxRates, categories.ids)
            ImportSummary(products.added, products.updated, taxRates.added, categories.added)
        }

    private suspend fun importTaxRates(rates: List<CatalogueTaxRate>): ImportedTaxRates {
        val existing = dao.taxRatesOnce()
        var added = 0
        val ids =
            rates.mapIndexed { index, rate ->
                existing
                    .firstOrNull { it.name.equals(rate.name, ignoreCase = true) && it.rateMilliPercent == rate.rateMilliPercent }
                    ?.id
                    ?: dao.insert(TaxRateEntity(name = rate.name, rateMilliPercent = rate.rateMilliPercent, sortOrder = index)).also {
                        added++
                    }
            }
        return ImportedTaxRates(ids, added)
    }

    private suspend fun importCategories(categories: List<CatalogueCategory>): ImportedCategories {
        val existing = dao.categoriesOnce()
        var added = 0
        val ids =
            categories.mapIndexed { index, category ->
                existing.firstOrNull { it.name.equals(category.name, ignoreCase = true) }?.id
                    ?: dao.insert(CategoryEntity(name = category.name, sortOrder = index)).also { added++ }
            }
        return ImportedCategories(ids, added)
    }

    private suspend fun importProducts(
        products: List<CatalogueProduct>,
        taxRates: ImportedTaxRates,
        categoryIds: List<Long>,
    ): ProductChanges {
        val existing = dao.productsOnce()
        val changes = ProductChanges()
        products.forEachIndexed { index, product ->
            val match =
                existing.firstOrNull {
                    if (product.sku != null) it.sku == product.sku else it.name.equals(product.name, ignoreCase = true)
                }
            val entity =
                ProductEntity(
                    id = match?.id ?: 0,
                    name = product.name,
                    priceMinor = product.priceMinor,
                    taxRateId = product.taxRateIndex?.let(taxRates.ids::get) ?: zeroRateId(taxRates),
                    categoryId = product.categoryIndex?.let(categoryIds::get),
                    sku = product.sku,
                    sortOrder = match?.sortOrder ?: index,
                    kind = if (product.preAuthorisation) SaleKind.PRE_AUTHORISATION else SaleKind.SALE,
                )
            if (match == null) {
                dao.insert(entity)
                changes.added++
            } else {
                dao.update(entity)
                changes.updated++
            }
        }
        return changes
    }

    /** Catalogues from older versions can have untaxed products; they get a 0% rate, created if there is none. */
    private suspend fun zeroRateId(taxRates: ImportedTaxRates): Long =
        taxRates.zeroRateId ?: (
            dao.taxRatesOnce().firstOrNull { it.rateMilliPercent == 0 }?.id
                ?: dao.insert(TaxRateEntity(name = zeroRateName, rateMilliPercent = 0, sortOrder = taxRates.ids.size)).also {
                    taxRates.added++
                }
        ).also { taxRates.zeroRateId = it }

    /** Row IDs of the imported tax rates by catalogue index, and the 0% rate once [zeroRateId] has looked it up. */
    private class ImportedTaxRates(
        val ids: List<Long>,
        var added: Int,
        var zeroRateId: Long? = null,
    )

    private class ImportedCategories(
        val ids: List<Long>,
        val added: Int,
    )

    private class ProductChanges(
        var added: Int = 0,
        var updated: Int = 0,
    )

    private companion object {
        /** 10% in thousandths of a percent. */
        const val DEFAULT_STANDARD_RATE = 10_000
    }
}
