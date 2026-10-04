package io.github.astiskala.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.PricingChange
import io.github.astiskala.minimpos.app.feature.launchWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import io.github.astiskala.minimpos.app.payment.PricingChanges as PricingChangeOperations

/** The settings screen's pricing confirmation, with durable work owned by [PricingChangeOperations]. */
internal class PricingChanges(
    private val owner: ViewModel,
    private val operations: PricingChangeOperations,
) {
    private val _pending = MutableStateFlow<PricingChange?>(null)
    val pending: StateFlow<PricingChange?> = _pending

    fun update(transform: (AppSettings) -> AppSettings) {
        owner.launchWrite({ operations.update(transform) }) { _pending.value = it }
    }

    fun cancel() {
        _pending.value = null
    }

    fun confirm() {
        val change = _pending.value ?: return
        owner.launchWrite({ operations.confirm(change) }) { confirmed -> if (confirmed) _pending.value = null }
    }
}
