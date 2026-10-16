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
    private val switches: HistorySwitches? = null,
    private val environment: HistorySwitchConfirmation,
) {
    val pending: StateFlow<PricingChange?>
        field = MutableStateFlow<PricingChange?>(null)

    fun edit(transform: (AppSettings) -> AppSettings) {
        owner.launchWrite({ operations.edit(transform, switches) }) { result ->
            when (result) {
                SettingsEdit.Saved -> pending.value = null
                is SettingsEdit.ConfirmHistorySwitch -> environment.propose(result.plan)
                is SettingsEdit.ConfirmPricing -> pending.value = result.change
            }
        }
    }

    fun cancel() {
        pending.value = null
    }

    fun confirm() {
        val change = pending.value ?: return
        owner.launchWrite({ operations.confirm(change) }) { confirmed -> if (confirmed) pending.value = null }
    }
}
