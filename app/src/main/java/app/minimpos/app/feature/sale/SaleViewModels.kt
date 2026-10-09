package app.minimpos.app.feature.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.CategoryEntity
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.CatalogRepository
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.TransactionActions
import app.minimpos.app.feature.TransactionActionsState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.CheckoutForm
import app.minimpos.app.payment.LinkUpdate
import app.minimpos.app.payment.PaymentLinks
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.ReceiptDelivery
import app.minimpos.app.payment.SaleSession
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.payment.WalletPayments
import app.minimpos.app.refund.PaymentAction
import app.minimpos.app.refund.StoredPayment
import app.minimpos.app.refund.StoredPayments
import app.minimpos.app.refund.awaitsLinkPayment
import app.minimpos.app.refund.decline
import app.minimpos.app.refund.simulatedLink
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.terminal.WalletDiscovery
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartTotals
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.client.RetryAdvice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
    val currency: CurrencySpec = CurrencySpec("EUR", 2),
    val defaultTaxRate: TaxRateEntity? = null,
    val chargeTax: Boolean = true,
    val kind: SaleKind = SaleKind.SALE,
) {
    /** Whether this is a pre-authorisation, which holds one item. */
    val preAuthorisation: Boolean get() = kind == SaleKind.PRE_AUTHORISATION

    /** The products matching [selectedCategory] and [query]. */
    val visibleProducts: List<ProductEntity>
        get() =
            products.filter { product ->
                (selectedCategory == null || product.categoryId == selectedCategory) &&
                    (
                        query.isBlank() || product.name.contains(query.trim(), ignoreCase = true) ||
                            product.sku?.contains(query.trim()) == true
                    )
            }

    /** Whether any product has a SKU, which is when barcode scanning is offered. */
    val hasSkus: Boolean get() = products.any { !it.sku.isNullOrBlank() }

    /** How many units of product [productId] are in the cart, over all its lines; shown as a badge on its tile. */
    fun quantityInCart(productId: Long): Int = cart.lines.filter { it.productId == productId }.sumOf { it.quantity }
}

/**
 * The sale screen, or the pre-authorise screen: browsing the products of the [session]'s kind and building its cart
 * (for a pre-authorisation, a single item).
 *
 * @param catalog Where the products are read.
 * @param session The payment being rung up; its [SaleSession.kind] decides whose products are offered.
 * @param settings The current settings.
 * @param currency The currency prices are shown in with the given settings.
 */
