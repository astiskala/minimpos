package app.minimpos.core.cart

import app.minimpos.core.tax.TaxAmounts
import app.minimpos.core.tax.TaxMode
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CartTest {
    private val gst = AppliedTax("GST", 10_000)
    private val free = AppliedTax("GST-free", 0)
    private var counter = 0

    private fun key() = "k${counter++}"

    @Test
    fun `adding the same product merges quantities`() {
        val cart =
            Cart()
                .addProduct(CartProduct(1, "Flat white", null, 450, gst), ::key)
                .addProduct(CartProduct(1, "Flat white", null, 450, gst), ::key)
                .addProduct(CartProduct(2, "Muffin", "SKU2", 500, free), ::key)
        assertThat(
            cart.lines.map {
                it.name to it.quantity
            },
        ).containsExactly("Flat white" to 2, "Muffin" to 1).inOrder()
    }

    @Test
    fun `custom items are separate lines and need a positive amount`() {
        val cart = Cart().addCustom("Custom", 300, gst, "a").addCustom("Custom", 300, gst, "b")
        assertThat(cart.lines.map { it.productId }).containsExactly(null, null)
        assertThrows(IllegalArgumentException::class.java) { Cart().addCustom("Zero", 0, gst, "c") }
    }

    @Test
    fun `quantity changes clamp and zero removes`() {
        val cart = Cart().addProduct(CartProduct(1, "Tea", null, 400, gst), ::key)
        val lineKey = cart.lines.single().key
        assertThat(
            cart
                .setQuantity(lineKey, 5000)
                .lines
                .single()
                .quantity,
        ).isEqualTo(Cart.MAX_QUANTITY)
        assertThat(cart.setQuantity(lineKey, 0).lines).isEmpty()
        assertThat(
            cart
                .setQuantity("other", 3)
                .lines
                .single()
                .quantity,
        ).isEqualTo(1)
        assertThat(cart.remove(lineKey).lines).isEmpty()
    }

    @Test
    fun `inclusive totals round tax per line`() {
        val cart =
            Cart()
                .addProduct(CartProduct(1, "Flat white", null, 450, gst), ::key)
                .addProduct(CartProduct(1, "Flat white", null, 450, gst), ::key)
                .addProduct(CartProduct(2, "Milk", null, 300, free), ::key)
        val totals = cart.totals(TaxMode.INCLUSIVE)
        assertThat(totals.amounts).isEqualTo(TaxAmounts(net = 1118, tax = 82, gross = 1200))
        assertThat(totals.itemCount).isEqualTo(3)
        assertThat(totals.isEmpty).isFalse()
        assertThat(totals.lines.map { it.amounts }).containsExactly(TaxAmounts(818, 82, 900), TaxAmounts(300, 0, 300)).inOrder()
    }

    @Test
    fun `exclusive totals add tax per line`() {
        val totals =
            Cart()
                .addProduct(CartProduct(1, "Widget", null, 333, AppliedTax("Sales tax", 8_875)), ::key)
                .totals(TaxMode.EXCLUSIVE)
        assertThat(totals.amounts).isEqualTo(TaxAmounts(333, 30, 363))
        assertThat(Cart().totals(TaxMode.EXCLUSIVE).isEmpty).isTrue()
    }

    @Test
    fun `untaxed lines carry no tax`() {
        val cart =
            Cart()
                .addProduct(CartProduct(1, "Flat white", null, 450, gst), ::key)
                .addProduct(CartProduct(2, "Stamp", null, 120, AppliedTax.NONE), ::key)
        val totals = cart.totals(TaxMode.EXCLUSIVE)
        assertThat(totals.amounts).isEqualTo(TaxAmounts(net = 570, tax = 45, gross = 615))
        assertThat(totals.lines.last().amounts).isEqualTo(TaxAmounts(120, 0, 120))
    }

    @Test
    fun `with tax switched off no line is taxed`() {
        val cart = Cart().addProduct(CartProduct(1, "Flat white", null, 450, gst), ::key).addCustom("Gift", 1_000, free, "c")
        val totals = cart.totals(TaxMode.EXCLUSIVE, chargeTax = false)
        assertThat(totals.amounts).isEqualTo(TaxAmounts(net = 1_450, tax = 0, gross = 1_450))
        assertThat(totals.lines.map { it.line.tax }).containsExactly(AppliedTax.NONE, AppliedTax.NONE)
        // The cart itself keeps each line's tax for when tax is switched back on.
        assertThat(cart.totals(TaxMode.EXCLUSIVE).amounts.tax).isEqualTo(45)
    }
}
