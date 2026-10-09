package app.minimpos.app.feature

import app.minimpos.app.AppContainer
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeStoreDetails
import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.closeViewModels
import app.minimpos.app.data.db.CategoryEntity
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
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
import app.minimpos.app.feature.settings.SettingsChecks
import app.minimpos.app.feature.settings.SettingsTest
import app.minimpos.app.feature.settings.SettingsViewModel
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.refund.RefundInvalidReason
import app.minimpos.app.refund.Refundability
import app.minimpos.app.refund.RefundablePayment
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.ReceiptBusiness
import app.minimpos.app.terminal.TerminalSetupSource
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.core.shopper.EmailReferenceMode
import app.minimpos.core.shopper.ShopperReferences
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.tax.TaxRates
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.client.RetryAdvice
import app.minimpos.terminal.simulator.SimulatedModifications
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.StoreLookup
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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

/** Settings through its view model: settings, secrets, tax rates, and the connection, email and print checks. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    private val env = TestEnvironment()
    private val container = env.container
    private val viewModels = mutableListOf<SettingsViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        closeViewModels(viewModels)
        env.close()
        Dispatchers.resetMain()
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

    private fun settingsViewModel(
        target: AppContainer = container,
        api: AdyenApi = target.api,
    ) = SettingsViewModel(
        target.pricingChanges,
        target.secrets,
        target.pinManager,
        target.sessionLock,
        SettingsChecks(target.terminalStatus, target.receipts, api, target.receiptBusinessDetails),
        target.history,
        target.catalog,
        target::sampleReceipt,
    ).also(viewModels::add)

    @Test
    fun `an API check runs once when another action finishes while it waits`() {
        env.useSimulator()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val modifications =
            object : PaymentModifications by SimulatedModifications() {
                override suspend fun verify(): Fault? {
                    calls++
                    entered.complete(Unit)
                    release.await()
                    return null
                }
            }
        val api =
            AdyenApi(TerminalSetupSource(container.settings, container.secrets, container.device), modifications, verifyAccess = { null })
        val vm = settingsViewModel(api = api)
        vm.saveAndTest(Secret.ADYEN_API_KEY)
        await { entered.await() }
        vm.saveAndTest(Secret.TERMINAL_PASSPHRASE)
        await { vm.actions.first { it.connection.done } }
        release.complete(Unit)
        val finished = await { vm.actions.first { it.api.done } }
        assertThat(calls).isEqualTo(1)
        assertThat(finished.connection.done).isTrue()
    }

    @Test
    fun `a connection check runs once when another action finishes while it waits`() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val terminal =
            FakeTerminal(beforeSend = {
                calls++
                entered.complete(Unit)
                release.await()
            })
        val onTerminal = TestEnvironment(FakeDevice(detectedPoiId = "AMS1-000168223606144"), terminal)
        try {
            onTerminal.useCheckoutApi()
            onTerminal.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "key")) }
            await { onTerminal.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
            val vm = settingsViewModel(onTerminal.container)
            vm.saveAndTest(Secret.TERMINAL_PASSPHRASE)
            await { entered.await() }
            vm.sendTestEmail("shopper@example.com")
            await { vm.actions.first { it.email.isError } }
            release.complete(Unit)
            val finished = await { vm.actions.first { it.connection.done } }
            assertThat(calls).isEqualTo(1)
            assertThat(finished.email.isError).isTrue()
        } finally {
            closeViewModels(viewModels)
            onTerminal.close()
        }
    }

    @Test
    fun `receipt import reports setup problems and rejects unoffered proposals`() {
        env.useSimulator()
        val vm = settingsViewModel()
        vm.businessImport.find()
        assertThat(await { vm.businessImport.state.first { it.lookup.isError } }.lookup.outcome)
            .isEqualTo(ActionOutcome.NotSetUp(SetupProblem.API_REQUIRED))
        env.useLinks()
        env.updateSettings { it.copy(terminal = it.terminal.copy(storeId = "ST1")) }
        vm.businessImport.find()
        assertThat(await { vm.businessImport.state.first { it.lookup.isError } }.lookup.outcome)
            .isEqualTo(ActionOutcome.NotSetUp(SetupProblem.STORE_ACCESS))
        val before = await { container.settings.current() }
        vm.businessImport.choose(ReceiptBusiness("Not offered", "", ""))
        assertThat(await { container.settings.current() }).isEqualTo(before)
        vm.businessImport.choose(null)
        assertThat(vm.businessImport.state.value.proposal).isNull()
    }

    @Test
    fun `receipt lookup failures leave receipt settings unchanged`() {
        val failed =
            TestEnvironment(
                stores = FakeStoreDetails(storeAnswer = StoreLookup.Failed(Fault.Permission(ApiKey.ADYEN, "Management API—Stores read"))),
            )
        try {
            failed.useLinks()
            failed.updateSettings { it.copy(terminal = it.terminal.copy(storeId = "ST1")) }
            val vm = settingsViewModel(failed.container)
            val before = await { failed.container.settings.current() }
            vm.businessImport.find()
            assertThat(await { vm.businessImport.state.first { it.lookup.isError } }.lookup.outcome)
                .isEqualTo(ActionOutcome.Failed(Failure.Remote(Fault.Permission(ApiKey.ADYEN, "Management API—Stores read"))))
            assertThat(await { failed.container.settings.current() }).isEqualTo(before)
            assertThat(vm.businessImport.state.value.proposal).isNull()
        } finally {
            closeViewModels(viewModels)
            failed.close()
        }
    }

    @Test
    fun `settings presents pricing confirmation without owning the commit sequence`() {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        val (tax, product) = seedCatalogue()
        val session = container.session(SaleKind.SALE)
        session.addProduct(product, tax)
        val vm = settingsViewModel()
        vm.update { it.copy(payment = it.payment.copy(currencyCode = "JPY")) }
        await { vm.pricing.pending.first { it != null } }
        assertThat(await { container.settings.current() }.payment.currencyCode).isEqualTo("AUD")
        vm.pricing.cancel()
        assertThat(vm.pricing.pending.value).isNull()
        assertThat(
            session.cart.value.lines
                .single()
                .unitPrice,
        ).isEqualTo(450)
        vm.update { it.copy(payment = it.payment.copy(currencyCode = "JPY")) }
        await { vm.pricing.pending.first { it != null } }
        vm.pricing.confirm()
        await { vm.pricing.pending.first { it == null } }
        val saved = await { vm.state.first { it.settings.payment.currencyCode == "JPY" && it.settings.pricingChange == null } }
        assertThat(saved.settings.payment.currencyCode).isEqualTo("JPY")
        assertThat(await { container.catalog.product(product.id) }!!.priceMinor).isEqualTo(5)
        assertThat(
            session.cart.value.lines
                .single()
                .unitPrice,
        ).isEqualTo(5)
    }

    @Test
    fun `settings view model saves the passphrase before testing, and reports what went wrong`() {
        val onTerminal = TestEnvironment(FakeDevice(detectedPoiId = "AMS1-000168223606144"), FakeTerminal())
        try {
            onTerminal.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "key")) }
            val vm = settingsViewModel(onTerminal.container)

            vm.saveAndTest(Secret.TERMINAL_PASSPHRASE, "wrong passphrase")
            val rejected = await { vm.actions.first { it.connection.isError } }
            assertThat((rejected.connection.outcome as? ActionOutcome.ConnectionFailed)?.failure)
                .isEqualTo(Failure.Remote(Fault.KeyRejected(ExternalText("Crypto error"))))
            assertThat(rejected.passphraseStored).isTrue()
            vm.dismissConnectionResult()
            assertThat(vm.actions.value.connection.outcome).isNull()

            vm.saveAndTest(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple")
            val connected = await { vm.actions.first { it.connection.done } }
            assertThat(connected.connection.outcome).isInstanceOf(ActionOutcome.Connected::class.java)
            assertThat(await { onTerminal.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse battery staple")

            // A device that cannot encrypt secrets is reported, not a crash, and the saved passphrase is kept.
            onTerminal.cipher.failEncrypt = true
            vm.saveAndTest(Secret.TERMINAL_PASSPHRASE, "another passphrase")
            val failed = await { vm.actions.first { it.connection.isError } }
            assertThat(failed.connection.outcome).isEqualTo(ActionOutcome.SecretNotStored("ProviderException: Keystore unavailable"))
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
            closeViewModels(viewModels)
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

        vm.saveAndTest(Secret.TERMINAL_PASSPHRASE)
        assertThat(await { vm.actions.first { it.connection.done } }.connection.outcome)
            .isEqualTo(ActionOutcome.Connected(globalStatus = "OK", hasPrinter = true))
        // The sample receipt follows the settings, before and after they are stored.
        val sample = await { vm.state.first { it.sampleReceipt != null } }.sampleReceipt!!
        assertThat(sample.qrCodes).hasSize(1)
        assertThat(PlainTextReceiptRenderer(48).render(sample)).contains("Shop")
        vm.printTest()
        assertThat(await { vm.actions.first { it.print.done } }.print.outcome).isEqualTo(ActionOutcome.Printed)
        assertThat(container.virtualPrinter.jobs.value).isNotEmpty()
        vm.sendTestEmail("a@b.co")
        assertThat(await { vm.actions.first { !it.email.running && it.email.outcome != null } }.email.isError).isTrue()
        vm.clearHistory()
        await { vm.actions.first { it.cleared } }

        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL)) }
        vm.saveAndTest(Secret.TERMINAL_PASSPHRASE)
        assertThat(await { vm.actions.first { it.connection.isError } }.connection.outcome)
            .isEqualTo(ActionOutcome.NotSetUp(SetupProblem.ENVIRONMENT))
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        poiIdOverride = "S1F2-000000001",
                        keyIdentifier = "k",
                        host = "127.0.0.1",
                        environment = TerminalEnvironment.TEST,
                    ),
            )
        }
        vm.saveAndTest(Secret.TERMINAL_PASSPHRASE)
        assertThat(
            await {
                vm.actions.first { it.connection.isError && it.connection.outcome is ActionOutcome.ConnectionFailed }
            }.connection.outcome.let { (it as? ActionOutcome.ConnectionFailed)?.failure },
        ).isEqualTo(Failure.Remote(Fault.Unreachable("127.0.0.1:8443", terminal = true)))
    }
}
