package io.github.astiskala.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.feature.ActionOutcome
import io.github.astiskala.minimpos.app.feature.ActionState
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.app.feature.persisting
import io.github.astiskala.minimpos.app.terminal.ConnectedTerminals
import io.github.astiskala.minimpos.app.terminal.TapToPayOutcome
import io.github.astiskala.minimpos.app.terminal.TapToPaySetup
import io.github.astiskala.minimpos.app.terminal.TerminalStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 */
data class TerminalSetupActions(
    val terminals: ActionState = ActionState(),
    val connectedTerminals: List<String>? = null,
    val tapToPay: ActionState = ActionState(),
    val paymentsAppKeyStored: Boolean = false,
    val apiKeyStored: Boolean = false,
)

/**
 * Settings › Terminal's setup of where payments go off-terminal: choosing a terminal in the cloud from those connected,
 * and setting up (or removing) Tap to Pay with the Adyen Payments app.
 *
 * @param settings Where the chosen terminal is stored.
 * @param secrets Where the Payments app API key and the API key for the cloud are stored.
 * @param status Lists the terminals connected in the cloud.
 * @param tapToPay Boards and removes the Payments app.
 */
class TerminalSetupViewModel(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val status: TerminalStatus,
    private val tapToPay: TapToPaySetup,
) : ViewModel() {
    private val _actions = MutableStateFlow(TerminalSetupActions())

    /** Outcomes of the latest actions. */
    val actions: StateFlow<TerminalSetupActions> = _actions.asStateFlow()

    /**
     * Looks for the terminals connected to the merchant account in the cloud, to offer them in [actions], first saving
     * [apiKey] as the API key if one was entered.
     */
    fun findTerminals(apiKey: String? = null) {
        _actions.update { it.copy(terminals = ActionState(running = true), connectedTerminals = null, apiKeyStored = false) }
        viewModelScope.launch {
            val entered = apiKey?.trim()?.takeIf { it.isNotEmpty() }
            val notStored = entered?.let { persisting { store(Secret.ADYEN_API_KEY, it) } }
            if (notStored != null) {
                _actions.update { it.copy(terminals = ActionState(outcome = notStored, isError = true)) }
                return@launch
            }
            if (entered != null) _actions.update { it.copy(apiKeyStored = true) }
            val found = status.connectedTerminals()
            _actions.update {
                when (found) {
                    is ConnectedTerminals.Listed -> {
                        it.copy(terminals = ActionState(done = true), connectedTerminals = found.poiIds)
                    }

                    is ConnectedTerminals.NotSetUp -> {
                        it.copy(terminals = ActionState(outcome = ActionOutcome.NotSetUp(found.problem), isError = true))
                    }

                    is ConnectedTerminals.Failed -> {
                        it.copy(terminals = ActionState(outcome = ActionOutcome.Failed(found.message), isError = true))
                    }
                }
            }
        }
    }

    /** Takes [poiId] (one of [TerminalSetupActions.connectedTerminals]) as the terminal in the cloud; null only closes the list. */
    fun chooseTerminal(poiId: String?) {
        _actions.update { it.copy(connectedTerminals = null, terminals = ActionState()) }
        if (poiId != null) launchWrite({ settings.update { it.copy(terminal = it.terminal.copy(poiIdOverride = poiId)) } })
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
