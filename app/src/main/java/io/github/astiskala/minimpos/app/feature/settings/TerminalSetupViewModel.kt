package io.github.astiskala.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.feature.ActionOutcome
import io.github.astiskala.minimpos.app.feature.ActionState
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.app.feature.persisting
import io.github.astiskala.minimpos.app.terminal.SetupDiscovery
import io.github.astiskala.minimpos.app.terminal.TapToPayOutcome
import io.github.astiskala.minimpos.app.terminal.TapToPaySetup
import io.github.astiskala.minimpos.app.terminal.TerminalStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
)

/**
 * Settings › Terminal's setup of where payments go off-terminal: choosing a terminal in the cloud from those connected,
 * and setting up (or removing) Tap to Pay with the Adyen Payments app.
 *
 * @param secrets Where the Payments app API key and the API key for the cloud are stored.
 * @param status Lists the terminals connected in the cloud.
 * @param tapToPay Boards and removes the Payments app.
 * @param discovery Reads optional terminal setup details.
 * @param settings Reads the fields after a discovered setup is saved.
 * @param observedSettings Screen state to synchronize before resetting field editing state.
 */
class TerminalSetupViewModel(
    private val secrets: SecretStore,
    private val status: TerminalStatus,
    private val tapToPay: TapToPaySetup,
    private val discovery: SetupDiscovery,
    private val settings: SettingsRepository,
    private val observedSettings: StateFlow<SettingsUiState>,
) : ViewModel() {
    private val _actions = MutableStateFlow(TerminalSetupActions())
    private var automaticStarted = false

    /** Outcomes of the latest actions. */
    val actions: StateFlow<TerminalSetupActions> = _actions.asStateFlow()

    /** Tracks only whether [secret] has a visible unsaved draft, so scanner navigation can warn before discarding it. */
    fun secretDraft(
        secret: Secret,
        present: Boolean,
    ) {
        _actions.update { it.copy(unsavedSecrets = if (present) it.unsavedSecrets + secret else it.unsavedSecrets - secret) }
    }

    /** When [requested], waits for imported settings and starts read-only discovery once per screen view model; never boards. */
    suspend fun startAutomaticSetup(requested: Boolean) {
        if (!requested) return
        observedSettings.first { it.loaded }
        if (automaticStarted) return
        automaticStarted = true
        findTerminals()
    }

    /**
     * Looks for the terminals connected to the merchant account in the cloud, to offer them in [actions], first saving
     * [apiKey] as the API key if one was entered.
     */
    fun findTerminals(apiKey: String? = null) {
        if (_actions.value.terminals.running) return
        _actions.update {
            it.copy(
                terminals = ActionState(running = true),
                connectedTerminals = null,
                apiKeyStored = false,
                manualDetails = false,
            )
        }
        launchWrite({
            val entered = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            val notStored = entered?.let { persisting { store(Secret.ADYEN_API_KEY, it) } }
            if (notStored != null) {
                _actions.update { it.copy(terminals = ActionState(outcome = notStored, isError = true)) }
                return@launchWrite
            }
            if (entered != null) _actions.update { it.copy(apiKeyStored = true) }
            val discovered = discovery.find()
            if (!discovered.isNullOrEmpty()) {
                _actions.update { it.copy(terminals = ActionState(done = true), connectedTerminals = discovered) }
                val current = status.state.value
                if (current.onTerminal && current.mode == TerminalMode.TERMINAL && discovered.size == 1) chooseTerminal(discovered.single())
                return@launchWrite
            }
            _actions.update { it.copy(terminals = ActionState(done = true), manualDetails = true) }
        })
    }

    /** Takes [poiId] (one of [TerminalSetupActions.connectedTerminals]) as the terminal in the cloud; null only closes the list. */
    fun chooseTerminal(poiId: String?) {
        _actions.update { it.copy(connectedTerminals = null, terminals = ActionState()) }
        launchWrite({
            val result = discovery.choose(poiId)
            if (poiId != null) {
                val terminal = settings.current().terminal
                observedSettings.first { it.settings.terminal == terminal }
                _actions.update { it.copy(manualDetails = result != true, revision = it.revision + 1) }
            }
        })
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

    private fun run(
        apiKey: String?,
        action: suspend () -> TapToPayOutcome,
    ) {
        _actions.update { it.copy(tapToPay = ActionState(running = true), paymentsAppKeyStored = false) }
        viewModelScope.launch {
            val entered = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            val notStored = entered?.let { persisting { store(Secret.PAYMENTS_APP_API_KEY, it) } }
            if (notStored != null) {
                _actions.update { it.copy(tapToPay = ActionState(outcome = notStored, isError = true)) }
                return@launch
            }
            if (entered != null) _actions.update { it.copy(paymentsAppKeyStored = true) }
            val result =
                when (val outcome = persisting { action() }) {
                    is TapToPayOutcome.Boarded -> {
                        ActionState(outcome = ActionOutcome.TapToPayReady(outcome.installationId), done = true)
                    }

                    TapToPayOutcome.Unregistered -> {
                        ActionState(outcome = ActionOutcome.TapToPayRemoved, done = true)
                    }

                    is TapToPayOutcome.NotSetUp -> {
                        ActionState(outcome = ActionOutcome.NotSetUp(outcome.problem), isError = true)
                    }

                    is TapToPayOutcome.Failed -> {
                        ActionState(
                            outcome = outcome.message?.let(ActionOutcome::Failed) ?: ActionOutcome.NoAnswer,
                            isError = true,
                        )
                    }
                }
            _actions.update { it.copy(tapToPay = result) }
        }
    }

    /** Stores [apiKey] as [secret]; null when it was stored, else why not. */
    private suspend fun store(
        secret: Secret,
        apiKey: String,
    ): ActionOutcome.SecretNotStored? =
        try {
            secrets.set(secret, apiKey)
            null
        } catch (e: SecretStoreException) {
            ActionOutcome.SecretNotStored(e.message)
        }
}
