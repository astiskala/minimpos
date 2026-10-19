package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.feature.persisting
import app.minimpos.app.terminal.SetupDiagnostics
import app.minimpos.app.terminal.SetupDiscovery
import app.minimpos.app.terminal.SetupDiscoverySearch
import app.minimpos.app.terminal.SharedKeyConnection
import app.minimpos.app.terminal.SharedKeyOffer
import app.minimpos.app.terminal.TapToPayCheck
import app.minimpos.app.terminal.TapToPayOutcome
import app.minimpos.app.terminal.TapToPaySetup
import app.minimpos.app.terminal.TerminalStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where the search for terminals in the cloud and the Tap to Pay setup stand.
 *
 * @property terminals The latest search for terminals connected in the cloud.
 * @property connectedTerminals The POIIDs that search found, to choose from; null while none is offered.
 * @property tapToPay The latest setup or removal of Tap to Pay.
 * @property paymentsAppKeyStored Whether the Payments app API key given to the latest setup was stored (so the field
 *   can be cleared).
 * @property apiKeyStored Whether the API key given to the latest search was stored (so the field can be cleared).
 * @property manualDetails Whether optional details need manual entry.
 * @property revision Completed discovery imports, used to refresh field editing state.
 * @property unsavedSecrets Which visible secret fields have unsaved text; never stores their values.
 * @property sharedKeyOffer Non-secret creation/recovery confirmation; null when not offered.
 * @property keyPending Whether a remote key is created but its terminal connection has not verified.
 * @property keyResult Latest explicitly requested key setup or connection check.
 * @property diagnostics Latest advisory Payments app check, invalidated when its credential/environment changes.
 * @property checkingDiagnostics Whether independent integration checks are refreshing.
 * @property integrationDiagnostics Latest completed integration checks, or null before checking or after a setup change.
 */
data class TerminalSetupActions(
    val terminals: ActionState = ActionState(),
    val connectedTerminals: List<String>? = null,
    val tapToPay: ActionState = ActionState(),
    val paymentsAppKeyStored: Boolean = false,
    val apiKeyStored: Boolean = false,
    val manualDetails: Boolean = false,
    val revision: Int = 0,
    val unsavedSecrets: Set<Secret> = emptySet(),
    val sharedKeyOffer: SharedKeyOffer? = null,
    val keyPending: Boolean = false,
    val keyResult: ActionState = ActionState(),
    val diagnostics: TapToPayCheck = TapToPayCheck(),
    val checkingDiagnostics: Boolean = false,
    val integrationDiagnostics: SetupDiagnostics? = null,
)

/** Which result of [TerminalSetupActions] a dialog shows, see [TerminalSetupViewModel.dismissResult]. */
enum class SetupResult {
    /** [TerminalSetupActions.terminals]. */
    TERMINALS,

    /** [TerminalSetupActions.tapToPay]. */
    TAP_TO_PAY,

    /** [TerminalSetupActions.keyResult]. */
    SHARED_KEY,
}

/**
 * Settings › Terminal's setup of where payments go off-terminal: choosing a terminal in the cloud from those connected,
 * and setting up (or removing) Tap to Pay with the Adyen Payments app.
 *
 * @param secrets Where the Payments app API key and the API key for the cloud are stored.
 * @param status Lists the terminals connected in the cloud.
 * @param tapToPay Boards and removes the Payments app.
 * @param discovery Reads optional terminal setup details.
 * @param settings Reads the fields after a discovered setup is saved.
 * @param observedTerminal Terminal settings stream to synchronize before resetting field editing state.
 * @param onConnected Invoked when a shared-key connection check verifies the terminal, so missing receipt business
 *   details can be looked up automatically just as after a manual connection test.
 */
