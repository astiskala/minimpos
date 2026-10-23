package app.minimpos.app.feature

import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.feature.sale.SaleCartQuantities
import app.minimpos.app.feature.sale.SaleUiState
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.CartProduct
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SaleBrowseTest {
    @Test
    fun `quantity index follows only its immutable cart and copied states reject stale indexes`() {
        val initial = SaleUiState()
        val cart = initial.cart.addProduct(CartProduct(1, "Coffee", null, 100, AppliedTax("None", 0))) { "one" }
        val quantities = SaleCartQuantities(cart)
        val state = initial.copy(cart = cart, quantities = quantities)
        assertThat(quantities.matches(cart)).isTrue()
        assertThat(quantities.matches(cart.copy())).isFalse()
        assertThat(state.copy(query = "Coffee").quantityInCart(1)).isEqualTo(1)
        assertThat(state.copy(cart = cart.setQuantity("one", 3)).quantityInCart(1)).isEqualTo(3)
        assertThat(state.quantityInCart(1)).isEqualTo(1)
    }

    @Test
    fun `cart and display changes reuse filtering while copied search and products invalidate it`() {
        val coffee = ProductEntity(id = 1, name = "Coffee", priceMinor = 100, taxRateId = 1, sku = "BEAN")
        val tea = coffee.copy(id = 2, name = "Tea", sku = null)
        val initial = SaleUiState(products = listOf(coffee, tea))
        val withCart = initial.copy(cart = initial.cart.addProduct(CartProduct(1, "Coffee", null, 100, AppliedTax("None", 0))) { "one" })
        assertThat(withCart.visibleProducts).isSameInstanceAs(initial.visibleProducts)
        assertThat(withCart.quantityInCart(1)).isEqualTo(1)
        assertThat(initial.copy(query = "tea").visibleProducts).containsExactly(tea)
        assertThat(initial.copy(products = listOf(tea)).hasSkus).isFalse()
    }
}
