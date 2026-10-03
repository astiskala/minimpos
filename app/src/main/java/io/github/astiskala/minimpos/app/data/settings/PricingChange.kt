package io.github.astiskala.minimpos.app.data.settings

import kotlinx.serialization.Serializable

/**
 * Confirmed pricing transition journal; prices are absolute minor-unit targets, so recovery is idempotent.
 * @property fromCurrency Original currency code.
 * @property toCurrency New currency code.
 * @property payment Confirmed currency and tax-style configuration.
 * @property prices Catalogue row IDs to absolute minor-unit target prices.
 */
@Serializable
data class PricingChange(
    val fromCurrency: String,
    val toCurrency: String,
    val payment: PaymentSettings,
    val prices: Map<Long, Long>,
)
