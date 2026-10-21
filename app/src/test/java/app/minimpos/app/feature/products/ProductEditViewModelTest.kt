package app.minimpos.app.feature.products

import app.minimpos.app.TestEnvironment
import app.minimpos.app.closeViewModels
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.TaxRateEntity
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ProductEditViewModelTest {
    private val env = TestEnvironment()
    private val dispatcher = UnconfinedTestDispatcher()
    private val models = mutableListOf<ProductEditViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        closeViewModels(models)
        env.close()
        Dispatchers.resetMain()
    }

    private suspend fun editor(
        lookup: suspend (String) -> ProductEntity? = { env.container.catalog.productBySku(it) },
    ): ProductEditViewModel {
        env.container.catalog.saveTaxRate(TaxRateEntity(name = "Zero", rateMilliPercent = 0))
        return ProductEditViewModel(null, null, env.container.catalog, env.container.settingsState, env.container::currency, lookup)
            .also {
                models += it
                it.state.first { state -> state.loaded && !state.skuChecking }
                it.update { form -> form.copy(name = "Coffee", price = "4.50") }
            }
    }

    @Test
    fun `the first loaded form already blocks saving until its initial SKU is checked`() =
        runTest(dispatcher) {
            env.container.catalog.saveTaxRate(TaxRateEntity(name = "Zero", rateMilliPercent = 0))
            val pending = CompletableDeferred<ProductEntity?>()
            val vm =
                ProductEditViewModel(null, "NEW1", env.container.catalog, env.container.settingsState, env.container::currency) {
                    pending.await()
                }.also(models::add)
            val firstLoaded = vm.state.first { it.loaded }
            assertThat(firstLoaded.skuChecking).isTrue()
            pending.complete(null)
            vm.state.first { it.loaded && !it.skuChecking }
        }

    @Test
    fun `pending validation blocks saving and a late obsolete lookup cannot replace the current result`() =
        runTest(dispatcher) {
            val old = CompletableDeferred<ProductEntity?>()
            val vm = editor { sku -> if (sku == "old") withContext(NonCancellable) { old.await() } else null }
            vm.update { it.copy(sku = "old") }
            assertThat(vm.state.value.skuChecking).isTrue()
            vm.save { error("pending validation saved") }
            assertThat(
                env.container.catalog.products
                    .first(),
            ).isEmpty()
            vm.update { it.copy(sku = "new") }
            vm.state.first { !it.skuChecking }
            old.complete(ProductEntity(id = 99, name = "Other", priceMinor = 100, taxRateId = 1))
            assertThat(vm.state.value.form.sku).isEqualTo("new")
            assertThat(vm.state.value.skuInUse).isFalse()
            assertThat(vm.state.value.valid).isTrue()
        }

    @Test
    fun `duplicate introduced after validation becomes a field error without saving or navigating`() =
        runTest(dispatcher) {
            val vm = editor()
            vm.update { it.copy(sku = "duplicate") }
            vm.state.first { !it.skuChecking }
            env.container.catalog.saveProduct(
                ProductEntity(name = "Other", priceMinor = 100, taxRateId = vm.state.value.form.taxRateId!!, sku = "duplicate"),
            )
            var navigated = false
            vm.save { navigated = true }
            val result = vm.state.first { it.skuInUse && !it.saving }
            assertThat(result.valid).isFalse()
            assertThat(navigated).isFalse()
            assertThat(
                env.container.catalog.products
                    .first(),
            ).hasSize(1)
            vm.update { it.copy(sku = "unique") }
            vm.state.first { !it.skuChecking }
            vm.save { navigated = true }
            vm.state.first { !it.saving }
            assertThat(navigated).isTrue()
            assertThat(
                env.container.catalog.products
                    .first(),
            ).hasSize(2)
        }

    @Test
    fun `database failure clears saving and exposes a typed error`() =
        runTest(dispatcher) {
            val vm = editor()
            env.container.database
                .catalogDao()
                .delete(
                    env.container.catalog.taxRates
                        .first()
                        .single(),
                )
            vm.save { error("failed save navigated") }
            val result = vm.state.first { it.failure != null && !it.saving }
            assertThat(result.failure).isEqualTo(Failure.Device(DeviceFault.DATABASE))
        }
}
