package io.minimpos.app.feature

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.db.CategoryEntity
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.feature.history.HistoryFilter
import io.minimpos.app.feature.history.HistoryViewModel
import io.minimpos.app.feature.history.SaleDetailViewModel
import io.minimpos.app.feature.sale.CheckoutViewModel
import io.minimpos.app.feature.sale.ResultMessages
import io.minimpos.app.feature.sale.SaleResultViewModel
import io.minimpos.app.feature.sale.SaleViewModel
import io.minimpos.app.payment.TransactionState
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

/** Pre-authorisations through the view models: rung up on their own, paid at checkout, kept apart in history, cancelled. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PreAuthorisationViewModelsTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val messages = ResultMessages("Printed", "Sent to %s")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD", tokenizeDefaultOn = false)) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun seed(): Triple<TaxRateEntity, ProductEntity, ProductEntity> =
        await {
            val taxId = container.catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
            val latte = container.catalog.saveProduct(ProductEntity(name = "Latte", priceMinor = 450, taxRateId = taxId, sku = "L1"))
            val deposit =
                container.catalog.saveProduct(
                    ProductEntity(
                        name = "Catering deposit",
                        priceMinor = 20_000,
                        taxRateId = taxId,
                        sku = "CD",
                        kind = SaleKind.PRE_AUTHORISATION,
                    ),
                )
            Triple(TaxRateEntity(taxId, "GST", 10_000), container.catalog.product(latte)!!, container.catalog.product(deposit)!!)
        }

    private fun preAuthViewModel() =
        SaleViewModel(container.catalog, container.preAuthSession, container.settingsState, container::currency, SaleKind.PRE_AUTHORISATION)

    /** Takes a pre-authorisation of [deposit] (taxed at [tax]) for customer CUST-1 and returns its sale ID. */
    private fun preAuthorise(
        deposit: ProductEntity,
        tax: TaxRateEntity,
    ): String {
        container.preAuthSession.addProduct(deposit, tax)
        val checkout =
            CheckoutViewModel(
                container.preAuthSession,
                container.payments,
                container.settingsState,
                container::currency,
                SaleKind.PRE_AUTHORISATION,
            )
        checkout.update { it.copy(customerReference = "CUST-1") }
        await { checkout.state.first { it.form.customerReference == "CUST-1" && !it.totals.isEmpty } }
        assertThat(checkout.pay()).isTrue()
        return await { (container.payments.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
    }

    @Test
    fun `each screen only shows the categories of its own products`() {
        val (tax, _, deposit) = seed()
        val (coffee, bookings, empty) =
            await {
                listOf("Coffee", "Bookings", "New").map { container.catalog.saveCategory(CategoryEntity(name = it)) }
            }
        await {
            container.catalog.saveProduct(ProductEntity(name = "Tea", priceMinor = 400, taxRateId = tax.id, categoryId = coffee))
            container.catalog.saveProduct(deposit.copy(categoryId = bookings))
        }
        val sale = SaleViewModel(container.catalog, container.saleSession, container.settingsState, container::currency)
        // A sale keeps categories that have no products yet, as before.
        assertThat(await { sale.state.first { it.categories.isNotEmpty() } }.categories.map { it.id }).containsExactly(coffee, empty)
        assertThat(await { preAuthViewModel().state.first { it.categories.isNotEmpty() } }.categories.map { it.id })
            .containsExactly(bookings)
    }

    @Test
    fun `the pre-authorise screen offers only pre-authorisation products, one at a time`() {
        val (_, _, deposit) = seed()
        val sale = SaleViewModel(container.catalog, container.saleSession, container.settingsState, container::currency)
        assertThat(await { sale.state.first { it.products.isNotEmpty() } }.products.map { it.name }).containsExactly("Latte")
        assertThat(await { sale.addBySku("CD") }).isNull()

        val preAuth = preAuthViewModel()
        val state = await { preAuth.state.first { it.products.isNotEmpty() } }
        assertThat(state.preAuthorisation).isTrue()
        assertThat(state.products).containsExactly(deposit)
        assertThat(await { preAuth.addBySku("L1") }).isNull()
        assertThat(await { preAuth.addBySku("CD") }).isEqualTo("Catering deposit")
        preAuth.add(deposit)
        assertThat(await { preAuth.state.first { it.cart.lines.isNotEmpty() } }.totals.itemCount).isEqualTo(1)
        assertThat(container.saleSession.cart.value.lines).isEmpty()
    }

    @Test
    fun `checkout saves the card by default, and the result clears only the pre-authorisation`() {
        val (tax, latte, deposit) = seed()
        val checkout =
            CheckoutViewModel(
                container.preAuthSession,
                container.payments,
                container.settingsState,
                container::currency,
                SaleKind.PRE_AUTHORISATION,
            )
        checkout.update { it.copy(customerReference = "CUST-1") }
        val form = await { checkout.state.first { it.form.customerReference == "CUST-1" } }
        assertThat(form.preAuthorisation).isTrue()
        // Saving the card is on for pre-authorisations, whatever the default for sales.
        assertThat(form.tokenize).isTrue()

        val id = preAuthorise(deposit, tax)
        val record = await { container.sales.get(id)!! }
        assertThat(record.sale.kind).isEqualTo(SaleKind.PRE_AUTHORISATION)
        assertThat(record.sale.storedPaymentMethodId).isNotNull()

        container.saleSession.addProduct(latte, tax)
        val result =
            SaleResultViewModel(
                id,
                container.sales,
                container.receipts,
                container.payments,
                container::session,
                container.settingsState,
                container.terminalStatus.state,
                messages,
            )
        assertThat(await { result.state.first { it.record != null } }.preAuthorisation).isTrue()
        await { container.preAuthSession.cart.first { it.lines.isEmpty() } }
        assertThat(container.saleSession.cart.value.lines).hasSize(1)
    }

    @Test
    fun `history keeps pre-authorisations apart, and cancels them once`() {
        val (tax, _, deposit) = seed()
        val id = preAuthorise(deposit, tax)
        container.payments.acknowledge()
        val history = HistoryViewModel(container.history)
        val day = await { history.state.first { it.days.isNotEmpty() } }.days.single()
        assertThat(day.totals.saleCount).isEqualTo(0)
        assertThat(day.totals.preAuthCount).isEqualTo(1)
        assertThat(day.totals.preAuthsMinor).containsExactly("AUD", 20_000L)
        history.setFilter(HistoryFilter.SALES)
        assertThat(await { history.state.first { it.filter == HistoryFilter.SALES } }.days).isEmpty()

        val detail =
            SaleDetailViewModel(
                id,
                container.sales,
                container.receipts,
                container.payments,
                container.refunds,
                container.settingsState,
                container.terminalStatus.state,
                messages,
            )
        val loaded = await { detail.state.first { it.record != null } }
        assertThat(loaded.canRefund).isFalse()
        assertThat(loaded.canCancel).isTrue()
        val cancellationId = detail.cancel()!!
        val cancellation =
            await {
                container.refunds.state.first { it == TransactionState.Finished(cancellationId) }
                container.refundRecords.get(cancellationId)!!
            }
        assertThat(cancellation.cancellation).isTrue()
        assertThat(await { detail.state.first { !it.canCancel } }.refunds.map { it.id }).containsExactly(cancellationId)
        assertThat(detail.cancel()).isNull()

        history.setFilter(HistoryFilter.PRE_AUTHS)
        val after =
            await {
                history.state.first {
                    it.filter == HistoryFilter.PRE_AUTHS && it.days
                        .single()
                        .items.size == 2
                }
            }
        assertThat(
            after.days
                .single()
                .totals.preAuthCount,
        ).isEqualTo(0)
        assertThat(
            after.days
                .single()
                .totals.refundCount,
        ).isEqualTo(0)
        history.setFilter(HistoryFilter.REFUNDS)
        assertThat(await { history.state.first { it.filter == HistoryFilter.REFUNDS } }.days).isEmpty()
    }
}
