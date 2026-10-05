package io.github.astiskala.minimpos.app.feature.sale

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.db.CategoryEntity
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.scan.ScanMode
import io.github.astiskala.minimpos.app.scan.ScannerDialog
import io.github.astiskala.minimpos.app.ui.components.ConfirmDialog
import io.github.astiskala.minimpos.app.ui.components.EmptyState
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.components.MiniScaffold
import io.github.astiskala.minimpos.app.ui.components.NumericKeypad
import io.github.astiskala.minimpos.app.ui.components.PrimaryButton
import io.github.astiskala.minimpos.app.ui.components.QuantityStepper
import io.github.astiskala.minimpos.app.ui.components.SearchControl
import io.github.astiskala.minimpos.app.ui.components.SearchField
import io.github.astiskala.minimpos.app.ui.components.SearchMode
import io.github.astiskala.minimpos.app.ui.components.SearchToggle
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton
import io.github.astiskala.minimpos.app.ui.components.currentLocale
import io.github.astiskala.minimpos.app.ui.components.rememberMoneyFormatter
import io.github.astiskala.minimpos.app.ui.components.rememberSearchControl
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.core.cart.AppliedTax
import io.github.astiskala.minimpos.core.cart.CartLine
import io.github.astiskala.minimpos.core.money.AmountEntry
import io.github.astiskala.minimpos.core.money.MoneyFormatter
import io.github.astiskala.minimpos.core.receipt.label
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Ringing up a payment of [kind]. For a sale: product tiles with search and category filters, custom amounts, barcode
 * scanning and the cart, with checkout as the main action. For a [SaleKind.singleItem] kind (the pre-authorise screen)
 * only its products, and no cart: tapping a product, adding a custom amount or scanning a barcode goes straight to
 * checkout with it (replacing any item chosen before).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SaleScreen(
    navigator: Navigator,
    kind: SaleKind,
    modifier: Modifier = Modifier,
    vm: SaleViewModel = saleViewModel(kind),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val money = rememberMoneyFormatter(state.currency)
    val checkout = Route.Checkout(kind)
    // Checking the screen on top keeps a second quick tap from opening checkout twice.
    val onChosen = { if (kind.singleItem && navigator.current == Route.ringUp(kind)) navigator.push(checkout) }
    var overlay by rememberSaveable { mutableStateOf<SaleOverlay?>(null) }
    var customOffered by rememberSaveable { mutableStateOf(false) }
    val search = rememberProductSearch(state.products.size, state.query, vm::setQuery)
    val snackbar = remember { SnackbarHostState() }
    val addScannedSku = rememberSkuHandler(snackbar, vm::addBySku, onAdd = onChosen)

    // With no products of this kind to choose from, offer the keypad once per visit, not again after cancelling or
    // returning from checkout. A pre-authorisation replaces its item, so an unfinished amount must not block the offer.
    LaunchedEffect(state.loaded) {
        if (state.loaded && !customOffered) {
            customOffered = true
            if (state.products.isEmpty() && (kind.singleItem || state.cart.lines.isEmpty())) overlay = SaleOverlay.CUSTOM_ITEM
        }
    }

    MiniScaffold(
        title = stringResource(if (kind == SaleKind.PRE_AUTHORISATION) R.string.pre_auth_title else R.string.sale_title),
        onBack = navigator::back,
        modifier = modifier,
        snackbarHostState = snackbar,
        actions = {
            SaleBarActions(
                canScan = state.hasSkus,
                canClear = state.cart.lines.isNotEmpty() && !kind.singleItem,
                onScan = { overlay = SaleOverlay.SCANNER },
                onClear = { overlay = SaleOverlay.CLEAR_CART },
                search = search.mode.takeIf { search.onRequest },
                onSearch = search.toggle,
            )
        },
    ) { padding ->
        SaleLayout(
            state = state,
            money = money,
            search = search.mode,
            onQuery = vm::setQuery,
            onCategory = vm::selectCategory,
            onAdd = { if (vm.add(it)) onChosen() },
            onCustom = { overlay = SaleOverlay.CUSTOM_ITEM },
            onQuantity = vm::setQuantity,
            onRemove = vm::remove,
            onCart = { overlay = SaleOverlay.CART },
            onCheckout = { navigator.push(checkout) },
            modifier = Modifier.padding(padding),
        )
    }
    SaleOverlayContent(
        overlay = overlay,
        state = state,
        money = money,
        onQuantity = vm::setQuantity,
        onRemove = vm::remove,
        onAddCustom = { name, amount, taxRateId -> if (vm.addCustom(name, amount, taxRateId)) onChosen() },
        onSkuScan = addScannedSku,
        onClear = vm::clear,
        onCheckout = { navigator.push(checkout) },
        onClose = { overlay = null },
    )
}