class SaleViewModel(
    private val catalog: CatalogRepository,
    private val session: SaleSession,
    settings: StateFlow<AppSettings>,
    currency: (AppSettings) -> CurrencySpec,
) : ViewModel() {
    private val kind = session.kind
    private val filters = MutableStateFlow("" to null as Long?)

    private val catalogue =
        combine(catalog.products, catalog.categories, catalog.taxRates) { products, categories, taxRates ->
            val offered = products.filter { it.kind == kind }
            // A category is shown when it has products of this kind; a sale also keeps categories without products yet.
            val shown =
                categories.filter { category ->
                    offered.any { it.categoryId == category.id } ||
                        (kind == SaleKind.SALE && products.none { it.categoryId == category.id })
                }
            Triple(offered, shown, taxRates)
        }

    /** The screen state, updated whenever the catalogue, cart, settings or filters change. */
    val state: StateFlow<SaleUiState> =
        combine(catalogue, session.cart, settings, filters) { (products, categories, taxRates), cart, appSettings, (query, category) ->
            SaleUiState(
                loaded = true,
                products = products,
                categories = categories,
                taxRates = taxRates,
                query = query,
                selectedCategory = category,
                cart = cart,
                totals = cart.totals(appSettings.payment.taxMode, appSettings.payment.chargeTax),
                currency = currency(appSettings),
                defaultTaxRate = appSettings.payment.defaultTaxRate(taxRates),
                chargeTax = appSettings.payment.chargeTax,
                kind = kind,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SaleUiState(kind = kind))

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
        val tax = catalog.taxRates.first().firstOrNull { it.id == product.taxRateId } ?: return null
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
 */
class CheckoutViewModel(
    private val session: SaleSession,
    private val payments: TransactionLifecycle<PaymentStart>,
    settings: StateFlow<AppSettings>,
    terminal: StateFlow<TerminalState>,
    currency: (AppSettings) -> CurrencySpec,
    private val links: PaymentLinks? = null,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val walletPayments: WalletPayments? = null,
    discovery: WalletDiscovery? = null,
) : ViewModel() {
    private val checkout =
        session.checkout(
            settings,
            terminal.map {
                it.printerAvailable
            },
            terminal.map { it.paymentLinks && links != null },
            currency,
        )
    private val withWallets =
        if (discovery == null) {
            checkout
        } else {
            combine(checkout, discovery.state) { checked, wallets ->
                checked.copy(wallets = wallets.offered(checked.currency.code))
            }
        }

    /** The screen state, updated whenever the form, cart, settings, printer or payment link setup change. */
    val state: StateFlow<Checkout> = withWallets.stateIn(viewModelScope, SharingStarted.Eagerly, Checkout(kind = session.kind))

    /** Updates the form (kept in the [SaleSession] so it survives going back to the cart). */
    fun update(transform: (CheckoutForm) -> CheckoutForm) = session.updateForm(transform)

    /** Starts the payment ([Checkout.paymentStart]); returns false when the form is not ready. */
    fun pay(): Boolean {
        val start = session.paymentStart(state.value, clock.instant(), zone()) ?: return false
        payments.start(start)
        return true
    }

    /** Prepares the unchanged sale for wallet scanning; false when unavailable or already busy. */
    fun scanWallet(): Boolean = walletPayments?.begin(state.value) == true

    /**
     * Starts creating a payment link instead ([Checkout.linkStart]) and returns its sale's ID; null when no link can be
     * made (the form is not ready, or links are not offered).
     */
    fun sendLink(): String? {
        val start = session.linkStart(state.value, clock.instant(), zone()) ?: return null
        return links?.start(start)
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
    val creating: Boolean get() = sale == null || sale?.status == SaleStatus.PENDING

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
 * @param fresh Whether the link was just created at checkout, so its automatic delivery is due.
 * @param pollInterval How often an open link is checked while the screen is shown.
 */
class PaymentLinkViewModel(
    private val saleId: String,
    payments: StoredPayments,
    receipts: ReceiptDelivery,
    private val links: PaymentLinks,
    fresh: Boolean,
    private val pollInterval: Duration = 10.seconds,
) : ViewModel() {
    private val local = MutableStateFlow(PaymentLinkUiState())

    /** The receipt (unpaid, with the link, until it is paid), printing, emailing and sharing it. */
    val transaction = TransactionActions.forLink(viewModelScope, saleId, receipts, links, fresh)

    /** The screen state, updated whenever the sale or an action changes. */
    val state: StateFlow<PaymentLinkUiState> =
        combine(payments.observe(saleId), local, transaction.state) { payment, ui, actions ->
            ui.copy(payment = payment, transaction = actions)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, PaymentLinkUiState())

    init {
        viewModelScope.launch {
            // Checking is quiet: a failed poll leaves the last answer shown, and the next one tries again.
            state.map { it.openLink != null }.distinctUntilChanged().collectLatest { open ->
                while (open) {
                    delay(pollInterval)
                    links.check(saleId)
                }
            }
        }
    }

    /** Asks Adyen now whether the link was paid (or, after an unknown outcome, creates it again). */
    fun check() {
        if (local.value.check.running) return
        local.update { it.copy(check = ActionState(running = true)) }
        launchWrite({ links.check(saleId) }) { update -> local.update { it.copy(check = update.toState(stillOpen = true)) } }
    }

    /** Expires the link, so the shopper can no longer pay with it. */
    fun cancel() {
        if (local.value.cancel.running) return
        local.update { it.copy(cancel = ActionState(running = true)) }
        launchWrite({ links.cancel(saleId) }) { update -> local.update { it.copy(cancel = update.toState(stillOpen = false)) } }
    }

    /** Completes a demo link after the screen's Manager approval; the operation checks approval again before writing. */
    fun simulate() {
        if (local.value.simulation.running) return
        local.update { it.copy(simulation = ActionState(running = true)) }
        launchWrite({ links.simulate(saleId) }) { update ->
            local.update { it.copy(simulation = update.toState(stillOpen = false)) }
        }
    }

    /** This update as a finished action: done once settled, a note that it is not paid yet, or the failure. */
    private fun LinkUpdate.toState(stillOpen: Boolean): ActionState =
        when (this) {
            LinkUpdate.Settled -> ActionState(done = true)
            LinkUpdate.StillOpen -> ActionState(outcome = ActionOutcome.LinkNotPaid.takeIf { stillOpen }, done = true)
            is LinkUpdate.Failed -> ActionState(outcome = ActionOutcome.Failed(failure), isError = true)
            is LinkUpdate.NotSetUp -> ActionState(outcome = ActionOutcome.NotSetUp(problem), isError = true)
        }
}

/**
 * What the payment result screen shows.
 *
 * @property payment The sale with what can be done with it now, or null until it has been loaded.
 * @property transaction The receipt, printing (with the merchant copy when it is due), emailing and re-checking the sale.
 * @property busyServiceId The terminal's own in-progress transaction, when the payment was declined as Busy.
 * @property abort The latest abort of that transaction.
 */
data class SaleResultUiState(
    val payment: StoredPayment? = null,
    val transaction: TransactionActionsState = TransactionActionsState(),
    val busyServiceId: String? = null,
    val abort: ActionState = ActionState(),
) {
    /** The sale and its lines, or null until loaded. */
    val record: SaleWithLines? get() = payment?.record

    /** What can be done with the payment now, such as entering the tip written on the receipt. */
    val actions: Set<PaymentAction> get() = payment?.actions.orEmpty()

    /** Whether the payment was approved. */
    val approved: Boolean get() = record?.sale?.status == SaleStatus.APPROVED

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
 * @param lifecycle The payments' lifecycle, for status checks, busy terminals and leaving the result.
 */
class SaleResultViewModel(
    private val saleId: String,
    payments: StoredPayments,
    receipts: ReceiptDelivery,
    private val lifecycle: TransactionLifecycle<PaymentStart>,
) : ViewModel() {
    private val local = MutableStateFlow(SaleResultUiState(busyServiceId = lifecycle.busyServiceId(saleId)))

    /** The receipt, printing (the customer copy, then the merchant copy when it is due), emailing and re-checking. */
    val transaction = TransactionActions.forSale(viewModelScope, saleId, receipts, lifecycle, fresh = true)

    /** The screen state, updated whenever the sale, the capture mode or an action changes. */
    val state: StateFlow<SaleResultUiState> =
        combine(payments.observe(saleId), local, transaction.state) { payment, ui, actions ->
            ui.copy(payment = payment, transaction = actions)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SaleResultUiState())

    /** Cancels the transaction a busy terminal is working on, so the payment can be retried. */
    fun abortBusyTransaction() {
        local.update { it.copy(abort = ActionState(running = true)) }
        viewModelScope.launch {
            val sent = lifecycle.abortBusyTransaction(saleId)
            local.update {
                it.copy(
                    busyServiceId = null,
                    abort = if (sent) ActionState(outcome = ActionOutcome.AbortSent, done = true) else ActionState(isError = true),
                )
            }
        }
    }

    /** Leaves the result, so the next payment can start. */
    fun finish() = lifecycle.acknowledge()
}
