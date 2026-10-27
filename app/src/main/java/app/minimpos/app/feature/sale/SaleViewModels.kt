package app.minimpos.app.feature.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.CategoryEntity
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.CatalogRepository
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.TransactionActions
import app.minimpos.app.feature.TransactionActionsState
import app.minimpos.app.feature.runAction
import app.minimpos.app.history.PaymentAction
import app.minimpos.app.history.StoredPayment
import app.minimpos.app.history.StoredPayments
import app.minimpos.app.history.awaitsLinkPayment
import app.minimpos.app.history.decline
import app.minimpos.app.history.simulatedLink
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.CheckoutForm
import app.minimpos.app.payment.FinancialOperations
import app.minimpos.app.payment.LinkUpdate
import app.minimpos.app.payment.PaymentLinks
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.SaleSession
import app.minimpos.app.payment.SavedCardCharges
import app.minimpos.app.payment.SavedCardLookupState
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.payment.WalletPayments
import app.minimpos.app.payment.savedCardStart
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.app.terminal.WalletDiscovery
import app.minimpos.app.terminal.usesPaymentsApp
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartTotals
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.client.RetryAdvice
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Cached product filtering, reusable when only the cart or price display changes.
 *
 * @param products Products in catalogue order, retained by reference to validate reuse without scanning.
 * @param query Search text.
 * @param category Selected category ID; null includes all categories.
 */
class SaleBrowse(
    private val products: List<ProductEntity>,
    private val query: String,
    private val category: Long?,
) {
    /** Products matching the search and category. */
    val visible: List<ProductEntity> =
        query.trim().let { search ->
            products.filter { product ->
                (category == null || product.categoryId == category) &&
                    (search.isEmpty() || product.name.contains(search, ignoreCase = true) || product.sku?.contains(search) == true)
            }
        }

    /** Whether the catalogue offers barcode scanning. */
    val hasSkus: Boolean = products.any { !it.sku.isNullOrBlank() }

    /** Whether this cached reading still describes these exact inputs. */
    fun matches(
        products: List<ProductEntity>,
        query: String,
        category: Long?,
    ): Boolean = this.products === products && this.query == query && this.category == category
}

/**
 * Quantities indexed once for an immutable cart, shared across search and display changes.
 * @param cart Exact cart snapshot represented by this index.
 */
class SaleCartQuantities(
    private val cart: Cart,
) {
    private val counts =
        buildMap<Long?, Int> {
            cart.lines.forEach { line -> put(line.productId, (get(line.productId) ?: 0) + line.quantity) }
        }

    /** Whether [cart] is the same immutable snapshot. */
    fun matches(cart: Cart): Boolean = this.cart === cart

    /** Units of [productId] across all cart lines, or zero when absent. */
    fun quantity(productId: Long): Int = counts[productId] ?: 0
}

/**
 * What the sale screen shows: the product tiles and the cart.
 *
 * @property loaded False until the catalogue and settings have been read, so an empty catalogue is not shown too early.
 * @property products The products of [kind], in display order.
 * @property categories The categories with products of [kind] (for a sale, also those without any products yet), in
 *   display order.
 * @property taxRates All tax rates, for custom items.
 * @property query The search text; it matches product names (ignoring case) and SKUs.
 * @property selectedCategory The category filter, or null for all products.
 * @property cart The cart of the current sale.
 * @property totals The cart priced with the current tax settings.
 * @property currency The currency prices are shown in.
 * @property defaultTaxRate The rate custom items start with
 *   ([app.minimpos.app.data.settings.PaymentSettings.defaultTaxRate]); null only before any rate exists.
 * @property chargeTax Settings › Tax › Charge tax.
 * @property kind A sale, or a pre-authorisation, whose cart holds a single item.
 * @param browse Cached product filtering, computed from public inputs by default.
 * @param quantities Cached quantities for this exact cart; replaced when a copied state changes its cart.
 */
