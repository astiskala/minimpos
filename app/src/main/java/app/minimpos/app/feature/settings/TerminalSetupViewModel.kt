package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.feature.persisting
import app.minimpos.app.terminal.SetupDiscovery
import app.minimpos.app.terminal.SetupDiscoverySearch
import app.minimpos.app.terminal.SharedKeyOffer
import app.minimpos.app.terminal.SharedKeySetupOutcome
import app.minimpos.app.terminal.TapToPayOutcome
import app.minimpos.app.terminal.TapToPaySetup
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.terminal.TerminalStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

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

    init {
        launchWrite({ discovery.pendingKey() }) { offer ->
            _actions.update { if (!it.terminals.running && it.revision == 0) it.copy(sharedKeyOffer = offer) else it }
        }
    }

    /** Outcomes of the latest actions. */
    val actions: StateFlow<TerminalSetupActions> = _actions.asStateFlow()

    /** Tracks only whether [secret] has a visible unsaved draft, so scanner navigation can warn before discarding it. */
    fun secretDraft(
        secret: Secret,
        present: Boolean,
    ) {
        _actions.update { it.copy(unsavedSecrets = if (present) it.unsavedSecrets + secret else it.unsavedSecrets - secret) }
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
            when (val discovered = discovery.find()) {
                is SetupDiscoverySearch.Found -> {
                    val current = status.state.value
                    if (current.onTerminal && current.mode == TerminalMode.TERMINAL && discovered.ids.size == 1) {
                        chooseTerminal(discovered.ids.single())
                    } else {
                        _actions.update { it.copy(terminals = ActionState(done = true), connectedTerminals = discovered.ids) }
                    }
                }

                SetupDiscoverySearch.Unavailable -> {
                    _actions.update { it.copy(terminals = ActionState(done = true), manualDetails = true) }
                }

                is SetupDiscoverySearch.Failed -> {
                    _actions.update {
                        it.copy(
                            terminals = ActionState(outcome = ActionOutcome.NotSetUp(discovered.problem), isError = true),
                        )
                    }
                }
            }
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
                _actions.update {
                    it.copy(
                        manualDetails = result.manualDetails,
                        terminals =
                            result.problem?.let { problem -> ActionState(outcome = ActionOutcome.NotSetUp(problem), isError = true) }
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
        if (_actions.value.sharedKeyOffer?.matches(offer) != true) return
        keyAction(offer)
    }

    /** Reads existing settings and checks communication again; any required PATCH needs a new explicit confirmation. */
    fun checkSharedKey() = keyAction(null)

    private fun keyAction(confirmed: SharedKeyOffer?) {
        if (_actions.value.terminals.running || _actions.value.keyResult.running) return
        _actions.update { it.copy(keyResult = ActionState(running = true)) }
        launchWrite({
            try {
                keyOutcome(discovery.setupKey(confirmed))
            } catch (ignored: SecretStoreException) {
                _actions.update { it.copy(keyResult = ActionState(outcome = SECURE_STORAGE_FAILED, isError = true)) }
            }
        })
    }

    private suspend fun keyOutcome(result: SharedKeySetupOutcome) {
        when (result) {
            is SharedKeySetupOutcome.Confirmation -> {
                _actions.update {
                    it.copy(sharedKeyOffer = result.offer, keyResult = ActionState(), manualDetails = false)
                }
            }

            is SharedKeySetupOutcome.Failed -> {
                val offer = discovery.pendingKey()
                val outcome =
                    result.problem?.let(ActionOutcome::NotSetUp) ?: result.failure?.let(ActionOutcome::Failed) ?: ActionOutcome.NoAnswer
                _actions.update { it.copy(sharedKeyOffer = offer, keyResult = ActionState(outcome = outcome, isError = true)) }
            }

            is SharedKeySetupOutcome.Ready -> {
                checkedKey(result)
            }
        }
    }

    private suspend fun checkedKey(ready: SharedKeySetupOutcome.Ready) {
        val pending = ready.pending
        val current = settings.current().terminal
        observedSettings.first { it.settings.terminal == current }
        val connected = status.check() is TerminalConnection.Connected
        if (connected) discovery.completeKey()
        val result =
            if (connected) {
                ActionState(done = true)
            } else {
                ActionState(
                    outcome = ActionOutcome.NotSetUp(ready.unverifiedProblem),
                    isError = true,
                )
            }
        _actions.update {
            it.copy(
                sharedKeyOffer = null,
                keyPending = !connected && pending,
                manualDetails = false,
                revision = it.revision + 1,
                keyResult = result,
            )
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

    private fun run(
        apiKey: String?,
        action: suspend () -> TapToPayOutcome,
    ) {
        if (_actions.value.tapToPay.running) return
        _actions.update { it.copy(tapToPay = ActionState(running = true), paymentsAppKeyStored = false) }
        launchWrite({
            val entered = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            val notStored = entered?.let { persisting { store(Secret.PAYMENTS_APP_API_KEY, it) } }
            if (notStored != null) {
                _actions.update { it.copy(tapToPay = ActionState(outcome = notStored, isError = true)) }
                return@launchWrite
            }
            if (entered != null) _actions.update { it.copy(paymentsAppKeyStored = true) }
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
                        ActionState(outcome = ActionOutcome.NotSetUp(outcome.problem), isError = true)
                    }

                    is TapToPayOutcome.Failed -> {
                        ActionState(outcome = ActionOutcome.Failed(outcome.failure), isError = true)
                    }
                }
            _actions.update { it.copy(tapToPay = result) }
        })
    }

    /** Stores [apiKey] as [secret]; null when it was stored, else why not. */
    private suspend fun store(
        secret: Secret,
        apiKey: String,
    ): ActionOutcome.Failed? =
        try {
            secrets.set(secret, apiKey)
            null
        } catch (ignored: SecretStoreException) {
            SECURE_STORAGE_FAILED
        }
}

/** A secret could not be stored on this device. */
private val SECURE_STORAGE_FAILED = ActionOutcome.Failed(Failure.Device(DeviceFault.SECURE_STORAGE))
