package io.github.astiskala.minimpos.app.feature

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.feature.history.HistoryFilter
import io.github.astiskala.minimpos.app.feature.history.HistoryUiState
import io.github.astiskala.minimpos.app.feature.history.HistoryViewModel
import io.github.astiskala.minimpos.app.feature.history.PaymentMethodFilter
import io.github.astiskala.minimpos.core.payment.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistorySearchViewModelTest {
    private val env = TestEnvironment()
    private val db = env.container.database

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun sale(
        id: String,
        totalMinor: Long,
        brand: String,
        variant: String,
        customer: String? = null,
    ) = SaleEntity(
        id = id,
        createdAt = 1_000L,
        currency = "AUD",
        taxMode = "INCLUSIVE",
        netMinor = totalMinor,
        taxMinor = 0,
        totalMinor = totalMinor,
        status = SaleStatus.APPROVED,
        merchantReference = "REF-$id",
        customerReference = customer,
        paymentBrand = brand,
        paymentMethodVariant = variant,
        maskedPan = "411111 **** 1111",
    )

    private fun HistoryViewModel.ids(predicate: (HistoryUiState) -> Boolean = { true }) =
        await { state.first { it.loaded && predicate(it) } }.days.flatMap { day -> day.items.map { it.id } }

    @Test
    fun `searches and filters by payment method, and shows everything again`() {
        await {
            db.saleDao().insert(sale("a", 3400, "visa", "visa_applepay", customer = "CUST-1042"))
            db.saleDao().insert(sale("b", 1150, "mc", "mc"))
            db.saleDao().insert(sale("c", 2950, "visa", "visa"))
            db.refundDao().insert(
                RefundEntity(
                    id = "r",
                    saleId = "a",
                    createdAt = 2_000,
                    merchantReference = "R-REF-a",
                    originalTransactionId = "T.P",
                    originalTimestamp = "2026-10-01T00:00:00Z",
                    originalReference = "REF-a",
                    currency = "AUD",
                    amountMinor = 1100,
                    full = false,
                    status = RefundStatus.REQUESTED,
                ),
            )
        }
        val vm = HistoryViewModel(env.container.history)
        val all = await { vm.state.first { it.loaded && it.days.isNotEmpty() } }
        assertThat(all.hasHistory).isTrue()
        assertThat(all.narrowed).isFalse()
        assertThat(all.methods.map { it.label }).containsExactly("Mastercard", "Visa", "Apple Pay").inOrder()

        vm.setQuery("cust-1042")
        assertThat(vm.ids { it.query == "cust-1042" }).containsExactly("r", "a")

        vm.setQuery("11.50")
        assertThat(vm.ids { it.query == "11.50" }).containsExactly("b")

        vm.setQuery("")
        vm.setMethod(PaymentMethodFilter.Brand("visa"))
        assertThat(vm.ids { it.method != null && it.query.isEmpty() }).containsExactly("r", "a", "c")
        vm.setMethod(PaymentMethodFilter.InWallet(Wallet.APPLE_PAY))
        assertThat(vm.ids { it.method is PaymentMethodFilter.InWallet }).containsExactly("r", "a")
        vm.setFilter(HistoryFilter.SALES)
        val sales = await { vm.state.first { it.filter == HistoryFilter.SALES } }
        assertThat(sales.days.flatMap { it.items }.map { it.id }).containsExactly("a")
        assertThat(
            sales.days
                .single()
                .totals.salesMinor,
        ).containsExactly("AUD", 3400L)

        vm.setQuery("nothing like it")
        val none = await { vm.state.first { it.query == "nothing like it" } }
        assertThat(none.days).isEmpty()
        assertThat(none.narrowed).isTrue()

        vm.showAll()
        val again = await { vm.state.first { it.query.isEmpty() && it.method == null && it.filter == HistoryFilter.ALL } }
        assertThat(again.days.flatMap { it.items }).hasSize(4)
    }

    @Test
    fun `an empty history is not a search without matches`() {
        val vm = HistoryViewModel(env.container.history)
        val state = await { vm.state.first { it.loaded } }
        assertThat(state.hasHistory).isFalse()
        assertThat(state.methods).isEmpty()
    }
}
