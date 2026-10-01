package io.minimpos.app.feature

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.ProductEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.TaxRateEntity
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.PrinterMode
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.feature.capture.CaptureProblem
import io.minimpos.app.feature.capture.CaptureViewModel
import io.minimpos.app.feature.capture.Submission
import io.minimpos.app.feature.capture.TipInput
import io.minimpos.app.feature.capture.TipViewModel
import io.minimpos.app.feature.history.HistoryFilter
import io.minimpos.app.feature.history.HistoryViewModel
import io.minimpos.app.feature.history.SaleDetailViewModel
import io.minimpos.app.feature.history.SaleOperations
import io.minimpos.app.feature.sale.CheckoutViewModel
import io.minimpos.app.feature.sale.ResultMessages
import io.minimpos.app.feature.settings.SettingsChecks
import io.minimpos.app.feature.settings.SettingsMessages
import io.minimpos.app.feature.settings.SettingsViewModel
import io.minimpos.app.payment.CaptureResult
import io.minimpos.app.payment.TransactionState
import io.minimpos.app.terminal.CaptureMode
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

/** Tipping on the receipt and captures through the view models, with the simulated terminal and Checkout API. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TippingViewModelsTest {
    private val env = TestEnvironment()
    private val container = env.container

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun seed(): Pair<TaxRateEntity, ProductEntity> =
        await {
            val taxId = container.catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
            val dinner = container.catalog.saveProduct(ProductEntity(name = "Dinner", priceMinor = 2_000, taxRateId = taxId))
            TaxRateEntity(taxId, "GST", 10_000) to container.catalog.product(dinner)!!
        }

    private fun checkout(kind: SaleKind = SaleKind.SALE) =
        CheckoutViewModel(
            container.session(kind),
            container.payments,
            container.settingsState,
            container.terminalStatus.state,
            container::currency,
            kind,
        )

    /** Takes a sale of the dinner for a tip on the receipt and returns its ID. */
    private fun tipSale(): String {
        val (tax, dinner) = seed()
        container.saleSession.addProduct(dinner, tax)
        val vm = checkout()
        vm.update { it.copy(tipOnReceipt = true) }
        await { vm.state.first { it.tipOnReceipt && !it.totals.isEmpty } }
        assertThat(vm.pay()).isTrue()
        val id = await { (container.payments.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
        container.payments.acknowledge()
        return id
    }

    private fun detail(id: String) =
        SaleDetailViewModel(
            id,
            container.sales,
            container.receipts,
            SaleOperations(container.payments, container.refunds, container.captures),
            container.settingsState,
            container.terminalStatus.state,
            ResultMessages("Printed", "Sent to %s", notCaptured = "Not captured: %s"),
        )

    @Test
    fun `checkout offers a tip on the receipt for sales with a printer, from the settings default`() {
        val sale = await { checkout().state.first { it.printerAvailable } }
        assertThat(sale.canTipOnReceipt).isTrue()
        assertThat(sale.tipOnReceipt).isFalse()
        env.useSimulator { it.copy(payment = it.payment.copy(tipOnReceiptDefaultOn = true)) }
        val vm = checkout()
        assertThat(await { vm.state.first { it.payment.tipOnReceiptDefaultOn && it.printerAvailable } }.tipOnReceipt).isTrue()
        vm.update { it.copy(tipOnReceipt = false) }
        assertThat(await { vm.state.first { it.form.tipOnReceipt == false } }.tipOnReceipt).isFalse()
        // Not for pre-authorisations, nor without a printer.
        assertThat(await { checkout(SaleKind.PRE_AUTHORISATION).state.first { it.printerAvailable } }.canTipOnReceipt).isFalse()
        env.useSimulator { it.copy(receipt = it.receipt.copy(printerMode = PrinterMode.OFF)) }
        assertThat(await { vm.state.first { !it.printerAvailable } }.tipOnReceipt).isFalse()
    }

    @Test
    fun `the tip is entered as the tip or the total and captured, then the sale can be refunded`() {
        val id = tipSale()
        val awaiting = await { detail(id).state.first { it.record != null } }
        assertThat(awaiting.canEnterTip).isTrue()
        assertThat(awaiting.canRefund).isFalse()
        assertThat(awaiting.canCancel).isTrue()

        val tip = TipViewModel(id, container.sales, container.captures, container.terminalStatus.state)
        assertThat(await { tip.state.first { it.sale != null } }.billMinor).isEqualTo(2_000)
        tip.setInput(TipInput.TOTAL)
        listOf(1, 5).forEach { digit -> tip.updateEntry { it.append(digit) } }
        // A total below the bill is not a tip.
        assertThat(await { tip.state.first { it.entry.minor == 15L } }.belowBill).isTrue()
        assertThat(tip.state.value.canAddTip).isFalse()
        tip.updateEntry { it.appendDoubleZero() }
        tip.updateEntry { it.append(0) }
        var state = await { tip.state.first { it.entry.minor == 15_000L } }
        assertThat(state.tipMinor).isEqualTo(13_000)
        assertThat(state.needsAdjustment).isTrue()
        tip.setInput(TipInput.TIP)
        listOf(3, 0, 0).forEach { digit -> tip.updateEntry { it.append(digit) } }
        state = await { tip.state.first { it.input == TipInput.TIP && it.entry.minor == 300L } }
        assertThat(state.needsAdjustment).isFalse()
        assertThat(state.canAddTip).isTrue()
        tip.submit(state.tipMinor!!)
        await { tip.state.first { it.submission.done && it.sale?.captureStatus == CaptureStatus.REQUESTED } }

        val captured = await { detail(id).state.first { it.record?.sale?.captured == true } }
        assertThat(captured.canEnterTip).isFalse()
        assertThat(captured.canCancel).isFalse()
        assertThat(captured.canRefund).isTrue()
        assertThat(captured.record!!.sale.amountMinor).isEqualTo(2_300)
        // Submitting again does nothing.
        tip.submit(100)
        assertThat(tip.state.value.submission.running).isFalse()
    }

    @Test
    fun `history lists sales awaiting a tip and totals tips`() {
        val id = tipSale()
        val history = HistoryViewModel(container.history)
        history.setFilter(HistoryFilter.AWAITING_TIP)
        assertThat(
            await { history.state.first { it.filter == HistoryFilter.AWAITING_TIP && it.days.isNotEmpty() } }.days.single().items,
        ).hasSize(1)
        assertThat(
            history.state.value.days
                .single()
                .totals.salesMinor,
        ).containsExactly("AUD", 2_000L)

        await { container.captures.addTip(id, 400) }
        val after = await { history.state.first { it.filter == HistoryFilter.AWAITING_TIP && it.days.isEmpty() } }
        assertThat(after.days).isEmpty()
        history.setFilter(HistoryFilter.ALL)
        val day = await { history.state.first { it.filter == HistoryFilter.ALL && it.days.isNotEmpty() } }.days.single()
        assertThat(day.totals.salesMinor).containsExactly("AUD", 2_400L)
        assertThat(day.totals.tipsMinor).containsExactly("AUD", 400L)
    }

    @Test
    fun `a cancelled tip sale is neither a sale nor shown with the pre-authorisations`() {
        val id = tipSale()
        val vm = detail(id)
        await { vm.state.first { it.canCancel } }
        val cancellationId = vm.cancel()!!
        await { container.refunds.state.first { it == TransactionState.Finished(cancellationId) } }
        container.refunds.acknowledge()
        val history = HistoryViewModel(container.history)
        val day =
            await {
                history.state.first {
                    it.days.isNotEmpty() && it.days
                        .single()
                        .items.size == 2
                }
            }.days.single()
        assertThat(day.totals.saleCount).isEqualTo(0)
        assertThat(day.totals.refundCount).isEqualTo(0)
        history.setFilter(HistoryFilter.PRE_AUTHS)
        assertThat(await { history.state.first { it.filter == HistoryFilter.PRE_AUTHS } }.days).isEmpty()
    }

    @Test
    fun `a failed capture is retried from the sale's detail`() {
        val id = tipSale()
        // In terminal mode with only a merchant account the API is not set up, so the capture fails.
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, merchantAccount = "Merchant")) }
        await {
            container.sales.update(
                container.sales
                    .get(id)!!
                    .sale
                    .copy(tipMinor = 100, capturedMinor = 2_100, captureStatus = CaptureStatus.FAILED),
            )
        }
        val vm = detail(id)
        assertThat(await { vm.state.first { it.record != null } }.canRetryCapture).isTrue()
        vm.retryCapture()
        assertThat(
            await {
                vm.state.first { it.retry.isError }
            }.retry.message,
        ).isEqualTo("Not captured: Enter the Checkout API key in Terminal settings")
        env.useSimulator()
        vm.retryCapture()
        await { vm.state.first { it.retry.done } }
        assertThat(await { vm.state.first { it.record?.sale?.captureStatus == CaptureStatus.REQUESTED } }.canRetryCapture).isFalse()
    }

    @Test
    fun `pre-authorisations are captured or adjusted from the capture screen`() {
        val (tax, _) = seed()
        val deposit =
            await {
                container.catalog.product(
                    container.catalog.saveProduct(
                        ProductEntity(name = "Deposit", priceMinor = 20_000, taxRateId = tax.id, kind = SaleKind.PRE_AUTHORISATION),
                    ),
                )!!
            }
        container.preAuthSession.addProduct(deposit, tax)
        val pay = checkout(SaleKind.PRE_AUTHORISATION)
        await { pay.state.first { !it.totals.isEmpty } }
        assertThat(pay.pay()).isTrue()
        val id = await { (container.payments.state.first { it is TransactionState.Finished } as TransactionState.Finished).id }
        val shown = await { detail(id).state.first { it.record != null } }
        assertThat(shown.canCapture).isTrue()
        assertThat(shown.canAdjust).isTrue()

        val adjust = CaptureViewModel(id, adjustOnly = true, container.sales, container.captures, container.terminalStatus.state)
        // The amount held is proposed; the first key starts a new amount.
        assertThat(await { adjust.state.first { it.sale != null } }.amountMinor).isEqualTo(20_000)
        listOf(2, 5, 0, 0, 0).forEach { digit -> adjust.updateEntry { it.append(digit) } }
        assertThat(await { adjust.state.first { it.amountMinor == 25_000L } }.canSubmit).isTrue()
        adjust.submit()
        await { adjust.state.first { it.submission.done } }
        assertThat(await { container.sales.get(id)!! }.sale.heldMinor).isEqualTo(25_000)

        val capture = CaptureViewModel(id, adjustOnly = false, container.sales, container.captures, container.terminalStatus.state)
        assertThat(await { capture.state.first { it.sale?.heldMinor == 25_000L } }.amountMinor).isEqualTo(25_000)
        listOf(2, 4, 0, 0, 0).forEach { digit -> capture.updateEntry { it.append(digit) } }
        await { capture.state.first { it.amountMinor == 24_000L } }
        capture.submit()
        await { capture.state.first { it.submission.done } }
        val captured = await { container.sales.get(id)!! }.sale
        assertThat(captured.capturedMinor).isEqualTo(24_000)
        assertThat(captured.captured).isTrue()
        // Now it is captured, it can be neither captured nor adjusted again.
        capture.submit()
        assertThat(capture.state.value.canSubmit).isFalse()

        // Without the API, adjusting is not offered and a capture is only recorded.
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
        val ca = await { container.terminalStatus.state.first { it.captureMode == CaptureMode.CUSTOMER_AREA } }
        assertThat(ca.apiProblem).isNull()
        assertThat(
            CaptureViewModel(
                id,
                adjustOnly = true,
                container.sales,
                container.captures,
                container.terminalStatus.state,
            ).state.value.canSubmit,
        ).isFalse()
    }

    @Test
    fun `a refused tip is explained and can be entered again`() {
        val id = tipSale()
        await {
            container.sales.update(
                container.sales
                    .get(id)!!
                    .sale
                    .copy(pspReference = null),
            )
        }
        val tip = TipViewModel(id, container.sales, container.captures, container.terminalStatus.state)
        // Without the PSP reference a capture cannot refer to the payment, so the tip cannot be entered.
        assertThat(await { tip.state.first { it.sale != null } }.canSubmit).isFalse()
        await {
            container.sales.update(
                container.sales
                    .get(id)!!
                    .sale
                    .copy(pspReference = "PSP", refundedMinor = 2_000),
            )
        }
        // Cancelled meanwhile.
        assertThat(await { tip.state.first { it.sale?.pspReference == "PSP" } }.canSubmit).isFalse()
        val problem = Submission().after(CaptureResult.Refused("No"), 2_500)
        assertThat(problem.problem).isEqualTo(CaptureProblem.Refused(2_500, "No"))
        assertThat(problem.done).isFalse()
    }

    @Test
    fun `settings save the API key before testing it`() {
        val vm =
            SettingsViewModel(
                container.settings,
                container.secrets,
                container.pinManager,
                container.sessionLock,
                SettingsChecks(container.terminalStatus, container.receipts, container.api),
                container.history,
                container.catalog,
                SettingsMessages("Connected", "Failed", "printer", "no printer", "Sent to %s", "Printed", "Not stored (%s)", "Works"),
            )
        // The simulator stands in for the API.
        vm.saveAndTest(Secret.CHECKOUT_API_KEY)
        assertThat(await { vm.actions.first { it.api.done } }.api.message).isEqualTo("Works")

        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, merchantAccount = "Merchant")) }
        vm.saveAndTest(Secret.CHECKOUT_API_KEY, " secret-key ")
        val tested = await { vm.actions.first { it.apiKeyStored && !it.api.running } }
        assertThat(tested.api.isError).isTrue()
        assertThat(tested.api.message).contains("Test the connection to the terminal first")
        assertThat(await { container.secrets.get(Secret.CHECKOUT_API_KEY) }).isEqualTo("secret-key")

        env.cipher.failEncrypt = true
        vm.saveAndTest(Secret.CHECKOUT_API_KEY, "other")
        assertThat(await { vm.actions.first { it.api.isError && !it.apiKeyStored } }.api.message).startsWith("Not stored")
        env.cipher.failEncrypt = false
    }
}
