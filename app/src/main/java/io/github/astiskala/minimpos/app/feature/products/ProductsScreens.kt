package io.github.astiskala.minimpos.app.feature.products

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.db.CategoryEntity
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.feature.sale.TaxRatePicker
import io.github.astiskala.minimpos.app.scan.ScanMode
import io.github.astiskala.minimpos.app.scan.ScannerDialog
import io.github.astiskala.minimpos.app.ui.components.BottomActions
import io.github.astiskala.minimpos.app.ui.components.ConfirmDialog
import io.github.astiskala.minimpos.app.ui.components.EmptyState
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.components.MiniScaffold
import io.github.astiskala.minimpos.app.ui.components.PrimaryButton
import io.github.astiskala.minimpos.app.ui.components.SwitchRow
import io.github.astiskala.minimpos.app.ui.components.currentLocale
import io.github.astiskala.minimpos.app.ui.components.rememberMoneyFormatter
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.core.cart.AppliedTax
import io.github.astiskala.minimpos.core.money.MoneyFormatter
import io.github.astiskala.minimpos.core.receipt.label
import java.util.Locale

/** The product and category lists, in two tabs, with adding, editing and deleting and the catalogue transfer menu. */
@Composable
fun ProductsScreen(
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: ProductsViewModel = productsViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    val money = rememberMoneyFormatter(state.currency)

    MiniScaffold(
        title = stringResource(R.string.products_title),
        onBack = navigator::back,
        modifier = modifier,
        actions = { TransferMenu(onOpen = navigator::push) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf(R.string.products_tab_products, R.string.products_tab_categories).forEachIndexed {
                        index,
                        label,
                        ->
                        Tab(selected = tab == index, onClick = {
                            tab = index
                        }, text = { Text(stringResource(label)) }, modifier = Modifier.testTag("tab_$index"))
                    }
                }
                when (tab) {
                    0 -> ProductList(state, money) { navigator.push(Route.ProductEdit(it.id)) }
                    else -> CategoryList(state) { editCategory = it }
                }
            }
            FloatingActionButton(
                onClick = {
                    when (tab) {
                        0 -> navigator.push(Route.ProductEdit())
                        else -> editCategory = CategoryEntity(name = "", sortOrder = state.categories.size)
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("addFab"),
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.action_add))
            }
        }
    }
    editCategory?.let { category ->
        CategoryDialog(
            category = category,
            onSave = {
                vm.saveCategory(it)
                editCategory = null
            },
            onDelete = {
                vm.deleteCategory(category)
                editCategory = null
            },
            onDismiss = { editCategory = null },
        )
    }
}

@Composable
private fun productsViewModel(): ProductsViewModel {
    val container = LocalAppContainer.current
    return viewModel { ProductsViewModel(container.catalog, container.settingsState, container::currency) }
}

/** The overflow menu with sharing to and setting up from another terminal. */
@Composable
private fun TransferMenu(onOpen: (Route) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }, modifier = Modifier.testTag("productsMenu")) {
            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transfer_export)) },
                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                onClick = {
                    menu = false
                    onOpen(Route.TransferExport)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.transfer_import)) },
                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                onClick = {
                    menu = false
                    onOpen(Route.TransferImport)
                },
            )
        }
    }
}

/**
 * The products tab: each product's name, whether it is a pre-authorisation product, tax rate (when tax is charged),
 * category, SKU and price.
 */
@Composable
private fun ProductList(
    state: ProductsUiState,
    money: MoneyFormatter,
    onOpen: (ProductEntity) -> Unit,
) {
    if (state.products.isEmpty()) {
        EmptyState(
            Icons.Default.Inventory2,
            stringResource(R.string.products_empty),
            stringResource(R.string.products_empty_hint),
        )
        return
    }
    val preAuth = stringResource(R.string.products_kind_pre_auth)
    // The bottom padding keeps the last row clear of the add button.
    LazyColumn(Modifier.fillMaxSize().testTag("productList"), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(state.products, key = { it.id }) { product ->
            val tax = state.taxRates.firstOrNull { it.id == product.taxRateId }
            val category = state.categories.firstOrNull { it.id == product.categoryId }
            ListRow(
                title = product.name,
                subtitle =
                    listOfNotNull(
                        preAuth.takeIf { product.kind == SaleKind.PRE_AUTHORISATION },
                        tax?.takeIf { state.chargeTax }?.let { AppliedTax(it.name, it.rateMilliPercent).label() },
                        category?.name,
                        product.sku,
                    ).joinToString(" · "),
                trailing = money.format(product.priceMinor),
                onClick = { onOpen(product) },
            )
        }
    }
}

