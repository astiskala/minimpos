package app.minimpos.app.payment

import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.CashbackSettings
import app.minimpos.app.terminal.ApiSetup
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.cart.CartProduct
import app.minimpos.core.cart.CartTotals
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.ScanWallet
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.MexicanPayment
import app.minimpos.terminal.client.ScannedPayment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlin.uuid.Uuid

/**
 * What the operator has entered on the checkout screen, kept while going back to the cart and forward again.
 *
 * @property transactionReference The merchant reference typed in; blank generates one.
 * @property customerReference The customer reference typed in (only asked for when it is the shopper reference).
 * @property email The shopper's email typed in, for the receipt and possibly the shopper reference.
 * @property tokenize Null until the operator touches the switch; then the settings default no longer applies.
 * @property tipOnReceipt "Tip on the receipt": null until the operator touches the switch, as with [tokenize].
 * @property askGratuity "Tip on terminal": null until the operator touches the switch; mutually exclusive with [tipOnReceipt].
 * @property askGiving "Charity donation (Adyen Giving)": null until the operator touches the switch.
 * @property audioGuidance Whether this shopper requests audio instructions on the terminal.
 * @property audioGuidanceConfirmedFor POIID whose software, Support enablement and non-signature card flow the operator confirmed.
 * @property cardEntryOverride Selected card entry mode (`NORMAL`, `MOTO` or `MKE`).
 * @property mexicanPayment Shopper-selected Mexican debit/credit routing; null uses ordinary terminal routing.
 * @property cashBackMinor Cash requested beyond merchandise in minor units; zero means none.
 * @property cashbackConfirmation Exact configuration whose card program the operator confirmed; changes require reconfirmation.
 */
data class CheckoutForm(
    val transactionReference: String = "",
    val customerReference: String = "",
    val email: String = "",
    val tokenize: Boolean? = null,
    val tipOnReceipt: Boolean? = null,
    val askGratuity: Boolean? = null,
    val askGiving: Boolean? = null,
    val audioGuidance: Boolean = false,
    val audioGuidanceConfirmedFor: String? = null,
    val cardEntryOverride: CardEntryOverride = CardEntryOverride.NORMAL,
    val mexicanPayment: MexicanPayment? = null,
    val cashBackMinor: Long = 0,
    val cashbackConfirmation: CashbackSettings? = null,
)

/**
 * Scanned-wallet verification facts for [SaleSession.paymentStart].
 *
 * @property actual Current checkout snapshot to compare against the prepared one.
 * @property wallet Chosen wallet, which must still be offered in [actual].
 * @property code Shopper-presented numeric payment code.
 * @property origin Verified wallet availability (context and setup identity) from preparation.
 */
class WalletScanStart(
    val actual: Checkout,
    val wallet: ScanWallet,
    val code: String,
    val origin: WalletAvailability,
)

/**
 * The payment being rung up, shared by the sale (or pre-authorise), checkout and payment screens of its [kind]: the
 * cart, the checkout form and, with the settings, the [Checkout] that decides what payment they make. One per kind
 * lives in the [app.minimpos.app.AppContainer] so the cart survives navigation (and a sale being rung up survives a
 * pre-authorisation meanwhile). Payment approval or link creation completes only its originating checkout snapshot;
 * newer work stays intact. Checkout observes the cart, form and revision together. Updates are atomic and thread-safe.
 */
