package io.github.astiskala.minimpos.core.money

/**
 * Cash-register style amount entry: digits shift in from the right, so typing 1, 2, 5, 0 yields 12.50
 * for a two-decimal currency. Every operation returns a new entry, which suits Compose state.
 *
 * @property minor the amount entered so far, in the currency's minor units.
 * @property maxDigits the most digits the entry accepts; further digits are ignored.
 */
data class AmountEntry(
    val minor: Long = 0,
    val maxDigits: Int = DEFAULT_MAX_DIGITS,
) {
    /**
     * Shifts [digit] in from the right. A leading zero, or a digit beyond [maxDigits], leaves the entry unchanged.
     *
     * @throws IllegalArgumentException if [digit] is not in 0..9.
     */
    fun append(digit: Int): AmountEntry {
        require(digit in 0..<RADIX) { "Not a digit: $digit" }
        return when {
            minor == 0L && digit == 0 -> this
            minor.toString().length >= maxDigits -> this
            else -> copy(minor = minor * RADIX + digit)
        }
    }

    /** The "00" key: appends two zeros, subject to the same rules as [append]. */
    fun appendDoubleZero(): AmountEntry = append(0).append(0)

    /** Removes the last digit entered (12.50 becomes 1.25); an empty entry stays at zero. */
    fun backspace(): AmountEntry = copy(minor = minor / RADIX)

    /** Holds the default digit limit. */
    companion object {
        /** Default digit limit: amounts up to 9,999,999.99 in a two-decimal currency (999,999,999 minor units). */
        const val DEFAULT_MAX_DIGITS = 9
        private const val RADIX = 10
    }
}
