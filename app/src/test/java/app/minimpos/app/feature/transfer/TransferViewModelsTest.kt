package app.minimpos.app.feature.transfer

import android.os.Looper
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeManagement
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.repo.ImportMode
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.TransferSeal
import app.minimpos.app.terminal.SetupQuestion
import app.minimpos.core.codec.QrChunks
import app.minimpos.core.codec.SealedSecrets
import app.minimpos.core.codec.Transfer
import app.minimpos.core.codec.TransferCodec
import app.minimpos.core.logo.MonoBitmap
import app.minimpos.terminal.paymentsapp.ManagementResult
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRoles
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.MerchantAccount
import app.minimpos.terminal.transport.MerchantAccountListing
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Sharing with and setting up from another terminal through the view models. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TransferViewModelsTest {
    private val source = TestEnvironment()
    private val target = TestEnvironment()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        source.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD"), receipt = it.receipt.copy(businessName = "Cafe")) }
        await {
            val rate = source.container.catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
            source.container.catalog.saveProduct(ProductEntity(name = "Latte", priceMinor = 450, taxRateId = rate))
            source.container.catalog.saveProduct(ProductEntity(name = "Tea", priceMinor = 400, taxRateId = rate))
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        source.close()
        target.close()
    }

    /**
     * Waits for [import] to reach a state that matches [condition], running the main looper meanwhile: parts of the
     * import resume on it, so blocking it as [await] does would stall them.
     */
    internal fun settled(
        import: TransferImportViewModel,
        condition: (ImportUiState) -> Boolean,
    ): ImportUiState {
        runBlocking {
            withTimeout(10_000) {
                while (!condition(import.state.value)) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
        return import.state.value
    }

    private fun exportCodes(configure: (TransferExportViewModel) -> Unit = {}): Pair<List<String>, String?> {
        val export = TransferExportViewModel(source.container.setupTransfer, "AUD", chunkSize = 40)
        configure(export)
        export.show()
        val shown = await { export.state.first { it.export != null } }
        return shown.codes to shown.export!!.code
    }

    @Test
    fun `completed transfer processing is dispatched and duplicate scans are ignored until decoding finishes`() {
        val cpu = StandardTestDispatcher()
        val (codes, _) = exportCodes()
        val import = TransferImportViewModel("AUD", target.container.setupImport, cpu)
        settled(import) { it is ImportUiState.Scanning }
        codes.forEach(import::onCode)
        assertThat(import.state.value).isEqualTo(ImportUiState.Importing)
        import.onCode("not-a-transfer")
        codes.forEach(import::onCode)
        assertThat(import.state.value).isEqualTo(ImportUiState.Importing)
        cpu.scheduler.runCurrent()
        val ready = settled(import) { it is ImportUiState.Ready } as ImportUiState.Ready
        assertThat(ready.received.catalogue!!.products).hasSize(2)
    }

    @Test
    fun `the export offers secrets only when some are set, and shows a new code each time`() {
        val export = TransferExportViewModel(source.container.setupTransfer, "AUD", chunkSize = 40)
        var state = await { export.state.first { true } }
        assertThat(state.sharesSecrets).isFalse()
        assertThat(state.canShow).isTrue()
        export.setContents { it.copy(catalogue = false, settings = false) }
        assertThat(export.state.value.canShow).isFalse()
        export.show()
        assertThat(export.state.value.export).isNull()

        await { source.container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        val withSecrets = TransferExportViewModel(source.container.setupTransfer, "AUD", chunkSize = 40)
        state = await { withSecrets.state.first { it.secretsAvailable.isNotEmpty() } }
        assertThat(state.sharesSecrets).isTrue()
        withSecrets.show()
        val first = await { withSecrets.state.first { it.export != null } }
        assertThat(first.export!!.code).isNotNull()
        assertThat(first.codes.size).isGreaterThan(1)
        // While the codes are shown the choice cannot change.
        withSecrets.setContents { it.copy(secrets = false) }
        assertThat(withSecrets.state.value.contents.secrets).isTrue()
        withSecrets.hide()
        withSecrets.show()
        val second = await { withSecrets.state.first { it.export != null } }
        assertThat(second.export!!.code).isNotEqualTo(first.export.code)
        assertThat(second.codes.first().substring(5, 9)).isNotEqualTo(first.codes.first().substring(5, 9))
    }

    @Test
    fun `the import scans codes in any order and imports the catalogue, settings and secrets`() {
        await { source.container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        val (codes, code) = exportCodes()
        val import = TransferImportViewModel("NZD", target.container.setupImport)
        settled(import) { it is ImportUiState.Scanning }
        import.onCode("hello")
        assertThat((import.state.value as ImportUiState.Scanning).error).isEqualTo(ImportError.NOT_A_TRANSFER)
        codes.reversed().dropLast(1).forEach(import::onCode)
        assertThat((import.state.value as ImportUiState.Scanning).received).isEqualTo(codes.size - 1)
        import.onCode(codes.first())
        val ready = settled(import) { it is ImportUiState.Ready } as ImportUiState.Ready
        // The settings bring the currency along, so the catalogue's matches.
        assertThat(ready.currencyMatches).isTrue()
        assertThat(ready.received.catalogue!!.products).hasSize(2)
        import.onCode(codes.first())

        // A code that cannot be a transfer code is refused straight away, a wrong one after trying it.
        import.setCode("abc")
        import.import()
        assertThat((import.state.value as ImportUiState.Ready).wrongCode).isTrue()
        import.setCode("AAAA-AAAA-AAAA")
        assertThat((import.state.value as ImportUiState.Ready).wrongCode).isFalse()
        import.import()
        settled(import) { it is ImportUiState.Ready && it.wrongCode }

        import.setMode(ImportMode.REPLACE)
        import.setCode(code!!)
        import.import()
        val done = settled(import) { it is ImportUiState.Done } as ImportUiState.Done
        assertThat(done.result.catalogue!!.productsAdded).isEqualTo(2)
        assertThat(done.result.settings).isTrue()
        assertThat(done.result.secrets).containsExactly(Secret.SMTP_PASSWORD)
        assertThat(await { target.container.settings.current() }.receipt.businessName).isEqualTo("Cafe")
        assertThat(await { target.container.secrets.get(Secret.SMTP_PASSWORD) }).isEqualTo("pw")
        import.import()
        import.setCode("x")
        assertThat(import.state.value).isInstanceOf(ImportUiState.Done::class.java)
    }

    /** A simulator with history, its Payments app boarded but not listed for the merchant account the import chooses. */
    private inner class UnlistedTapToPay : AutoCloseable {
        private val details =
            object : TerminalDetailsApi {
                override suspend fun credential(environment: TerminalEnvironment) =
                    CredentialLookup.Allowed(CredentialRoles(AdyenRole.required(), emptyList()))

                override suspend fun merchantAccounts(environment: TerminalEnvironment) =
                    MerchantAccountListing.Listed(listOf(MerchantAccount("A", "First"), MerchantAccount("B", "Second")))

                override suspend fun accountKey(
                    merchantAccount: String,
                    storeId: String?,
                    environment: TerminalEnvironment,
                ) = SharedKeyLookup.Found(DiscoveredKey("store-key", 2, "correct horse battery staple"))

                override suspend fun terminals(
                    environment: TerminalEnvironment,
                    id: String?,
                ) = TerminalListing.Listed(emptyList(), environment)

                override suspend fun sharedKey(
                    id: String,
                    environment: TerminalEnvironment,
                ) = SharedKeyLookup.Missing
            }
        val phone = FakePaymentsApp().apply { boarded = true }
        val management = FakeManagement().apply { registrationResult = ManagementResult.NotBoarded }
        val env =
            TestEnvironment(
                FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST)),
                paymentsApp = phone,
                management = management,
                terminalDetails = details,
                verifiedSetup = false,
            )
        val import = TransferImportViewModel("AUD", env.container.setupImport)

        /** Imports Automatic Tap to Pay for merchant account B, confirming history loss, until Tap to Pay is required. */
        fun untilBoardingRequired(): ImportUiState.Ready {
            env.useSimulator()
            await { env.container.sampleData.populate(env.container.settings.current()) }
            val seal = TransferSeal(iterations = 1_000)
            val code = seal.newCode()
            val public = Transfer(connection = """{"destination":"tapToPay","environment":"TEST","automatic":true}""")
            val encrypted = seal.seal("""{"ADYEN_API_KEY":"key"}""".toByteArray(), code, TransferCodec.authenticationData(public))
            settled(import) { it is ImportUiState.Scanning }
            QrChunks
                .split(TransferCodec.encode(public.copy(sealedSecrets = SealedSecrets(encrypted))), "TAPP")
                .forEach { import.onCode(it.encode()) }
            settled(import) { it is ImportUiState.Ready }
            import.setCode(code)
            import.import()
            settled(import) { (it as? ImportUiState.Ready)?.question is SetupQuestion.Merchants }
            import.chooseMerchant("B")
            settled(import) { (it as? ImportUiState.Ready)?.historySwitch != null }
            import.reviewSetup(true)
            return settled(import) { it is ImportUiState.Ready && it.boardingRequired } as ImportUiState.Ready
        }

        override fun close() = env.close()
    }

    @Test
    fun `confirmed history loss is not asked again when the import needs something else`() =
        UnlistedTapToPay().use { assertThat(it.untilBoardingRequired().historySwitch).isNull() }

    @Test
    fun `a Payments app not listed for the chosen account is set up again only on request`() =
        UnlistedTapToPay().use { setup ->
            val import = setup.import
            assertThat(setup.untilBoardingRequired().setUpAgain).isTrue()
            setup.phone.opened.clear()
            import.setUpTapToPay()
            assertThat((settled(import) { it is ImportUiState.Ready } as ImportUiState.Ready).boardingRequired).isTrue()
            assertThat(setup.phone.opened.none { "reboard=true" in it }).isTrue()
            setup.management.registrationResult = ManagementResult.Done()
            import.setUpTapToPay(again = true)
            assertThat(settled(import) { it is ImportUiState.Done }).isInstanceOf(ImportUiState.Done::class.java)
            assertThat(setup.phone.opened.any { "reboard=true" in it }).isTrue()
            val terminal =
                await {
                    setup.env.container.settings
                        .current()
                }.terminal
            assertThat(terminal.merchantAccount).isEqualTo("B")
            assertThat(terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
        }

    @Test
    fun `corrupt transfer data clears scanned chunks and allows another transfer`() {
        val import = TransferImportViewModel("NZD", target.container.setupImport)
        settled(import) { it is ImportUiState.Scanning }
        import.onCode("MPC1:ABCD:1/1:AAAA")
        assertThat((settled(import) { it is ImportUiState.Scanning } as ImportUiState.Scanning).error).isEqualTo(ImportError.CORRUPT)
        val (codes, _) = exportCodes()
        codes.forEach(import::onCode)
        assertThat(settled(import) { it is ImportUiState.Ready }).isInstanceOf(ImportUiState.Ready::class.java)
    }

    @Test
    fun `logo only transfer imports after confirmation without a code`() {
        val bitmap = MonoBitmap(3, 1, byteArrayOf(0xA0.toByte()))
        val codes = QrChunks.split(TransferCodec.encode(Transfer(logo = bitmap)), "LOGO").map { it.encode() }
        val import = TransferImportViewModel("AUD", target.container.setupImport)
        settled(import) { it is ImportUiState.Scanning }
        codes.forEach(import::onCode)
        val ready = settled(import) { it is ImportUiState.Ready } as ImportUiState.Ready
        assertThat(ready.received.hasLogo).isTrue()
        assertThat(ready.received.accepts("")).isTrue()
        assertThat(await { target.container.receiptLogo.current() }).isNull()
        import.import()
        val done = settled(import) { it is ImportUiState.Done } as ImportUiState.Done
        assertThat(done.result.logo).isTrue()
        assertThat(await { target.container.receiptLogo.current() }).isEqualTo(bitmap)
    }

    @Test
    fun `without the code nothing imports and catalogue-only transfers still need a code`() {
        await { source.container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        val (codes, code) = exportCodes()
        val import = TransferImportViewModel("NZD", target.container.setupImport)
        settled(import) { it is ImportUiState.Scanning }
        codes.forEach(import::onCode)
        settled(import) { it is ImportUiState.Ready }
        import.import()
        assertThat((import.state.value as ImportUiState.Ready).wrongCode).isTrue()
        assertThat(await { target.container.secrets.get(Secret.SMTP_PASSWORD) }).isNull()
        assertThat(
            await {
                target.container.catalog.products
                    .first()
            },
        ).isEmpty()
        import.setCode(checkNotNull(code))
        import.import()
        val done = settled(import) { it is ImportUiState.Done } as ImportUiState.Done
        assertThat(done.result.secrets).containsExactly(Secret.SMTP_PASSWORD)

        val (catalogueCodes, catalogueCode) =
            exportCodes {
                it.setContents { contents ->
                    contents.copy(
                        settings = false,
                        secrets = false,
                    )
                }
            }
        assertThat(catalogueCode).isNotEmpty()
        val catalogueOnly = TransferImportViewModel("NZD", target.container.setupImport)
        settled(catalogueOnly) { it is ImportUiState.Scanning }
        catalogueCodes.forEach(catalogueOnly::onCode)
        val ready = settled(catalogueOnly) { it is ImportUiState.Ready } as ImportUiState.Ready
        assertThat(ready.currencyMatches).isFalse()
        assertThat(ready.received.accepts("")).isFalse()
    }
}
