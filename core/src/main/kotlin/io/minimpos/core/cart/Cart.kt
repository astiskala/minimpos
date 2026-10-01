package io.minimpos.core.cart

import io.minimpos.core.tax.TaxAmounts
import io.minimpos.core.tax.TaxMode
import io.minimpos.core.tax.TaxRates

/**
 * A tax rate snapshot as applied to a line; later edits to the catalogue do not change past sales.
 *
 * @property name the rate's label, e.g. "GST"; empty for [NONE].
 * @property rateMilliPercent the rate in thousandths of a percent (10% = 10_000), see [TaxRates].
 */
data class AppliedTax(
    val name: String,
    val rateMilliPercent: Int,
) {
    /** Holds [NONE]. */
    companion object {
        /** Tax is not charged at all (tax switched off); items without tax otherwise carry a named 0% rate. */
        val NONE = AppliedTax("", 0)
    }
}

/**
 * A catalogue product as it goes into a cart.
 *
 * @property id the product's catalogue id; lines for the same id, price and tax merge.
 * @property name the product name shown on the sale screen and the receipt.
 * @property sku the product's barcode or SKU, if it has one.
 * @property unitPrice the price of one unit in the currency's minor units.
 * @property tax the tax rate snapshot applied to the line.
 */
data class CartProduct(
    val id: Long,
    val name: String,
    val sku: String?,
    val unitPrice: Long,
    val tax: AppliedTax,
)

/**
 * One line of a [Cart]: a product or custom item with its quantity.
 *
 * @property key identifies the line within the cart (supplied by the caller, unique per cart) for [Cart.setQuantity]
 *   and [Cart.remove].
 * @property productId the catalogue product's id, or null for a custom item keyed in at checkout.
 * @property name the item name shown on the sale screen and the receipt.
 * @property sku the product's barcode or SKU, or null (always null for custom items).
 * @property unitPrice the price of one unit in the currency's minor units; net or gross depending on the [TaxMode].
 * @property quantity the number of units, kept between 1 and [Cart.MAX_QUANTITY] by [Cart].
 * @property tax the tax rate snapshot for the line.
 */
data class CartLine(
    val key: String,
    val productId: Long?,
    val name: String,
    val sku: String?,
    val unitPrice: Long,
    val quantity: Int,
    val tax: AppliedTax,
) {
    /** [unitPrice] times [quantity], before [TaxRates.apply] splits it into net, tax and gross. */
    val amount: Long get() = unitPrice * quantity
}

/**
 * A cart line with its tax calculated.
 *
 * @property line the cart line.
 * @property amounts the line's net, tax and gross amounts, with tax rounded for the line as a whole.
 */
data class PricedLine(
    val line: CartLine,
    val amounts: TaxAmounts,
)

/**
 * The totals for all lines taxed at one rate, as listed in a receipt's tax breakdown.
 *
 * @property tax the rate the lines share.
 * @property amounts the sum of those lines' amounts.
 */
data class TaxBreakdown(
    val tax: AppliedTax,
    val amounts: TaxAmounts,
)

/**
 * A priced cart, as produced by [Cart.totals].
 *
 * @property mode whether the prices included tax or had it added.
 * @property lines every line with its amounts, in cart order.
 * @property amounts the sum of all lines; its gross is the amount to charge.
 */
data class CartTotals(
    val mode: TaxMode,
    val lines: List<PricedLine>,
    val amounts: TaxAmounts,
) {
    /** The number of units across all lines (two coffees and a cake count as three). */
    val itemCount: Int get() = lines.sumOf { it.line.quantity }

    /** True when the cart has no lines. */
    val isEmpty: Boolean get() = lines.isEmpty()
}

/**
 * Immutable sale basket. Product lines merge by product id; custom lines are always separate.
 *
 * @property lines the lines in the order they were added.
 */
data class Cart(
    val lines: List<CartLine> = emptyList(),
) {
    /**
     * Adds one unit of [product]. It joins an existing line for the same product at the same price and tax; otherwise
     * it starts a new line, and only then is [key] called for the line's key.
     */
    fun addProduct(
        product: CartProduct,
        key: () -> String,
    ): Cart {
        val existing =
            lines.indexOfFirst { it.productId == product.id && it.unitPrice == product.unitPrice && it.tax == product.tax }
        if (existing >= 0) return setQuantity(lines[existing].key, lines[existing].quantity + 1)
        return copy(lines = lines + CartLine(key(), product.id, product.name, product.sku, product.unitPrice, 1, product.tax))
    }

    /**
     * Adds a custom item (one unit of [unitPrice] minor units) as a new line with the given [key].
     *
     * @throws IllegalArgumentException if [unitPrice] is not positive.
     */
    fun addCustom(
        name: String,
        unitPrice: Long,
        tax: AppliedTax,
        key: String,
    ): Cart {
        require(unitPrice > 0) { "Custom items need a positive amount" }
        return copy(lines = lines + CartLine(key, null, name, null, unitPrice, 1, tax))
    }

    /**
     * Sets the quantity of the line with [key], capped at [MAX_QUANTITY]; zero or less removes the line. An unknown
     * key leaves the cart unchanged.
     */
    fun setQuantity(
        key: String,
        quantity: Int,
    ): Cart =
        if (quantity <= 0) {
            remove(key)
        } else {
            copy(lines = lines.map { if (it.key == key) it.copy(quantity = quantity.coerceAtMost(MAX_QUANTITY)) else it })
        }

    /** Removes the line with [key], if there is one. */
    fun remove(key: String): Cart = copy(lines = lines.filterNot { it.key == key })

    /**
     * Prices the cart in [mode]: each line's tax is rounded separately, then the lines are summed. With [chargeTax] off
     * (tax switched off in settings) every line is priced with [AppliedTax.NONE], so no line is taxed.
     */
    fun totals(
        mode: TaxMode,
        chargeTax: Boolean = true,
    ): CartTotals {
        val priced =
            (if (chargeTax) lines else lines.map { it.copy(tax = AppliedTax.NONE) })
                .map { PricedLine(it, TaxRates.apply(it.amount, it.tax.rateMilliPercent, mode)) }
        return CartTotals(mode, priced, priced.fold(TaxAmounts.ZERO) { acc, p -> acc + p.amounts })
    }

    /** Holds the quantity limit. */
    companion object {
        /** The highest quantity a line can have; larger quantities are capped. */
        const val MAX_QUANTITY = 999
    }
}
