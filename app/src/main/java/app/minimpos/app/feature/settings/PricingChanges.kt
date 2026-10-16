package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PricingChange
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.payment.SettingsEdit
import app.minimpos.app.terminal.HistorySwitches
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import app.minimpos.app.payment.PricingChanges as PricingChangeOperations

/** The settings screen's pricing confirmation, with durable work owned by [PricingChangeOperations]. */
internal class PricingChanges(
    private val owner: ViewModel,
    private val operations: PricingChangeOperations,
) {
    private val _pending = MutableStateFlow<PricingChange?>(null)
    val pending: StateFlow<PricingChange?> = _pending

    fun edit(
        transform: (AppSettings) -> AppSettings,
        switches: HistorySwitches?,
        environment: HistorySwitchConfirmation,
    ) {
        owner.launchWrite({ operations.edit(transform, switches) }) { result ->
            when (result) {
                SettingsEdit.Saved -> _pending.value = null
                is SettingsEdit.ConfirmHistorySwitch -> environment.propose(result.plan)
                is SettingsEdit.ConfirmPricing -> _pending.value = result.change
            }
        }
    }

    fun cancel() {
        _pending.value = null
    }

    fun confirm() {
        val change = _pending.value ?: return
        owner.launchWrite({ operations.confirm(change) }) { confirmed -> if (confirmed) _pending.value = null }
    }
}
