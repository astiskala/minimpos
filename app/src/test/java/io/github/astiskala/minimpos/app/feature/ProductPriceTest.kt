package io.github.astiskala.minimpos.app.feature

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.feature.products.ProductEditUiState
import io.github.astiskala.minimpos.app.feature.products.ProductForm
import io.github.astiskala.minimpos.core.money.CurrencySpec
import org.junit.Test

class ProductPriceTest {
    @Test
    fun `product price parsing retains its syntax and rejects overflow and invalid precision`() {
        val state =
            ProductEditUiState(
                form = ProductForm(name = "Product", taxRateId = 1),
                currency = CurrencySpec.of("AUD"),
            )
        mapOf("4,50" to 450L, ".5" to 50L, "34." to 3_400L, "+1" to 100L, "1e2" to 10_000L).forEach { (price, minor) ->
            val entered = state.copy(form = state.form.copy(price = price))
            assertThat(entered.priceMinor).isEqualTo(minor)
            assertThat(entered.valid).isTrue()
        }
        listOf("1e17", "92233720368547758.08", "0", "-1", "1.001", "$1", "not a price").forEach { price ->
            val entered = state.copy(form = state.form.copy(price = price))
            assertThat(entered.priceMinor).isNull()
            assertThat(entered.valid).isFalse()
        }
        val zeroDecimals = state.copy(currency = CurrencySpec.of("JPY"))
        assertThat(zeroDecimals.copy(form = state.form.copy(price = "1.1")).priceMinor).isNull()
        assertThat(zeroDecimals.copy(form = state.form.copy(price = "100")).priceMinor).isEqualTo(100)
    }
}
