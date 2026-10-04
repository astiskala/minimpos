package io.github.astiskala.minimpos.app.feature.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.astiskala.minimpos.app.data.db.CategoryEntity
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.CatalogRepository
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.core.money.CurrencySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the product list shows.
 *
 * @property products All products, in display order.
 * @property categories All categories, in display order.
 * @property taxRates All tax rates, to label each product's rate.
 * @property currency The currency prices are shown in.
 * @property chargeTax Settings › Tax › Charge tax; when off, tax rates are not shown.
 */
data class ProductsUiState(
    val products: List<ProductEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val taxRates: List<TaxRateEntity> = emptyList(),
    val currency: CurrencySpec = CurrencySpec("EUR", 2),
    val chargeTax: Boolean = true,
)

/** Products and categories. Tax rates are managed in Settings › Tax. */
class ProductsViewModel(
    private val catalog: CatalogRepository,
    settingsState: StateFlow<AppSettings>,
    currency: (AppSettings) -> CurrencySpec,
) : ViewModel() {
    /** The screen state, updated whenever the catalogue or settings change. */
    val state: StateFlow<ProductsUiState> =
        combine(catalog.products, catalog.categories, catalog.taxRates, settingsState) { products, categories, taxRates, appSettings ->
            ProductsUiState(products, categories, taxRates, currency(appSettings), appSettings.payment.chargeTax)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProductsUiState())

    /** Adds [category] (ID 0) or renames it, trimming the name. */
    fun saveCategory(category: CategoryEntity) {
        launchWrite({ catalog.saveCategory(category.copy(name = category.name.trim())) })
    }

    /** Deletes [category]; its products become uncategorised. */
    fun deleteCategory(category: CategoryEntity) {
        launchWrite({ catalog.deleteCategory(category) })
    }
}

/**
 * The product being edited, as typed.
 *
 * @property id The product's row ID; 0 for a new product.
 * @property name The name as typed.
 * @property price The unit price as typed in major units, such as "4.50" (a comma also works as decimal separator).
 * @property taxRateId Null only until a tax rate exists; items without tax use a 0% rate.
 * @property categoryId The category, or null for none.
 * @property sku The SKU or barcode as typed; blank for none.
 * @property sortOrder The product's position, kept unchanged by the form.
 * @property preAuthorisation Whether the product is taken as a pre-authorisation (under Pre-authorise) rather than
 *   sold in a sale.
 */
data class ProductForm(
    val id: Long = 0,
    val name: String = "",
    val price: String = "",
    val taxRateId: Long? = null,
    val categoryId: Long? = null,
    val sku: String = "",
    val sortOrder: Int = 0,
    val preAuthorisation: Boolean = false,
)

/**
 * What the product editor shows.
 *
 * @property loaded False until the product (if any) and the lists have been read.
 * @property form The product as typed.
 * @property taxRates The rates to choose from.
 * @property categories The categories to choose from.
 * @property currency The currency of the price.
 * @property skuInUse Whether another product already has the typed SKU, which blocks saving.
 * @property chargeTax Settings › Tax › Charge tax; when off the product's tax rate is hidden (but kept).
 * @property saving Whether a save or delete is under way; another one is ignored until it has finished.
 */
data class ProductEditUiState(
    val loaded: Boolean = false,
    val form: ProductForm = ProductForm(),
    val taxRates: List<TaxRateEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val currency: CurrencySpec = CurrencySpec("EUR", 2),
    val skuInUse: Boolean = false,
    val chargeTax: Boolean = true,
    val saving: Boolean = false,
) {
    /** The typed price in minor units; null on overflow, nonpositive values or too many decimals for the currency. */
    val priceMinor: Long?
        get() =
            form.price
                .trim()
                .replace(',', '.')
                .toBigDecimalOrNull()
                ?.takeIf { it.signum() > 0 && it.scale() <= currency.fractionDigits }
                ?.let { runCatching { currency.toMinor(it) }.getOrNull() }

    /** Whether the product can be saved: a name, a valid price, a tax rate and a SKU no other product has. */
    val valid: Boolean
        get() = form.name.isNotBlank() && priceMinor != null && form.taxRateId != null && !skuInUse
}

