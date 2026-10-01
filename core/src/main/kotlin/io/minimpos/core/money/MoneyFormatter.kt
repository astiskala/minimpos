package io.minimpos.core.money

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/**
 * Formats minor-unit amounts as localised currency strings. Not thread-safe (wraps [NumberFormat]).
 *
 * Both formats are set to the currency's [CurrencySpec.fractionDigits] decimals, i.e. Adyen's, which can differ from
 * the JDK's (ISK 100050 is "1,000.50").
 *
 * @property currency the currency the amounts are in; it decides the number of decimals and the symbol.
 * @property locale the locale for the symbol, separators and grouping; also used by callers formatting surrounding
 *   text, such as receipt quantity lines.
 */
class MoneyFormatter(
    val currency: CurrencySpec,
    val locale: Locale,
) {
    /** Null for codes the JDK does not know (e.g. CNH), which are shown as "CNH 12.50". */
    private val iso = runCatching { Currency.getInstance(currency.code) }.getOrNull()

    private val withSymbol =
        iso?.let { iso -> configure(NumberFormat.getCurrencyInstance(locale)).also { it.currency = iso } }

    private val plain = configure(NumberFormat.getNumberInstance(locale)).apply { isGroupingUsed = true }

    private fun configure(format: NumberFormat): NumberFormat {
        format.minimumFractionDigits = currency.fractionDigits
        format.maximumFractionDigits = currency.fractionDigits
        return format
    }

    /**
     * Formats [minor] units with the currency symbol, e.g. "$12.50" for AUD 1250 in en-AU. Currencies the JDK does not
     * know get their code as a prefix instead, e.g. "CNH 12.50".
     */
    fun format(minor: Long): String = withSymbol?.format(currency.toMajor(minor)) ?: "${currency.code} ${formatPlain(minor)}"

    /** Formats [minor] units as a grouped number without a currency symbol, e.g. "1,250.00". */
    fun formatPlain(minor: Long): String = plain.format(currency.toMajor(minor))
}
