package io.github.astiskala.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import io.github.astiskala.minimpos.app.data.repo.CatalogRepository
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.PricingChange
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.core.money.CurrencySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Explicit pricing previews and recoverable commits owned by the settings screen. */
internal class PricingChanges(
    private val owner: ViewModel,
    private val settings: SettingsRepository,
    private val catalog: CatalogRepository,
    private val currency: (AppSettings) -> CurrencySpec,
    private val onRepriced: (CurrencySpec, CurrencySpec) -> Unit,
    private val active: () -> Boolean,
) {
    private val _pending = MutableStateFlow<PricingChange?>(null)
    val pending: StateFlow<PricingChange?> = _pending

    fun update(
        current: AppSettings,
        transform: (AppSettings) -> AppSettings,
    ) {
        val proposed = transform(current)
        if (currency(current) == currency(proposed) && current.payment.taxMode == proposed.payment.taxMode) {
            owner.launchWrite({ settings.update(transform) })
        } else if (!active()) {
            owner.launchWrite({
                val from = currency(current)
                val to = currency(proposed)
                val prices = catalog.products.first().associate { it.id to to.toMinor(from.toMajor(it.priceMinor)) }
                if (prices.values.any { it <= 0 }) null else PricingChange(from.code, to.code, proposed.payment, prices)
            }) { _pending.value = it }
        }
    }

    fun cancel() {
        _pending.value = null
    }

    fun confirm() {
        val change = _pending.value ?: return
        if (active()) return
        owner.launchWrite({
            settings.update { it.copy(pricingChange = change) }
            catalog.pricing.apply(change.prices)
            settings.update {
                it.copy(
                    payment = it.payment.copy(currencyCode = change.payment.currencyCode, taxMode = change.payment.taxMode),
                    pricingChange = null,
                )
            }
            onRepriced(CurrencySpec.of(change.fromCurrency), CurrencySpec.of(change.toCurrency))
        }) { _pending.value = null }
    }
}