/** The categories tab: each category with its number of products. */
@Composable
private fun CategoryList(
    state: ProductsUiState,
    onEdit: (CategoryEntity) -> Unit,
) {
    if (state.categories.isEmpty()) {
        EmptyState(
            Icons.Default.Category,
            stringResource(R.string.products_no_categories),
            stringResource(R.string.products_no_categories_hint),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        items(state.categories, key = { it.id }) { category ->
            val count = state.products.count { it.categoryId == category.id }
            ListRow(category.name, stringResource(R.string.products_count, count), null) { onEdit(category) }
        }
    }
}

@Composable
private fun ListRow(
    title: String,
    subtitle: String,
    trailing: String?,
    onClick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = LocalDimens.current.rowPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun CategoryDialog(
    category: CategoryEntity,
    onSave: (CategoryEntity) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(category.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (category.id == 0L) R.string.products_add_category else R.string.products_edit_category)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.products_name)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth().testTag("categoryName"),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(category.copy(name = name))
            }, enabled = name.isNotBlank(), modifier = Modifier.testTag("saveCategory")) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            Row {
                if (category.id != 0L) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/**
 * Adding ([productId] null, optionally with a scanned [sku]) or editing a product: name, price, tax rate,
 * category, SKU (which can be scanned) and whether it is a pre-authorisation product.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductEditScreen(
    productId: Long?,
    sku: String?,
    navigator: Navigator,
    modifier: Modifier = Modifier,
    vm: ProductEditViewModel = productEditViewModel(productId, sku),
) {
    val container = LocalAppContainer.current
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf(false) }
    val form = state.form
    val dimens = LocalDimens.current

    MiniScaffold(
        title = stringResource(if (productId == null) R.string.products_add else R.string.products_edit),
        onBack = navigator::back,
        modifier = modifier,
        actions = {
            if (productId != null) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.action_delete))
                }
            }
        },
        // Back (in the app bar) cancels, so Save is the only button and always in reach.
        bottomBar = {
            if (state.loaded) {
                BottomActions {
                    PrimaryButton(
                        stringResource(R.string.action_save),
                        { vm.save(navigator::back) },
                        enabled = state.valid && !state.saving,
                        modifier = Modifier.testTag("saveProduct"),
                    )
                }
            }
        },
    ) { padding ->
        if (!state.loaded) return@MiniScaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(dimens.spacing)) {
                ProductFields(state, onUpdate = {
                    container.userActivity()
                    vm.update(it)
                }, onScan = { scan = true })
            }
        }
    }
    if (scan) {
        ScannerDialog(ScanMode.BARCODE, onDismiss = { scan = false }) { code ->
            scan = false
            vm.update { it.copy(sku = code) }
        }
    }
    if (confirmDelete) {
        DeleteProductDialog(name = form.name, onDelete = { vm.delete(navigator::back) }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun DeleteProductDialog(
    name: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) = ConfirmDialog(
    title = stringResource(R.string.products_delete_title),
    message = stringResource(R.string.products_delete_message, name),
    confirmLabel = stringResource(R.string.action_delete),
    destructive = true,
    onConfirm = {
        onDismiss()
        onDelete()
    },
    onDismiss = onDismiss,
)

/** The product form's fields; [onUpdate] applies a change to the form. */
@Composable
private fun ColumnScope.ProductFields(
    state: ProductEditUiState,
    onUpdate: ((ProductForm) -> ProductForm) -> Unit,
    onScan: () -> Unit,
) {
    val form = state.form
    OutlinedTextField(
        value = form.name,
        onValueChange = { value -> onUpdate { it.copy(name = value.take(60)) } },
        label = { Text(stringResource(R.string.products_name)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().testTag("productName"),
    )
    OutlinedTextField(
        value = form.price,
        onValueChange = { value -> onUpdate { it.copy(price = value.take(12)) } },
        label = { Text(stringResource(R.string.products_price, state.currency.code)) },
        isError = form.price.isNotEmpty() && state.priceMinor == null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth().testTag("productPrice"),
    )
    // Items without tax get a 0% rate (such as No tax) from the same list.
    if (state.chargeTax) {
        TaxRatePicker(
            state.taxRates,
            state.taxRates.firstOrNull { it.id == form.taxRateId },
            onSelect = { rate -> onUpdate { it.copy(taxRateId = rate.id) } },
        )
    }
    if (state.taxRates.isEmpty()) {
        Text(
            stringResource(R.string.products_no_tax_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    CategoryPicker(state.categories, form.categoryId) { id -> onUpdate { it.copy(categoryId = id) } }
    OutlinedTextField(
        value = form.sku,
        onValueChange = { value -> onUpdate { it.copy(sku = value.trim().take(40)) } },
        label = { Text(stringResource(R.string.products_sku)) },
        isError = state.skuInUse,
        supportingText = { if (state.skuInUse) Text(stringResource(R.string.products_sku_in_use)) },
        trailingIcon = {
            IconButton(onClick = onScan) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.sale_scan_barcode))
            }
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("productSku"),
    )
    PreAuthorisationSwitch(form.preAuthorisation) { checked -> onUpdate { it.copy(preAuthorisation = checked) } }
}

/** Whether the product is taken as a pre-authorisation ([checked]) rather than sold, with what that means. */
@Composable
private fun PreAuthorisationSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SwitchRow(
        title = stringResource(R.string.products_pre_auth),
        checked = checked,
        onChange = onChange,
        subtitle = stringResource(R.string.products_pre_auth_hint),
        modifier = Modifier.testTag("productPreAuth"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(
    categories: List<CategoryEntity>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(R.string.products_no_category)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = categories.firstOrNull { it.id == selectedId }?.name ?: none,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.products_category)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(none) }, onClick = {
                onSelect(null)
                expanded = false
            })
            categories.forEach { category ->
                DropdownMenuItem(text = { Text(category.name) }, onClick = {
                    onSelect(category.id)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun productEditViewModel(
    productId: Long?,
    sku: String?,
): ProductEditViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = "product-${productId ?: "new"}") {
        ProductEditViewModel(productId, sku, container.catalog, container.settingsState, container::currency)
    }
}