class SaleSession(
    /** What is being rung up; a [SaleKind.singleItem] kind holds at most one item, with a quantity of one. */
    val kind: SaleKind = SaleKind.SALE,
    /** Resolves prompts in the current app language; null only for sessions without shopper display. */
    private val shopperTexts: (() -> ShopperPromptTexts)? = null,
    /** Generates cart line keys; tests make them predictable. */
    private val newKey: () -> String = { Uuid.random().toString() },
) {
    private val singleItem = kind.singleItem
    private val edits = AtomicLong()

    /** The session's edit revision, used to avoid clearing newer work when a payment is recovered. */
    private val revision: Long get() = edits.get()

    /** The current cart. */
    val cart: StateFlow<Cart>
        field = MutableStateFlow(Cart())

    /** The current checkout form. */
    val form: StateFlow<CheckoutForm>
        field = MutableStateFlow(CheckoutForm())

    private data class Snapshot(
        val cart: Cart,
        val form: CheckoutForm,
        val revision: Long,
    )

    private val snapshot = MutableStateFlow(Snapshot(cart.value, form.value, revision))

    /**
     * The checkout of this session's cart and form with [settings], charged in the [currency] the settings give,
     * following [terminal] (printer, payment links when [offersLinks], and whole destination capabilities) and
     * scanned wallets from [wallets]; updated whenever any of them changes.
     */
    fun checkout(
        settings: Flow<AppSettings>,
        terminal: Flow<TerminalState> = flowOf(TerminalState()),
        wallets: Flow<WalletAvailability> = flowOf(WalletAvailability()),
        offersLinks: Boolean = true,
        managerConfigured: Flow<Boolean> = flowOf(false),
        currency: (AppSettings) -> CurrencySpec,
    ): Flow<Checkout> =
        flow {
            val prices = CheckoutPrices()
            emitAll(
                combine(
                    snapshot,
                    settings,
                    terminal,
                    wallets,
                    managerConfigured,
                ) { snapshot, appSettings, status, availableWallets, manager ->
                    val payment = appSettings.payment
                    val resolvedCurrency = currency(appSettings)
                    val caps = status.capabilities
                    val shopperDisplay = caps.supportsShopperDisplay && shopperTexts != null
                    Checkout(
                        snapshot.form,
                        payment,
                        prices.totals(snapshot.cart, payment.taxMode, payment.chargeTax, PricingChanges.ready(appSettings)),
                        resolvedCurrency,
                        kind,
                        status.printerAvailable,
                        status.paymentLinks && offersLinks,
                        snapshot.revision,
                        availableWallets.offered(resolvedCurrency.code),
                        terminalTippingAvailable = caps.supportsTerminalFeatures,
                        shopperDisplayAvailable = shopperDisplay,
                        shopperTexts = if (shopperDisplay) shopperTexts.invoke() else null,
                        emailOnTerminal = appSettings.asksEmailOnTerminal,
                        audioGuidanceMinimumSoftware = status.audioGuidanceMinimumSoftware,
                        audioGuidanceSimulated = status.apiSetup == ApiSetup.Simulated,
                        audioGuidanceTarget = status.poiId,
                        managerConfigured = manager,
                        cashbackAvailable = Cashback.ready(payment.cashback, availableWallets, resolvedCurrency.code, false),
                        mexicanRoutingAvailable = availableWallets.mexicanRouting,
                        mexicanContext = availableWallets.context,
                        mexicanIdentity = availableWallets.identity,
                    )
                },
            )
        }

    private class CheckoutPrices {
        private var cart: Cart? = null
        private var mode: TaxMode? = null
        private var chargeTax = false
        private var ready = false
        private var priced: CartTotals? = null

        fun totals(
            cart: Cart,
            mode: TaxMode,
            chargeTax: Boolean,
            ready: Boolean,
        ): CartTotals {
            val pricingChanged = this.mode != mode || this.chargeTax != chargeTax || this.ready != ready
            if (this.cart !== cart || pricingChanged) {
                priced = (if (ready) cart else Cart()).totals(mode, chargeTax)
                this.cart = cart
                this.mode = mode
                this.chargeTax = chargeTax
                this.ready = ready
            }
            return checkNotNull(priced)
        }
    }

    /**
     * Builds a payment from the displayed [checkout] at [now] in [zone] (for [walletScan], only when [checkout] and
     * the offered wallet still match [WalletScanStart.actual]); null when not ready or an edit made it stale.
     * Building an audio-guided start consumes its per-shopper choice and retains the new revision for completion.
     * A caller whose runner refuses the start restores its choice through [updateForm], fenced by that start.
     */
    fun paymentStart(
        checkout: Checkout,
        now: Instant,
        zone: ZoneId,
        walletScan: WalletScanStart? = null,
    ): PaymentStart? {
        val current = current(checkout) ?: return null
        if (walletScan == null) {
            val start = current.paymentStart(now, zone) ?: return null
            if (!current.form.audioGuidance) return start
            // Consume this shopper's choice once, even if the attempt is cancelled or its result stays unknown.
            return synchronized(this) {
                if (current(checkout) == null) return@synchronized null
                updateForm { it.copy(audioGuidance = false, audioGuidanceConfirmedFor = null) }
                start.copy(sessionRevision = revision)
            }
        }
        return current
            .takeIf { it.matchesWalletCheckout(walletScan.actual, walletScan.wallet) }
            ?.walletStart(now, zone)
            ?.withScanned(
                ScannedPayment(walletScan.wallet.brand, walletScan.code),
                checkNotNull(walletScan.origin.context),
                checkNotNull(walletScan.origin.identity),
            )
    }

    /** Builds a link from the displayed [checkout] at [now] in [zone]; null when unavailable or an edit made it stale. */
    fun linkStart(
        checkout: Checkout,
        now: Instant,
        zone: ZoneId,
    ): PaymentLinkStart? = current(checkout)?.linkStart(now, zone)

    /**
     * Adds one unit of [product] with [tax], joining an existing line for the same product at the same price and tax
     * (for a [SaleKind.singleItem] kind, it replaces the cart instead). The tax rate's name and rate are copied, so later edits to the
     * rate do not change the cart.
     */
    fun addProduct(
        product: ProductEntity,
        tax: TaxRateEntity,
    ) = edited {
        cart.update {
            (if (singleItem) Cart() else it).addProduct(
                CartProduct(
                    product.id,
                    product.name,
                    product.sku,
                    product.priceMinor,
                    AppliedTax(tax.name, tax.rateMilliPercent),
                    product.mealVoucherEligible,
                ),
                newKey,
            )
        }
    }

    /**
     * Adds a custom item of [amountMinor] (minor units, positive) with [tax] as a new line (for a [SaleKind.singleItem]
     * kind, it replaces the cart instead).
     *
     * @throws IllegalArgumentException if [amountMinor] is not positive.
     */
    fun addCustom(
        name: String,
        amountMinor: Long,
        tax: TaxRateEntity,
    ) = edited {
        cart.update { (if (singleItem) Cart() else it).addCustom(name, amountMinor, AppliedTax(tax.name, tax.rateMilliPercent), newKey()) }
    }

    /**
     * Sets the quantity of the line with [key], capped at [Cart.MAX_QUANTITY] (at one for a [SaleKind.singleItem] kind); zero or
     * less removes the line.
     */
    fun setQuantity(
        key: String,
        quantity: Int,
    ) = edited { cart.update { it.setQuantity(key, if (singleItem) quantity.coerceAtMost(1) else quantity) } }

    /** Removes the line with [key]; an unknown key changes nothing. */
    fun remove(key: String) = edited { cart.update { it.remove(key) } }

    /**
     * Updates the form atomically; [transform] must have no side effects, as it can run more than once.
     * A non-null [start] fences restoration of a rejected start to its originating kind and current revision.
     */
    fun updateForm(
        start: PaymentStart? = null,
        transform: (CheckoutForm) -> CheckoutForm,
    ) {
        synchronized(this) {
            if (start != null && (start.kind != kind || start.sessionRevision != revision)) return
            edited { form.update(transform) }
        }
    }

    /** Preserves displayed unit prices when moving from [before] to [after]; increments the originating revision. */
    internal fun reprice(
        before: CurrencySpec,
        after: CurrencySpec,
    ) = edited {
        cart.update { cart -> cart.copy(lines = cart.lines.map { it.copy(unitPrice = after.toMinor(before.toMajor(it.unitPrice))) }) }
    }

    /** Empties the cart and the checkout form, for the next sale. */
    fun clear() =
        edited {
            cart.value = Cart()
            form.value = CheckoutForm()
        }

    /** Clears only the session revision that made [start]; newer work is kept. */
    internal fun complete(start: PaymentStart) =
        synchronized(this) {
            if (start.kind == kind && start.sessionRevision == revision) clear()
        }

    private fun edited(change: () -> Unit) =
        synchronized(this) {
            change()
            edits.incrementAndGet()
            snapshot.value = Snapshot(cart.value, form.value, revision)
            Unit
        }

    private fun current(checkout: Checkout): Checkout? =
        synchronized(this) {
            checkout.takeIf { it.kind == kind && it.sessionRevision == revision }
        }
}

