package app.minimpos.terminal.client

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Converts amounts between the minor units that cross [TerminalClient] and the major units nexo carries, with each
 * currency's Adyen decimals: the one place either conversion is made.
 *
 * @param digits The Adyen decimals of an ISO 4217 currency, null for one Adyen does not support.
 */
internal class TerminalMoney(
    private val digits: (currency: String) -> Int?,
) {
    /** The Adyen decimals of [currency]; a request in a currency Adyen does not support is a programming error. */
    fun fractionDigits(currency: String): Int = checkNotNull(digits(currency)) { "Adyen has no decimals for $currency" }

    /** [minor] minor units of [currency] in the major units nexo carries. */
    fun major(
        minor: Long,
        currency: String,
    ): BigDecimal = BigDecimal.valueOf(minor, fractionDigits(currency))

    /**
     * [major] units of [currency] that nexo carried, as minor units rounded half up to the currency's Adyen decimals;
     * null without an amount or a currency, or for a currency Adyen does not support.
     */
    fun minor(
        major: BigDecimal?,
        currency: String?,
    ): Long? {
        val scale = currency?.let(digits) ?: return null
        return major?.setScale(scale, RoundingMode.HALF_UP)?.unscaledValue()?.longValueExact()
    }
}
