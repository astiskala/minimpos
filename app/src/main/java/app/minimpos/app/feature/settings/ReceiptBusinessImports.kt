package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.ReceiptBusiness
import app.minimpos.app.terminal.ReceiptBusinessDetails
import app.minimpos.app.terminal.ReceiptBusinesses
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.minimpos.app.payment.PricingChanges as SettingsOperations

/**
 * Receipt import presentation, separate from saved receipt settings.
 * @property lookup Latest read-only Management lookup.
 * @property proposal Proposal offered for review; null when no review is open.
 * @property revision Advances after import so business text editors reload confirmed values.
 * @property resultPending Whether an explicitly requested action has an unacknowledged completion.
 */
internal data class ReceiptBusinessImportState(
    val lookup: ActionState = ActionState(),
    val proposal: ReceiptBusiness? = null,
    val revision: Int = 0,
    val resultPending: Boolean = false,
)

/** Settings' receipt-business review and confirmation; durable settings writes follow the owning view model's write lifecycle. */
internal class ReceiptBusinessImports(
    private val owner: ViewModel,
    private val details: ReceiptBusinessDetails,
    private val operations: SettingsOperations,
    private val observedReceipt: Flow<ReceiptSettings>,
    private val api: AdyenApi? = null,
) {
    val state: StateFlow<ReceiptBusinessImportState>
        field = MutableStateFlow(ReceiptBusinessImportState())
    private var origin: TerminalSettings? = null
    private var automatic = false

    /** Automatically looks up store or merchant receipt details when the Checkout API is ready and receipt details are blank. */
    suspend fun findIfMissing() {
        val target = api?.target() ?: return
        if (target.realAndReady && observedReceipt.first().businessDetailsMissing) {
            find(automatic = true)
        }
    }

    fun find(automatic: Boolean = false) {
        if (state.value.lookup.running) return
        this.automatic = automatic
        state.update { it.copy(lookup = ActionState(running = true), proposal = null, resultPending = false) }
        owner.viewModelScope.launch {
            origin = operations.changes.first().terminal
            val result = details.lookup()
            val changed = details.validateOrigin(checkNotNull(origin))
            if (changed != null) {
                state.update {
                    it.copy(
                        resultPending = !automatic,
                        lookup =
                            ActionState(
                                outcome = ActionOutcome.Failed(Failure.NotSetUp(changed)),
                                isError = true,
                            ),
                    )
                }
                return@launch
            }
            state.update {
                when (result) {
                    is ReceiptBusinesses.Found -> {
                        it.copy(lookup = ActionState(done = true), proposal = result.business)
                    }

                    is ReceiptBusinesses.Failed -> {
                        it.copy(
                            lookup = ActionState(outcome = ActionOutcome.Failed(result.failure), isError = true),
                            resultPending = !automatic,
                        )
                    }
                }
            }
            if (automatic && result is ReceiptBusinesses.Found && result.business.available) choose(result.business)
        }
    }

    fun choose(business: ReceiptBusiness?) {
        if (business != null && (business != state.value.proposal || !business.available)) return
        state.update { it.copy(proposal = null, lookup = ActionState()) }
        if (business != null) {
            val notify = !automatic
            owner.launchWrite({
                operations.update { if (it.terminal == origin) it.copy(receipt = business.applyTo(it.receipt)) else it }
                val receipt = operations.changes.first().receipt
                observedReceipt.first { it == receipt }
            }) { _ ->
                state.update { it.copy(revision = it.revision + 1, resultPending = notify) }
            }
        }
    }

    /** Consumes only the explicit import/lookup result; background lookup failures remain inline. */
    fun dismissResult() {
        state.update { it.copy(resultPending = false, lookup = it.lookup.dismissed()) }
    }
}
