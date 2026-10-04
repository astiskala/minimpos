package io.github.astiskala.minimpos.app.payment

import android.database.sqlite.SQLiteConstraintException
import androidx.datastore.core.DataStore
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.PaymentSettings
import io.github.astiskala.minimpos.app.data.settings.PricingChange
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.tax.TaxMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PricingChangesTest {
    private val env = TestEnvironment(dispatcher = UnconfinedTestDispatcher())
    private val container = env.container
    private val store = FailingSettings()
    private val settings = SettingsRepository(store)
    private val sessions = SaleKind.entries.map { container.session(it) }
    private var busy = false
    private val operations = PricingChanges(settings, container.catalog, { CurrencySpec.of(it.payment.currencyCode) }, sessions) { busy }

    @After
    fun tearDown() = env.close()

    private fun seed(price: Long = 450): ProductEntity =
        await {
            val tax = TaxRateEntity(container.catalog.saveTaxRate(TaxRateEntity(name = "Tax", rateMilliPercent = 0)), "Tax", 0)
            val id = container.catalog.saveProduct(ProductEntity(name = "Tea", priceMinor = price, taxRateId = tax.id))
            val product = checkNotNull(container.catalog.product(id))
            sessions.forEach { it.addProduct(product, tax) }
            product
        }

    private fun preview(): PricingChange =
        checkNotNull(await { operations.update { it.copy(payment = it.payment.copy(currencyCode = "JPY")) } })

    @Test
    fun `confirmation preserves displayed prices and reprices both sessions only once`() {
        val product = seed()
        val change = preview()
        assertThat(change.prices).containsExactly(product.id, 5L)
        assertThat(store.data.value.pricingChange).isNull()
        assertThat(await { container.catalog.product(product.id) }!!.priceMinor).isEqualTo(450)
        assertThat(await { operations.confirm(change) }).isTrue()
        assertThat(store.data.value.payment.currencyCode).isEqualTo("JPY")
        assertThat(store.data.value.pricingChange).isNull()
        assertThat(await { container.catalog.product(product.id) }!!.priceMinor).isEqualTo(5)
        sessions.forEach {
            assertThat(
                it.cart.value.lines
                    .single()
                    .unitPrice,
            ).isEqualTo(5)
        }
        await { operations.recover() }
        assertThat(await { operations.confirm(change) }).isFalse()
        sessions.forEach {
            assertThat(
                it.cart.value.lines
                    .single()
                    .unitPrice,
            ).isEqualTo(5)
        }
    }

    @Test
    fun `ordinary settings persist while tax-style changes wait for confirmation`() {
        seed()
        assertThat(await { operations.update { it.copy(receipt = it.receipt.copy(title = "Shop")) } }).isNull()
        assertThat(store.data.value.receipt.title).isEqualTo("Shop")
        val change = checkNotNull(await { operations.update { it.copy(payment = it.payment.copy(taxMode = TaxMode.EXCLUSIVE)) } })
        assertThat(store.data.value.payment.taxMode).isEqualTo(TaxMode.INCLUSIVE)
        assertThat(await { operations.confirm(change) }).isTrue()
        assertThat(store.data.value.payment.taxMode).isEqualTo(TaxMode.EXCLUSIVE)
        sessions.forEach {
            assertThat(
                it.cart.value.lines
                    .single()
                    .unitPrice,
            ).isEqualTo(450)
        }
    }

    @Test
    fun `busy requests, stale catalogues, and prices rounded to zero cannot commit`() {
        val product = seed()
        busy = true
        assertThat(await { operations.update { it.copy(payment = it.payment.copy(currencyCode = "JPY")) } }).isNull()
        busy = false
        val change = preview()
        busy = true
        assertThat(await { operations.confirm(change) }).isFalse()
        busy = false
        await { container.catalog.saveProduct(product.copy(priceMinor = 600)) }
        assertThat(await { operations.confirm(change) }).isFalse()
        await { container.catalog.saveProduct(product.copy(priceMinor = 1)) }
        assertThat(await { operations.update { it.copy(payment = it.payment.copy(currencyCode = "JPY")) } }).isNull()
        assertThat(store.data.value.pricingChange).isNull()
    }

    @Test
    fun `failure before journalling changes neither prices nor sessions`() {
        val product = seed()
        val change = preview()
        store.failAt = 1
        assertThrows(IOException::class.java) { runBlocking { operations.confirm(change) } }
        assertThat(store.data.value.pricingChange).isNull()
        assertThat(await { container.catalog.product(product.id) }!!.priceMinor).isEqualTo(450)
        sessions.forEach {
            assertThat(
                it.cart.value.lines
                    .single()
                    .unitPrice,
            ).isEqualTo(450)
        }
        store.failAt = null
        assertThat(await { operations.confirm(change) }).isTrue()
    }

    @Test
    fun `catalogue failure leaves a blocked journal that recovery replays atomically`() {
        val product = seed()
        val change = preview()
        val database = container.database.openHelper.writableDatabase
        database.execSQL("CREATE TRIGGER fail_prices BEFORE UPDATE ON products BEGIN SELECT RAISE(ABORT, 'prices failed'); END")
        assertThrows(SQLiteConstraintException::class.java) { runBlocking { operations.confirm(change) } }
        assertThat(store.data.value.pricingChange).isEqualTo(change)
        assertThat(PricingChanges.ready(store.data.value)).isFalse()
        assertThat(await { operations.confirm(change) }).isFalse()
        assertThat(await { operations.update { it.copy(receipt = it.receipt.copy(title = "ignored")) } }).isNull()
        assertThat(await { container.catalog.product(product.id) }!!.priceMinor).isEqualTo(450)
        database.execSQL("DROP TRIGGER fail_prices")
        await { operations.recover() }
        assertThat(store.data.value.pricingChange).isNull()
        assertThat(await { container.catalog.product(product.id) }!!.priceMinor).isEqualTo(5)
    }

    @Test
    fun `failure publishing pricing does not reprice sessions twice on replay`() {
        val product = seed()
        val change = preview()
        store.failAt = 2
        assertThrows(IOException::class.java) { runBlocking { operations.confirm(change) } }
        assertThat(store.data.value.pricingChange).isEqualTo(change)
        assertThat(store.data.value.payment.currencyCode).isEqualTo("USD")
        assertThat(await { container.catalog.product(product.id) }!!.priceMinor).isEqualTo(5)
        sessions.forEach {
            assertThat(
                it.cart.value.lines
                    .single()
                    .unitPrice,
            ).isEqualTo(5)
        }
        store.failAt = null
        await { operations.recover() }
        sessions.forEach {
            assertThat(
                it.cart.value.lines
                    .single()
                    .unitPrice,
            ).isEqualTo(5)
        }
        assertThat(PricingChanges.ready(store.data.value)).isTrue()
    }

    @Test
    fun `startup completes a persisted journal without a settings screen`() {
        val product = seed()
        val change = preview()
        env.updateSettings { it.copy(pricingChange = change) }
        container.start()
        await { container.settings.settings.first { it.pricingChange == null && it.payment.currencyCode == "JPY" } }
        assertThat(await { container.catalog.product(product.id) }!!.priceMinor).isEqualTo(5)
    }

    private class FailingSettings : DataStore<AppSettings> {
        override val data = MutableStateFlow(AppSettings(payment = PaymentSettings(currencyCode = "USD")))
        var failAt: Int? = null
        private var writes = 0

        override suspend fun updateData(transform: suspend (AppSettings) -> AppSettings): AppSettings {
            val updated = transform(data.value)
            writes += 1
            if (writes == failAt) throw IOException("settings write failed")
            data.value = updated
            return updated
        }
    }
}
