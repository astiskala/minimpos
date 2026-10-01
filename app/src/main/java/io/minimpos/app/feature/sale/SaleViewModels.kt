package io.minimpos.app.feature.sale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.minimpos.app.data.db.CategoryEntity
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.repo.CatalogRepository
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.app.payment.CheckoutForm
import io.minimpos.app.payment.PaymentStart
import io.minimpos.app.payment.ReceiptDelivery
import io.minimpos.app.payment.SaleSession
import io.minimpos.app.payment.TokenizationRequest
import io.minimpos.app.payment.TransactionLifecycle
import io.minimpos.app.receipt.ActionResult
import io.minimpos.app.terminal.TerminalState
import io.minimpos.core.cart.Cart
import io.minimpos.core.cart.CartTotals
import io.minimpos.core.ids.Ids
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.receipt.ReceiptCopy
import io.minimpos.core.shopper.ShopperReferences
import io.minimpos.core.tax.TaxMode
import io.minimpos.terminal.client.RetryAdvice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import java.util.Locale

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
 * @property defaultTaxRateId The configured default tax rate for custom items; see [defaultTaxRate].
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
    val defaultTaxRateId: Long? = null,
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

    /** The rate custom items start with: the configured default, else the first rate; null only before any exists. */
    val defaultTaxRate: TaxRateEntity? get() = taxRates.firstOrNull { it.id == defaultTaxRateId } ?: taxRates.firstOrNull()

    /** Whether any product has a SKU, which is when barcode scanning is offered. */
    val hasSkus: Boolean get() = products.any { !it.sku.isNullOrBlank() }

    /** How many units of product [productId] are in the cart, over all its lines; shown as a badge on its tile. */
    fun quantityInCart(productId: Long): Int = cart.lines.filter { it.productId == productId }.sumOf { it.quantity }
}

/**
 * The sale screen, or the pre-authorise screen: browsing the products of [kind] and building the cart in the shared
 * [SaleSession] (for a pre-authorisation, a single-item one).
 *
 * @param catalog Where the products are read.
 * @param session The cart being built.
 * @param settings The current settings.
 * @param currency The currency prices are shown in with the given settings.
 * @param kind Whose products are offered: sale products, or pre-authorisation products.
 */