data class SaleUiState(
    val loaded: Boolean = false,
    val products: List<ProductEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val taxRates: List<TaxRateEntity> = emptyList(),
    val query: String = "",
    val selectedCategory: Long? = null,
    val cart: Cart = Cart(),
    val totals: CartTotals = Cart().totals(TaxMode.INCLUSIVE),
    val currency: CurrencySpec = PaymentSettings.FALLBACK_CURRENCY_SPEC,
    val defaultTaxRate: TaxRateEntity? = null,
    val chargeTax: Boolean = true,
    val kind: SaleKind = SaleKind.SALE,
    private val browse: SaleBrowse = SaleBrowse(products, query, selectedCategory),
    private val quantities: SaleCartQuantities = SaleCartQuantities(cart),
) {
    /** Whether this is a pre-authorisation, which holds one item. */
    val preAuthorisation: Boolean get() = kind == SaleKind.PRE_AUTHORISATION

    // The readings below are worked out once per state, not on every recomposition that reads them.

    private val productFilter =
        browse.takeIf { it.matches(products, query, selectedCategory) }
            ?: SaleBrowse(products, query, selectedCategory)

    /** The products matching [selectedCategory] and [query]. */
    val visibleProducts: List<ProductEntity> = productFilter.visible

    /** Whether any product has a SKU, which is when barcode scanning is offered. */
    val hasSkus: Boolean = productFilter.hasSkus

    /** Units in the cart by product ID (null for custom items), over all of each product's lines. */
    private val cartQuantities = quantities.takeIf { it.matches(cart) } ?: SaleCartQuantities(cart)

    /** How many units of product [productId] are in the cart, over all its lines; shown as a badge on its tile. */
    fun quantityInCart(productId: Long): Int = cartQuantities.quantity(productId)
}

/**
 * The sale screen, or the pre-authorise screen: browsing the products of the [session]'s kind and building its cart
 * (for a pre-authorisation, a single item).
 *
 * @param catalog Where the products are read.
 * @param session The payment being rung up; its [SaleSession.kind] decides whose products are offered.
 * @param settings The current settings.
 * @param currency The currency prices are shown in with the given settings.
 * @param cpu Dispatcher for catalog filtering, indexing and cart totals.
 */
class SaleViewModel(
    private val catalog: CatalogRepository,
    private val session: SaleSession,
    settings: StateFlow<AppSettings>,
    currency: (AppSettings) -> CurrencySpec,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val kind = session.kind
    private val filters = MutableStateFlow("" to null as Long?)

    private val catalogue =
        combine(catalog.products, catalog.categories, catalog.taxRates) { products, categories, taxRates ->
            val offered = products.filter { it.kind == kind }
            // A category is shown when it has products of this kind; a sale also keeps categories without products yet.
            val offeredCategories = offered.mapTo(mutableSetOf()) { it.categoryId }
            val usedCategories = products.mapTo(mutableSetOf()) { it.categoryId }
            val shown =
                categories.filter { category ->
                    category.id in offeredCategories || (kind == SaleKind.SALE && category.id !in usedCategories)
                }
            Triple(offered, shown, taxRates)
        }

    private val browsing =
        combine(catalogue, filters) { (products, categories, taxRates), (query, category) ->
            SaleUiState(
                loaded = true,
                products = products,
                categories = categories,
                taxRates = taxRates,
                query = query,
                selectedCategory = category,
                kind = kind,
            )
        }
    private val prices = settings.map { it.payment.taxMode to it.payment.chargeTax }.distinctUntilChanged()
    private val pricedCart =
        combine(session.cart, prices) { cart, (mode, chargeTax) ->
            Triple(cart, cart.totals(mode, chargeTax), SaleCartQuantities(cart))
        }
    private val display =
        settings
            .map { currency(it) to it.payment }
            .distinctUntilChanged()

    /** The screen state; product filtering and cart totals each react only to their own inputs. */
    val state: StateFlow<SaleUiState> =
        combine(browsing, pricedCart, display) { browse, (cart, totals, quantities), (unit, payment) ->
            browse.copy(
                cart = cart,
                totals = totals,
                quantities = quantities,
                currency = unit,
                defaultTaxRate = payment.defaultTaxRate(browse.taxRates),
                chargeTax = payment.chargeTax,
            )
        }.flowOn(cpu).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaleUiState(kind = kind))

    /** Filters the tiles by [query]. */
    fun setQuery(query: String) = filters.update { query to it.second }

    /** Shows only the products of category [categoryId], or all of them for null. */
    fun selectCategory(categoryId: Long?) = filters.update { it.first to categoryId }

    /** Adds one unit of [product] and returns true; ignored (false) while its tax rate is not loaded. */
    fun add(product: ProductEntity): Boolean {
        val tax = state.value.taxRates.firstOrNull { it.id == product.taxRateId } ?: return false
        session.addProduct(product, tax)
        return true
    }

    /** Adds a custom item of [amountMinor] with the tax rate [taxRateId] and returns true; ignored (false) for an unknown rate. */
    fun addCustom(
        name: String,
        amountMinor: Long,
        taxRateId: Long,
    ): Boolean {
        val tax = state.value.taxRates.firstOrNull { it.id == taxRateId } ?: return false
        session.addCustom(name, amountMinor, tax)
        return true
    }

    /** Sets the quantity of cart line [key]; zero or less removes it. */
    fun setQuantity(
        key: String,
        quantity: Int,
    ) = session.setQuantity(key, quantity)

    /** Removes cart line [key]. */
    fun remove(key: String) = session.remove(key)

    /** Empties the cart and the checkout form. */
    fun clear() = session.clear()

    /** Adds the product of this screen's kind with this barcode/SKU; returns its name, or null when there is none. */
    suspend fun addBySku(sku: String): String? {
        val product = catalog.productBySku(sku, kind) ?: return null
        val tax = catalog.reader.taxRates().firstOrNull { it.id == product.taxRateId } ?: return null
        session.addProduct(product, tax)
        return product.name
    }
}

