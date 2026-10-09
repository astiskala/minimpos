package app.minimpos.app.feature

import app.minimpos.app.AppContainer
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.CategoryEntity
import app.minimpos.app.data.db.EmailFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.ImportMode
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.EmailCapture
import app.minimpos.app.data.settings.MerchantCopyPolicy
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.history.HistoryFilter
import app.minimpos.app.feature.history.HistoryViewModel
import app.minimpos.app.feature.history.SaleDetailViewModel
import app.minimpos.app.feature.products.ProductEditViewModel
import app.minimpos.app.feature.products.ProductsViewModel
import app.minimpos.app.feature.refund.RefundOption
import app.minimpos.app.feature.refund.RefundResultViewModel
import app.minimpos.app.feature.refund.RefundViewModel
import app.minimpos.app.feature.sale.CheckoutViewModel
import app.minimpos.app.feature.sale.SaleResultViewModel
import app.minimpos.app.feature.sale.SaleViewModel
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.refund.PaymentAction
import app.minimpos.app.refund.RefundInvalidReason
import app.minimpos.app.refund.Refundability
import app.minimpos.app.refund.RefundablePayment
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.receipt.ReceiptCopy
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.core.shopper.EmailReferenceMode
import app.minimpos.core.shopper.ShopperReferences
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.tax.TaxRates
import app.minimpos.terminal.client.RetryAdvice
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.simulator.TerminalSimulator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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

    private fun saleViewModel() =
        SaleViewModel(container.catalog, container.session(SaleKind.SALE), container.settingsState, container::currency)

    private fun payAndWait(configure: (CheckoutViewModel) -> Unit = {}): String {
        val checkout =
            CheckoutViewModel(
                container.session(SaleKind.SALE),
                container.payments,
                container.settingsState,
                container.terminalStatus.state,
                container::currency,
            )
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
        env.useSimulator { it.copy(payment = it.payment.copy(shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE)) }
        val (tax, latte) = seedCatalogue()
        container.session(SaleKind.SALE).addProduct(latte, tax)
        val vm =
            CheckoutViewModel(
                container.session(SaleKind.SALE),
                container.payments,
                container.settingsState,
                container.terminalStatus.state,
                container::currency,
            )
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
        assertThat(state.payment.effectiveEmailCapture).isEqualTo(EmailCapture.BEFORE_PAYMENT)
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
                payment = it.payment.copy(emailCapture = EmailCapture.BEFORE_PAYMENT, autoSendEmail = true),
                email = it.email.copy(host = "smtp", fromAddress = "shop@example.com"),
            )
        }
        await { container.terminalStatus.state.first { it.printerAvailable } }
        val (tax, latte) = seedCatalogue()
        container.session(SaleKind.SALE).addProduct(latte, tax)
        val saleId = payAndWait { vm -> vm.update { it.copy(email = "a@b.co") } }
        // The payment's cart was cleared as soon as it was approved.
        assertThat(
            container
                .session(SaleKind.SALE)
                .cart.value.lines,
        ).isEmpty()
        val vm = SaleResultViewModel(saleId, container.storedPayments, container.receipts, container.payments)
        val state = await { vm.state.first { it.transaction.print.done && it.transaction.email.done } }
        assertThat(state.approved).isTrue()
        assertThat(state.transaction.merchantCopyPending).isTrue()
        assertThat(state.transaction.email.outcome).isEqualTo(ActionOutcome.Emailed("a@b.co"))
        assertThat(state.transaction.canPrint).isTrue()
        assertThat(state.transaction.canEmail).isTrue()
        assertThat(state.transaction.receipt).isNotNull()
        assertThat(env.mail.sent).hasSize(1)
        assertThat(
            container
                .session(SaleKind.SALE)
                .cart.value.lines,
        ).isEmpty()
        vm.transaction.print(ReceiptCopy.MERCHANT)
        assertThat(
            await {
                vm.state.first { it.transaction.print.done && !it.transaction.merchantCopyPending }
            }.transaction.print.outcome,
        ).isEqualTo(ActionOutcome.Printed)
        vm.transaction.email("c@d.co")
        await { vm.state.first { it.transaction.email.outcome == ActionOutcome.Emailed("c@d.co") } }
        // A second view model for the same sale (the screen recreated) delivers nothing again.
        val again = SaleResultViewModel(saleId, container.storedPayments, container.receipts, container.payments)
        await { again.state.first { it.record != null && it.transaction.receipt != null } }
        assertThat(again.state.value.transaction.print).isEqualTo(ActionState())
        assertThat(env.mail.sent).hasSize(2)
        vm.transaction.recheck()
        await { vm.state.first { !it.transaction.rechecking } }
        assertThat(state.advice).isNull()
        vm.finish()
        assertThat(container.payments.state.value).isEqualTo(TransactionState.Idle)
    }

    @Test
    fun `an email sent from a result screen is recorded on the sale even when the screen closes`() {
        env.useSimulator { it.copy(email = it.email.copy(host = "smtp", fromAddress = "shop@example.com")) }
        val (tax, latte) = seedCatalogue()
        container.session(SaleKind.SALE).addProduct(latte, tax)
        val saleId = payAndWait()
        val screen = CoroutineScope(Job() + UnconfinedTestDispatcher())
        val actions = TransactionActions.forSale(screen, saleId, container.receipts, container.payments, fresh = false)
        actions.email("a@b.co")
        // Leaving the screen cancels its scope while the email is being sent.
        screen.cancel()
        assertThat(await { container.sales.observe(saleId).first { it?.sale?.emailedTo != null } }!!.sale.emailedTo).isEqualTo("a@b.co")
        assertThat(env.mail.sent).hasSize(1)
        // The screen that is gone is not updated.
        assertThat(actions.state.value.email.running).isTrue()
    }

    @Test
    fun `result view model gives retry advice and cancels a busy terminal's transaction`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.BUSY)) }
        val (tax, latte) = seedCatalogue()
        container.session(SaleKind.SALE).addProduct(latte, tax)
        val saleId = payAndWait()
        val vm = SaleResultViewModel(saleId, container.storedPayments, container.receipts, container.payments)
        val busy = await { vm.state.first { it.record != null } }
        assertThat(busy.advice).isEqualTo(RetryAdvice.TERMINAL_BUSY)
        assertThat(busy.busyServiceId).isEqualTo(TerminalSimulator.BUSY_SERVICE_ID)
        vm.abortBusyTransaction()
        val aborted = await { vm.state.first { it.abort.done } }
        assertThat(aborted.abort.outcome).isEqualTo(ActionOutcome.AbortSent)
        assertThat(aborted.busyServiceId).isNull()
        vm.abortBusyTransaction()
        assertThat(await { vm.state.first { it.abort.isError } }.abort.isError).isTrue()
    }

    @Test
    fun `refund view model supports full, item and amount refunds`() {
        env.useSimulator()
        val (tax, latte) = seedCatalogue()
        container.session(SaleKind.SALE).addProduct(latte, tax)
        container.session(SaleKind.SALE).addProduct(latte, tax)
        val saleId = payAndWait()
        container.payments.acknowledge()
        val record = await { container.sales.get(saleId)!! }

        val vm = RefundViewModel(null, saleId, container.sales, container.storedPaymentActions)
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

        val result = RefundResultViewModel(refundId, container.refundRecords, container.receipts, container.refunds)
        val resultState = await { result.state.first { it.refund != null } }
        assertThat(resultState.accepted).isTrue()
        assertThat(resultState.canRecheck).isFalse()
        result.transaction.print()
        await { result.state.first { it.transaction.print.done || it.transaction.print.isError } }
        result.transaction.email("a@b.co")
        assertThat(await { result.state.first { it.transaction.email.isError } }.transaction.email.outcome)
            .isEqualTo(ActionOutcome.Failed(Failure.Email(EmailFault.NOT_CONFIGURED)))
        result.finish()
        assertThat(container.refunds.state.value).isEqualTo(TransactionState.Idle)
    }

    @Test
    fun `refunds opened again from history or scanned again know what is left`() {
        env.useSimulator()
        val (tax, latte) = seedCatalogue()
        container.session(SaleKind.SALE).addProduct(latte, tax)
        container.session(SaleKind.SALE).addProduct(latte, tax)
        val saleId = payAndWait()
        container.payments.acknowledge()
        val lineId = await { container.sales.get(saleId)!! }.sortedLines.single().id
        val vm = RefundViewModel(null, saleId, container.sales, container.storedPaymentActions)
        await { vm.state.first { it.load is Refundability.Refundable } }
        vm.selectOption(RefundOption.ITEMS)
        vm.setQuantity(lineId, 1)
        assertThat(vm.refund()).isTrue()
        val refundId = await { (container.refunds.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
        container.refunds.acknowledge()

        // Opened again from history, nothing is delivered automatically or acknowledged.
        val fromHistory = RefundResultViewModel(refundId, container.refundRecords, container.receipts, container.refunds, justMade = false)
        assertThat(await { fromHistory.state.first { it.refund != null } }.transaction.print.running).isFalse()
        // Only an unknown outcome is checked again.
        fromHistory.transaction.recheck()
        assertThat(
            await {
                fromHistory.state.first { !it.transaction.rechecking && it.transaction.stillUnknown }
            }.transaction.stillUnknown,
        ).isTrue()
        fromHistory.finish()

        // Scanning the same receipt again knows what is left.
        val qr = RefundablePayment.qrCode(await { container.sales.get(saleId)!! })!!
        val again = RefundViewModel(qr, null, container.sales, container.storedPaymentActions)
        val original = await { again.state.first { it.load is Refundability.Refundable } }.original!!
        assertThat(original.remainingMinor).isEqualTo(450)
        assertThat(original.local).isNotNull()
    }

    @Test
    fun `refund view model handles foreign and invalid receipts`() {
        env.useSimulator()
        val foreign = RefundQrPayload("TENDER.PSPX", Instant.parse("2026-01-01T00:00:00Z"), 1_000, "AUD", "MP-9").encode()
        val vm = RefundViewModel(foreign, null, container.sales, container.storedPaymentActions)
        val ready = await { vm.state.first { it.load is Refundability.Refundable } }
        assertThat(ready.original!!.local).isNull()
        assertThat(ready.original!!.timestamp).isEqualTo("2026-01-01T00:00:00.000Z")
        assertThat(vm.refund()).isFalse()
        vm.reviewRemote(true)
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
                    RefundViewModel(payload, saleId, container.sales, container.storedPaymentActions).state.first {
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
        container.session(SaleKind.SALE).addProduct(latte, tax)
        val declined = payAndWait()
        assertThat(invalid(null, declined)).isEqualTo(RefundInvalidReason.NOT_REFUNDABLE)
    }

    @Test
    fun `history groups by day and filters`() {
        env.useSimulator()
        val (tax, latte) = seedCatalogue()
        container.session(SaleKind.SALE).addProduct(latte, tax)
        val saleId = payAndWait()
        container.payments.acknowledge()
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.DECLINE)) }
        container.session(SaleKind.SALE).addProduct(latte, tax)
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
                container.storedPayments,
                container.refundRecords,
                container.receipts,
                container.storedPaymentActions,
                container.payments,
            )
        val state = await { detail.state.first { it.record != null } }
        assertThat(state.actions).contains(PaymentAction.REFUND)
        detail.transaction.print()
        await { detail.state.first { !it.transaction.print.running } }
        detail.transaction.email("a@b.co")
        await { detail.state.first { it.transaction.email.isError } }
        detail.transaction.recheck()
        assertThat(
            await {
                detail.state.first {
                    !it.transaction.rechecking && it.transaction.stillUnknown
                }
            }.transaction.stillUnknown,
        ).isTrue()
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
        val saved = CompletableDeferred<Unit>()
        edit.save { saved.complete(Unit) }
        assertThat(edit.state.value.saving).isTrue()
        edit.save { error("a second save must be ignored") }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        queued.scheduler.advanceUntilIdle()

        await { saved.await() }
        assertThat(edit.state.value.saving).isFalse()
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
        edit.update { it.copy(name = "Scone", price = "1e17") }
        assertThat(edit.state.value.priceMinor).isNull()
        assertThat(edit.state.value.valid).isFalse()
        edit.save { error("must not save an overflowing price") }
        edit.update { it.copy(name = "Scone", price = "4.505") }
        assertThat(edit.state.value.priceMinor).isNull()
        edit.update { it.copy(price = "4,50") }
        assertThat(edit.state.value.priceMinor).isEqualTo(450)
        val saved = CompletableDeferred<Unit>()
        edit.save { saved.complete(Unit) }
        await { saved.await() }
        assertThat(await { container.catalog.products.first { it.any { p -> p.name == "Scone" } } }).isNotEmpty()

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
        val deleted = CompletableDeferred<Unit>()
        existing.delete { deleted.complete(Unit) }
        await { deleted.await() }
        val remaining = await { container.catalog.products.first { it.none { p -> p.name == "Scone" } } }
        assertThat(remaining.map { it.name }).doesNotContain("Scone")
        other.delete { error("new products cannot be deleted") }
    }
}