class SaleViewModel(
    private val catalog: CatalogRepository,
    private val session: SaleSession,
    settings: StateFlow<AppSettings>,
    currency: (AppSettings) -> CurrencySpec,
    private val kind: SaleKind = SaleKind.SALE,
) : ViewModel() {
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
                defaultTaxRateId = appSettings.payment.defaultTaxRateId,
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
 * What the checkout screen shows and whether the payment can start.
 *
 * @property form What the operator entered.
 * @property payment The payment settings, which decide the fields shown.
 * @property totals The cart priced with the current tax settings; its gross total is charged.
 * @property currency The currency charged.
 * @property preAuthorisation Whether the amount is only held (a pre-authorisation) rather than charged.
 * @property printerAvailable Whether printing is offered, which tipping on the receipt needs.
 */
data class CheckoutUiState(
    val form: CheckoutForm = CheckoutForm(),
    val payment: PaymentSettings = PaymentSettings(),
    val totals: CartTotals = Cart().totals(TaxMode.INCLUSIVE),
    val currency: CurrencySpec = CurrencySpec("EUR", 2),
    val preAuthorisation: Boolean = false,
    val printerAvailable: Boolean = false,
) {
    /** Whether the email field is shown. */
    val showEmail: Boolean get() = payment.captureEmailBefore

    /** Whether the customer reference field is shown (exactly when it is the shopper reference). */
    val showCustomerReference: Boolean get() = payment.asksCustomerReference

    /** False when an email was typed that is not a valid address; blank is valid. */
    val emailValid: Boolean get() = form.email.isBlank() || ShopperReferences.isValidEmail(form.email)

    /** False when the typed merchant reference is longer than [MAX_REFERENCE_LENGTH]. */
    val referenceValid: Boolean get() = form.transactionReference.trim().length <= MAX_REFERENCE_LENGTH

    /** The entered customer reference; null when none was entered or none is asked for (the email is the shopper reference). */
    val customerReference: String? get() = form.customerReference.trim().takeIf { showCustomerReference && it.isNotEmpty() }

    /** False when the entered customer reference is not a valid Adyen shopper reference; none entered is valid. */
    val customerReferenceValid: Boolean get() = customerReference?.let(ShopperReferences::isValidReference) != false

    /** The Adyen shopperReference tokenization would use, if the entered data allows one. */
    val shopperReference: String?
        get() =
            when (payment.shopperReferenceSource) {
                ShopperReferenceSource.CUSTOMER_REFERENCE -> {
                    customerReference?.takeIf(ShopperReferences::isValidReference)
                }

                ShopperReferenceSource.EMAIL -> {
                    form.email.trim().takeIf { ShopperReferences.isValidEmail(it) }?.let {
                        ShopperReferences.fromEmail(it, payment.emailReferenceMode, payment.emailReferenceSalt)
                    }
                }
            }

    /** Whether the card can be saved, which needs a [shopperReference]; the switch is disabled otherwise. */
    val canTokenize: Boolean get() = shopperReference != null

    /**
     * Whether the card will be saved: the operator's choice, else the settings default (the pre-authorisation one for
     * [preAuthorisation]), when [canTokenize].
     */
    val tokenize: Boolean
        get() = canTokenize && (form.tokenize ?: if (preAuthorisation) payment.preAuthTokenizeDefaultOn else payment.tokenizeDefaultOn)

    /** Whether "Tip on the receipt" is offered: for a sale, while a printer is available to print the receipt. */
    val canTipOnReceipt: Boolean get() = !preAuthorisation && printerAvailable

    /** Whether the sale is taken for tipping on the receipt: the operator's choice, else the settings default. */
    val tipOnReceipt: Boolean get() = canTipOnReceipt && (form.tipOnReceipt ?: payment.tipOnReceiptDefaultOn)

    /** Whether "Pay" is enabled: something to charge and every entered field valid. */
    val canPay: Boolean get() = !totals.isEmpty && totals.amounts.gross > 0 && emailValid && referenceValid && customerReferenceValid

    /** Field limits. */
    companion object {
        /** Longest merchant reference accepted, in characters (Adyen's limit for `merchantReference`). */
        const val MAX_REFERENCE_LENGTH = 80
    }
}

/**
 * The checkout screen: references, email and saving the card, then starting the payment.
 *
 * @param session The cart (or pre-authorisation item) and the form.
 * @param payments Runs the payment.
 * @param settings The current settings.
 * @param terminal Whether printing is offered, which tipping on the receipt needs.
 * @param currency The currency charged with the given settings.
 * @param kind A sale, or a pre-authorisation that only holds the amount.
 * @param clock Stamps generated merchant references.
 * @param zone The time zone of generated merchant references.
 */
class CheckoutViewModel(
    private val session: SaleSession,
    private val payments: TransactionLifecycle<PaymentStart>,
    settings: StateFlow<AppSettings>,
    terminal: StateFlow<TerminalState>,
    currency: (AppSettings) -> CurrencySpec,
    private val kind: SaleKind = SaleKind.SALE,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {
    /** The screen state, updated whenever the form, cart, settings or printer change. */
    val state: StateFlow<CheckoutUiState> =
        combine(session.checkout, session.cart, settings, terminal) { form, cart, appSettings, status ->
            CheckoutUiState(
                form,
                appSettings.payment,
                cart.totals(appSettings.payment.taxMode, appSettings.payment.chargeTax),
                currency(appSettings),
                kind == SaleKind.PRE_AUTHORISATION,
                status.printerAvailable,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, CheckoutUiState(preAuthorisation = kind == SaleKind.PRE_AUTHORISATION))

    /** Updates the form (kept in the [SaleSession] so it survives going back to the cart). */
    fun update(transform: (CheckoutForm) -> CheckoutForm) = session.updateCheckout(transform)

    /**
     * Starts the payment; returns false when the form is not ready. A blank merchant reference is generated with the
     * configured prefix.
     */
    fun pay(): Boolean {
        val current = state.value
        if (!current.canPay) return false
        val payment = current.payment
        val reference =
            current.form.transactionReference.trim().ifEmpty {
                Ids.transactionReference(payment.referencePrefix, clock.instant(), zone())
            }
        val email =
            current.form.email
                .trim()
                .takeIf { it.isNotEmpty() }
        payments.start(
            PaymentStart(
                totals = current.totals,
                currency = current.currency,
                merchantReference = reference,
                customerReference = current.customerReference,
                shopperEmail = email,
                tokenization =
                    current.shopperReference?.takeIf { current.tokenize }?.let {
                        TokenizationRequest(it, payment.recurringModel(), payment.sendShopperEmail)
                    },
                kind = kind,
                tipOnReceipt = current.tipOnReceipt,
            ),
        )
        return true
    }
}

/**
 * The state of a button-triggered action such as printing, emailing or a connection test.
 *
 * @property running Whether it is in progress (the button shows a spinner).
 * @property message The outcome to show, or null.
 * @property isError Whether [message] is an error.
 * @property done Whether it finished successfully.
 */
data class ActionState(
    val running: Boolean = false,
    val message: String? = null,
    val isError: Boolean = false,
    val done: Boolean = false,
)

/**
 * What the payment result screen shows.
 *
 * @property record The sale, or null until it has been loaded.
 * @property settings The current settings.
 * @property printerAvailable Whether printing is offered.
 * @property print The latest print.
 * @property merchantCopyPending Whether the customer copy has printed and the merchant copy is still to be printed.
 * @property email The latest email.
 * @property rechecking Whether a transaction status check is running.
 * @property busyServiceId The terminal's own in-progress transaction, when the payment was declined as Busy.
 * @property abort The latest abort of that transaction.
 */
data class SaleResultUiState(
    val record: SaleWithLines? = null,
    val settings: AppSettings = AppSettings(),
    val printerAvailable: Boolean = false,
    val print: ActionState = ActionState(),
    val merchantCopyPending: Boolean = false,
    val email: ActionState = ActionState(),
    val rechecking: Boolean = false,
    val busyServiceId: String? = null,
    val abort: ActionState = ActionState(),
) {
    /** Whether the payment was approved. */
    val approved: Boolean get() = record?.sale?.status == SaleStatus.APPROVED

    /** Whether the payment was a pre-authorisation, which only held the amount. */
    val preAuthorisation: Boolean get() = record?.sale?.kind == SaleKind.PRE_AUTHORISATION

    /** Adyen's retry guidance for a failed payment; null when the terminal gave no ErrorCondition. */
    val advice: RetryAdvice?
        get() =
            record?.sale?.takeIf { it.status != SaleStatus.APPROVED && it.errorCondition != null }?.let {
                RetryAdvice.forPayment(it.errorCondition, it.refusalReason)
            }
}

/**
 * Post-payment actions: printing (with the optional merchant copy) and emailing the receipt. When the sale is approved
 * its cart is cleared, and what [ReceiptDelivery] delivers automatically runs once (not again when the screen is
 * recreated).
 *
 * @param saleId The sale (or pre-authorisation) that was paid.
 * @param sales Where it is stored.
 * @param receipts Prints and emails its receipt.
 * @param payments The payments' lifecycle, for status checks, busy terminals and leaving the result.
 * @param sessions The cart of each kind of payment; the one of the sale's kind is cleared once it is approved.
 * @param settings The current settings.
 * @param terminal Whether printing is offered.
 * @param messages Localised outcome messages.
 */
class SaleResultViewModel(
    private val saleId: String,
    sales: SaleRepository,
    private val receipts: ReceiptDelivery,
    private val payments: TransactionLifecycle<PaymentStart>,
    private val sessions: (SaleKind) -> SaleSession,
    settings: StateFlow<AppSettings>,
    terminal: StateFlow<TerminalState>,
    private val messages: ResultMessages,
) : ViewModel() {
    private val local = MutableStateFlow(SaleResultUiState(busyServiceId = payments.busyServiceId(saleId)))

    /** The screen state, updated whenever the sale, settings, printer or an action changes. */
    val state: StateFlow<SaleResultUiState> =
        combine(sales.observe(saleId), settings, terminal, local) { record, appSettings, status, ui ->
            ui.copy(record = record, settings = appSettings, printerAvailable = status.printerAvailable)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, SaleResultUiState())

    init {
        viewModelScope.launch {
            val record = sales.observe(saleId).first { it != null } ?: return@launch
            if (record.sale.status == SaleStatus.APPROVED) sessions(record.sale.kind).clear()
            val automatic = receipts.automationForSale(saleId)
            if (automatic.print) print()
            automatic.emailTo?.let(::email)
        }
    }

    /** Prints the customer copy; the merchant copy then waits for the operator to tear off the first (see [printMerchantCopy]). */
    fun print() {
        local.update { it.copy(print = ActionState(running = true)) }
        viewModelScope.launch {
            val printed = receipts.printSale(saleId, ReceiptCopy.CUSTOMER)
            local.update { it.copy(print = printed.result.toState(messages.printed), merchantCopyPending = printed.merchantCopyDue) }
        }
    }

    /** Prints the merchant copy, with Adyen's cashier receipt lines. */
    fun printMerchantCopy() {
        local.update { it.copy(print = ActionState(running = true), merchantCopyPending = false) }
        viewModelScope.launch {
            val result = receipts.printSale(saleId, ReceiptCopy.MERCHANT).result.toState(messages.printed)
            local.update { it.copy(print = result) }
        }
    }

    /** Emails the receipt to [to]. */
    fun email(to: String) {
        local.update { it.copy(email = ActionState(running = true)) }
        viewModelScope.launch {
            val result = receipts.emailSale(saleId, to).toState(messages.emailed.format(Locale.getDefault(), to))
            local.update { it.copy(email = result) }
        }
    }

    /** Checks the transaction status of an unknown outcome again; the sale updates when it is settled. */
    fun recheck() {
        local.update { it.copy(rechecking = true) }
        viewModelScope.launch {
            payments.recheck(saleId)
            local.update { it.copy(rechecking = false) }
        }
    }

    /** Cancels the transaction a busy terminal is working on, so the payment can be retried. */
    fun abortBusyTransaction() {
        local.update { it.copy(abort = ActionState(running = true)) }
        viewModelScope.launch {
            val sent = payments.abortBusyTransaction(saleId)
            local.update {
                it.copy(
                    busyServiceId = null,
                    abort = if (sent) ActionState(message = messages.abortSent, done = true) else ActionState(isError = true),
                )
            }
        }
    }

    /** Leaves the result, so the next payment can start. */
    fun finish() = payments.acknowledge()
}

/**
 * Localised messages of the result screens.
 *
 * @property printed Shown after a successful print.
 * @property emailed Format with the address, shown after the receipt was sent.
 * @property abortSent Shown after the busy terminal's transaction was aborted.
 * @property stillUnknown Shown when a status check found the outcome still unknown.
 * @property notCaptured Format with the reason, shown when a capture sent again did not go through.
 */
data class ResultMessages(
    val printed: String,
    val emailed: String,
    val abortSent: String = "",
    val stillUnknown: String = "",
    val notCaptured: String = "",
)

/** This result as a finished [ActionState]: [successMessage] on success, else the failure's message as an error. */
fun ActionResult.toState(successMessage: String) =
    when (this) {
        ActionResult.Success -> ActionState(message = successMessage, done = true)
        is ActionResult.Failure -> ActionState(message = message, isError = true)
    }