/**
 * Adding or editing one product.
 *
 * @param productId The product to edit, or null to add one.
 * @param initialSku A scanned barcode to start a new product with, or null.
 * @param catalog Where the product is read and saved.
 * @param settingsState The current settings, read once for the currency, default tax rate and "Charge tax".
 * @param currency The currency prices are entered in with the given settings.
 */
class ProductEditViewModel(
    productId: Long?,
    initialSku: String?,
    private val catalog: CatalogRepository,
    settingsState: StateFlow<AppSettings>,
    currency: (AppSettings) -> CurrencySpec,
) : ViewModel() {
    private val _state = MutableStateFlow(ProductEditUiState())

    /** The editor state. */
    val state: StateFlow<ProductEditUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val appSettings = settingsState.value
            val spec = currency(appSettings)
            val taxRates = catalog.taxRates.first()
            val categories = catalog.categories.first()
            val product = productId?.let { catalog.product(it) }
            val defaultTaxRateId = appSettings.payment.defaultTaxRate(taxRates)?.id
            val form =
                product?.let {
                    ProductForm(
                        id = it.id,
                        name = it.name,
                        price = spec.toMajor(it.priceMinor).toPlainString(),
                        taxRateId = it.taxRateId,
                        categoryId = it.categoryId,
                        sku = it.sku.orEmpty(),
                        sortOrder = it.sortOrder,
                        preAuthorisation = it.kind == SaleKind.PRE_AUTHORISATION,
                    )
                } ?: ProductForm(taxRateId = defaultTaxRateId, sku = initialSku.orEmpty())
            _state.value = ProductEditUiState(true, form, taxRates, categories, spec, chargeTax = appSettings.payment.chargeTax)
            checkSku(form.sku)
        }
    }

    /** Applies [transform] to the form and, when the SKU changed, checks whether another product has it. */
    fun update(transform: (ProductForm) -> ProductForm) {
        val before = _state.value.form.sku
        _state.update { it.copy(form = transform(it.form)) }
        val sku = _state.value.form.sku
        if (sku != before) viewModelScope.launch { checkSku(sku) }
    }

    private suspend fun checkSku(sku: String) {
        val existing = sku.trim().takeIf { it.isNotEmpty() }?.let { catalog.productBySku(it) }
        _state.update { it.copy(skuInUse = existing != null && existing.id != it.form.id) }
    }

    /**
     * Saves the product when the form is [ProductEditUiState.valid] and nothing is being saved, then calls [onSaved]
     * (unless the editor was closed meanwhile; the save still finishes). Does nothing otherwise.
     */
    fun save(onSaved: () -> Unit) {
        val current = _state.value
        val price = current.priceMinor ?: return
        val taxRateId = current.form.taxRateId ?: return
        if (!current.valid || current.saving) return
        val product =
            ProductEntity(
                id = current.form.id,
                name = current.form.name.trim(),
                priceMinor = price,
                taxRateId = taxRateId,
                categoryId = current.form.categoryId,
                sku =
                    current.form.sku
                        .trim()
                        .ifEmpty { null },
                sortOrder = current.form.sortOrder,
                kind = if (current.form.preAuthorisation) SaleKind.PRE_AUTHORISATION else SaleKind.SALE,
            )
        _state.update { it.copy(saving = true) }
        launchWrite({ catalog.saveProduct(product) }) { _ ->
            _state.update { it.copy(saving = false) }
            onSaved()
        }
    }

    /**
     * Deletes the product being edited, then calls [onDeleted] (unless the editor was closed meanwhile); does nothing
     * for a new product or while saving.
     */
    fun delete(onDeleted: () -> Unit) {
        val current = _state.value
        val id = current.form.id
        if (id == 0L || current.saving) return
        _state.update { it.copy(saving = true) }
        launchWrite({ catalog.product(id)?.let { catalog.deleteProduct(it) } }) { _ ->
            _state.update { it.copy(saving = false) }
            onDeleted()
        }
    }
}
