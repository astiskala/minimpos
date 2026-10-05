package io.github.astiskala.minimpos.app.payment

import io.github.astiskala.minimpos.app.data.repo.CatalogRepository
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.PricingChange
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.core.money.CurrencySpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Confirmed currency and tax-style changes, including their durable journal and startup recovery. Operations are
 * main-safe and serialized. Persistence failures propagate, leaving the journal available for [recover]; callers
 * finish a confirmation even when its screen closes. Absolute catalogue targets make replay idempotent, and sessions
 * are repriced while the journal still blocks checkout.
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

    /** Stores ordinary settings, or previews a pricing change; null when stored, blocked, or rounding would erase a price. */
    suspend fun update(transform: (AppSettings) -> AppSettings): PricingChange? =
        mutex.withLock {
            val current = settings.current()
            val proposed = transform(current)
            if (current.pricingChange != null) return@withLock null
            if (currency(current) == currency(proposed) && current.payment.taxMode == proposed.payment.taxMode) {
                settings.update(transform)
                null
            } else if (active()) {
                null
            } else {
                preview(current, proposed)
            }
        }

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
        val prices = catalog.products.first().associate { it.id to to.toMinor(from.toMajor(it.priceMinor)) }
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
