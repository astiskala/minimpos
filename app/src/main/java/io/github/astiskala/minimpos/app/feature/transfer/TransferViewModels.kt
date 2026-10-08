package io.github.astiskala.minimpos.app.feature.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.data.transfer.ImportOutcome
import io.github.astiskala.minimpos.app.data.transfer.ReceivedTransfer
import io.github.astiskala.minimpos.app.data.transfer.SetupTransfer
import io.github.astiskala.minimpos.app.data.transfer.TransferContents
import io.github.astiskala.minimpos.app.data.transfer.TransferExport
import io.github.astiskala.minimpos.app.data.transfer.TransferResult
import io.github.astiskala.minimpos.app.feature.ActionOutcome
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.app.terminal.HistorySwitchPlan
import io.github.astiskala.minimpos.app.terminal.SetupImport
import io.github.astiskala.minimpos.app.terminal.SetupImportOutcome
import io.github.astiskala.minimpos.app.terminal.SharedKeyOffer
import io.github.astiskala.minimpos.core.codec.QrChunkAssembler
import io.github.astiskala.minimpos.core.codec.QrChunks
import io.github.astiskala.minimpos.core.codec.TransferCodec
import io.github.astiskala.minimpos.core.codec.TransferFormatException
import io.github.astiskala.minimpos.core.ids.Ids
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * What the export screen shows: first the choice of what to share, then the QR codes.
 *
 * @property contents What the operator chose to share.
 * @property secretsAvailable The secrets set on this terminal; sharing secrets is only offered when there are some.
 * @property loading Whether the codes are being made.
 * @property export The export shown as [codes]; null while choosing.
 * @property codes The QR code contents, in order (`MPC1:` chunks of one set).
 */
data class ExportUiState(
    val contents: TransferContents = TransferContents(),
    val secretsAvailable: Set<Secret> = emptySet(),
    val loading: Boolean = false,
    val export: TransferExport? = null,
    val codes: List<String> = emptyList(),
) {
    /** Whether the secrets would be shared: chosen, and some are set. */
    val sharesSecrets: Boolean get() = contents.secrets && secretsAvailable.isNotEmpty()

    /** Whether "Show QR codes" is enabled: something is chosen and no codes are being made. */
    val canShow: Boolean get() = !loading && (contents.catalogue || contents.settings || sharesSecrets)
}

/**
 * Exports the catalogue, settings and secrets as QR codes for another terminal to scan (see [SetupTransfer]). Each
 * export gets a new random set ID, so its codes cannot be mixed up with an earlier export's, and a new transfer code.
 *
 * @param setup Builds the export.
 * @param currencyCode The currency the prices are in, carried in the catalogue.
 * @param chunkSize Data characters per QR code.
 * @param random Source of the set ID; tests make it predictable.
 */
class TransferExportViewModel(
    private val setup: SetupTransfer,
    private val currencyCode: String,
    private val chunkSize: Int = QrChunks.DEFAULT_MAX_DATA_CHARS,
    private val random: Random = Random.Default,
) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState())

    /** The export state. */
    val state: StateFlow<ExportUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val available = setup.configuredSecrets()
            _state.update { it.copy(secretsAvailable = available) }
        }
    }

    /** Changes what is shared with [transform]; ignored while the codes are made or shown. */
    fun setContents(transform: (TransferContents) -> TransferContents) {
        if (_state.value.loading || _state.value.export != null) return
        _state.update { it.copy(contents = transform(it.contents)) }
    }

    /** Makes the codes for what was chosen; ignored unless [ExportUiState.canShow]. */
    fun show() {
        val current = _state.value
        if (!current.canShow || current.export != null) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            // The secrets set are looked up again, in case they were asked for before they had been read.
            val export = setup.export(current.contents, currencyCode)
            val setId = Ids.randomString(QrChunks.SET_ID_LENGTH, random)
            val codes = QrChunks.split(export.payload, setId, chunkSize).map { it.encode() }
            _state.update { it.copy(loading = false, export = export, codes = codes) }
        }
    }

    /** Goes back from the codes to the choice; the next codes get a new set ID and transfer code. */
    fun hide() = _state.update { it.copy(export = null, codes = emptyList()) }
}

