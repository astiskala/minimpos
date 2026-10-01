package io.minimpos.app.payment

import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.Cart
import io.minimpos.core.cart.CartProduct
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * What the operator has entered on the checkout screen, kept while going back to the cart and forward again.
 *
 * @property transactionReference The merchant reference typed in; blank generates one.
 * @property customerReference The customer reference typed in (only asked for when it is the shopper reference).
 * @property email The shopper's email typed in, for the receipt and possibly the shopper reference.
 * @property tokenize Null until the operator touches the switch; then the settings default no longer applies.
 */
data class CheckoutForm(
    val transactionReference: String = "",
    val customerReference: String = "",
    val email: String = "",
    val tokenize: Boolean? = null,
)

/**
 * The sale being rung up, shared by the sale, checkout and payment screens. It lives in the [io.minimpos.app.AppContainer]
 * so the cart survives navigation, and is cleared once the payment is approved. Updates are atomic and thread-safe.
 */
class SaleSession(
    /**
     * Holds at most one item, with a quantity of one, as a pre-authorisation does: adding a product or custom item
     * replaces whatever is in the cart.
     */
    val singleItem: Boolean = false,
    /** Generates cart line keys; tests make them predictable. */
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val _cart = MutableStateFlow(Cart())

    /** The current cart. */
    val cart: StateFlow<Cart> = _cart.asStateFlow()

    private val _checkout = MutableStateFlow(CheckoutForm())

    /** The current checkout form. */
    val checkout: StateFlow<CheckoutForm> = _checkout.asStateFlow()

    /**
     * Adds one unit of [product] with [tax], joining an existing line for the same product at the same price and tax
     * (with [singleItem], it replaces the cart instead). The tax rate's name and rate are copied, so later edits to the
     * rate do not change the cart.
     */
    fun addProduct(
        product: ProductEntity,
        tax: TaxRateEntity,
    ) = _cart.update { base(it).addProduct(CartProduct(product.id, product.name, product.sku, product.priceMinor, applied(tax)), newKey) }

    /**
     * Adds a custom item of [amountMinor] (minor units, positive) with [tax] as a new line (with [singleItem], it
     * replaces the cart instead).
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
     * Sets the quantity of the line with [key], capped at [Cart.MAX_QUANTITY] (at one with [singleItem]); zero or less
     * removes the line.
     */
    fun setQuantity(
        key: String,
        quantity: Int,
    ) = _cart.update { it.setQuantity(key, if (singleItem) quantity.coerceAtMost(1) else quantity) }

    /** Removes the line with [key]; an unknown key changes nothing. */
    fun remove(key: String) = _cart.update { it.remove(key) }

    /** Updates the checkout form atomically; [transform] must not have side effects, as it can run more than once. */
    fun updateCheckout(transform: (CheckoutForm) -> CheckoutForm) = _checkout.update(transform)

    /** Empties the cart and the checkout form, for the next sale. */
    fun clear() {
        _cart.value = Cart()
        _checkout.value = CheckoutForm()
    }
}
