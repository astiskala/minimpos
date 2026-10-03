package io.github.astiskala.minimpos.core.tax

import java.util.Locale

/**
 * The tax a new installation starts with on a device in a country, the way the currency follows the device's country.
 * Only the national standard rate is known (plus Japan's reduced rate); merchants add any other rate themselves, and a
 * country not in the table starts with no rate but 0%, so that no wrong tax is charged before the merchant sets one.
 * Rates change now and then: keep the table up to date.
 *
 * @property standardMilliPercent The national standard VAT or GST rate, in thousandths of a percent ([TaxRates]); null
 *   where there is none to start with: no national rate (the US and Canada, where it depends on the state or province)
 *   or a country not in the table.
 * @property reducedMilliPercent A reduced rate to start with as well: only Japan's 8% (food and drink), which Japanese
 *   receipts total separately; null elsewhere.
 * @property mode Whether prices include tax: tax is added on top in the US and Canada, included elsewhere.
 */
data class StarterTax(
    val standardMilliPercent: Int?,
    val reducedMilliPercent: Int? = null,
    val mode: TaxMode = TaxMode.INCLUSIVE,
) {
    /** The table of countries. */
    companion object {
        /** Prices including tax, with no rate but 0%: for a country not in the table. */
        val NONE = StarterTax(null)

        private val ADDED_ON_TOP = StarterTax(null, mode = TaxMode.EXCLUSIVE)

        private val SPECIAL =
            mapOf(
                "CA" to ADDED_ON_TOP,
                "JP" to StarterTax(10_000, reducedMilliPercent = 8_000),
                "US" to ADDED_ON_TOP,
            )

        // Standard VAT/GST rates, in thousandths of a percent, of the countries where prices include tax.
        private val STANDARD_RATES =
            mapOf(
                "AE" to 5_000,
                "AT" to 20_000,
                "AU" to 10_000,
                "BE" to 21_000,
                "BG" to 20_000,
                "CH" to 8_100,
                "CY" to 19_000,
                "CZ" to 21_000,
                "DE" to 19_000,
                "DK" to 25_000,
                "EE" to 24_000,
                "ES" to 21_000,
                "FI" to 25_500,
                "FR" to 20_000,
                "GB" to 20_000,
                "GR" to 24_000,
                "HR" to 25_000,
                "HU" to 27_000,
                "IE" to 23_000,
                "IS" to 24_000,
                "IT" to 22_000,
                "KR" to 10_000,
                "LI" to 8_100,
                "LT" to 21_000,
                "LU" to 17_000,
                "LV" to 21_000,
                "MT" to 18_000,
                "MX" to 16_000,
                "NL" to 21_000,
                "NO" to 25_000,
                "NZ" to 15_000,
                "PH" to 12_000,
                "PL" to 23_000,
                "PT" to 23_000,
                "RO" to 21_000,
                "SA" to 15_000,
                "SE" to 25_000,
                "SG" to 9_000,
                "SI" to 22_000,
                "SK" to 23_000,
                "TH" to 7_000,
                "TW" to 5_000,
                "ZA" to 15_000,
            )

        /** The starter tax in [country] (ISO 3166-1 alpha-2, any case); [NONE] for a blank or unknown one. */
        fun forCountry(country: String): StarterTax {
            val code = country.trim().uppercase(Locale.ROOT)
            return SPECIAL[code] ?: STANDARD_RATES[code]?.let { StarterTax(it) } ?: NONE
        }
    }
}
