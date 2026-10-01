package io.minimpos.core.payment

import java.util.Locale

/**
 * A mobile wallet a card can be presented in, as Adyen names it at the end of a `paymentMethodVariant` (for example
 * `visa_applepay`).
 *
 * @property displayName The wallet's own name, which is not translated.
 * @property suffix The part of the variant after the last underscore.
 */
enum class Wallet(
    val displayName: String,
    val suffix: String,
) {
    /** Apple Pay (`*_applepay`). */
    APPLE_PAY("Apple Pay", "applepay"),

    /** Google Pay (`*_googlepay`). */
    GOOGLE_PAY("Google Pay", "googlepay"),

    /** Samsung Pay (`*_samsungpay`). */
    SAMSUNG_PAY("Samsung Pay", "samsungpay"),
}

/** Names for the card brands and wallets the terminal reports, for lists and filters. */
object PaymentMethods {
    /** Adyen's brand codes (`PaymentBrand`, `paymentMethod`) and the names schemes use for themselves. */
    private val BRAND_NAMES =
        mapOf(
            "accel" to "Accel",
            "amex" to "American Express",
            "bcmc" to "Bancontact",
            "cartebancaire" to "Cartes Bancaires",
            "cup" to "UnionPay",
            "dankort" to "Dankort",
            "diners" to "Diners Club",
            "discover" to "Discover",
            "eftpos_australia" to "eftpos",
            "elo" to "Elo",
            "girocard" to "girocard",
            "hipercard" to "Hipercard",
            "interac_card" to "Interac",
            "jcb" to "JCB",
            "maestro" to "Maestro",
            "mc" to "Mastercard",
            "nyce" to "NYCE",
            "pulse" to "Pulse",
            "star" to "Star",
            "visa" to "Visa",
            "vpay" to "V Pay",
        )

    /** [brand] in lower case without surrounding spaces, so codes compare the same however the terminal cased them. */
    fun normalizeBrand(brand: String): String = brand.trim().lowercase(Locale.ROOT)

    /** The scheme's name for the Adyen brand code [brand] (`mc` is Mastercard), else the code in upper case. */
    fun brandName(brand: String): String = normalizeBrand(brand).let { BRAND_NAMES[it] ?: it.uppercase(Locale.ROOT) }

    /** The wallet a `paymentMethodVariant` such as `mc_googlepay` names; null for a plain card, or no [variant]. */
    fun wallet(variant: String?): Wallet? {
        val suffix = variant?.let(::normalizeBrand)?.substringAfterLast('_', missingDelimiterValue = "") ?: return null
        return Wallet.entries.firstOrNull { it.suffix == suffix }
    }
}
