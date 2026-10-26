package app.minimpos.app.payment

import app.minimpos.app.data.settings.UnreferencedRefundSettings
import app.minimpos.app.terminal.WalletAvailability

/** Why an unreferenced refund cannot be made now, in the order the Refund screen explains them. */
enum class UnreferencedRefundBlock {
    /** The destination carries none (the Payments app, as this app sets it up). */
    DESTINATION,

    /** The merchant has not confirmed Adyen's enablement for this store. */
    NOT_ENABLED,

    /** No positive local limit is configured. */
    NO_LIMIT,

    /** The assigned store, its country and the connection have not been verified with Adyen yet. */
    NOT_VERIFIED,

    /** Adyen does not permit unreferenced refunds for the verified store's country. */
    COUNTRY,

    /** The store, connection or currency changed since the merchant confirmed the enablement. */
    CHANGED,
}

/**
 * Pure market and readiness rules of unreferenced card refunds. Readiness comes from verified Management store facts and
 * explicit merchant configuration, never from currency, language, locale or a simulator approval; the simulator offers an
 * explicit offline demonstration only. Adyen's own unreferenced refund limit, refund delay and merchant-category
 * exclusions still apply and are enforced by Adyen.
 */
object UnreferencedRefunds {
    /**
     * Store countries where Adyen does not permit unreferenced refunds, as documented on October 26, 2026: Brazil,
     * Mexico, Australia, Malaysia, Singapore, Hong Kong and India.
     */
    val excludedCountries: Set<String> = setOf("BR", "MX", "AU", "MY", "SG", "HK", "IN")

    /** Why an unreferenced refund in [currency] cannot be made with [settings] and verified [facts]; null when it can. */
    fun block(
        settings: UnreferencedRefundSettings,
        facts: WalletAvailability,
        currency: String,
    ): UnreferencedRefundBlock? =
        when {
            !facts.unreferencedRefundDestination -> UnreferencedRefundBlock.DESTINATION
            !settings.enabled -> UnreferencedRefundBlock.NOT_ENABLED
            settings.limitMinor <= 0 -> UnreferencedRefundBlock.NO_LIMIT
            !verified(facts) -> UnreferencedRefundBlock.NOT_VERIFIED
            !facts.simulated && facts.storeCountry in excludedCountries -> UnreferencedRefundBlock.COUNTRY
            !confirmedFor(settings, facts, currency) -> UnreferencedRefundBlock.CHANGED
            else -> null
        }

    /** Whether [amountMinor] may be refunded unreferenced in [currency] now: ready and within the local limit. */
    fun allows(
        settings: UnreferencedRefundSettings,
        facts: WalletAvailability,
        currency: String,
        amountMinor: Long,
    ): Boolean = block(settings, facts, currency) == null && amountMinor in 1..settings.limitMinor

    private fun verified(facts: WalletAvailability): Boolean =
        facts.checked && facts.problem == null && facts.context != null && facts.identity != null &&
            (facts.simulated || (facts.storeId != null && facts.storeCountry != null))

    private fun confirmedFor(
        settings: UnreferencedRefundSettings,
        facts: WalletAvailability,
        currency: String,
    ): Boolean =
        settings.context == facts.context && settings.currency == currency &&
            (facts.simulated || (settings.storeId == facts.storeId && settings.storeCountry == facts.storeCountry))
}
