package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.CapabilityDiscovery
import app.minimpos.app.terminal.CapabilityPatchResult
import app.minimpos.app.terminal.GratuityCapability
import app.minimpos.app.terminal.MotoCapability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * ViewModel for Adyen capabilities, following setup-identity changes from [CapabilityDiscovery.changes].
 * @param discovery Reads and updates Management terminal capabilities.
 */
class CapabilitiesViewModel(
    private val discovery: CapabilityDiscovery,
) : ViewModel() {
    /** Current non-secret capability state. */
    val state: StateFlow<AdyenCapabilitiesState> = discovery.state

    /** Outcomes of the latest capability updates. */
    val actions: StateFlow<CapabilitiesActions>
        field = MutableStateFlow(CapabilitiesActions())

    init {
        viewModelScope.launch {
            // A later setup change cancels the refresh it supersedes (whose result would not be published) instead of
            // being skipped while that refresh is still checking, which would leave the stale reading checking.
            discovery.changes.collectLatest { _ ->
                discovery.refresh()
                actions.update { it.copy(revision = it.revision + 1) }
            }
        }
    }

    /** Refreshes Management capability inspection; ignored while a refresh is checking. */
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
    ) = save({ it.gratuity }, { copy(gratuity = it) }) { discovery.updateGratuity(currency, gratuity) }

    /** Updates the terminal's Dynamic Currency Conversion setting. */
    fun saveDcc(enabled: Boolean) = save({ it.dcc }, { copy(dcc = it) }) { discovery.updateDcc(enabled) }

    /** Updates the terminal's MOTO configuration. */
    fun saveMoto(moto: MotoCapability) = save({ it.moto }, { copy(moto = it) }) { discovery.updateMoto(moto) }

    /** Updates the terminal's offline EMV and store-and-forward limits for [currency]. */
    fun saveOffline(
        currency: String,
        chipFloorLimit: Int?,
        maxStoreAndForwardMinor: Int?,
        maxPayments: Int?,
    ) = save({ it.offline }, { copy(offline = it) }) {
        discovery.updateOffline(
            currency = currency,
            chipFloorLimit = chipFloorLimit,
            maxStoreAndForwardMinor = maxStoreAndForwardMinor,
            maxPayments = maxPayments,
        )
    }

    /** Sends [patch] unless the action in [slot] is running, recording its outcome there with [record]. */
    private fun save(
        slot: (CapabilitiesActions) -> ActionState,
        record: CapabilitiesActions.(ActionState) -> CapabilitiesActions,
        patch: suspend () -> CapabilityPatchResult,
    ) {
        if (slot(actions.value).running) return
        actions.update { it.record(ActionState(running = true)) }
        launchWrite(patch) { result ->
            actions.update {
                val recorded = it.record(result.toActionState())
                if (result == CapabilityPatchResult.Updated) recorded.copy(revision = recorded.revision + 1) else recorded
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
