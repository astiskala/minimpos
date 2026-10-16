package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.terminal.HistorySwitchPlan
import app.minimpos.app.terminal.HistorySwitches
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Settings presentation for explicit history deletion; persistence and recovery belong to HistorySwitches. */
internal class HistorySwitchConfirmation(
    private val owner: ViewModel,
    private val switches: HistorySwitches?,
) {
    private val proposed = MutableStateFlow<HistorySwitchPlan?>(null)
    val pending: StateFlow<HistorySwitchPlan?> = proposed

    fun propose(plan: HistorySwitchPlan) {
        proposed.value = plan
    }

    fun review(confirm: Boolean) {
        val plan = proposed.value ?: return
        proposed.value = null
        if (!confirm) return
        val operations = switches ?: return
        owner.launchWrite({ operations.confirm(plan) }) { saved ->
            if (!saved) owner.launchWrite({ operations.preview(plan.target) }) { proposed.value = it }
        }
    }
}