/**
 * Builds a saved-card charge for [card] from the displayed [checkout] at [now] in [zone]; null when unavailable,
 * [card] is ineligible for the recurring model, or an edit made [checkout] stale.
 */
fun SaleSession.savedCardStart(
    checkout: Checkout,
    card: StoredCard,
    now: Instant,
    zone: ZoneId,
    fromCheckout: Boolean = false,
): SavedCardStart? {
    if (checkout.form.audioGuidance) return null
    // The session's payment start is null once an edit made checkout stale; the charge is built from checkout itself.
    paymentStart(checkout, now, zone) ?: return null
    return checkout.savedCardStart(card, now, zone, fromCheckout = fromCheckout)
}

/**
 * Builds the first tender of a split of the displayed [checkout] at [now] in [zone] ([Checkout.splitStart]) for
 * [amountMinor] chosen as [allocation]; null when unavailable or an edit made it stale. A card tender and a confirmed
 * cash tender both complete only this revision once they collect money.
 */
fun SaleSession.splitStart(
    checkout: Checkout,
    now: Instant,
    zone: ZoneId,
    amountMinor: Long,
    allocation: TenderAllocation?,
): PaymentStart? {
    // Splitting never carries audio guidance, so checking freshness cannot consume a shopper's audio choice.
    if (!checkout.canSplit) return null
    paymentStart(checkout, now, zone) ?: return null
    return checkout.splitStart(now, zone, amountMinor, allocation)
}
