package io.minimpos.app.payment

import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.Cart
import io.minimpos.core.cart.CartProduct
import io.minimpos.core.money.CurrencySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import java.util.UUID

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
 * lives in the [io.minimpos.app.AppContainer] so the cart survives navigation (and a sale being rung up survives a
 * pre-authorisation meanwhile); the payments' [TransactionLifecycle] clears it once a payment started from it is
 * approved. Updates are atomic and thread-safe.
 */
class SaleSession(
    /** What is being rung up; a [SaleKind.singleItem] kind holds at most one item, with a quantity of one. */
    val kind: SaleKind = SaleKind.SALE,
    /** Generates cart line keys; tests make them predictable. */
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val singleItem = kind.singleItem
    private val _cart = MutableStateFlow(Cart())

    /** The current cart. */
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    private val _form = MutableStateFlow(CheckoutForm())

    /** The current checkout form. */
    val form: StateFlow<CheckoutForm> = _form.asStateFlow()

    /**
     * The checkout of this session's cart and form with [settings], charged in the [currency] the settings give and
     * offering tipping on the receipt while [printerAvailable]; updated whenever any of them changes.
     */
    fun checkout(
        settings: Flow<AppSettings>,
        printerAvailable: Flow<Boolean>,
        currency: (AppSettings) -> CurrencySpec,
    ): Flow<Checkout> =
        combine(form, cart, settings, printerAvailable) { form, cart, appSettings, printer ->
            val payment = appSettings.payment
            Checkout(form, payment, cart.totals(payment.taxMode, payment.chargeTax), currency(appSettings), kind, printer)
        }

    /**
     * Adds one unit of [product] with [tax], joining an existing line for the same product at the same price and tax
     * (for a [SaleKind.singleItem] kind, it replaces the cart instead). The tax rate's name and rate are copied, so later edits to the
     * rate do not change the cart.
     */
    fun addProduct(
        product: ProductEntity,
        tax: TaxRateEntity,
    ) = _cart.update { base(it).addProduct(CartProduct(product.id, product.name, product.sku, product.priceMinor, applied(tax)), newKey) }

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
    ) = _cart.update { base(it).addCustom(name, amountMinor, applied(tax), newKey()) }

    private fun base(cart: Cart) = if (singleItem) Cart() else cart

    private fun applied(tax: TaxRateEntity) = AppliedTax(tax.name, tax.rateMilliPercent)

    /**
     * Sets the quantity of the line with [key], capped at [Cart.MAX_QUANTITY] (at one for a [SaleKind.singleItem] kind); zero or
     * less removes the line.
     */
    fun setQuantity(
        key: String,
        quantity: Int,
    ) = _cart.update { it.setQuantity(key, if (singleItem) quantity.coerceAtMost(1) else quantity) }

    /** Removes the line with [key]; an unknown key changes nothing. */
    fun remove(key: String) = _cart.update { it.remove(key) }

    /** Updates the checkout form atomically; [transform] must not have side effects, as it can run more than once. */
    fun updateForm(transform: (CheckoutForm) -> CheckoutForm) = _form.update(transform)

    /** Empties the cart and the checkout form, for the next sale. */
    fun clear() {
        _cart.value = Cart()
        _form.value = CheckoutForm()
    }
}