/**
 * The checkout screen: references, email, saving the card and tipping on the receipt, then starting the payment on the
 * terminal or creating a payment link instead. What the entries make of the payment is the session's [Checkout].
 *
 * @param session The cart (or pre-authorisation item) and the form; its [SaleSession.kind] is the kind of payment.
 * @param payments Runs the payment.
 * @param settings The current settings.
 * @param terminal Whether printing is offered, which tipping on the receipt needs, and whether payment links are.
 * @param currency The currency charged with the given settings.
 * @param links Creates payment links; null offers none.
 * @param clock Stamps generated merchant references.
 * @param zone The time zone of generated merchant references.
 * @param walletPayments Prepares scanned-wallet sales; null offers no wallet action.
 * @param discovery Memory-only, currency-filtered wallet availability; null offers none.
 * @param charges Looks up and charges the shopper's saved tokens; null offers no token charge.
 * @param lookupDelay How long the shopper reference must stay unchanged before its tokens are looked up.
 * @param cpu Dispatcher for checkout calculation shared with saved-card lookup.
 * @param managerConfigured Whether cashback is public or a Manager PIN is configured to approve it.
 */
class CheckoutViewModel(
    private val session: SaleSession,
    private val payments: TransactionLifecycle<PaymentStart>,
    settings: StateFlow<AppSettings>,
    private val terminal: StateFlow<TerminalState>,
    currency: (AppSettings) -> CurrencySpec,
    private val links: PaymentLinks? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val walletPayments: WalletPayments? = null,
    discovery: WalletDiscovery? = null,
    charges: SavedCardCharges? = null,
    lookupDelay: Duration = 700.milliseconds,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    managerConfigured: Flow<Boolean> = flowOf(false),
) : ViewModel() {
    private val checkout =
        session.checkout(
            settings = settings,
            terminal = terminal,
            wallets = discovery?.state ?: flowOf(WalletAvailability()),
            managerConfigured = managerConfigured,
            offersLinks = links != null,
            currency = currency,
        )

    /** The screen state, updated whenever the form, cart, settings, printer or payment link setup change. */
    val state: StateFlow<Checkout> = checkout.flowOn(cpu).stateIn(viewModelScope, SharingStarted.Eagerly, Checkout(kind = session.kind))

    /**
     * The tokens saved for the entered shopper reference that can be charged with the recurring model, looked up in the
     * background once the reference stops changing; empty while there is none, the lookup fails or runs, or the sale
     * cannot be charged to a token (a pre-authorisation, or no Checkout API). A lookup changes nothing.
     */
    private val cardLookup = charges?.lookup(viewModelScope, state, terminal, lookupDelay)

    /** Current lookup progress and failure, including an approval requirement for an explicit retry. */
    val tokenLookup: StateFlow<SavedCardLookupState> = cardLookup?.state ?: MutableStateFlow(SavedCardLookupState())

    /** Explicitly repeats the current lookup after Manager approval, without changing its shopper reference. */
    fun findTokens() {
        cardLookup?.find()
    }

    /** Current chargeable cards; freshness and recurring-model eligibility belong to the saved-card lookup. */
    val tokens: StateFlow<List<StoredCard>> =
        cardLookup
            ?.state
            ?.map { it.eligible }
            ?.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
            ?: MutableStateFlow(emptyList())

    /** Updates the form (kept in the [SaleSession] so it survives going back to the cart). */
    fun update(transform: (CheckoutForm) -> CheckoutForm) = session.updateForm(transform = transform)

    /** Starts the payment ([Checkout.paymentStart]); returns false when the form is not ready or a payment is running. */
    fun pay(): Boolean {
        val start = session.paymentStart(state.value, clock.instant(), zone()) ?: return false
        if (payments.start(start) != null) return true
        if (start.audioGuidance) {
            session.updateForm(start) { it.copy(audioGuidance = true, audioGuidanceConfirmedFor = start.audioGuidancePoiId) }
        }
        return false
    }

    /** Prepares the unchanged sale for wallet scanning; false when unavailable or already busy. */
    fun scanWallet(): Boolean = walletPayments?.begin(state.value) == true

    /**
     * Charges [card], one of [tokens], for the sale as rung up, with the shopper not present; Manager approval is asked
     * for first and checked again before sending. Returns false when the form is not ready, [card] is no longer offered,
     * or another charge is running.
     */
    fun chargeToken(card: StoredCard): Boolean {
        val lookup = cardLookup ?: return false
        if (card !in tokens.value) return false
        val start = session.savedCardStart(state.value, card, clock.instant(), zone(), fromCheckout = true) ?: return false
        return lookup.start(start) != null
    }

    /**
     * Starts creating a payment link instead ([Checkout.linkStart]) and returns its sale's ID; null when no link can
     * be made (the form is not ready, or links are not offered), while one is being created, or this checkout already
     * started one, such as on a second tap: each link can be paid on its own, so a second one would be a second charge.
     */
    fun sendLink(): String? {
        val links = links ?: return null
        val start = session.linkStart(state.value, clock.instant(), zone()) ?: return null
        return links.start(start)
    }
}

