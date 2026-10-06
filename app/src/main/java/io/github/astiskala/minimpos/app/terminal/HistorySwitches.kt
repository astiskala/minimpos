package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.repo.HistoryItem
import io.github.astiskala.minimpos.app.data.repo.HistoryRepository
import io.github.astiskala.minimpos.app.data.repo.retentionEligible
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Explicit destructive confirmation for an environment change, not a payment cancellation. */
data class HistorySwitchPlan(
    /** Terminal settings against which confirmation must remain current. */
    val original: TerminalSettings,
    /** Proposed non-secret destination settings. */
    val target: TerminalSettings,
    /** Whether the extra loss-of-recovery warning is required. */
    val unfinished: Boolean,
) {
    /** Whether [confirmed] covers this exact destination and every currently required warning. */
    fun wasConfirmedBy(confirmed: HistorySwitchPlan?): Boolean {
        if (confirmed == null) return false
        if (original != confirmed.original || target != confirmed.target) return false
        return !unfinished || confirmed.unfinished
    }
}

/** Serializes confirmed history deletion with its durable destination journal; never cancels Adyen operations. */
class HistorySwitches(
    /** Current non-secret settings, preserving unrelated merchant configuration on commit. */
    private val settings: SettingsRepository,
    /** Owns transactional purge and its non-secret recovery journal. */
    private val history: HistoryRepository,
    /** Device-resolved default destination, TERMINAL or SIMULATOR. */
    private val automaticMode: TerminalMode,
) {
    private val mutex = Mutex()

    /** Returns a required warning for [target], or null when history is empty or its environment is unchanged. */
    suspend fun preview(target: TerminalSettings): HistorySwitchPlan? = mutex.withLock { previewUnlocked(target) }

    private suspend fun previewUnlocked(target: TerminalSettings): HistorySwitchPlan? {
        val original = settings.current().terminal
        if (target == original) return null
        val after = target.historyEnvironment(automaticMode)
        val items = history.items().first()
        val recorded = items.mapNotNull(::recordedEnvironment).toSet()
        val before = original.historyEnvironment(automaticMode) ?: recorded.singleOrNull()
        val changed = before != after && (after != null || before == "SIMULATOR")
        val mismatched = before == null && after != null && recorded.any { it != after }
        if (items.isEmpty() || (!changed && !mismatched)) return null
        return HistorySwitchPlan(original, target, items.any(::unfinished))
    }

    private fun recordedEnvironment(item: HistoryItem): String? {
        val context =
            when (item) {
                is HistoryItem.Sale -> item.sale.context
                is HistoryItem.Refund -> item.refund.context
            }
        return if (item is HistoryItem.Sale && item.sale.sample) "SIMULATOR" else context?.historyEnvironment
    }

    private fun unfinished(item: HistoryItem): Boolean =
        when (item) {
            is HistoryItem.Sale -> !item.sale.retentionEligible && !item.sale.sample
            is HistoryItem.Refund -> item.refund.status == RefundStatus.PENDING || item.refund.status == RefundStatus.UNKNOWN
        }

    /** Commits an explicit [plan]; false if destination or required warnings changed since preview. Main-safe. */
    suspend fun confirm(plan: HistorySwitchPlan): Boolean =
        mutex.withLock {
            if (settings.current().terminal != plan.original) return@withLock false
            val current = previewUnlocked(plan.target)
            if (current != null && current.unfinished && !plan.unfinished) return@withLock false
            history.purgeForEnvironmentSwitch(plan.target)
            recoverUnlocked()
            true
        }

    /** Completes only an already confirmed purge after interruption; never silently approves a new deletion. */
    suspend fun recover() = mutex.withLock { recoverUnlocked() }

    private suspend fun recoverUnlocked() {
        val target = history.switchTarget() ?: return
        settings.update { it.copy(terminal = target) }
        history.finishSwitch()
    }
}
