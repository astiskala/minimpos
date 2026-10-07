package io.github.astiskala.minimpos.app.feature

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.closeViewModels
import io.github.astiskala.minimpos.app.data.db.CaptureStatus
import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.repo.HistoryItem
import io.github.astiskala.minimpos.app.feature.history.HistoryFilter
import io.github.astiskala.minimpos.app.feature.history.HistoryUiState
import io.github.astiskala.minimpos.app.feature.history.HistoryViewModel
import io.github.astiskala.minimpos.app.feature.history.PaymentMethodFilter
import io.github.astiskala.minimpos.app.refund.HistoryAccounting
import io.github.astiskala.minimpos.app.refund.HistoryActivityKind
import io.github.astiskala.minimpos.core.payment.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HistorySearchViewModelTest {
    private val env = TestEnvironment()
    private val db = env.container.database
    private val dispatcher = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<HistoryViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() {
        closeViewModels(viewModels)
        env.close()
        Dispatchers.resetMain()
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

    private suspend fun HistoryViewModel.ids(predicate: (HistoryUiState) -> Boolean = { true }) =
        state.first { it.loaded && predicate(it) }.days.flatMap { day -> day.items.map { it.id } }

    private fun historyViewModel() = HistoryViewModel(env.container.history).also(viewModels::add)

    @Test
    fun `searches and filters by payment method, and shows everything again`() =
        runTest(dispatcher) {
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
            val vm = historyViewModel()
            val all = vm.state.first { it.loaded && it.days.isNotEmpty() }
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
            val sales = vm.state.first { it.filter == HistoryFilter.SALES }
            assertThat(sales.days.flatMap { it.items }.map { it.id }).containsExactly("a")
            assertThat(
                sales.days
                    .single()
                    .totals.salesMinor,
            ).containsExactly("AUD", 3400L)

            vm.setQuery("nothing like it")
            val none = vm.state.first { it.query == "nothing like it" }
            assertThat(none.days).isEmpty()
            assertThat(none.narrowed).isTrue()

            vm.showAll()
            val again = vm.state.first { it.query.isEmpty() && it.method == null && it.filter == HistoryFilter.ALL }
            assertThat(again.days.flatMap { it.items }).hasSize(4)
        }

    @Test
    fun `capture-only processing day links to original sale without moving original hold`() =
        runTest(dispatcher) {
            val monday = Instant.parse("2026-10-05T12:00:00Z").toEpochMilli()
            val tuesday = monday + 86_400_000
            db.saleDao().insert(
                sale("tip", 2000, "visa", "visa").copy(
                    createdAt = monday,
                    processedAt = monday,
                    tipOnReceipt = true,
                    tipMinor = 400,
                    capturedMinor = 2400,
                    captureStatus = CaptureStatus.REQUESTED,
                    captureProcessedAt = tuesday,
                ),
            )
            val vm = HistoryViewModel(env.container.history, zone = { ZoneOffset.UTC }).also(viewModels::add)
            val days = vm.state.first { it.loaded }.days
            assertThat(days.map { it.date.toString() }).containsExactly("2026-10-06", "2026-10-05").inOrder()
            assertThat(days.first().captures).containsExactly("tip")
            assertThat(
                days
                    .first()
                    .items
                    .single()
                    .id,
            ).isEqualTo("tip")
            assertThat(days.first().totals.salesMinor).containsExactly("AUD", 2400L)
            assertThat(days.last().totals.salesMinor).isEmpty()
            assertThat(days.last().totals.preAuthsMinor).containsExactly("AUD", 2000L)
        }

    @Test
    fun `issues filter agrees with accounting across sale refund and capture outcomes`() =
        runTest(dispatcher) {
            val sales =
                SaleStatus.entries.map { sale("sale-$it", 1000, "visa", "visa").copy(status = it) } +
                    CaptureStatus.entries.map {
                        sale("capture-$it", 1000, "visa", "visa").copy(tipOnReceipt = true, captureStatus = it, capturedMinor = 1000)
                    } +
                    listOf(
                        sale(
                            "adjustment",
                            1000,
                            "visa",
                            "visa",
                        ).copy(adjustmentPending = true, adjustmentKey = "adjust", adjustmentAmountMinor = 1100),
                        sale("two-issues", 1000, "visa", "visa").copy(
                            tipOnReceipt = true,
                            captureStatus = CaptureStatus.UNKNOWN,
                            adjustmentPending = true,
                            adjustmentKey = "adjust-both",
                            adjustmentAmountMinor = 1100,
                        ),
                    )
            val refunds =
                RefundStatus.entries.map {
                    RefundEntity(
                        id = "refund-$it",
                        saleId = null,
                        createdAt = 1000,
                        merchantReference = "R-$it",
                        originalTransactionId = "T",
                        originalTimestamp = "2026-10-01T00:00:00Z",
                        originalReference = null,
                        currency = "AUD",
                        amountMinor = 100,
                        full = false,
                        status = it,
                    )
                }
            sales.forEach { db.saleDao().insert(it) }
            refunds.forEach { db.refundDao().insert(it) }
            val items = sales.map { HistoryItem.Sale(it) } + refunds.map { HistoryItem.Refund(it) }
            val expected =
                HistoryAccounting
                    .activities(items)
                    .filter { it.kind == HistoryActivityKind.ISSUE }
                    .map { it.item.id }
                    .distinct()
            val vm = historyViewModel()
            vm.setFilter(HistoryFilter.ISSUES)
            assertThat(vm.ids { it.filter == HistoryFilter.ISSUES }.distinct()).containsExactlyElementsIn(expected)
        }

    @Test
    fun `an empty history is not a search without matches`() =
        runTest(dispatcher) {
            val vm = historyViewModel()
            val state = vm.state.first { it.loaded }
            assertThat(state.hasHistory).isFalse()
            assertThat(state.methods).isEmpty()
        }
}
