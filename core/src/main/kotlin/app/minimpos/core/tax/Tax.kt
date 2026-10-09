package app.minimpos.core.tax

import java.math.BigDecimal
import java.math.RoundingMode

/** Whether catalogue prices already include tax (AU/NZ/EU/UK style) or tax is added on top (US style). */
enum class TaxMode {
    /** Prices are gross: the tax is the part of the price that the rate accounts for, and the total is unchanged. */
    INCLUSIVE,

    /** Prices are net: tax is calculated on top and added to the total. */
    EXCLUSIVE,
}

/**
 * Net, tax and gross amounts (minor units) for a line or a group of lines; `net + tax == gross` for amounts from
 * [TaxRates.apply] and sums of them.
 *
 * @property net the amount before tax.
 * @property tax the tax amount.
 * @property gross the amount including tax, which is what the shopper pays.
 */
data class TaxAmounts(
    val net: Long,
    val tax: Long,
    val gross: Long,
) {
    /** Adds each amount separately, to total lines or groups of lines. */
    operator fun plus(other: TaxAmounts) = TaxAmounts(net + other.net, tax + other.tax, gross + other.gross)

    /** Holds [ZERO]. */
    companion object {
        /** All three amounts zero: the starting value for summing. */
        val ZERO = TaxAmounts(0, 0, 0)
    }
}

/**
 * Tax rates are stored as thousandths of a percent so rates such as 8.875% are exact:
 * 10% = 10_000, 8.875% = 8_875. Valid rates are 0 to [MAX] (0% to 100%).
 */
object TaxRates {
    /** Stored units per percent: a rate of 1% is stored as 1_000. */
    const val SCALE = 1_000

    /** The highest valid rate, 100%. */
    const val MAX = 100 * SCALE

    /** Decimal places of a percentage that [SCALE] keeps (10^3 = [SCALE]). */
    private const val SCALE_DIGITS = 3
    private val HUNDRED_PERCENT = BigDecimal.valueOf(100L * SCALE)

    /** Whether [rateMilliPercent] is between 0% and 100% inclusive. */
    fun isValid(rateMilliPercent: Int): Boolean = rateMilliPercent in 0..MAX

    /** The rate as a percentage without trailing zeros (8_875 becomes 8.875, 10_000 becomes 10). */
    fun toPercent(rateMilliPercent: Int): BigDecimal = BigDecimal.valueOf(rateMilliPercent.toLong(), SCALE_DIGITS).stripTrailingZeros()

    /** The percentage as plain text: "10", "8.875", "0" - suitable for labels such as "GST 10%". */
    fun format(rateMilliPercent: Int): String = toPercent(rateMilliPercent).toPlainString()

    /**
     * Parses "10", "8.875", "12,5" or "10 %" into thousandths of a percent; null when the text is not a number, has
     * more than three decimals, or is outside 0% to 100%.
     */
    fun parse(text: String): Int? {
        val value =
            text
                .trim()
                .replace(',', '.')
                .removeSuffix("%")
                .trim()
                .toBigDecimalOrNull()
                ?.stripTrailingZeros() ?: return null
        if (value.scale() > SCALE_DIGITS || value.signum() < 0) return null
        val milli = runCatching { value.movePointRight(SCALE_DIGITS).intValueExact() }.getOrNull() ?: return null
        return milli.takeIf(::isValid)
    }

    /**
     * Splits [amount] (unit price x quantity, minor units) into net/tax/gross, rounding tax half-up per line. In
     * [TaxMode.INCLUSIVE] [amount] is the gross and the tax is `amount * rate / (100% + rate)`; in [TaxMode.EXCLUSIVE]
     * it is the net and the tax is `amount * rate / 100%`.
     *
     * @throws IllegalArgumentException if [rateMilliPercent] is not [isValid].
     */
    fun apply(
        amount: Long,
        rateMilliPercent: Int,
        mode: TaxMode,
    ): TaxAmounts {
        require(isValid(rateMilliPercent)) { "Invalid tax rate: $rateMilliPercent" }
        val rate = BigDecimal.valueOf(rateMilliPercent.toLong())
        val value = BigDecimal.valueOf(amount)
        return when (mode) {
            TaxMode.INCLUSIVE -> {
                val tax =
                    value
                        .multiply(
                            rate,
                        ).divide(HUNDRED_PERCENT.add(rate), 0, RoundingMode.HALF_UP)
                        .longValueExact()
                TaxAmounts(net = amount - tax, tax = tax, gross = amount)
            }

            TaxMode.EXCLUSIVE -> {
                val tax = value.multiply(rate).divide(HUNDRED_PERCENT, 0, RoundingMode.HALF_UP).longValueExact()
                TaxAmounts(net = amount, tax = tax, gross = amount + tax)
            }
        }
    }
}
