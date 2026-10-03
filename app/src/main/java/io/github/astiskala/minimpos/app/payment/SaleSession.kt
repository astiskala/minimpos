package io.github.astiskala.minimpos.app.payment

import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.core.cart.AppliedTax
import io.github.astiskala.minimpos.core.cart.Cart
import io.github.astiskala.minimpos.core.cart.CartProduct
import io.github.astiskala.minimpos.core.money.CurrencySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * What the operator has entered on the checkout screen, kept while going back to the cart and forward again.
 *
 * @property transactionReference The merchant reference typed in; blank generates one.
 * @property customerReference The customer reference typed in (only asked for when it is the shopper reference).
 * @property email The shopper's email typed in, for the receipt and possibly the shopper reference.
 * @property tokenize Null until the operator touches the switch; then the settings default no longer applies.
 * @property tipOnReceipt "Tip on the receipt": null until the operator touches the switch, as with [tokenize].
 */
data class CheckoutForm(
    val transactionReference: String = "",
    val customerReference: String = "",
    val email: String = "",
    val tokenize: Boolean? = null,
    val tipOnReceipt: Boolean? = null,
)

/**
 * The payment being rung up, shared by the sale (or pre-authorise), checkout and payment screens of its [kind]: the
 * cart, the checkout form and, with the settings, the [Checkout] that decides what payment they make. One per kind
 * lives in the [io.github.astiskala.minimpos.app.AppContainer] so the cart survives navigation (and a sale being rung up survives a
 * pre-authorisation meanwhile). Payment approval or link creation completes only its originating checkout snapshot;
 * newer work stays intact. Checkout observes the cart, form and revision together. Updates are atomic and thread-safe.
 */
class SaleSession(
    /** What is being rung up; a [SaleKind.singleItem] kind holds at most one item, with a quantity of one. */
    val kind: SaleKind = SaleKind.SALE,
    /** Generates cart line keys; tests make them predictable. */
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val singleItem = kind.singleItem
    private val edits = AtomicLong()

    /** The session's edit revision, used to avoid clearing newer work when a payment is recovered. */
    private val revision: Long get() = edits.get()
    private val _cart = MutableStateFlow(Cart())

    /** The current cart. */
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    private val _form = MutableStateFlow(CheckoutForm())

    /** The current checkout form. */
    val form: StateFlow<CheckoutForm> = _form.asStateFlow()

    private data class Snapshot(
        val cart: Cart,
        val form: CheckoutForm,
        val revision: Long,
    )

    private val snapshot = MutableStateFlow(Snapshot(_cart.value, _form.value, revision))

    /**
     * The checkout of this session's cart and form with [settings], charged in the [currency] the settings give,
     * offering tipping on the receipt while [printerAvailable] and payment links while [linksAvailable]; updated
     * whenever any of them changes.
     */
    fun checkout(
        settings: Flow<AppSettings>,
        printerAvailable: Flow<Boolean>,
        linksAvailable: Flow<Boolean> = flowOf(false),
        currency: (AppSettings) -> CurrencySpec,
    ): Flow<Checkout> =
        combine(snapshot, settings, printerAvailable, linksAvailable) { snapshot, appSettings, printer, links ->
            val payment = appSettings.payment
            Checkout(
                snapshot.form,
                payment,
                (
                    if (PricingChanges.ready(appSettings)) {
                        snapshot.cart
                    } else {
                        Cart()
                    }
                ).totals(payment.taxMode, payment.chargeTax),
                currency(appSettings),
                kind,
                printer,
                links,
                snapshot.revision,
            )
        }

    /** Builds a payment from the displayed [checkout] at [now] in [zone]; null when not ready or an edit made it stale. */
    fun paymentStart(
        checkout: Checkout,
        now: Instant,
        zone: ZoneId,
    ): PaymentStart? =
        synchronized(this) {
            checkout.takeIf { it.kind == kind && it.sessionRevision == revision }?.paymentStart(now, zone)
        }

    /** Builds a link from the displayed [checkout] at [now] in [zone]; null when unavailable or an edit made it stale. */
    fun linkStart(
        checkout: Checkout,
        now: Instant,
        zone: ZoneId,
    ): PaymentLinkStart? =
        synchronized(this) {
            checkout.takeIf { it.kind == kind && it.sessionRevision == revision }?.linkStart(now, zone)
        }

    /**
     * Adds one unit of [product] with [tax], joining an existing line for the same product at the same price and tax
     * (for a [SaleKind.singleItem] kind, it replaces the cart instead). The tax rate's name and rate are copied, so later edits to the
     * rate do not change the cart.
     */
    fun addProduct(
        product: ProductEntity,
        tax: TaxRateEntity,
    ) = edited {
        _cart.update { base(it).addProduct(CartProduct(product.id, product.name, product.sku, product.priceMinor, applied(tax)), newKey) }
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
    ) = edited { _cart.update { base(it).addCustom(name, amountMinor, applied(tax), newKey()) } }

    private fun base(cart: Cart) = if (singleItem) Cart() else cart

    private fun applied(tax: TaxRateEntity) = AppliedTax(tax.name, tax.rateMilliPercent)

    /**
     * Sets the quantity of the line with [key], capped at [Cart.MAX_QUANTITY] (at one for a [SaleKind.singleItem] kind); zero or
     * less removes the line.
     */
    fun setQuantity(
        key: String,
        quantity: Int,
    ) = edited { _cart.update { it.setQuantity(key, if (singleItem) quantity.coerceAtMost(1) else quantity) } }

    /** Removes the line with [key]; an unknown key changes nothing. */
    fun remove(key: String) = edited { _cart.update { it.remove(key) } }

    /** Updates the checkout form atomically; [transform] must not have side effects, as it can run more than once. */
    fun updateForm(transform: (CheckoutForm) -> CheckoutForm) = edited { _form.update(transform) }

    /** Preserves displayed unit prices when moving from [before] to [after]; increments the originating revision. */
    internal fun reprice(
        before: CurrencySpec,
        after: CurrencySpec,
    ) = edited {
        _cart.update { cart -> cart.copy(lines = cart.lines.map { it.copy(unitPrice = after.toMinor(before.toMajor(it.unitPrice))) }) }
    }

    /** Empties the cart and the checkout form, for the next sale. */
    fun clear() =
        edited {
            _cart.value = Cart()
            _form.value = CheckoutForm()
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
            snapshot.value = Snapshot(_cart.value, _form.value, revision)
            Unit
        }
}