/**
 * What the payment link screen shows.
 *
 * @property payment The sale with what can be done with it now, or null until it has been stored.
 * @property transaction The unpaid (or, once paid, the paid) receipt, sharing, emailing and printing it.
 * @property check The latest check whether the link was paid (or, after an unknown outcome, whether it was created).
 * @property cancel The latest cancellation of the link.
 * @property simulation The latest explicit completion of a demo link.
 */
data class PaymentLinkUiState(
    val payment: StoredPayment? = null,
    val transaction: TransactionActionsState = TransactionActionsState(),
    val check: ActionState = ActionState(),
    val cancel: ActionState = ActionState(),
    val simulation: ActionState = ActionState(),
) {
    /** The sale, or null until stored. */
    val sale: SaleEntity? get() = payment?.sale

    /** The link's address while the shopper can still pay with it; else null. */
    val openLink: String? get() = sale?.takeIf { it.awaitsLinkPayment }?.paymentLinkUrl

    /** Whether Adyen is still being asked for the link (the sale is not stored yet, or still PENDING). */
    val creating: Boolean get() = sale == null || sale?.pending == true

    /** Whether this link was created without a real payment service, regardless of the current destination. */
    val simulated: Boolean get() = sale?.simulatedLink == true
}

/**
 * The payment link of a sale: the link as a QR code and an address to share, email or print (its unpaid receipt), and
 * checking with Adyen whether it was paid, every [pollInterval] while it is open and on request, or cancelling it. Once
 * paid, its receipt is delivered like any other.
 *
 * @param saleId The sale paid through the link.
 * @param payments Follows it, with what can be done with it.
 * @param receipts Prints, emails and shares its receipt.
 * @param links Asks Adyen about the link, and cancels it.
 * @param operations Asks again about an unknown link creation, as every receipt's status check does.
 * @param fresh Whether the link was just created at checkout, so its automatic delivery is due.
 * @param pollInterval How often an open link is checked while the screen is shown.
 */
