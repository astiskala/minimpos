package app.minimpos.app.payment

import app.minimpos.app.data.repo.CatalogRepository
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PricingChange
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.terminal.HistorySwitchPlan
import app.minimpos.app.terminal.HistorySwitches
import app.minimpos.core.money.CurrencySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What an attempted settings write requires before it can take effect. */
sealed interface SettingsEdit {
    /** The non-destructive, non-repricing change was saved (or ignored while busy/recovering). */
    data object Saved : SettingsEdit

    /**
     * The change switches history environment and requires explicit destructive confirmation.
     *
     * @property plan Destructive switch plan to confirm.
     */
    data class ConfirmHistorySwitch(
        val plan: HistorySwitchPlan,
    ) : SettingsEdit

    /**
     * The change alters currency or tax style and requires explicit repricing confirmation.
     *
     * @property change Repricing preview to confirm.
     */
    data class ConfirmPricing(
        val change: PricingChange,
    ) : SettingsEdit
}

/**
 * Confirmed currency and tax-style changes, including their durable journal and startup recovery. Operations are
 * main-safe and serialized. Persistence failures propagate, leaving the journal available for [recover]; callers
 * finish a confirmation even when its screen closes. Absolute catalogue targets make replay idempotent, and sessions
 * are repriced while the journal still blocks checkout.
 *
 * A change's pricing intent is read from the stored diff in one pass ([reprices]), never declared by callers: any
 * write that reprices the catalogue must be caught wherever it comes from, and a declared intent could be forgotten.
 * A change that both switches the history environment and reprices is confirmed as the switch first ([edit]); its
 * repricing follows once the switch is settled.
 *
 * @param settings Stores pricing and the local journal.
 * @param catalog Stores absolute catalogue prices atomically.
 * @param currency Resolves the device-country currency when the setting is blank.
 * @param sessions Sessions whose displayed unit prices follow a confirmed change.
 * @param active Whether a financial request is running, checked again before confirmation.
 */
class PricingChanges(
    private val settings: SettingsRepository,
    private val catalog: CatalogRepository,
    private val currency: (AppSettings) -> CurrencySpec,
    private val sessions: Collection<SaleSession>,
    private val active: () -> Boolean,
) {
    /** Stored settings for confirmation presentation; their pricing writes go through [update] and [confirm]. */
    val changes: Flow<AppSettings> = settings.settings

    private val mutex = Mutex()
    private var repriced: PricingChange? = null

    /**
     * Runs [write] with stable current settings while excluding pricing confirmation and ordinary settings writes.
     * Used when creating sample prices so they cannot race a currency/tax-style transition.
     * @throws IllegalStateException while a pricing journal still needs recovery.
     */
    suspend fun <T> withStableSettings(write: suspend (AppSettings) -> T): T =
        mutex.withLock {
            val current = settings.current()
            check(current.pricingChange == null) { "Pricing change in progress" }
            write(current)
        }

    /**
     * Evaluates [transform] in one serialized pass: proposes a history-switch confirmation when [switches] requires
     * one (even when the change also reprices), otherwise stores ordinary settings or proposes a pricing confirmation.
     */
    suspend fun edit(
        transform: (AppSettings) -> AppSettings,
        switches: HistorySwitches? = null,
    ): SettingsEdit =
        mutex.withLock {
            val current = settings.current()
            if (current.pricingChange != null) return@withLock SettingsEdit.Saved
            val proposed = transform(current)
            val switchPlan = switches?.preview(proposed.terminal)
            if (switchPlan != null) {
                SettingsEdit.ConfirmHistorySwitch(switchPlan)
            } else {
                updateUnlocked(current, proposed, transform)?.let(SettingsEdit::ConfirmPricing) ?: SettingsEdit.Saved
            }
        }

    /** Stores ordinary settings, or previews a pricing change; null when stored, blocked, or rounding would erase a price. */
    suspend fun update(transform: (AppSettings) -> AppSettings): PricingChange? =
        mutex.withLock {
            val current = settings.current()
            if (current.pricingChange != null) return@withLock null
            updateUnlocked(current, transform(current), transform)
        }

    private suspend fun updateUnlocked(
        current: AppSettings,
        proposed: AppSettings,
        transform: (AppSettings) -> AppSettings,
    ): PricingChange? =
        if (!reprices(current, proposed)) {
            settings.update(transform)
            null
        } else if (active()) {
            null
        } else {
            preview(current, proposed)
        }

    /**
     * Whether changing the settings from [current] to [proposed] reprices the catalogue: the charged currency or the
     * tax style changes. The single-pass evaluation reads the stored values; see the class contract for why a caller
     * never declares this intent.
     */
    private fun reprices(
        current: AppSettings,
        proposed: AppSettings,
    ): Boolean = currency(current) != currency(proposed) || current.payment.taxMode != proposed.payment.taxMode

    /** Commits [change]; false while busy, another journal exists, or its currency or catalogue preview is stale. */
    suspend fun confirm(change: PricingChange): Boolean =
        mutex.withLock {
            val current = settings.current()
            if (active() || current.pricingChange != null || currency(current).code != change.fromCurrency) return@withLock false
            val proposed = current.copy(payment = change.payment)
            if (preview(current, proposed) != change || active()) return@withLock false
            settings.update { it.copy(pricingChange = change) }
            apply(change)
            true
        }

    /** Completes a journal left by interruption; repeated recovery without a journal changes nothing. */
    suspend fun recover() =
        mutex.withLock {
            settings.current().pricingChange?.let { apply(it) }
            Unit
        }

    private suspend fun preview(
        current: AppSettings,
        proposed: AppSettings,
    ): PricingChange? {
        val from = currency(current)
        val to = currency(proposed)
        val prices = catalog.reader.products().associate { it.id to to.toMinor(from.toMajor(it.priceMinor)) }
        return if (prices.values.any { it <= 0 }) null else PricingChange(from.code, to.code, proposed.payment, prices)
    }

    private suspend fun apply(change: PricingChange) {
        catalog.pricing.apply(change.prices)
        if (repriced != change) {
            sessions.forEach { it.reprice(CurrencySpec.of(change.fromCurrency), CurrencySpec.of(change.toCurrency)) }
            repriced = change
        }
        settings.update {
            it.copy(
                payment = it.payment.copy(currencyCode = change.payment.currencyCode, taxMode = change.payment.taxMode),
                pricingChange = null,
            )
        }
        repriced = null
    }

    /** Checkout readiness uses the same journal as confirmation and recovery. */
    companion object {
        /** False while [settings] holds a pricing journal; no payment may start with partially updated prices. */
        fun ready(settings: AppSettings): Boolean = settings.pricingChange == null
    }
}
