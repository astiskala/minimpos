package io.github.astiskala.minimpos.app.feature

import android.os.Looper
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.feature.transfer.ImportError
import io.github.astiskala.minimpos.app.feature.transfer.ImportUiState
import io.github.astiskala.minimpos.app.feature.transfer.TransferExportViewModel
import io.github.astiskala.minimpos.app.feature.transfer.TransferImportViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
    private fun settled(
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
        val import = TransferImportViewModel(target.container.setupTransfer, "NZD", target.container.setupImport)
        settled(import) { it is ImportUiState.Scanning }
        import.onCode("hello")
        assertThat((import.state.value as ImportUiState.Scanning).error).isEqualTo(ImportError.NOT_A_TRANSFER)
        codes.reversed().dropLast(1).forEach(import::onCode)
        assertThat((import.state.value as ImportUiState.Scanning).received).isEqualTo(codes.size - 1)
        import.onCode(codes.first())
        val ready = import.state.value as ImportUiState.Ready
        // The settings bring the currency along, so the catalogue's matches.
        assertThat(ready.currencyMatches).isTrue()
        assertThat(ready.received.catalogue!!.products).hasSize(2)
        import.onCode(codes.first())

        // A code that cannot be a transfer code is refused straight away, a wrong one after trying it.
        import.setCode("abc")
        import.import()
        assertThat((import.state.value as ImportUiState.Ready).wrongCode).isTrue()
        import.setCode("2222-2222-2222")
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

        import.restart()
        assertThat(import.state.value).isEqualTo(ImportUiState.Scanning())
        import.onCode("MPC1:ABCD:1/1:AAAA")
        assertThat((import.state.value as ImportUiState.Scanning).error).isEqualTo(ImportError.CORRUPT)
    }

    @Test
    fun `without the code nothing imports and catalogue-only transfers still need a code`() {
        await { source.container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        val (codes, code) = exportCodes()
        val import = TransferImportViewModel(target.container.setupTransfer, "NZD", target.container.setupImport)
        settled(import) { it is ImportUiState.Scanning }
        codes.forEach(import::onCode)
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
        val catalogueOnly = TransferImportViewModel(target.container.setupTransfer, "NZD", target.container.setupImport)
        settled(catalogueOnly) { it is ImportUiState.Scanning }
        catalogueCodes.forEach(catalogueOnly::onCode)
        val ready = catalogueOnly.state.value as ImportUiState.Ready
        assertThat(ready.currencyMatches).isFalse()
        assertThat(ready.received.accepts("")).isFalse()
    }
}
