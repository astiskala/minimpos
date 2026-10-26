package app.minimpos.app.payment

import app.minimpos.app.data.settings.CashbackCard
import app.minimpos.app.data.settings.CashbackSettings
import app.minimpos.app.terminal.WalletAvailability

/** Pure market and request rules, shared by checkout and standalone cash-out. */
object Cashback {
    // Geography bounds the merchant's explicit Support confirmation; membership alone never establishes support.
    private val europe =
        setOf(
            "AT",
            "BE",
            "BG",
            "HR",
            "CY",
            "CZ",
            "DK",
            "EE",
            "FI",
            "FR",
            "DE",
            "GR",
            "HU",
            "IS",
            "IE",
            "IT",
            "LV",
            "LI",
            "LT",
            "LU",
            "MT",
            "NL",
            "NO",
            "PL",
            "PT",
            "RO",
            "SK",
            "SI",
            "ES",
            "SE",
            "CH",
            "GB",
        )

    /** Whether verified store facts and explicit configuration permit [standalone] in [currency]. */
    fun ready(
        settings: CashbackSettings,
        availability: WalletAvailability,
        currency: String,
        standalone: Boolean,
    ): Boolean {
        if (!(if (standalone) settings.standalone else settings.enabled)) return false
        if (settings.limitMinor <= 0 || settings.card == null) return false
        if (!verified(settings, availability, currency)) return false
        val market = market(settings.card, availability.storeCountry, currency, availability.simulated)
        return market && (!standalone || settings.card == CashbackCard.EFTPOS)
    }

    private fun market(
        card: CashbackCard,
        country: String?,
        currency: String,
        simulated: Boolean,
    ): Boolean =
        when (card) {
            CashbackCard.EFTPOS -> (country == "AU" || simulated) && currency == "AUD"
            CashbackCard.GIROCARD -> (country == "DE" || simulated) && currency == "EUR"
            CashbackCard.VISA, CashbackCard.MASTERCARD -> country in europe || simulated
        }

    private fun verified(
        settings: CashbackSettings,
        facts: WalletAvailability,
        currency: String,
    ): Boolean {
        if (!facts.cashbackDestination || !facts.checked || facts.problem != null) return false
        if (facts.context == null || facts.identity == null) return false
        if (settings.context != facts.context || settings.currency != currency) return false
        return facts.simulated || sameStore(settings, facts)
    }

    private fun sameStore(
        settings: CashbackSettings,
        facts: WalletAvailability,
    ): Boolean = settings.storeId != null && settings.storeId == facts.storeId && settings.storeCountry == facts.storeCountry
}