/** Where an import is. */
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
     * Every code was scanned and decoded; waiting for the operator to import.
     *
     * @property received What was scanned.
     * @property currencyMatches Whether the catalogue's currency is the one this terminal will use, see
     *   [ReceivedTransfer.currencyMatches].
     * @property mode How the catalogue is combined with this one.
     * @property code The transfer code typed so far, for the secrets.
     * @property wrongCode Whether the last code tried did not authenticate this transfer.
     * @property outcome Current typed validation or storage error; null before checking or after correction.
     * @property incomplete Whether required connection fields are missing and the helper must generate complete codes.
     * @property terminalChoices Eligible IDs offered only for an ambiguous destination; null when not selecting.
     * @property boardingRequired Whether explicit phone registration must run before this import can commit.
     * @property historySwitch Destructive history confirmation for this exact candidate; null before verification.
     * @property sharedKeyOffer Explicit key-creation/recovery confirmation; null when not offered.
     * @property keyPending Whether the remote key exists but this import still needs connection verification.
     */
    data class Ready(
        val received: ReceivedTransfer,
        val currencyMatches: Boolean,
        val mode: ImportMode = ImportMode.MERGE,
        val code: String = "",
        val wrongCode: Boolean = false,
        val outcome: ActionOutcome? = null,
        val incomplete: Boolean = false,
        val terminalChoices: List<String>? = null,
        val boardingRequired: Boolean = false,
        /** Destructive environment-change confirmation; null until authenticated setup validation succeeds. */
        val historySwitch: HistorySwitchPlan? = null,
        val sharedKeyOffer: SharedKeyOffer? = null,
        val keyPending: Boolean = false,
    ) : ImportUiState

    /** Opening the secrets and writing everything. */
    data object Importing : ImportUiState

    /** A durable import still needs recovery; no new QR or code is required.
     * @property outcome Localized setup problem or non-secret storage failure.
     */
    data class RecoveryFailed(
        val outcome: ActionOutcome,
    ) : ImportUiState

    /**
     * The import finished.
     *
     * @property result What was verified and durably imported; its secrets are never skipped.
     */
    data class Done(
        val result: TransferResult,
    ) : ImportUiState
}

/** Why a scanned code could not be used. */
enum class ImportError {
    /** It is not a transfer code (such as a refund code or a product barcode). */
    NOT_A_TRANSFER,

    /** All codes were scanned but the data did not decode; scanning starts over. */
    CORRUPT,
}

/**
 * Scans another terminal's transfer codes, in any order, then imports them (see [SetupTransfer]).
 *
 * @param setup Reads the authenticated transfer and builds read-only previews.
 * @param currencyCode This terminal's currency, to warn when the catalogue's differs.
 * @param importer Verifies candidate setup before activation and resumes encrypted interrupted commits.
 */