class TerminalSetupViewModel(
    private val secrets: SecretStore,
    private val status: TerminalStatus,
    private val tapToPay: TapToPaySetup,
    private val discovery: SetupDiscovery,
    private val settings: SettingsRepository,
    private val observedTerminal: Flow<TerminalSettings> = settings.settings.map { it.terminal },
    private val onConnected: suspend () -> Unit = {},
) : ViewModel() {
    /** Outcomes of the latest actions. */
    val actions: StateFlow<TerminalSetupActions>
        field = MutableStateFlow(TerminalSetupActions())

    init {
        viewModelScope.launch {
            tapToPay.diagnostics.state.collect { reading -> actions.update { it.copy(diagnostics = reading) } }
        }
        launchWrite({ discovery.pendingKey() }) { offer ->
            actions.update { if (!it.terminals.running && it.revision == 0) it.copy(sharedKeyOffer = offer) else it }
        }
    }

    /** Tracks only whether [secret] has a visible unsaved draft, so scanner navigation can warn before discarding it. */
    fun secretDraft(
        secret: Secret,
        present: Boolean,
    ) {
        actions.update { it.copy(unsavedSecrets = if (present) it.unsavedSecrets + secret else it.unsavedSecrets - secret) }
    }

    /**
     * Looks for the terminals connected to the merchant account in the cloud, to offer them in [actions], first saving
     * [apiKey] as the API key if one was entered.
     */
    fun findTerminals(apiKey: String? = null) {
        if (actions.value.terminals.running) return
        actions.update {
            it.copy(
                terminals = ActionState(running = true),
                connectedTerminals = null,
                apiKeyStored = false,
                manualDetails = false,
            )
        }
        launchWrite({
            val entered = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            val notStored = entered?.let { persisting { secrets.storeSafely(Secret.ADYEN_API_KEY, it) } }
            if (notStored != null) {
                actions.update { it.copy(terminals = ActionState(outcome = notStored, isError = true)) }
                return@launchWrite
            }
            if (entered != null) actions.update { it.copy(apiKeyStored = true) }
            when (val discovered = discovery.find()) {
                is SetupDiscoverySearch.Found -> {
                    val current = status.state.value
                    if (current.onTerminal && current.mode == TerminalMode.TERMINAL && discovered.ids.size == 1) {
                        chooseTerminal(discovered.ids.single())
                    } else {
                        actions.update { it.copy(terminals = ActionState(done = true), connectedTerminals = discovered.ids) }
                    }
                }

                SetupDiscoverySearch.Unavailable -> {
                    actions.update { it.copy(terminals = ActionState(done = true), manualDetails = true) }
                }

                is SetupDiscoverySearch.Failed -> {
                    actions.update {
                        it.copy(
                            terminals = ActionState(outcome = ActionOutcome.Failed(discovered.failure), isError = true),
                        )
                    }
                }
            }
        })
    }

    /** Takes [poiId] (one of [TerminalSetupActions.connectedTerminals]) as the terminal in the cloud; null only closes the list. */
    fun chooseTerminal(poiId: String?) {
        actions.update { it.copy(connectedTerminals = null, terminals = ActionState()) }
        launchWrite({
            val result = discovery.choose(poiId)
            if (poiId != null) {
                val terminal = settings.current().terminal
                observedTerminal.first { it == terminal }
                actions.update {
                    it.copy(
                        manualDetails = result.manualDetails,
                        terminals =
                            result.failure?.let { failure -> ActionState(outcome = ActionOutcome.Failed(failure), isError = true) }
                                ?: ActionState(),
                        revision = it.revision + 1,
                    )
                }
                if (result.keyMissing || discovery.pendingKey() != null) keyAction(null)
            }
        })
    }

    /** Confirms only the currently offered terminal/environment; never triggered by discovery or startup. */
    fun createSharedKey(offer: SharedKeyOffer) {
        if (actions.value.sharedKeyOffer?.matches(offer) != true) return
        keyAction(offer)
    }

    /** Reads existing settings and checks communication again; any required PATCH needs a new explicit confirmation. */
    fun checkSharedKey() = keyAction(null)

    private fun keyAction(confirmed: SharedKeyOffer?) {
        if (actions.value.terminals.running || actions.value.keyResult.running) return
        actions.update { it.copy(keyResult = ActionState(running = true)) }
        launchWrite({
            runSecretWrite { keyOutcome(discovery.setupKey(confirmed)) }?.let { failed ->
                actions.update { it.copy(keyResult = ActionState(outcome = failed, isError = true)) }
            }
        })
    }

    private suspend fun keyOutcome(result: SharedKeyConnection) {
        if (result.configured) {
            val terminal = settings.current().terminal
            observedTerminal.first { it == terminal }
        }
        when (result) {
            is SharedKeyConnection.Confirmation -> {
                actions.update {
                    it.copy(sharedKeyOffer = result.offer, keyResult = ActionState(), manualDetails = false)
                }
            }

            is SharedKeyConnection.Failed -> {
                actions.update {
                    it.copy(
                        sharedKeyOffer = result.offer,
                        keyPending = result.pending,
                        manualDetails = if (result.configured) false else it.manualDetails,
                        revision = if (result.configured) it.revision + 1 else it.revision,
                        keyResult = ActionState(outcome = ActionOutcome.Failed(result.failure), isError = true),
                    )
                }
            }

            SharedKeyConnection.Connected -> {
                onConnected()
                actions.update {
                    it.copy(
                        sharedKeyOffer = null,
                        keyPending = false,
                        manualDetails = false,
                        revision = it.revision + 1,
                        keyResult = ActionState(done = true),
                    )
                }
            }
        }
    }

    /**
     * Sets up Tap to Pay with the Adyen Payments app (see [TapToPaySetup.board]), first saving [apiKey] as the Payments
     * app API key if one was entered; [again] boards it afresh.
     */
    fun setUpTapToPay(
        apiKey: String? = null,
        again: Boolean = false,
    ) = run(apiKey) { tapToPay.board(reboard = again) }

    /** Removes this phone's Payments app instance (see [TapToPaySetup.unregister]). */
    fun removeTapToPay() = run(null) { tapToPay.unregister() }

    /** Refreshes read-only support checks on expansion; no setup-result dialog or setup mutation. */
    fun refreshDiagnostics() {
        if (actions.value.checkingDiagnostics) return
        actions.update { it.copy(checkingDiagnostics = true, integrationDiagnostics = null) }
        viewModelScope.launch {
            try {
                val reading = status.inspect()
                actions.update { it.copy(integrationDiagnostics = reading) }
            } finally {
                actions.update { it.copy(checkingDiagnostics = false) }
            }
        }
    }

    /** Closes the dialog showing [result]'s outcome, keeping whether it succeeded. */
    fun dismissResult(result: SetupResult) {
        actions.update {
            when (result) {
                SetupResult.TERMINALS -> it.copy(terminals = it.terminals.dismissed())
                SetupResult.TAP_TO_PAY -> it.copy(tapToPay = it.tapToPay.dismissed())
                SetupResult.SHARED_KEY -> it.copy(keyResult = it.keyResult.dismissed())
            }
        }
    }

    private fun run(
        apiKey: String?,
        action: suspend () -> TapToPayOutcome,
    ) {
        if (actions.value.tapToPay.running) return
        actions.update { it.copy(tapToPay = ActionState(running = true), paymentsAppKeyStored = false) }
        launchWrite({
            val entered = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            val notStored = entered?.let { persisting { secrets.storeSafely(Secret.PAYMENTS_APP_API_KEY, it) } }
            if (notStored != null) {
                actions.update { it.copy(tapToPay = ActionState(outcome = notStored, isError = true)) }
                return@launchWrite
            }
            if (entered != null) actions.update { it.copy(paymentsAppKeyStored = true) }
            val result =
                when (val outcome = persisting { action() }) {
                    is TapToPayOutcome.Boarded -> {
                        // Registration does not change editable key/account fields or discard unsaved secret drafts.
                        ActionState(outcome = ActionOutcome.TapToPayReady(outcome.installationId), done = true)
                    }

                    TapToPayOutcome.Unregistered -> {
                        ActionState(outcome = ActionOutcome.TapToPayRemoved, done = true)
                    }

                    is TapToPayOutcome.NotSetUp -> {
                        ActionState(outcome = ActionOutcome.Failed(Failure.NotSetUp(outcome.problem)), isError = true)
                    }

                    is TapToPayOutcome.Failed -> {
                        ActionState(outcome = ActionOutcome.Failed(outcome.failure), isError = true)
                    }
                }
            actions.update { it.copy(tapToPay = result) }
        })
    }
}
