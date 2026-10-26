package app.minimpos.app.terminal

import app.minimpos.app.data.db.unresolved
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.data.repo.HistoryRepository
import app.minimpos.app.data.repo.recordedEnvironment
import app.minimpos.app.data.repo.retentionEligible
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
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
    /**
     * Whether [confirmed] covers this destination and every warning; key creation and phone registration do not change
     * history's environment.
     */
    fun wasConfirmedBy(confirmed: HistorySwitchPlan?): Boolean {
        if (confirmed == null) return false
        val generated =
            target.copy(
                keyIdentifier = confirmed.target.keyIdentifier,
                keyVersion = confirmed.target.keyVersion,
                paymentsAppInstallationId = confirmed.target.paymentsAppInstallationId,
            )
        if (original != confirmed.original || generated != confirmed.target) {
            return false
        }
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
        val facts =
            history.reader.fold(SwitchFacts()) { current, batch ->
                SwitchFacts(
                    current.present || batch.isNotEmpty(),
                    current.unfinished || batch.any(::unfinished),
                    current.environments + batch.mapNotNull(::recordedEnvironment),
                )
            }
        val recorded = facts.environments
        val before = original.historyEnvironment(automaticMode) ?: recorded.singleOrNull()
        val changed = before != after && (after != null || before == "SIMULATOR")
        val mismatched = before == null && after != null && recorded.any { it != after }
        if (!facts.present || (!changed && !mismatched)) return null
        return HistorySwitchPlan(original, target, facts.unfinished)
    }

    private data class SwitchFacts(
        val present: Boolean = false,
        val unfinished: Boolean = false,
        val environments: Set<String> = emptySet(),
    )

    private fun recordedEnvironment(item: HistoryItem): String? = item.recordedEnvironment

    private fun unfinished(item: HistoryItem): Boolean =
        when (item) {
            is HistoryItem.Sale -> !item.sale.retentionEligible && !item.sale.sample
            is HistoryItem.Refund -> item.refund.unresolved
            is HistoryItem.GiftCard -> item.activity.unresolved || item.activity.needsAttention
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
