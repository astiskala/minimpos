package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.CapabilityDiscovery
import app.minimpos.app.terminal.CapabilityPatchResult
import app.minimpos.app.terminal.GratuityCapability
import app.minimpos.app.terminal.MotoCapability
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Action outcomes for Settings > Adyen capabilities.
 * @property gratuity Latest terminal gratuity `PATCH` outcome.
 * @property dcc Latest DCC `PATCH` outcome.
 * @property moto Latest MOTO `PATCH` outcome.
 * @property offline Latest offline limits `PATCH` outcome.
 * @property revision Increments on remote read/update so local field drafts reload verified values.
 */
data class CapabilitiesActions(
    val gratuity: ActionState = ActionState(),
    val dcc: ActionState = ActionState(),
    val moto: ActionState = ActionState(),
    val offline: ActionState = ActionState(),
    val revision: Int = 0,
)

/**
 * ViewModel for Adyen capabilities, observing only the narrow [TerminalSettings] slice.
 * @param discovery Reads and updates Management terminal capabilities.
 * @param observedTerminal Narrow terminal settings slice that triggers capability refresh on change.
 */
class CapabilitiesViewModel(
    private val discovery: CapabilityDiscovery,
    observedTerminal: Flow<TerminalSettings>,
) : ViewModel() {
    /** Current non-secret capability state. */
    val state: StateFlow<AdyenCapabilitiesState> = discovery.state

    /** Outcomes of the latest capability updates. */
    val actions: StateFlow<CapabilitiesActions>
        field = MutableStateFlow(CapabilitiesActions())

    init {
        viewModelScope.launch {
            observedTerminal.distinctUntilChanged().collectLatest {
                refresh()
            }
        }
    }

    /** Refreshes Management capability inspection. */
    fun refresh() {
        if (state.value.checking) return
        launchWrite({ discovery.refresh() }) { _ ->
            actions.update { it.copy(revision = it.revision + 1) }
        }
    }

    /** Updates the terminal's gratuity configuration for [currency]. */
    fun saveGratuity(
        currency: String,
        gratuity: GratuityCapability?,
    ) {
        if (actions.value.gratuity.running) return
        actions.update { it.copy(gratuity = ActionState(running = true)) }
        launchWrite({ discovery.updateGratuity(currency, gratuity) }) { result ->
            actions.update {
                it.copy(
                    gratuity = result.toActionState(),
                    revision = if (result == CapabilityPatchResult.Updated) it.revision + 1 else it.revision,
                )
            }
        }
    }

    /** Updates the terminal's Dynamic Currency Conversion setting. */
    fun saveDcc(enabled: Boolean) {
        if (actions.value.dcc.running) return
        actions.update { it.copy(dcc = ActionState(running = true)) }
        launchWrite({ discovery.updateDcc(enabled) }) { result ->
            actions.update {
                it.copy(
                    dcc = result.toActionState(),
                    revision = if (result == CapabilityPatchResult.Updated) it.revision + 1 else it.revision,
                )
            }
        }
    }

    /** Updates the terminal's MOTO configuration. */
    fun saveMoto(moto: MotoCapability) {
        if (actions.value.moto.running) return
        actions.update { it.copy(moto = ActionState(running = true)) }
        launchWrite({ discovery.updateMoto(moto) }) { result ->
            actions.update {
                it.copy(
                    moto = result.toActionState(),
                    revision = if (result == CapabilityPatchResult.Updated) it.revision + 1 else it.revision,
                )
            }
        }
    }

    /** Updates the terminal's offline EMV and store-and-forward limits for [currency]. */
    fun saveOffline(
        currency: String,
        chipFloorLimit: Int?,
        maxStoreAndForwardMinor: Int?,
        maxPayments: Int?,
    ) {
        if (actions.value.offline.running) return
        actions.update { it.copy(offline = ActionState(running = true)) }
        launchWrite({
            discovery.updateOffline(
                currency = currency,
                chipFloorLimit = chipFloorLimit,
                maxStoreAndForwardMinor = maxStoreAndForwardMinor,
                maxPayments = maxPayments,
            )
        }) { result ->
            actions.update {
                it.copy(
                    offline = result.toActionState(),
                    revision = if (result == CapabilityPatchResult.Updated) it.revision + 1 else it.revision,
                )
            }
        }
    }

    private fun CapabilityPatchResult.toActionState(): ActionState =
        when (this) {
            CapabilityPatchResult.Updated -> ActionState(done = true)
            is CapabilityPatchResult.NotDone -> ActionState(outcome = ActionOutcome.Failed(failure), isError = true)
            is CapabilityPatchResult.Unconfirmed -> ActionState(outcome = ActionOutcome.Unconfirmed(failure), isError = true)
        }
}
