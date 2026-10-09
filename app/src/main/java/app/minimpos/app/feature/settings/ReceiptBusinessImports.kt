package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.terminal.ReceiptBusiness
import app.minimpos.app.terminal.ReceiptBusinessDetails
import app.minimpos.app.terminal.ReceiptBusinesses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.minimpos.app.payment.PricingChanges as SettingsOperations

/**
 * Receipt import presentation, separate from saved receipt settings.
 * @property lookup Latest read-only Management lookup.
 * @property proposal Proposal offered for review; null when no review is open.
 * @property revision Advances after import so business text editors reload confirmed values.
 */
internal data class ReceiptBusinessImportState(
    val lookup: ActionState = ActionState(),
    val proposal: ReceiptBusiness? = null,
    val revision: Int = 0,
)

/** Settings' receipt-business review and confirmation; durable settings writes follow the owning view model's write lifecycle. */
internal class ReceiptBusinessImports(
    private val owner: ViewModel,
    private val details: ReceiptBusinessDetails,
    private val operations: SettingsOperations,
    private val settings: StateFlow<SettingsUiState>,
) {
    private val _state = MutableStateFlow(ReceiptBusinessImportState())
    val state: StateFlow<ReceiptBusinessImportState> = _state.asStateFlow()
    private var origin: TerminalSettings? = null

    fun find(automatic: Boolean = false) {
        if (_state.value.lookup.running) return
        _state.update { it.copy(lookup = ActionState(running = true), proposal = null) }
        owner.viewModelScope.launch {
            origin = operations.changes.first().terminal
            val result = details.lookup()
            val changed = details.validateOrigin(checkNotNull(origin))
            if (changed != null) {
                _state.update {
                    it.copy(
                        lookup =
                            ActionState(
                                outcome = ActionOutcome.Failed(Failure.NotSetUp(changed)),
                                isError = true,
                            ),
                    )
                }
                return@launch
            }
            _state.update {
                when (result) {
                    is ReceiptBusinesses.Found -> {
                        it.copy(lookup = ActionState(done = true), proposal = result.business)
                    }

                    is ReceiptBusinesses.Failed -> {
                        it.copy(lookup = ActionState(outcome = ActionOutcome.Failed(result.failure), isError = true))
                    }
                }
            }
            if (automatic && result is ReceiptBusinesses.Found && result.business.available) choose(result.business)
        }
    }

    fun choose(business: ReceiptBusiness?) {
        if (business != null && (business != _state.value.proposal || !business.available)) return
        _state.update { it.copy(proposal = null, lookup = ActionState()) }
        if (business != null) {
            owner.launchWrite({
                operations.update { if (it.terminal == origin) it.copy(receipt = business.applyTo(it.receipt)) else it }
                val receipt = operations.changes.first().receipt
                settings.first { it.settings.receipt == receipt }
            }) { _ ->
                _state.update { it.copy(revision = it.revision + 1) }
            }
        }
    }
}
