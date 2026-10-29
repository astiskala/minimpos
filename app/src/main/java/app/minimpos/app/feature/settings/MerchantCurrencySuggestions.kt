package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.terminal.ApiSetup
import app.minimpos.app.terminal.MerchantCurrency
import app.minimpos.app.terminal.MerchantCurrencyProposal
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.terminal.TerminalStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.minimpos.app.payment.PricingChanges as PricingOperations

/** Settings-only optional proposal; setup succeeds even when Management cannot supply a currency. */
internal class MerchantCurrencySuggestions(
    private val owner: ViewModel,
    private val lookup: MerchantCurrency?,
    status: TerminalStatus,
    operations: PricingOperations,
    private val pricing: PricingChanges,
) {
    val proposal: StateFlow<MerchantCurrencyProposal?>
        field = MutableStateFlow<MerchantCurrencyProposal?>(null)

    init {
        owner.viewModelScope.launch {
            combine(status.state, operations.changes.map { it.terminal }.distinctUntilChanged()) { state, terminal ->
                val ready =
                    state.connection is TerminalConnection.Connected && state.apiSetup == ApiSetup.Complete &&
                        state.setupProblem == null && !state.importPending
                terminal to ready
            }.distinctUntilChanged().collectLatest { (_, ready) ->
                proposal.value = null
                if (ready) proposal.value = lookup?.find()
            }
        }
    }

    fun dismiss() {
        proposal.value = null
    }

    fun choose() =
        owner.viewModelScope.launch {
            val offered = proposal.value ?: return@launch
            if (!offered.current()) {
                proposal.value = null
                return@launch
            }
            val terminal: TerminalSettings = offered.terminal
            pricing.edit {
                if (it.terminal == terminal) it.copy(payment = it.payment.copy(currencyCode = offered.code)) else it
            }
        }
}