/**
 * The product search for [productCount] products: shown once there are enough products to need it, but where height is
 * short only once it is asked for, so it takes no room from the tiles.
 */
@Composable
private fun rememberProductSearch(
    productCount: Int,
    query: String,
    onQuery: (String) -> Unit,
): SearchControl = rememberSearchControl(productCount > SEARCH_THRESHOLD, LocalDimens.current.compact, query, onQuery)

/** What the sale screen shows over the products; only one at a time. */
private enum class SaleOverlay { CART, CUSTOM_ITEM, SCANNER, CLEAR_CART }

/** The open [overlay], if any. Each action that ends it also calls [onClose]. */
@Composable
private fun SaleOverlayContent(
    overlay: SaleOverlay?,
    state: SaleUiState,
    money: MoneyFormatter,
    onQuantity: (key: String, quantity: Int) -> Unit,
    onRemove: (key: String) -> Unit,
    onAddCustom: (name: String, amountMinor: Long, taxRateId: Long) -> Unit,
    onSkuScan: (String) -> Unit,
    onClear: () -> Unit,
    onCheckout: () -> Unit,
    onClose: () -> Unit,
) {
    when (overlay) {
        SaleOverlay.CART -> {
            CartSheet(state, money, onQuantity, onRemove, onCharge = {
                onClose()
                onCheckout()
            }, onDismiss = onClose)
        }

        SaleOverlay.CUSTOM_ITEM -> {
            CustomItemDialog(
                taxRates = state.taxRates,
                defaultTaxRate = state.defaultTaxRate,
                chargeTax = state.chargeTax,
                money = money,
                onAdd = { name, amount, taxId ->
                    onAddCustom(name, amount, taxId)
                    onClose()
                },
                onDismiss = onClose,
            )
        }

        SaleOverlay.SCANNER -> {
            ScannerDialog(mode = ScanMode.BARCODE, onDismiss = onClose) { code ->
                onClose()
                onSkuScan(code)
            }
        }

        SaleOverlay.CLEAR_CART -> {
            ConfirmDialog(
                title = stringResource(R.string.sale_clear_title),
                message = stringResource(R.string.sale_clear_message),
                confirmLabel = stringResource(R.string.sale_clear),
                destructive = true,
                onConfirm = {
                    onClear()
                    onClose()
                },
                onDismiss = onClose,
            )
        }

        null -> {}
    }
}

/**
 * Adds the product with a scanned SKU, calls [onAdd] when there was one, and says in [snackbar] whether one was
 * found. The work runs in the calling screen's scope, so it finishes even though the scanner closes straight away.
 */
@Composable
private fun rememberSkuHandler(
    snackbar: SnackbarHostState,
    addBySku: suspend (String) -> String?,
    onAdd: () -> Unit,
): (String) -> Unit {
    val scope = rememberCoroutineScope()
    val notFound = stringResource(R.string.sale_sku_not_found)
    val added = stringResource(R.string.sale_sku_added)
    val locale = currentLocale()
    return { code ->
        scope.launch {
            val name = addBySku(code)
            if (name != null) onAdd()
            snackbar.showSnackbar(if (name != null) added.format(locale, name) else notFound.format(locale, code))
        }
    }
}