class TransferImportViewModel(
    private val setup: SetupTransfer,
    private val currencyCode: String,
    private val importer: SetupImport,
) : ViewModel() {
    private val assembler = QrChunkAssembler()
    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Importing)
    private var selectedTerminal: String? = null
    private var confirmedHistory: HistorySwitchPlan? = null

    init {
        retryRecovery()
    }

    /** The import state. */
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** Handles a scanned code [text]; ignored unless scanning. Called on the main thread by the scanner. */
    fun onCode(text: String) {
        if (_state.value !is ImportUiState.Scanning) return
        val chunk = QrChunks.parse(text)
        if (chunk == null) {
            _state.value = ImportUiState.Scanning(assembler.received, assembler.expected, ImportError.NOT_A_TRANSFER)
            return
        }
        assembler.add(chunk)
        if (!assembler.isComplete) {
            _state.value = ImportUiState.Scanning(assembler.received, assembler.expected)
            return
        }
        _state.value =
            try {
                val received = setup.receive(TransferCodec.decode(assembler.assemble()))
                ImportUiState.Ready(received, received.currencyMatches(currencyCode))
            } catch (ignored: TransferFormatException) {
                assembler.reset()
                ImportUiState.Scanning(error = ImportError.CORRUPT)
            }
    }

    /** Chooses how the catalogue is combined with this one; ignored unless [ImportUiState.Ready]. */
    fun setMode(mode: ImportMode) = updateReady { it.copy(mode = mode) }

    /** Updates the typed transfer code; ignored unless [ImportUiState.Ready]. */
    fun setCode(code: String) =
        updateReady {
            it.copy(
                code = TransferSeal.normalize(code),
                wrongCode = false,
                outcome = null,
                incomplete = false,
            )
        }

    /**
     * Verifies what was scanned before import; ignored unless [ImportUiState.Ready]. Every transfer requires its code.
     * A missing or incorrect code returns to [ImportUiState.Ready] with [ImportUiState.Ready.wrongCode] before any writes.
     * Required setup checks must also pass before the encrypted commit begins.
     */
    fun import() = verify(board = false)

    /** Explicitly opens phone registration for the currently authenticated candidate; never called by scanning. */
    fun setUpTapToPay() {
        if ((_state.value as? ImportUiState.Ready)?.boardingRequired == true) verify(board = true)
    }

    /** Chooses only an offered terminal, or dismisses the selector for null; no fields have been imported yet. */
    fun chooseTerminal(id: String?) {
        val ready = _state.value as? ImportUiState.Ready ?: return
        if (id != null && id !in ready.terminalChoices.orEmpty()) return
        updateReady { it.copy(terminalChoices = null) }
        if (id != null) {
            selectedTerminal = id
            import()
        }
    }

    /** Resumes an encrypted verified commit, without requiring its transfer code again or repeating remote registration. */
    fun retryRecovery() {
        _state.value = ImportUiState.Importing
        launchWrite({ importer.recover() }) { result ->
            _state.value =
                when (result) {
                    null -> ImportUiState.Scanning()
                    is ImportOutcome.Imported -> ImportUiState.Done(result.result)
                    is ImportOutcome.StorageFailed -> ImportUiState.RecoveryFailed(ActionOutcome.SecretNotStored(result.reason))
                    is ImportOutcome.Rejected -> ImportUiState.RecoveryFailed(ActionOutcome.NotSetUp(result.problem))
                    ImportOutcome.WrongCode -> ImportUiState.RecoveryFailed(ActionOutcome.NoAnswer)
                }
        }
    }

    /** Reviews permanent history loss for this import; cancellation leaves current settings and history untouched. */
    fun reviewSetup(confirm: Boolean) {
        val ready = _state.value as? ImportUiState.Ready ?: return
        val offer = ready.sharedKeyOffer
        if (offer != null) {
            if (confirm) verify(board = false, confirmedKey = offer) else updateReady { it.copy(sharedKeyOffer = null) }
        } else {
            val plan = ready.historySwitch ?: return
            if (confirm) {
                confirmedHistory = plan
                verify(board = false, confirmedHistorySwitch = plan)
            } else {
                confirmedHistory = null
                updateReady { it.copy(historySwitch = null) }
            }
        }
    }

    private fun verify(
        board: Boolean,
        confirmedHistorySwitch: HistorySwitchPlan? = null,
        confirmedKey: SharedKeyOffer? = null,
    ) {
        val ready = _state.value as? ImportUiState.Ready ?: return
        if (!ready.received.accepts(ready.code)) {
            _state.value = ready.copy(wrongCode = true)
            return
        }
        _state.value = ImportUiState.Importing
        launchWrite({
            importer.import(
                ready.received,
                ready.mode,
                ready.code,
                selectedTerminal,
                board,
                confirmedHistorySwitch ?: confirmedHistory,
                confirmedKey,
            )
        }) { result ->
            _state.value = finished(ready, result)
        }
    }

    private fun finished(
        ready: ImportUiState.Ready,
        result: SetupImportOutcome,
    ): ImportUiState =
        when (result) {
            is SetupImportOutcome.Committed -> {
                committed(ready, result.outcome)
            }

            is SetupImportOutcome.Terminals -> {
                ready.copy(terminalChoices = result.ids, outcome = null)
            }

            is SetupImportOutcome.HistoryConfirmation -> {
                ready.copy(historySwitch = result.plan, sharedKeyOffer = null)
            }

            is SetupImportOutcome.KeyConfirmation -> {
                ready.copy(sharedKeyOffer = result.offer, historySwitch = null, outcome = null, incomplete = false)
            }

            SetupImportOutcome.BoardingRequired -> {
                ready.copy(boardingRequired = true, outcome = null)
            }

            is SetupImportOutcome.Failed -> {
                ready.copy(
                    outcome =
                        result.problem?.let(ActionOutcome::NotSetUp)
                            ?: result.message?.let(ActionOutcome::Failed)
                            ?: ActionOutcome.NoAnswer,
                    incomplete = result.incomplete,
                    sharedKeyOffer = null,
                    historySwitch = null,
                    keyPending = result.keyPending,
                )
            }
        }

    private fun committed(
        ready: ImportUiState.Ready,
        result: ImportOutcome,
    ): ImportUiState =
        when (result) {
            ImportOutcome.WrongCode -> {
                ready.copy(wrongCode = true)
            }

            is ImportOutcome.Imported -> {
                ImportUiState.Done(result.result)
            }

            is ImportOutcome.Rejected -> {
                ready.copy(outcome = ActionOutcome.NotSetUp(result.problem), keyPending = false)
            }

            is ImportOutcome.StorageFailed -> {
                if (result.pending) {
                    ImportUiState.RecoveryFailed(ActionOutcome.SecretNotStored(result.reason))
                } else {
                    ready.copy(outcome = ActionOutcome.SecretNotStored(result.reason), keyPending = false)
                }
            }
        }

    private fun updateReady(transform: (ImportUiState.Ready) -> ImportUiState.Ready) =
        _state.update { if (it is ImportUiState.Ready) transform(it) else it }
}
