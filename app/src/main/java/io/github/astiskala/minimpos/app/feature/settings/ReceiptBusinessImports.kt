package io.github.astiskala.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.astiskala.minimpos.app.feature.ActionOutcome
import io.github.astiskala.minimpos.app.feature.ActionState
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.app.terminal.ReceiptBusiness
import io.github.astiskala.minimpos.app.terminal.ReceiptBusinessDetails
import io.github.astiskala.minimpos.app.terminal.ReceiptBusinesses
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.astiskala.minimpos.app.payment.PricingChanges as SettingsOperations

/**
 * Receipt import presentation, separate from saved receipt settings.
 * @property lookup Latest read-only Management lookup.
 * @property stores Proposals offered for review; null when no selection is open.
 * @property revision Advances after import so business text editors reload confirmed values.
 */
internal data class ReceiptBusinessImportState(
    val lookup: ActionState = ActionState(),
    val stores: List<ReceiptBusiness>? = null,
    val revision: Int = 0,
)

/** Settings' store selection and confirmation; durable settings writes follow the owning view model's write lifecycle. */
internal class ReceiptBusinessImports(
    private val owner: ViewModel,
    private val details: ReceiptBusinessDetails,
    private val operations: SettingsOperations,
    private val settings: StateFlow<SettingsUiState>,
) {
    private val _state = MutableStateFlow(ReceiptBusinessImportState())
    val state: StateFlow<ReceiptBusinessImportState> = _state.asStateFlow()

    fun find() {
        if (_state.value.lookup.running) return
        _state.update { it.copy(lookup = ActionState(running = true), stores = null) }
        owner.viewModelScope.launch {
            val result = details.stores()
            _state.update {
                when (result) {
                    is ReceiptBusinesses.Listed -> {
                        it.copy(lookup = ActionState(done = true), stores = result.stores)
                    }

                    is ReceiptBusinesses.NotSetUp -> {
                        it.copy(
                            lookup = ActionState(outcome = ActionOutcome.NotSetUp(result.problem), isError = true),
                        )
                    }

                    is ReceiptBusinesses.Failed -> {
                        it.copy(
                            lookup = ActionState(outcome = ActionOutcome.Failed(result.message), isError = true),
                        )
                    }
                }
            }
        }
    }

    fun choose(business: ReceiptBusiness?) {
        if (business != null && (business !in _state.value.stores.orEmpty() || !business.available)) return
        _state.update { it.copy(stores = null, lookup = ActionState()) }
        if (business != null) {
            owner.launchWrite({
                operations.update { it.copy(receipt = business.applyTo(it.receipt)) }
                val receipt = operations.changes.first().receipt
                settings.first { it.settings.receipt == receipt }
            }) { _ ->
                _state.update { it.copy(revision = it.revision + 1) }
            }
        }
    }
}