/**
 * Opening or closing the search with [onSearch] ([search] is null unless the field is only shown on request), scanning
 * a barcode (when products have SKUs) and clearing the cart (when it has items).
 */
@Composable
private fun RowScope.SaleBarActions(
    canScan: Boolean,
    canClear: Boolean,
    search: SearchMode?,
    onScan: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
) {
    if (search != null) SearchToggle(search, stringResource(R.string.sale_search), onSearch)
    if (canScan) {
        IconButton(onClick = onScan, modifier = Modifier.testTag("scanSku")) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.sale_scan_barcode))
        }
    }
    if (canClear) {
        IconButton(onClick = onClear, modifier = Modifier.testTag("clearCart")) {
            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.sale_clear))
        }
    }
}

/**
 * The product browser with, on narrow screens, a checkout bar that opens the cart sheet; screens at least 720 dp wide
 * show the cart beside the products instead. A pre-authorisation has neither: choosing its item opens checkout.
 */
@Composable
private fun SaleLayout(
    state: SaleUiState,
    money: MoneyFormatter,
    search: SearchMode,
    onQuery: (String) -> Unit,
    onCategory: (Long?) -> Unit,
    onAdd: (ProductEntity) -> Unit,
    onCustom: () -> Unit,
    onQuantity: (key: String, quantity: Int) -> Unit,
    onRemove: (key: String) -> Unit,
    onCart: () -> Unit,
    onCheckout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val cart = !state.kind.singleItem
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ProductBrowser(
                    state = state,
                    money = money,
                    search = search,
                    onQuery = onQuery,
                    onCategory = onCategory,
                    onAdd = onAdd,
                    onCustom = onCustom,
                    modifier = Modifier.weight(1f),
                )
                if (cart && !wide) {
                    CheckoutBar(
                        itemCount = state.totals.itemCount,
                        total = money.format(state.totals.amounts.gross),
                        onCart = onCart,
                        onCharge = onCheckout,
                    )
                }
            }
            if (cart && wide) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.width(340.dp).fillMaxHeight()) {
                    Column {
                        CartList(state.cart.lines, money, onQuantity, onRemove, Modifier.weight(1f))
                        CheckoutBar(state.totals.itemCount, money.format(state.totals.amounts.gross), null, onCheckout)
                    }
                }
            }
        }
    }
}

