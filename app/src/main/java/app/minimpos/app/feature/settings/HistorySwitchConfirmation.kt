package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.terminal.HistorySwitchPlan
import app.minimpos.app.terminal.HistorySwitches
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Settings presentation for explicit history deletion; persistence and recovery belong to HistorySwitches. */
internal class HistorySwitchConfirmation(
    private val owner: ViewModel,
    private val switches: HistorySwitches?,
    private val settings: Flow<AppSettings>,
) {
    private val proposed = MutableStateFlow<HistorySwitchPlan?>(null)
    val pending: StateFlow<HistorySwitchPlan?> = proposed

    fun update(
        transform: (AppSettings) -> AppSettings,
        unchanged: () -> Unit,
    ) {
        val operations = switches
        if (operations == null) {
            unchanged()
            return
        }
        owner.launchWrite({ operations.preview(transform(settings.first()).terminal) }) { plan ->
            if (plan == null) unchanged() else proposed.value = plan
        }
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
