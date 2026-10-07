package io.github.astiskala.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
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
import io.github.astiskala.minimpos.app.terminal.SharedKeyOffer
import io.github.astiskala.minimpos.app.terminal.SharedKeySetupOutcome
import io.github.astiskala.minimpos.app.terminal.TapToPayOutcome
import io.github.astiskala.minimpos.app.terminal.TapToPaySetup
import io.github.astiskala.minimpos.app.terminal.TerminalConnection
import io.github.astiskala.minimpos.app.terminal.TerminalStatus
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
 * @property guided Whether an imported helper setup should collapse already supplied details.
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
    val guided: Boolean = false,
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
    private var automaticStarted = false

    init {
        launchWrite({ discovery.pendingKey() }) { offer ->
            _actions.update { if (!automaticStarted && !it.terminals.running && it.revision == 0) it.copy(sharedKeyOffer = offer) else it }
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

    /** Sets [helperSetup]'s guided presentation; [requested] starts optional physical-terminal discovery once, never boarding. */
    suspend fun startAutomaticSetup(
        requested: Boolean,
        helperSetup: Boolean = false,
    ) {
        if (!requested && !helperSetup) return
        observedSettings.first { it.loaded }
        _actions.update { it.copy(guided = helperSetup) }
        if (!requested || automaticStarted) return
        automaticStarted = true
        if (observedSettings.value.settings.terminal.mode != TerminalMode.PAYMENTS_APP) {
            findTerminals()
        }
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
                val current = status.state.value
                if (current.onTerminal && current.mode == TerminalMode.TERMINAL && discovered.size == 1) {
                    chooseTerminal(discovered.single())
                } else {
                    _actions.update { it.copy(terminals = ActionState(done = true), connectedTerminals = discovered) }
                }
                return@launchWrite
            }
            val problem = discovery.problem
            _actions.update {
                it.copy(
                    terminals =
                        if (problem ==
                            null
                        ) {
                            ActionState(done = true)
                        } else {
                            ActionState(outcome = ActionOutcome.NotSetUp(problem), isError = true)
                        },
                    manualDetails = problem == null,
                )
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
                val problem = discovery.problem
                _actions.update {
                    it.copy(
                        manualDetails = result != true && problem == null,
                        terminals =
                            if (problem ==
                                null
                            ) {
                                ActionState()
                            } else {
                                ActionState(outcome = ActionOutcome.NotSetUp(problem), isError = true)
                            },
                        revision = it.revision + 1,
                    )
                }
                if (discovery.keyMissing || discovery.pendingKey() != null) keyAction(null)
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
            } catch (error: SecretStoreException) {
                _actions.update { it.copy(keyResult = ActionState(outcome = ActionOutcome.SecretNotStored(error.message), isError = true)) }
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
                    result.problem?.let(ActionOutcome::NotSetUp) ?: result.message?.let(ActionOutcome::Failed) ?: ActionOutcome.NoAnswer
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
                        ActionState(
                            outcome = outcome.message?.let(ActionOutcome::Failed) ?: ActionOutcome.NoAnswer,
                            isError = true,
                        )
                    }
                }
            _actions.update { it.copy(tapToPay = result) }
        })
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