class PaymentLinkViewModel(
    private val saleId: String,
    private val payments: StoredPayments,
    receipts: ReceiptDelivery,
    private val links: PaymentLinks,
    operations: FinancialOperations,
    fresh: Boolean,
    private val pollInterval: Duration = 10.seconds,
) : ViewModel() {
    private val local = MutableStateFlow(PaymentLinkUiState())
    private val polling = MutableStateFlow(false)

    /** Enables optional status polling only while the link screen is visible and started. */
    fun setPolling(active: Boolean) {
        polling.value = active
    }

    /** The receipt (unpaid, with the link, until it is paid), printing, emailing and sharing it. */
    val transaction = TransactionActions.forSale(viewModelScope, saleId, receipts, operations, fresh)

    /** The screen state, updated whenever the sale or an action changes. */
    val state: StateFlow<PaymentLinkUiState> =
        combine(payments.observe(saleId), local, transaction.state) { payment, ui, actions ->
            ui.copy(payment = payment, transaction = actions)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PaymentLinkUiState())

    init {
        viewModelScope.launch {
            // Checking is quiet: a failed poll leaves the last answer shown, and the next one tries again.
            combine(state, polling) { current, active -> current.openLink != null && active }
                .distinctUntilChanged()
                .collectLatest { active ->
                    var interval = pollInterval
                    while (active) {
                        interval = if (links.check(saleId) == LinkUpdate.StillOpen) pollInterval else (interval * 2).coerceAtMost(2.minutes)
                        delay(interval)
                    }
                }
        }
    }

    /** Asks Adyen now whether the link was paid (or, after an unknown outcome, creates it again). */
    fun check() =
        runAction(local, { it.check }, { copy(check = it) }) {
            val update = links.check(saleId)
            if (update == LinkUpdate.Settled) {
                val sale = payments.get(saleId)?.sale
                ActionState(
                    outcome = sale?.let { ActionOutcome.LinkChecked(it.status) } ?: ActionOutcome.Missing,
                    done = sale != null,
                    isError = sale == null,
                )
            } else {
                update.toState(stillOpen = true)
            }
        }

    /** Closes the manual check's popup without changing the stored payment or background polling. */
    fun dismissCheckResult() {
        local.update { it.copy(check = it.check.dismissed()) }
    }

    /** Expires the link, so the shopper can no longer pay with it. */
    fun cancel() = runAction(local, { it.cancel }, { copy(cancel = it) }) { links.cancel(saleId).toState(stillOpen = false) }

    /** Completes a demo link after the screen's Manager approval; the operation checks approval again before writing. */
    fun simulate() = runAction(local, { it.simulation }, { copy(simulation = it) }) { links.simulate(saleId).toState(stillOpen = false) }

    /** This update as a finished action: done once settled, a note that it is not paid yet, or the failure. */
    private fun LinkUpdate.toState(stillOpen: Boolean): ActionState =
        when (this) {
            LinkUpdate.Settled -> ActionState(done = true)
            LinkUpdate.StillOpen -> ActionState(outcome = ActionOutcome.LinkNotPaid.takeIf { stillOpen }, done = true)
            is LinkUpdate.NotDone -> ActionState(outcome = ActionOutcome.Failed(failure), isError = true)
            is LinkUpdate.Unconfirmed -> ActionState(outcome = ActionOutcome.Unconfirmed(failure), isError = true)
        }
}

