package io.minimpos.app.feature.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.minimpos.app.data.repo.CatalogRepository
import io.minimpos.app.data.repo.ImportMode
import io.minimpos.app.data.repo.ImportSummary
import io.minimpos.app.feature.launchWrite
import io.minimpos.core.catalogue.Catalogue
import io.minimpos.core.codec.CatalogueCodec
import io.minimpos.core.codec.CatalogueFormatException
import io.minimpos.core.codec.QrChunkAssembler
import io.minimpos.core.codec.QrChunks
import io.minimpos.core.ids.Ids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * What the catalogue export screen shows.
 *
 * @property loading Whether the codes are still being made.
 * @property codes The QR code contents, in order (`MPC1:` chunks of one set).
 * @property catalogue The exported catalogue, for its counts; null while loading.
 */
data class ExportUiState(
    val loading: Boolean = true,
    val codes: List<String> = emptyList(),
    val catalogue: Catalogue? = null,
)

/**
 * Encodes the whole catalogue into QR codes for another terminal to scan. Each export gets a new random set ID, so its
 * codes cannot be mixed up with an earlier export's.
 *
 * @param catalog The catalogue to export, read once.
 * @param currencyCode The currency the prices are in, carried in the catalogue.
 * @param chunkSize Data characters per QR code.
 * @param random Source of the set ID; tests make it predictable.
 */
class CatalogueExportViewModel(
    catalog: CatalogRepository,
    currencyCode: String,
    chunkSize: Int = QrChunks.DEFAULT_MAX_DATA_CHARS,
    random: Random = Random.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())

    /** The export state. */
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val catalogue = catalog.export(currencyCode)
            val setId = Ids.randomString(QrChunks.SET_ID_LENGTH, random)
            val codes = QrChunks.split(CatalogueCodec.encode(catalogue), setId, chunkSize).map { it.encode() }
            _state.value = ExportUiState(loading = false, codes = codes, catalogue = catalogue)
        }
    }
}

/** Where a catalogue import is. */
sealed interface ImportUiState {
    /**
     * Scanning the codes.
     *
     * @property received Codes of the current set scanned so far.
     * @property expected Codes in the current set; 0 before the first.
     * @property error What was wrong with the last code scanned, or null.
     */
    data class Scanning(
        val received: Int = 0,
        val expected: Int = 0,
        val error: ImportError? = null,
    ) : ImportUiState

    /**
     * Every code was scanned and the catalogue decoded; waiting for the operator to replace or merge.
     *
     * @property catalogue The decoded catalogue.
     * @property currencyMatches Whether its currency is this device's; if not, prices are imported as they are.
     */
    data class Ready(
        val catalogue: Catalogue,
        val currencyMatches: Boolean,
    ) : ImportUiState

    /** Writing the catalogue to the database. */
    data object Importing : ImportUiState

    /**
     * The import finished.
     *
     * @property summary What was added and updated.
     */
    data class Done(
        val summary: ImportSummary,
    ) : ImportUiState
}

/** Why a scanned code could not be used. */
enum class ImportError {
    /** It is not a catalogue transfer code (such as a refund code or a product barcode). */
    NOT_A_CATALOGUE,

    /** All codes were scanned but the data did not decode; scanning starts over. */
    CORRUPT,
}

/** Scans a catalogue from another terminal's export codes, in any order, then imports it. */
class CatalogueImportViewModel(
    private val catalog: CatalogRepository,
    /** This device's currency, to warn when the catalogue's differs. */
    private val currencyCode: String,
) : ViewModel() {
    private val assembler = QrChunkAssembler()
    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Scanning())

    /** The import state. */
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Handles a scanned code [text]; ignored unless scanning. Called on the main thread by the scanner. */
    fun onCode(text: String) {
        if (_state.value !is ImportUiState.Scanning) return
        val chunk = QrChunks.parse(text)
        if (chunk == null) {
            _state.value = ImportUiState.Scanning(assembler.received, assembler.expected, ImportError.NOT_A_CATALOGUE)
            return
        }
        assembler.add(chunk)
        if (!assembler.isComplete) {
            _state.value = ImportUiState.Scanning(assembler.received, assembler.expected)
            return
        }
        _state.value =
            try {
                val catalogue = CatalogueCodec.decode(assembler.assemble())
                ImportUiState.Ready(catalogue, catalogue.currencyCode == currencyCode)
            } catch (ignored: CatalogueFormatException) {
                assembler.reset()
                ImportUiState.Scanning(error = ImportError.CORRUPT)
            }
    }

    /** Imports the decoded catalogue with [mode]; ignored unless [ImportUiState.Ready]. */
    fun import(mode: ImportMode) {
        val ready = _state.value as? ImportUiState.Ready ?: return
        _state.value = ImportUiState.Importing
        launchWrite({ catalog.import(ready.catalogue, mode) }) { _state.value = ImportUiState.Done(it) }
    }

    /** Forgets the scanned codes and starts scanning again. */
    fun restart() {
        assembler.reset()
        _state.value = ImportUiState.Scanning()
    }
}
