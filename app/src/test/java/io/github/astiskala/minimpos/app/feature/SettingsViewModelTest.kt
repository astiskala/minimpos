package io.github.astiskala.minimpos.app.feature

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.AppContainer
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakeTerminal
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.CategoryEntity
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.EmailCapture
import io.github.astiskala.minimpos.app.data.settings.MerchantCopyPolicy
import io.github.astiskala.minimpos.app.data.settings.ShopperReferenceSource
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.feature.history.HistoryFilter
import io.github.astiskala.minimpos.app.feature.history.HistoryViewModel
import io.github.astiskala.minimpos.app.feature.history.SaleDetailViewModel
import io.github.astiskala.minimpos.app.feature.history.SaleOperations
import io.github.astiskala.minimpos.app.feature.products.ProductEditViewModel
import io.github.astiskala.minimpos.app.feature.products.ProductsViewModel
import io.github.astiskala.minimpos.app.feature.refund.RefundOption
import io.github.astiskala.minimpos.app.feature.refund.RefundResultViewModel
import io.github.astiskala.minimpos.app.feature.refund.RefundViewModel
import io.github.astiskala.minimpos.app.feature.sale.CheckoutViewModel
import io.github.astiskala.minimpos.app.feature.sale.SaleResultViewModel
import io.github.astiskala.minimpos.app.feature.sale.SaleViewModel
import io.github.astiskala.minimpos.app.feature.settings.SettingsChecks
import io.github.astiskala.minimpos.app.feature.settings.SettingsViewModel
import io.github.astiskala.minimpos.app.payment.TransactionState
import io.github.astiskala.minimpos.app.refund.RefundInvalidReason
import io.github.astiskala.minimpos.app.refund.Refundability
import io.github.astiskala.minimpos.app.refund.RefundablePayment
import io.github.astiskala.minimpos.app.terminal.ReceiptBusiness
import io.github.astiskala.minimpos.core.codec.RefundQrPayload
import io.github.astiskala.minimpos.core.receipt.PlainTextReceiptRenderer
import io.github.astiskala.minimpos.core.receipt.ReceiptElement
import io.github.astiskala.minimpos.core.shopper.EmailReferenceMode
import io.github.astiskala.minimpos.core.shopper.ShopperReferences
import io.github.astiskala.minimpos.core.tax.TaxMode
import io.github.astiskala.minimpos.core.tax.TaxRates
import io.github.astiskala.minimpos.terminal.client.RetryAdvice
import io.github.astiskala.minimpos.terminal.simulator.SimulatedOutcome
import io.github.astiskala.minimpos.terminal.simulator.TerminalSimulator
import io.github.astiskala.minimpos.terminal.transport.StoreDetailsApi
import io.github.astiskala.minimpos.terminal.transport.StoreListing
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
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

    private fun settingsViewModel(target: AppContainer = container) =
        SettingsViewModel(
            target.pricingChanges,
            target.secrets,
            target.pinManager,
            target.sessionLock,
            SettingsChecks(target.terminalStatus, target.receipts, target.api, target.receiptBusinessDetails),
            target.history,
            target.catalog,
            target::sampleReceipt,
        )

    @Test
    fun `receipt import reports setup problems and rejects unoffered proposals`() {
        env.useSimulator()
        val vm = settingsViewModel()
        vm.businessImport.find()
        assertThat(await { vm.businessImport.state.first { it.lookup.isError } }.lookup.outcome)
            .isEqualTo(ActionOutcome.NotSetUp(SetupProblem.API_REQUIRED))
        env.useLinks()
        vm.businessImport.find()
        assertThat(await { vm.businessImport.state.first { it.stores != null } }.stores).isEmpty()
        val before = await { container.settings.current() }
        vm.businessImport.choose(ReceiptBusiness("ST1", "cafe", "Not offered", "", ""))
        assertThat(await { container.settings.current() }).isEqualTo(before)
        vm.businessImport.choose(null)
        assertThat(vm.businessImport.state.value.stores).isNull()
    }

    @Test
    fun `receipt lookup failures leave receipt settings unchanged`() {
        val failed = TestEnvironment(stores = StoreDetailsApi { StoreListing.Failed("Missing store permission") })
        try {
            failed.useLinks()
            val vm = settingsViewModel(failed.container)
            val before = await { failed.container.settings.current() }
            vm.businessImport.find()
            assertThat(await { vm.businessImport.state.first { it.lookup.isError } }.lookup.outcome)
                .isEqualTo(ActionOutcome.Failed("Missing store permission"))
            assertThat(await { failed.container.settings.current() }).isEqualTo(before)
            assertThat(vm.businessImport.state.value.stores).isNull()
        } finally {
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
            assertThat((rejected.connection.outcome as? ActionOutcome.ConnectionFailed)?.reason).contains("shared key")
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
            }.connection.outcome.let { (it as? ActionOutcome.ConnectionFailed)?.reason },
        ).contains("Cannot connect")
    }
}
