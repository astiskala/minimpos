package io.github.astiskala.minimpos.core.payment

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

/**
 * Merchant-scanned wallets requested by this app; configuration eligibility is decided separately.
 * Numeric validation never follows URLs or extracts digits from other payloads. PayMe is deliberately best-effort.
 *
 * @property brand Terminal API AllowedPaymentBrand value, unchanged on the wire.
 * @property displayName Wallet's own name, not translated.
 * @param codePattern ASCII-digit contract; the bounded generic contract does not establish provider support.
 * @param managementType Management configuration type when different from the Terminal API brand.
 */
enum class ScanWallet(
    val brand: String,
    val displayName: String,
    codePattern: String = "[0-9]{1,128}",
    private val managementType: String = brand,
) {
    /** GCash when independently configured for POS, not inferred from Alipay+. */
    GCASH("gcash", "GCash"),

    /** DANA when independently configured for POS. */
    DANA("dana", "DANA"),

    /** Kakao Pay when independently configured for POS. */
    KAKAO_PAY("kakaopay", "Kakao Pay"),

    /** TrueMoney when independently configured for POS. */
    TRUE_MONEY("truemoney", "TrueMoney"),

    /** PayMe; the published scan-flow limitation remains an integration verification gap. */
    PAYME("payme_pos", "PayMe", managementType = "payme"),

    /** Alipay's documented 16–24 digit contract. */
    ALIPAY("alipay", "Alipay", "[0-9]{16,24}"),

    /** AlipayHK's 17–19 digit user-presented code. */
    ALIPAY_HK("alipay_hk", "AlipayHK", "[0-9]{17,19}"),

    /** Alipay+ numeric user-presented codes, retaining capacity for its documented length expansion. */
    ALIPAY_PLUS("alipay_plus", "Alipay+", "[0-9]{16,32}"),

    /** WeChat Pay's documented 18-digit contract. */
    WECHAT_PAY("wechatpay_pos", "WeChat Pay", "[0-9]{18}"),

    /** PayPal's documented 12-digit POS contract. */
    PAYPAL("paypal_pos", "PayPal", "[0-9]{12}", "paypal"),

    /** Venmo's documented 12-digit POS contract. */
    VENMO("venmo_pos", "Venmo", "[0-9]{12}", "venmo"),
    ;

    private val pattern = Regex(codePattern)

    /** Whether [code] matches this wallet's numeric contract; leading zeros remain significant. */
    fun accepts(code: String): Boolean = pattern.matches(code)

    /** Synthetic code for offline simulation only; never suitable for real payments. */
    val demoCode: String
        get() =
            when (this) {
                PAYPAL, VENMO -> "000190468703"
                WECHAT_PAY -> "133341022926803846"
                GCASH, DANA, KAKAO_PAY, TRUE_MONEY, PAYME, ALIPAY, ALIPAY_HK, ALIPAY_PLUS -> "284687593190468703"
            }

    /** Exact published configuration types and returned payment variants. */
    companion object {
        /** Matches a Management [type]; this alone does not establish POS, enabled, allowed or store eligibility. */
        fun configured(type: String): ScanWallet? {
            val normalized = PaymentMethods.normalizeBrand(type)
            return entries.firstOrNull { normalized == it.brand || normalized == it.managementType }
        }

        /** Wallet reported in [type], including resolved Alipay+ variants; null for an unknown method. */
        fun reported(type: String?): ScanWallet? {
            if (type == null) return null
            val normalized = PaymentMethods.normalizeBrand(type)
            return configured(normalized) ?: when (normalized) {
                "wechatpay" -> WECHAT_PAY
                "alipay_plus_alipay_cn" -> ALIPAY
                "alipay_plus_alipay_hk" -> ALIPAY_HK
                else -> normalized.takeIf { it.startsWith("alipay_plus_") }?.removePrefix("alipay_plus_")?.let(::configured)
            }
        }
    }
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
    fun brandName(brand: String): String =
        ScanWallet.reported(brand)?.displayName ?: normalizeBrand(brand).let { BRAND_NAMES[it] ?: it.uppercase(Locale.ROOT) }

    /** The wallet a `paymentMethodVariant` such as `mc_googlepay` names; null for a plain card, or no [variant]. */
    fun wallet(variant: String?): Wallet? {
        val suffix = variant?.let(::normalizeBrand)?.substringAfterLast('_', missingDelimiterValue = "") ?: return null
        return Wallet.entries.firstOrNull { it.suffix == suffix }
    }
}