/**
 * What the payment result screen shows.
 *
 * @property payment The sale with what can be done with it now, or null until it has been loaded.
 * @property transaction The receipt, printing (with the merchant copy when it is due), emailing and re-checking the sale.
 * @property abort The latest abort of the transaction a busy terminal is working on.
 */
data class SaleResultUiState(
    val payment: StoredPayment? = null,
    val transaction: TransactionActionsState = TransactionActionsState(),
    val abort: ActionState = ActionState(),
) {
    /** The sale and its lines, or null until loaded. */
    val record: SaleWithLines? get() = payment?.record

    /** What can be done with the payment now, such as entering the tip written on the receipt. */
    val actions: Set<PaymentAction> get() = payment?.actions.orEmpty()

    /** Whether the payment was approved. */
    val approved: Boolean get() = record?.sale?.approved == true

    /** Whether Payments app-specific failure advice applies to this historical operation. */
    val tapToPayHelp: Boolean get() = !approved && record?.sale?.context.usesPaymentsApp()

    /** Whether the stored refusal names a transaction a busy terminal is working on, whose abort was not sent yet. */
    val canAbortBusy: Boolean get() = record?.sale?.busyServiceId != null

    /** What kind of payment it was; a sale until the record is loaded. */
    val kind: SaleKind get() = record?.sale?.kind ?: SaleKind.SALE

    /** Whether the payment was a pre-authorisation, which only held the amount. */
    val preAuthorisation: Boolean get() = kind == SaleKind.PRE_AUTHORISATION

    /** Adyen's retry guidance for a failed payment; null when the terminal gave no ErrorCondition. */
    val advice: RetryAdvice? get() = record?.sale?.decline?.advice
}

/**
 * Post-payment actions: the receipt, printing it (with the optional merchant copy), emailing it and re-checking an
 * unknown outcome ([transaction], which also delivers the automatic receipt once), and cancelling what a busy terminal
 * is working on.
 *
 * @param saleId The sale (or pre-authorisation) that was paid.
 * @param payments Follows it, with what can be done with it.
 * @param receipts Prints and emails its receipt.
 * @param lifecycle The terminal payments' lifecycle, for busy terminals and collecting the remaining balance.
 * @param operations Asks again about an unknown outcome the way the payment was sent, and leaves its result.
 */
class SaleResultViewModel(
    private val saleId: String,
    payments: StoredPayments,
    receipts: ReceiptDelivery,
    private val lifecycle: TransactionLifecycle<PaymentStart>,
    private val operations: FinancialOperations,
) : ViewModel() {
    private val local = MutableStateFlow(SaleResultUiState())

    /** The receipt, printing (the customer copy, then the merchant copy when it is due), emailing and re-checking. */
    val transaction = TransactionActions.forSale(viewModelScope, saleId, receipts, operations, fresh = true)

    /** The screen state, updated whenever the sale, the capture mode or an action changes. */
    val state: StateFlow<SaleResultUiState> =
        combine(
            payments.observe(saleId),
            local,
            transaction.state,
        ) { payment, ui, actions ->
            ui.copy(payment = payment, transaction = actions)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SaleResultUiState())

    /** Cancels the transaction a busy terminal is working on, so the payment can be retried. */
    fun abortBusyTransaction() =
        runAction(local, { it.abort }, { copy(abort = it) }) {
            // Once sent, the stored refusal no longer names the busy transaction, so the action disappears on its own.
            if (lifecycle.abortBusyTransaction(
                    saleId,
                )
            ) {
                ActionState(outcome = ActionOutcome.AbortSent, done = true)
            } else {
                ActionState(isError = true)
            }
        }

    /** Leaves the result, so the next payment can start, whichever way this one was sent. */
    fun finish() = operations.acknowledgePayment()

    /**
     * Starts collecting the remaining balance of a partially paid sale on another card; false when not partially paid
     * or a payment is already running.
     */
    fun payRemaining(): Boolean {
        val sale = state.value.record?.sale ?: return false
        val start = Checkout.remainingPayment(sale) ?: return false
        operations.acknowledgePayment()
        return lifecycle.start(start) != null
    }
}
