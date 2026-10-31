package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.feature.runAction
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.CapabilityDiscovery
import app.minimpos.app.terminal.CapabilityPatchResult
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
 * @property readRevision Increments only after a successful explicit refresh; never discards another feature's draft.
 * @property savedRevisions Successful saves by feature, so only that editor adopts its read-back.
 */
data class CapabilitiesActions(
    val gratuity: ActionState = ActionState(),
    val dcc: ActionState = ActionState(),
    val moto: ActionState = ActionState(),
    val offline: ActionState = ActionState(),
    val readRevision: Int = 0,
    val savedRevisions: Map<CapabilityResult, Int> = emptyMap(),
) {
    /** Whether any save is in flight; refreshing or sending another section must wait. */
    val running: Boolean get() = listOf(gratuity, dcc, moto, offline).any { it.running }

    /** This feature's latest outcome. */
    fun action(result: CapabilityResult): ActionState =
        when (result) {
            CapabilityResult.GRATUITY -> gratuity
            CapabilityResult.DCC -> dcc
            CapabilityResult.MOTO -> moto
            CapabilityResult.OFFLINE -> offline
        }
}

/** Explicit Adyen settings save whose completion dialog is being acknowledged. */
enum class CapabilityResult {
    /** Terminal tipping configuration. */
    GRATUITY,

    /** Dynamic Currency Conversion configuration. */
    DCC,

    /** Mail Order / Telephone Order configuration. */
    MOTO,

    /** Offline payment limits. */
    OFFLINE,
}

/**
 * ViewModel for Adyen capabilities, following setup-identity changes from [CapabilityDiscovery.changes].
 * @param discovery Reads and updates Management terminal capabilities.
 * @param refreshAssignments Refreshes store-dependent checkout offers after an explicit capability refresh.
 */
class CapabilitiesViewModel(
    private val discovery: CapabilityDiscovery,
    private val refreshAssignments: suspend () -> Unit = {},
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
            }
        }
    }

    /** Refreshes Management capability inspection; ignored while a refresh is checking. */
    fun refresh() {
        if (state.value.checking || actions.value.running) return
        launchWrite({ discovery.refresh().also { refreshAssignments() } }) { _ ->
            if (state.value.editable) actions.update { it.copy(readRevision = it.readRevision + 1) }
        }
    }

    /** Consumes [result]'s completion without changing the saved Adyen configuration. */
    fun dismissResult(result: CapabilityResult) {
        actions.update {
            when (result) {
                CapabilityResult.GRATUITY -> it.copy(gratuity = ActionState())
                CapabilityResult.DCC -> it.copy(dcc = ActionState())
                CapabilityResult.MOTO -> it.copy(moto = ActionState())
                CapabilityResult.OFFLINE -> it.copy(offline = ActionState())
            }
        }
    }

    /** Validates the complete draft again and binds the write to the identity the operator actually edited. */
    internal fun saveEditor(
        feature: CapabilityFeature,
        draft: CapabilityDraft,
        currency: String,
        identity: String?,
    ) {
        val result = feature.editorResult ?: return
        if (!state.value.editable || state.value.identity != identity || !draft.valid(feature, currency)) return
        if (result == CapabilityResult.MOTO && draft.motoCurrency != state.value.motoCurrency) return
        save(result) {
            when (result) {
                CapabilityResult.GRATUITY -> {
                    discovery.updateGratuity(currency, draft.gratuity(currency), identity)
                }

                CapabilityResult.DCC -> {
                    discovery.updateDcc(draft.enabled, identity)
                }

                CapabilityResult.MOTO -> {
                    discovery.updateMoto(draft.moto(), identity)
                }

                CapabilityResult.OFFLINE -> {
                    discovery.updateOffline(
                        currency,
                        if (draft.chip.inherited) null else checkNotNull(draft.chip.amount(currency)),
                        if (draft.storeAndForward.inherited) null else checkNotNull(draft.storeAndForward.amount(currency)),
                        if (draft.count.inherited) null else checkNotNull(draft.count.integer()),
                        identity,
                    )
                }
            }
        }
    }

    /** Sends one explicit patch through the shared action runner; no result ever triggers a retry. */
    private fun save(
        result: CapabilityResult,
        patch: suspend () -> CapabilityPatchResult,
    ) {
        if (actions.value.running) return
        runAction(
            actions,
            { it.action(result) },
            { action ->
                val recorded =
                    when (result) {
                        CapabilityResult.GRATUITY -> copy(gratuity = action)
                        CapabilityResult.DCC -> copy(dcc = action)
                        CapabilityResult.MOTO -> copy(moto = action)
                        CapabilityResult.OFFLINE -> copy(offline = action)
                    }
                if (action.done) {
                    recorded.copy(savedRevisions = savedRevisions + (result to ((savedRevisions[result] ?: 0) + 1)))
                } else {
                    recorded
                }
            },
        ) { patch().toActionState() }
    }

    private fun CapabilityPatchResult.toActionState(): ActionState =
        when (this) {
            CapabilityPatchResult.Updated -> ActionState(done = true)
            is CapabilityPatchResult.NotDone -> ActionState(outcome = ActionOutcome.Failed(failure), isError = true)
            is CapabilityPatchResult.Unconfirmed -> ActionState(outcome = ActionOutcome.Unconfirmed(failure), isError = true)
        }
}