/** The cart as a bottom sheet on narrow screens, with the charge button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CartSheet(
    state: SaleUiState,
    money: MoneyFormatter,
    onQuantity: (key: String, quantity: Int) -> Unit,
    onRemove: (key: String) -> Unit,
    onCharge: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dimens = LocalDimens.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = dimens.screenPadding)) {
            Text(
                stringResource(R.string.sale_cart),
                style = dimens.titleStyle,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            CartList(state.cart.lines, money, onQuantity, onRemove, Modifier.heightIn(max = 420.dp))
            Box(Modifier.padding(horizontal = dimens.screenPadding)) {
                PrimaryButton(
                    text = stringResource(R.string.sale_charge, money.format(state.totals.amounts.gross)),
                    enabled = !state.totals.isEmpty,
                    onClick = onCharge,
                )
            }
        }
    }
}

@Composable
private fun saleViewModel(kind: SaleKind): SaleViewModel {
    val container = LocalAppContainer.current
    return viewModel(key = kind.name) {
        SaleViewModel(container.catalog, container.session(kind), container.settingsState, container::currency)
    }
}

@Composable
private fun ProductBrowser(
    state: SaleUiState,
    money: MoneyFormatter,
    search: SearchMode,
    onQuery: (String) -> Unit,
    onCategory: (Long?) -> Unit,
    onAdd: (ProductEntity) -> Unit,
    onCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalDimens.current
    val gap = dimens.spacing
    val tileHeight = dimens.tileHeight
    Column(modifier) {
        if (search != SearchMode.HIDDEN) {
            SearchField(
                state.query,
                onQuery,
                stringResource(R.string.sale_search),
                focus = search == SearchMode.REQUESTED,
                modifier = Modifier.fillMaxWidth().padding(start = gap, end = gap, top = gap),
            )
        }
        if (state.categories.isNotEmpty()) {
            CategoryFilters(state.categories, state.selectedCategory, onCategory, gap)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (dimens.compact) 96.dp else 118.dp),
            contentPadding = PaddingValues(gap),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize().testTag("productGrid"),
        ) {
            item(key = "custom") { CustomItemTile(tileHeight, onCustom) }
            items(state.visibleProducts, key = { it.id }) { product ->
                // A pre-authorisation's item is never shown as in a cart: choosing it opens checkout.
                val inCart = if (state.kind.singleItem) 0 else state.quantityInCart(product.id)
                ProductTile(product, money.format(product.priceMinor), inCart, tileHeight) { onAdd(product) }
            }
            if (state.loaded && state.products.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        stringResource(if (state.preAuthorisation) R.string.pre_auth_no_products else R.string.sale_no_products),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

/** "All" and a chip per category; [selected] is null for all products. */
@Composable
private fun CategoryFilters(
    categories: List<CategoryEntity>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    gap: Dp,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = gap),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = if (LocalDimens.current.compact) 4.dp else 8.dp),
    ) {
        item {
            FilterChip(selected = selected == null, onClick = {
                onSelect(null)
            }, label = { Text(stringResource(R.string.sale_all)) })
        }
        items(categories, key = { it.id }) { category ->
            FilterChip(selected = selected == category.id, onClick = {
                onSelect(category.id)
            }, label = { Text(category.name) })
        }
    }
}

