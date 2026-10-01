package io.minimpos.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A currency and the number of digits in its minor unit (e.g. AUD = 2, JPY = 0), as Adyen defines it. The app keeps
 * every amount as a whole number of minor units; this converts between that and decimal major units (AUD 1250 is
 * 12.50).
 *
 * Create it with [of] so the digits come from [AdyenCurrencies], which differs from ISO 4217 for some currencies. The
 * constructor only checks the format and throws [IllegalArgumentException] when [code] is not three letters A-Z or
 * [fractionDigits] is outside 0..4.
 *
 * @property code the upper-case ISO 4217 code, e.g. "AUD".
 * @property fractionDigits the number of minor-unit digits, 0 to 4.
 */
data class CurrencySpec(
    val code: String,
    val fractionDigits: Int,
) {
    init {
        require(code.length == CODE_LENGTH && code.all { it in 'A'..'Z' }) { "Invalid ISO 4217 currency code: $code" }
        require(fractionDigits in 0..MAX_FRACTION_DIGITS) { "Unsupported fraction digits: $fractionDigits" }
    }

    /** Converts [minor] units to an exact decimal with [fractionDigits] decimals (1250 becomes 12.50 for AUD). */
    fun toMajor(minor: Long): BigDecimal = BigDecimal.valueOf(minor, fractionDigits)

    /**
     * Converts a decimal amount to minor units, rounding half up to the minor unit (12.505 AUD becomes 1251).
     *
     * @throws ArithmeticException if the result does not fit in a [Long].
     */
    fun toMinor(major: BigDecimal): Long = major.setScale(fractionDigits, RoundingMode.HALF_UP).unscaledValue().longValueExact()

    /**
     * Reads an amount typed in major units, such as `34`, `34.5`, `34,50` or `$34.50` (one leading currency symbol, a
     * dot or comma before the decimals, no grouping separators), in minor units (3450 for AUD). Null when [text] is
     * not such an amount, has more decimals than [fractionDigits] or does not fit in a [Long].
     */
    fun parseMinor(text: String): Long? {
        val match = TYPED_AMOUNT.matchEntire(text.trim()) ?: return null
        val (whole, decimals) = match.destructured
        if (decimals.length > fractionDigits) return null
        return runCatching { toMinor(BigDecimal(if (decimals.isEmpty()) whole else "$whole.$decimals")) }.getOrNull()
    }

    /** Looking up currencies by code. */
    companion object {
        /** ISO 4217 codes have three letters. */
        const val CODE_LENGTH = 3
        private const val MAX_FRACTION_DIGITS = 4
        private val TYPED_AMOUNT = Regex("""\p{Sc}?(\d+)(?:[.,](\d+))?""")

        /**
         * Resolves [code] (case and surrounding whitespace ignored) via Adyen's currency table.
         *
         * @throws IllegalArgumentException for currencies Adyen does not support.
         */
        fun of(code: String): CurrencySpec {
            val currency = requireNotNull(AdyenCurrencies[code]) { "Currency not supported by Adyen: $code" }
            return CurrencySpec(currency.code, currency.decimals)
        }
    }
}
