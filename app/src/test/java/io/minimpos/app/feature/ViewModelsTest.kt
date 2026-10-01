package io.minimpos.app.feature

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.AppContainer
import io.minimpos.app.FakeDevice
import io.minimpos.app.FakeTerminal
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.db.CategoryEntity
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.repo.ImportMode
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.EmailCapture
import io.minimpos.app.data.settings.MerchantCopyPolicy
import io.minimpos.app.data.settings.ShopperReferenceSource
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.feature.history.HistoryFilter
import io.minimpos.app.feature.history.HistoryViewModel
import io.minimpos.app.feature.history.SaleDetailViewModel
import io.minimpos.app.feature.products.ProductEditViewModel
import io.minimpos.app.feature.products.ProductsViewModel
import io.minimpos.app.feature.refund.RefundOption
import io.minimpos.app.feature.refund.RefundResultViewModel
import io.minimpos.app.feature.refund.RefundViewModel
import io.minimpos.app.feature.sale.CheckoutViewModel
import io.minimpos.app.feature.sale.ResultMessages
import io.minimpos.app.feature.sale.SaleResultViewModel
import io.minimpos.app.feature.sale.SaleViewModel
import io.minimpos.app.feature.settings.SettingsChecks
import io.minimpos.app.feature.settings.SettingsMessages
import io.minimpos.app.feature.settings.SettingsViewModel
import io.minimpos.app.feature.transfer.CatalogueExportViewModel
import io.minimpos.app.feature.transfer.CatalogueImportViewModel
import io.minimpos.app.feature.transfer.ImportError
import io.minimpos.app.feature.transfer.ImportUiState
import io.minimpos.app.payment.TransactionState
import io.minimpos.app.refund.RefundInvalidReason
import io.minimpos.app.refund.Refundability
import io.minimpos.app.refund.RefundablePayment
import io.minimpos.core.codec.RefundQrPayload
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.receipt.ReceiptElement
import io.minimpos.core.shopper.EmailReferenceMode
import io.minimpos.core.shopper.ShopperReferences
import io.minimpos.core.tax.TaxMode
import io.minimpos.core.tax.TaxRates
import io.minimpos.terminal.client.RetryAdvice
import io.minimpos.terminal.simulator.SimulatedOutcome
import io.minimpos.terminal.simulator.TerminalSimulator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ViewModelsTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val messages = ResultMessages("Printed", "Sent to %s")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun seedCatalogue(): Pair<TaxRateEntity, ProductEntity> =
        await {
            val taxId = container.catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
            val categoryId = container.catalog.saveCategory(CategoryEntity(name = "Coffee"))
            val productId =
                container.catalog.saveProduct(
                    ProductEntity(name = "Latte", priceMinor = 450, taxRateId = taxId, categoryId = categoryId, sku = "L1"),
                )
            container.catalog.saveProduct(ProductEntity(name = "Tea", priceMinor = 400, taxRateId = taxId))
            TaxRateEntity(taxId, "GST", 10_000) to container.catalog.product(productId)!!
        }

    private fun saleViewModel() = SaleViewModel(container.catalog, container.saleSession, container.settingsState, container::currency)

    private fun payAndWait(configure: (CheckoutViewModel) -> Unit = {}): String {
        val checkout = CheckoutViewModel(container.saleSession, container.payments, container.settingsState, container::currency)
        configure(checkout)
        assertThat(checkout.pay()).isTrue()
        return await { (container.payments.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
    }

    @Test
    fun `sale view model filters and builds the cart`() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        val (tax, latte) = seedCatalogue()
        val vm = saleViewModel()
        val loaded = await { vm.state.first { it.products.size == 2 } }
        assertThat(loaded.hasSkus).isTrue()
        assertThat(loaded.defaultTaxRate).isEqualTo(tax)
        vm.setQuery("lat")
        assertThat(await { vm.state.first { it.query == "lat" } }.visibleProducts.map { it.name }).containsExactly("Latte")
        vm.setQuery("L1")
        assertThat(await { vm.state.first { it.query == "L1" } }.visibleProducts).hasSize(1)
        vm.setQuery("")
        vm.selectCategory(latte.categoryId)
        assertThat(await { vm.state.first { it.selectedCategory != null } }.visibleProducts.map { it.name }).containsExactly("Latte")
        vm.add(latte)
        vm.add(latte.copy(taxRateId = 999))
        vm.addCustom("Gift", 1_000, tax.id)
        vm.addCustom("Nope", 1_000, 999)
        assertThat(await { vm.addBySku("L1") }).isEqualTo("Latte")
        assertThat(await { vm.addBySku("none") }).isNull()
        val state = await { vm.state.first { it.totals.itemCount == 3 } }
        assertThat(state.quantityInCart(latte.id)).isEqualTo(2)
        assertThat(state.totals.amounts.gross).isEqualTo(1_900)
        val key =
            state.cart.lines
                .first()
                .key
        vm.setQuantity(key, 5)
        vm.remove(
            state.cart.lines
                .last()
                .key,
        )
        assertThat(await { vm.state.first { it.totals.itemCount == 5 } }.cart.lines).hasSize(1)
        vm.clear()
        assertThat(await { vm.state.first { it.cart.lines.isEmpty() } }.totals.isEmpty).isTrue()
    }

    @Test
    fun `zero-rated products and custom items, and switching tax off`() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD", taxMode = TaxMode.EXCLUSIVE)) }
        val (_, latte) = seedCatalogue()
        val free = await { container.catalog.saveTaxRate(TaxRateEntity(name = "GST-free", rateMilliPercent = 0, sortOrder = 1)) }
        val stamp =
            await {
                val id = container.catalog.saveProduct(ProductEntity(name = "Stamp", priceMinor = 120, taxRateId = free, sku = "S1"))
                container.catalog.product(id)!!
            }
        val vm = saleViewModel()
        await { vm.state.first { it.products.size == 3 && it.taxRates.size == 2 } }
        vm.add(latte)
        vm.add(stamp)
        vm.addCustom("Donation", 1_000, free)
        assertThat(await { vm.addBySku("S1") }).isEqualTo("Stamp")
        val taxed = await { vm.state.first { it.totals.itemCount == 4 } }
        assertThat(taxed.cart.lines.map { it.name to it.tax.rateMilliPercent })
            .containsExactly("Latte" to 10_000, "Stamp" to 0, "Donation" to 0)
            .inOrder()
        // Only the latte is taxed: 10% of 4.50; the zero-rated items are listed at 0%.
        assertThat(taxed.totals.amounts.tax).isEqualTo(45)

        env.updateSettings { it.copy(payment = it.payment.copy(chargeTax = false)) }
        val untaxed = await { vm.state.first { !it.chargeTax } }
        assertThat(untaxed.totals.amounts.tax).isEqualTo(0)
        assertThat(untaxed.totals.amounts.gross).isEqualTo(450 + 240 + 1_000)
        // Each line keeps its rate for when tax is switched back on.
        assertThat(
            untaxed.cart.lines
                .first()
                .tax.rateMilliPercent,
        ).isEqualTo(10_000)
    }

    @Test
    fun `checkout validates input and derives the shopper reference`() {
        env.useSimulator()
        val (tax, latte) = seedCatalogue()
        container.saleSession.addProduct(latte, tax)
        val vm = CheckoutViewModel(container.saleSession, container.payments, container.settingsState, container::currency)
        vm.update { it.copy(customerReference = "ab") }
        var state = await { vm.state.first { it.form.customerReference == "ab" } }
        assertThat(state.customerReferenceValid).isFalse()
        assertThat(state.canPay).isFalse()
        assertThat(vm.pay()).isFalse()
        vm.update { it.copy(customerReference = "CUST-1", email = "bad", transactionReference = "x".repeat(81)) }
        state = await { vm.state.first { it.form.email == "bad" } }
        assertThat(state.emailValid).isFalse()
        assertThat(state.referenceValid).isFalse()
        assertThat(state.showCustomerReference).isTrue()
        assertThat(state.customerReference).isEqualTo("CUST-1")
        assertThat(state.canTokenize).isTrue()
        assertThat(state.tokenize).isFalse()
        assertThat(state.showEmail).isFalse()

        env.updateSettings {
            it.copy(
                payment =
                    it.payment.copy(
                        shopperReferenceSource = ShopperReferenceSource.EMAIL,
                        tokenizeDefaultOn = true,
                        emailReferenceMode = EmailReferenceMode.HASHED,
                        emailCapture = EmailCapture.AFTER_PAYMENT,
                    ),
            )
        }
        vm.update { it.copy(email = "S@Example.com", transactionReference = "") }
        state = await { vm.state.first { it.form.email == "S@Example.com" && it.payment.tokenizeDefaultOn } }
        // The email is the shopper reference, so it is asked for before payment even though "After payment" was chosen.
        assertThat(state.showEmail).isTrue()
        assertThat(state.payment.effectiveEmailCapture).isEqualTo(EmailCapture.BOTH)
        // ...and there is no separate customer reference: the one left in the form is neither sent nor stored.
        assertThat(state.showCustomerReference).isFalse()
        assertThat(state.customerReference).isNull()
        assertThat(state.shopperReference).isEqualTo(ShopperReferences.fromEmail("s@example.com", EmailReferenceMode.HASHED))
        assertThat(state.tokenize).isTrue()
        vm.update { it.copy(tokenize = false) }
        assertThat(await { vm.state.first { it.form.tokenize == false } }.tokenize).isFalse()
        vm.update { it.copy(tokenize = true) }
        await { vm.state.first { it.form.tokenize == true } }

        assertThat(vm.pay()).isTrue()
        val saleId = await { (container.payments.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
        val sale = await { container.sales.get(saleId)!!.sale }
        assertThat(sale.status).isEqualTo(SaleStatus.APPROVED)
        // No reference prefix by default.
        assertThat(sale.merchantReference).matches("\\d{6}-\\d{6}-[0-9A-Z]{4}")
        assertThat(sale.shopperReference).startsWith("em_")
        assertThat(sale.shopperEmail).isEqualTo("S@Example.com")
        assertThat(sale.customerReference).isNull()
        assertThat(sale.storedPaymentMethodId).isNotNull()
    }

    @Test
    fun `result view model runs automatic print and email once`() {
        env.useSimulator {
            it.copy(
                receipt = it.receipt.copy(autoPrint = true, merchantCopy = MerchantCopyPolicy.ALWAYS),
                payment = it.payment.copy(emailCapture = EmailCapture.BOTH, autoSendEmail = true),
                email = it.email.copy(host = "smtp", fromAddress = "shop@example.com"),
            )
        }
        await { container.terminalStatus.state.first { it.printerAvailable } }
        val (tax, latte) = seedCatalogue()
        container.saleSession.addProduct(latte, tax)
        val saleId = payAndWait { vm -> vm.update { it.copy(email = "a@b.co") } }
        val vm =
            SaleResultViewModel(
                saleId,
                container.sales,
                container.receipts,
                container.payments,
                container.saleSession,
                container.settingsState,
                container.terminalStatus.state,
                messages,
            )
        val state = await { vm.state.first { it.print.done && it.email.done } }
        assertThat(state.approved).isTrue()
        assertThat(state.merchantCopyPending).isTrue()
        assertThat(state.email.message).isEqualTo("Sent to a@b.co")
        assertThat(env.mail.sent).hasSize(1)
        assertThat(container.saleSession.cart.value.lines).isEmpty()
        vm.printMerchantCopy()
        assertThat(await { vm.state.first { it.print.done && !it.merchantCopyPending } }.print.message).isEqualTo("Printed")
        vm.email("c@d.co")
        await { vm.state.first { it.email.message == "Sent to c@d.co" } }
        vm.recheck()
        await { vm.state.first { !it.rechecking } }
        assertThat(state.advice).isNull()
        vm.finish()
        assertThat(container.payments.state.value).isEqualTo(TransactionState.Idle)
    }

    @Test
    fun `result view model gives retry advice and cancels a busy terminal's transaction`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.BUSY)) }
        val (tax, latte) = seedCatalogue()
        container.saleSession.addProduct(latte, tax)
        val saleId = payAndWait()
        val vm =
            SaleResultViewModel(
                saleId,
                container.sales,
                container.receipts,
                container.payments,
                container.saleSession,
                container.settingsState,
                container.terminalStatus.state,
                messages.copy(abortSent = "Cancel sent"),
            )
        val busy = await { vm.state.first { it.record != null } }
        assertThat(busy.advice).isEqualTo(RetryAdvice.TERMINAL_BUSY)
        assertThat(busy.busyServiceId).isEqualTo(TerminalSimulator.BUSY_SERVICE_ID)
        vm.abortBusyTransaction()
        val aborted = await { vm.state.first { it.abort.done } }
        assertThat(aborted.abort.message).isEqualTo("Cancel sent")
        assertThat(aborted.busyServiceId).isNull()
        vm.abortBusyTransaction()
        assertThat(await { vm.state.first { it.abort.isError } }.abort.isError).isTrue()
    }

    @Test
    fun `refund view model supports full, item and amount refunds`() {
        env.useSimulator()
        val (tax, latte) = seedCatalogue()
        container.saleSession.addProduct(latte, tax)
        container.saleSession.addProduct(latte, tax)
        val saleId = payAndWait()
        container.payments.acknowledge()
        val record = await { container.sales.get(saleId)!! }

        val vm = RefundViewModel(null, saleId, container.sales, container.refunds, container.settingsState)
        val ready = await { vm.state.first { it.load is Refundability.Refundable } }
        assertThat(ready.refundMinor).isEqualTo(900)
        assertThat(ready.amountValid).isTrue()
        vm.selectOption(RefundOption.ITEMS)
        assertThat(vm.state.value.refundMinor).isEqualTo(0)
        val lineId = record.sortedLines.single().id
        vm.setQuantity(lineId, 1)
        vm.setQuantity(999, 1)
        assertThat(vm.state.value.refundMinor).isEqualTo(450)
        vm.selectOption(RefundOption.AMOUNT)
        vm.updateAmount {
            it
                .append(9)
                .append(9)
                .append(9)
                .append(9)
        }
        assertThat(vm.state.value.amountValid).isFalse()
        assertThat(vm.refund()).isFalse()
        vm.selectOption(RefundOption.ITEMS)
        assertThat(vm.refund()).isTrue()
        val refundId = await { (container.refunds.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
        val refund = await { container.refundRecords.get(refundId)!! }
        assertThat(refund.amountMinor).isEqualTo(450)
        assertThat(refund.merchantReference).startsWith("R-")
        assertThat(refund.full).isFalse()

        val result =
            RefundResultViewModel(
                refundId,
                container.refundRecords,
                container.receipts,
                container.refunds,
                container.settingsState,
                container.terminalStatus.state,
                messages,
            )
        val resultState = await { result.state.first { it.refund != null } }
        assertThat(resultState.accepted).isTrue()
        assertThat(resultState.canRecheck).isFalse()
        result.print()
        await { result.state.first { it.print.done || it.print.isError } }
        result.email("a@b.co")
        assertThat(await { result.state.first { it.email.isError } }.email.message).contains("not set up")
        result.finish()
        assertThat(container.refunds.state.value).isEqualTo(TransactionState.Idle)
    }

    @Test
    fun `refunds opened again from history or scanned again know what is left`() {
        env.useSimulator()
        val (tax, latte) = seedCatalogue()
        container.saleSession.addProduct(latte, tax)
        container.saleSession.addProduct(latte, tax)
        val saleId = payAndWait()
        container.payments.acknowledge()
        val lineId = await { container.sales.get(saleId)!! }.sortedLines.single().id
        val vm = RefundViewModel(null, saleId, container.sales, container.refunds, container.settingsState)
        await { vm.state.first { it.load is Refundability.Refundable } }
        vm.selectOption(RefundOption.ITEMS)
        vm.setQuantity(lineId, 1)
        assertThat(vm.refund()).isTrue()
        val refundId = await { (container.refunds.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
        container.refunds.acknowledge()

        // Opened again from history, nothing is delivered automatically or acknowledged.
        val fromHistory =
            RefundResultViewModel(
                refundId,
                container.refundRecords,
                container.receipts,
                container.refunds,
                container.settingsState,
                container.terminalStatus.state,
                messages.copy(stillUnknown = "still unknown"),
                justMade = false,
            )
        assertThat(await { fromHistory.state.first { it.refund != null } }.print.running).isFalse()
        // Only an unknown outcome is checked again.
        fromHistory.recheck()
        assertThat(await { fromHistory.state.first { !it.rechecking && it.recheckMessage != null } }.recheckMessage)
            .isEqualTo("still unknown")
        fromHistory.finish()

        // Scanning the same receipt again knows what is left.
        val qr = RefundablePayment.qrCode(await { container.sales.get(saleId)!! })!!
        val again = RefundViewModel(qr, null, container.sales, container.refunds, container.settingsState)
        val original = await { again.state.first { it.load is Refundability.Refundable } }.original!!
        assertThat(original.remainingMinor).isEqualTo(450)
        assertThat(original.local).isNotNull()
    }

    @Test
    fun `refund view model handles foreign and invalid receipts`() {
        env.useSimulator()
        val foreign = RefundQrPayload("TENDER.PSPX", Instant.parse("2026-01-01T00:00:00Z"), 1_000, "AUD", "MP-9").encode()
        val vm = RefundViewModel(foreign, null, container.sales, container.refunds, container.settingsState)
        val ready = await { vm.state.first { it.load is Refundability.Refundable } }
        assertThat(ready.original!!.local).isNull()
        assertThat(ready.original!!.timestamp).isEqualTo("2026-01-01T00:00:00.000Z")
        assertThat(vm.refund()).isTrue()
        await { container.refunds.state.first { it is TransactionState.Finished } }
        assertThat(vm.refund()).isTrue()
        container.refunds.acknowledge()

        fun invalid(
            payload: String?,
            saleId: String?,
        ) = (
            checkNotNull(
                await {
                    RefundViewModel(payload, saleId, container.sales, container.refunds, container.settingsState).state.first {
                        it.load != null
                    }
                }.load,
            ) as Refundability.NotRefundable
        ).reason
        assertThat(invalid("garbage", null)).isEqualTo(RefundInvalidReason.NOT_A_RECEIPT)
        assertThat(invalid(null, "missing")).isEqualTo(RefundInvalidReason.NOT_A_RECEIPT)
        assertThat(invalid(RefundQrPayload("T.P", Instant.EPOCH, 0, "AUD").encode(), null)).isEqualTo(RefundInvalidReason.FULLY_REFUNDED)

        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.DECLINE)) }
        val (tax, latte) = seedCatalogue()
        container.saleSession.addProduct(latte, tax)
        val declined = payAndWait()
        assertThat(invalid(null, declined)).isEqualTo(RefundInvalidReason.NOT_REFUNDABLE)
    }

    @Test
    fun `history groups by day and filters`() {
        env.useSimulator()
        val (tax, latte) = seedCatalogue()
        container.saleSession.addProduct(latte, tax)
        val saleId = payAndWait()
        container.payments.acknowledge()
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.DECLINE)) }
        container.saleSession.addProduct(latte, tax)
        payAndWait()
        val vm = HistoryViewModel(container.history)
        val day = await { vm.state.first { it.days.sumOf { d -> d.items.size } == 2 } }.days.single()
        assertThat(day.totals.saleCount).isEqualTo(1)
        assertThat(day.totals.salesMinor).containsExactly(
            "AUD".takeIf {
                container.currency().code == "AUD"
            } ?: container.currency().code,
            450L,
        )
        vm.setFilter(HistoryFilter.REFUNDS)
        assertThat(await { vm.state.first { it.filter == HistoryFilter.REFUNDS } }.days).isEmpty()
        vm.setFilter(HistoryFilter.ISSUES)
        assertThat(await { vm.state.first { it.filter == HistoryFilter.ISSUES } }.days).isEmpty()
        vm.setFilter(HistoryFilter.SALES)
        assertThat(await { vm.state.first { it.filter == HistoryFilter.SALES } }.days.single().items).hasSize(2)

        val detail =
            SaleDetailViewModel(
                saleId,
                container.sales,
                container.receipts,
                container.payments,
                container.settingsState,
                container.terminalStatus.state,
                messages.copy(stillUnknown = "still unknown"),
            )
        val state = await { detail.state.first { it.record != null } }
        assertThat(state.canRefund).isTrue()
        detail.print()
        await { detail.state.first { !it.print.running } }
        detail.email("a@b.co")
        await { detail.state.first { it.email.isError } }
        detail.recheck()
        assertThat(await { detail.state.first { !it.rechecking && it.recheckMessage != null } }.recheckMessage).isEqualTo("still unknown")
    }

    @Test
    fun `a second tap on Save while the product is being saved is ignored`() {
        val (tax, _) = seedCatalogue()
        val edit = ProductEditViewModel(null, null, container.catalog, container.settingsState, container::currency)
        await { edit.state.first { it.loaded } }
        edit.update { it.copy(name = "Scone", price = "4.50", taxRateId = tax.id) }
        // Hold the save on a queue, so the second tap certainly comes while the first save is under way.
        val queued = StandardTestDispatcher()
        Dispatchers.setMain(queued)
        var saved = false
        edit.save { saved = true }
        assertThat(edit.state.value.saving).isTrue()
        edit.save { error("a second save must be ignored") }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        queued.scheduler.advanceUntilIdle()

        await { edit.state.first { !it.saving } }
        assertThat(saved).isTrue()
        val products = await { container.catalog.products.first { it.any { p -> p.name == "Scone" } } }
        assertThat(products.count { it.name == "Scone" }).isEqualTo(1)
    }

    @Test
    fun `products view models manage the catalogue`() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        val (tax, _) = seedCatalogue()
        val vm = ProductsViewModel(container.catalog, container.settingsState, container::currency)
        await { vm.state.first { it.products.size == 2 } }
        vm.saveCategory(CategoryEntity(name = " Food "))
        val state = await { vm.state.first { it.categories.size == 2 } }
        assertThat(state.categories.map { it.name }).contains("Food")
        assertThat(state.taxRates.map { it.id }).containsExactly(tax.id)
        vm.deleteCategory(state.categories.first { it.name == "Food" })
        await { vm.state.first { it.categories.size == 1 } }

        val edit = ProductEditViewModel(null, "NEW1", container.catalog, container.settingsState, container::currency)
        var form = await { edit.state.first { it.loaded } }
        assertThat(form.form.sku).isEqualTo("NEW1")
        assertThat(form.form.taxRateId).isEqualTo(tax.id)
        edit.update { it.copy(name = "Scone", price = "4.505") }
        assertThat(edit.state.value.priceMinor).isNull()
        edit.update { it.copy(price = "4,50") }
        assertThat(edit.state.value.priceMinor).isEqualTo(450)
        var saved = false
        edit.save { saved = true }
        await { container.catalog.products.first { it.any { p -> p.name == "Scone" } } }
        assertThat(saved).isTrue()

        val scone = await { container.catalog.productBySku("NEW1")!! }
        assertThat(scone.taxRateId).isEqualTo(tax.id)
        val existing = ProductEditViewModel(scone.id, null, container.catalog, container.settingsState, container::currency)
        form = await { existing.state.first { it.loaded } }
        assertThat(form.form.price).isEqualTo("4.50")
        assertThat(form.form.taxRateId).isEqualTo(tax.id)

        // Items without tax use a 0% rate; a product always needs a rate.
        val free = await { container.catalog.saveTaxRate(TaxRateEntity(name = "GST-free", rateMilliPercent = 0)) }
        existing.update { it.copy(taxRateId = free) }
        existing.save {}
        assertThat(await { container.catalog.products.first { it.any { p -> p.id == scone.id && p.taxRateId == free } } }).isNotEmpty()
        val reopened = ProductEditViewModel(scone.id, null, container.catalog, container.settingsState, container::currency)
        assertThat(await { reopened.state.first { it.loaded } }.form.taxRateId).isEqualTo(free)
        reopened.update { it.copy(taxRateId = null) }
        assertThat(reopened.state.value.valid).isFalse()
        reopened.save { error("must not save without a tax rate") }

        // With tax switched off in settings, the rate is hidden but new products still get the default one.
        env.updateSettings { it.copy(payment = it.payment.copy(chargeTax = false)) }
        val offline = ProductEditViewModel(null, null, container.catalog, container.settingsState, container::currency)
        val off = await { offline.state.first { it.loaded } }
        assertThat(off.chargeTax).isFalse()
        assertThat(off.form.taxRateId).isEqualTo(tax.id)
        env.updateSettings { it.copy(payment = it.payment.copy(chargeTax = true)) }
        val other = ProductEditViewModel(null, "NEW1", container.catalog, container.settingsState, container::currency)
        assertThat(await { other.state.first { it.loaded && it.skuInUse } }.valid).isFalse()
        other.save { error("must not save") }
        var deleted = false
        existing.delete { deleted = true }
        await { container.catalog.products.first { it.none { p -> p.name == "Scone" } } }
        assertThat(deleted).isTrue()
        other.delete { error("new products cannot be deleted") }
    }

    @Test
    fun `catalogue transfer view models round trip`() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        seedCatalogue()
        val export = CatalogueExportViewModel(container.catalog, "AUD", chunkSize = 20)
        val codes = await { export.state.first { !it.loading } }.codes
        assertThat(codes.size).isGreaterThan(1)

        val import = CatalogueImportViewModel(container.catalog, "NZD")
        import.onCode("hello")
        assertThat((import.state.value as ImportUiState.Scanning).error).isEqualTo(ImportError.NOT_A_CATALOGUE)
        import.onCode(codes.first())
        assertThat((import.state.value as ImportUiState.Scanning).received).isEqualTo(1)
        codes.drop(1).forEach(import::onCode)
        val ready = import.state.value as ImportUiState.Ready
        assertThat(ready.currencyMatches).isFalse()
        assertThat(ready.catalogue.products).hasSize(2)
        import.onCode(codes.first())
        import.import(ImportMode.MERGE)
        val done = await { import.state.first { it is ImportUiState.Done } } as ImportUiState.Done
        assertThat(done.summary.productsUpdated).isEqualTo(2)
        import.import(ImportMode.REPLACE)
        import.restart()
        assertThat(import.state.value).isEqualTo(ImportUiState.Scanning())
        import.onCode("MPC1:ABCD:1/1:AAAA")
        assertThat((import.state.value as ImportUiState.Scanning).error).isEqualTo(ImportError.CORRUPT)
    }

    private fun settingsViewModel(target: AppContainer = container) =
        SettingsViewModel(
            target.settings,
            target.secrets,
            target.pinManager,
            target.sessionLock,
            SettingsChecks(target.terminalStatus, target.receipts),
            target.history,
            target.catalog,
            SettingsMessages("Connected", "Failed", "printer", "no printer", "Sent to %s", "Printed", "Not stored (%s)"),
        )

    @Test
    fun `settings view model saves the passphrase before testing, and reports what went wrong`() {
        val onTerminal = TestEnvironment(FakeDevice(detectedPoiId = "AMS1-000168223606144"), FakeTerminal())
        try {
            onTerminal.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "key")) }
            val vm = settingsViewModel(onTerminal.container)

            vm.testConnection("wrong passphrase")
            val rejected = await { vm.actions.first { it.connection.isError } }
            assertThat(rejected.connection.message).contains("shared key")
            assertThat(rejected.passphraseStored).isTrue()
            vm.dismissConnectionResult()
            assertThat(vm.actions.value.connection.message).isNull()

            vm.testConnection("correct horse battery staple")
            val connected = await { vm.actions.first { it.connection.done } }
            assertThat(connected.connection.message).startsWith("Connected")
            assertThat(await { onTerminal.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery staple")

            // A device that cannot encrypt secrets is reported, not a crash, and the saved passphrase is kept.
            onTerminal.cipher.failEncrypt = true
            vm.testConnection("another passphrase")
            val failed = await { vm.actions.first { it.connection.isError } }
            assertThat(failed.connection.message).startsWith("Not stored (ProviderException: Keystore unavailable)")
            assertThat(failed.passphraseStored).isFalse()
            assertThat(await { onTerminal.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery staple")
            vm.setSecret(Secret.SMTP_PASSWORD, "hunter2")
            vm.setPin("2468")
            await { vm.actions.first { it.secretError != null } }
            assertThat(onTerminal.container.sessionLock.unlocked.value).isFalse()
            onTerminal.cipher.failEncrypt = false
            vm.setSecret(Secret.SMTP_PASSWORD, "hunter2")
            await { vm.actions.first { it.secretError == null } }
        } finally {
            onTerminal.close()
        }
    }

    @Test
    fun `settings view model manages named tax rates`() {
        val (gst, _) = seedCatalogue()
        val vm = settingsViewModel()
        var state = await { vm.state.first { it.loaded && it.taxRates.isNotEmpty() } }
        assertThat(state.taxRateUsage[gst.id]).isEqualTo(2)
        assertThat(state.defaultTaxRate).isEqualTo(gst)

        vm.saveTaxRate(TaxRateEntity(name = " VAT ", rateMilliPercent = TaxRates.parse("25.5")!!), makeDefault = true)
        // VAT sorts first, so it is the fallback default too: wait until it is stored as the default.
        state = await { vm.state.first { s -> s.settings.payment.defaultTaxRateId != null && s.defaultTaxRate?.name == "VAT" } }
        val vat = state.taxRates.single { it.name == "VAT" }
        assertThat(vat.rateMilliPercent).isEqualTo(25_500)
        assertThat(state.taxRateUsage[vat.id]).isNull()
        vm.saveTaxRate(vat.copy(name = "Consumption tax", rateMilliPercent = TaxRates.parse("8.1")!!), makeDefault = false)
        state = await { vm.state.first { s -> s.taxRates.any { it.name == "Consumption tax" } } }
        assertThat(state.defaultTaxRate!!.rateMilliPercent).isEqualTo(8_100)

        // Rates still used by products stay, and say why.
        vm.deleteTaxRate(gst)
        assertThat(await { vm.actions.first { it.taxRateInUse != null } }.taxRateInUse).isEqualTo(2)
        vm.deleteTaxRate(state.defaultTaxRate!!)
        await { vm.actions.first { it.taxRateInUse == null } }
        state = await { vm.state.first { it.taxRates.size == 1 } }
        // The default falls back to the remaining rate, which cannot be deleted.
        assertThat(state.defaultTaxRate).isEqualTo(gst)
        await {
            container.catalog.products
                .first()
                .forEach { p -> container.catalog.deleteProduct(p) }
        }
        vm.deleteTaxRate(gst)
        assertThat(await { vm.state.first { it.taxRateUsage.isEmpty() } }.taxRates).containsExactly(gst)
    }

    @Test
    fun `settings view model updates settings, secrets and runs checks`() {
        env.useSimulator()
        val vm = settingsViewModel()
        vm.update { it.copy(receipt = it.receipt.copy(businessName = "Shop")) }
        await { vm.state.first { it.settings.receipt.businessName == "Shop" } }
        vm.setSecret(Secret.TERMINAL_PASSPHRASE, "secret")
        await { vm.state.first { Secret.TERMINAL_PASSPHRASE in it.secrets } }
        vm.setPin("2468")
        assertThat(await { vm.state.first { it.pinSet } }.pinSet).isTrue()
        await { container.sessionLock.unlocked.first { it } }
        vm.clearPin()
        await { vm.state.first { !it.pinSet } }

        vm.testConnection()
        assertThat(await { vm.actions.first { it.connection.done } }.connection.message).contains("Connected")
        vm.printTest(ReceiptDocument(listOf(ReceiptElement.Text("Test"))))
        assertThat(await { vm.actions.first { it.print.done } }.print.message).isEqualTo("Printed")
        vm.sendTestEmail("a@b.co")
        assertThat(await { vm.actions.first { !it.email.running && it.email.message != null } }.email.isError).isTrue()
        vm.clearHistory()
        await { vm.actions.first { it.cleared } }

        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
        vm.testConnection()
        assertThat(await { vm.actions.first { it.connection.isError } }.connection.message).contains("POIID")
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(poiIdOverride = "S1F2-000000001", keyIdentifier = "k", host = "127.0.0.1"))
        }
        vm.testConnection()
        assertThat(
            await {
                vm.actions.first { it.connection.isError && it.connection.message?.startsWith("Failed") == true }
            }.connection.message,
        ).contains("Cannot connect")
    }
}