/** The first tile, which opens the keypad for an item with a typed amount. */
@Composable
private fun CustomItemTile(
    minHeight: Dp,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        modifier = Modifier.heightIn(min = minHeight).testTag("customItem"),
    ) {
        Column(
            Modifier.padding(LocalDimens.current.spacing),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.sale_custom_item),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProductTile(
    product: ProductEntity,
    price: String,
    quantity: Int,
    minHeight: Dp,
    onClick: () -> Unit,
) {
    val gap = LocalDimens.current.spacing
    val inCart = quantity > 0
    // Products in the cart get a green outline and their count beside the price, where it never covers the name.
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = if (inCart) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (inCart) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().heightIn(min = minHeight).testTag("product_${product.id}"),
    ) {
        Column(Modifier.padding(gap), verticalArrangement = Arrangement.SpaceBetween) {
            Text(product.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = gap / 2), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    price,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (inCart) {
                    Badge(containerColor = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("inCart_${product.id}")) {
                        Text(quantity.toString(), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckoutBar(
    itemCount: Int,
    total: String,
    onCart: (() -> Unit)?,
    onCharge: () -> Unit,
) {
    val dimens = LocalDimens.current
    // The same frame as the other screens' BottomActions (the scaffold has already made room for the navigation bar).
    Surface(shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = dimens.screenPadding, vertical = dimens.spacing),
            horizontalArrangement = Arrangement.spacedBy(dimens.spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onCart != null) {
                OutlinedButton(
                    onClick = onCart,
                    enabled = itemCount > 0,
                    shape = MaterialTheme.shapes.medium,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier.heightIn(min = dimens.buttonHeight).testTag("cartButton"),
                ) {
                    Icon(Icons.Default.ShoppingCart, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(pluralStringResource(R.plurals.sale_items, itemCount, itemCount), style = MaterialTheme.typography.labelLarge)
                }
            }
            PrimaryButton(
                text = stringResource(R.string.sale_charge, total),
                onClick = onCharge,
                enabled = itemCount > 0,
                modifier = Modifier.weight(1f).testTag("charge"),
            )
        }
    }
}

@Composable
private fun CartList(
    lines: List<CartLine>,
    money: MoneyFormatter,
    onQuantity: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lines.isEmpty()) {
        EmptyState(
            Icons.Default.ShoppingCart,
            stringResource(R.string.sale_cart_empty),
            stringResource(R.string.sale_cart_empty_hint),
            modifier,
        )
        return
    }
    LazyColumn(modifier.testTag("cartList")) {
        items(lines, key = { it.key }) { line ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = LocalDimens.current.rowPadding),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(line.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${money.format(line.unitPrice)} · ${line.tax.label()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QuantityStepper(line.quantity, onChange = { onQuantity(line.key, it) })
                IconButton(onClick = { onRemove(line.key) }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.action_remove))
                }
            }
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CustomItemDialog(
    taxRates: List<TaxRateEntity>,
    defaultTaxRate: TaxRateEntity?,
    chargeTax: Boolean,
    money: MoneyFormatter,
    onAdd: (name: String, amountMinor: Long, taxRateId: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var entry by remember { mutableStateOf(AmountEntry()) }
    var name by remember { mutableStateOf("") }
    // Items without tax get a 0% rate from the same list; with tax switched off the rate is kept but not shown.
    var taxRate by remember { mutableStateOf(defaultTaxRate) }
    val defaultName = stringResource(R.string.sale_custom_default_name)
    val dimens = LocalDimens.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // On small screens the keypad takes the whole screen, its keys sharing whatever height is left, so nothing scrolls.
        Surface(
            shape = if (dimens.compact) RectangleShape else MaterialTheme.shapes.large,
            modifier = if (dimens.compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Column(
                (
                    if (dimens.compact) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier.verticalScroll(
                            rememberScrollState(),
                        )
                    }
                ).padding(dimens.screenPadding),
                verticalArrangement = Arrangement.spacedBy(dimens.spacing),
            ) {
                if (!dimens.compact) Text(stringResource(R.string.sale_custom_item), style = MaterialTheme.typography.titleLarge)
                Text(
                    money.format(entry.minor),
                    style = dimens.amountStyle,
                    modifier = Modifier.fillMaxWidth().testTag("customAmount"),
                    textAlign = TextAlign.Center,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.sale_custom_description)) },
                    placeholder = { Text(defaultName) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (chargeTax) TaxRatePicker(taxRates, taxRate, onSelect = { taxRate = it })
                NumericKeypad(
                    onDigit = { entry = entry.append(it) },
                    onDoubleZero = { entry = entry.appendDoubleZero() },
                    onBackspace = { entry = entry.backspace() },
                    modifier = if (dimens.compact) Modifier.weight(1f) else Modifier,
                    fill = dimens.compact,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(dimens.spacing)) {
                    SecondaryButton(stringResource(R.string.action_cancel), onDismiss, Modifier.weight(1f))
                    PrimaryButton(
                        text = stringResource(R.string.action_add),
                        enabled = entry.minor > 0 && taxRate != null,
                        onClick = { taxRate?.let { onAdd(name.trim().ifEmpty { defaultName }, entry.minor, it.id) } },
                        modifier = Modifier.weight(1f).testTag("addCustom"),
                    )
                }
            }
        }
    }
}

/** A drop-down to choose one of [taxRates], showing each as its name and rate. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxRatePicker(
    taxRates: List<TaxRateEntity>,
    selected: TaxRateEntity?,
    onSelect: (TaxRateEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = selected?.let { AppliedTax(it.name, it.rateMilliPercent).label() }.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.tax_rate)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag("taxRatePicker"),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            taxRates.forEach { rate ->
                DropdownMenuItem(
                    text = { Text(AppliedTax(rate.name, rate.rateMilliPercent).label()) },
                    onClick = {
                        onSelect(rate)
                        expanded = false
                    },
                )
            }
        }
    }
}

private const val SEARCH_THRESHOLD = 8
