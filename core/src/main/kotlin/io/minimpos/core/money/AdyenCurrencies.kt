package io.minimpos.core.money

import java.util.Currency
import java.util.Locale

/**
 * A currency Adyen supports, with the number of decimals Adyen uses for it.
 *
 * @property code the upper-case ISO 4217 code, e.g. "AUD" (plus Adyen-specific codes such as "CNH").
 * @property name the English name from Adyen's table, shown in the currency picker and matched by
 *   [AdyenCurrencies.search].
 * @property decimals the number of minor-unit digits Adyen expects in amounts, which can differ from ISO 4217
 *   (e.g. ISK is 2, IDR is 0).
 */
data class AdyenCurrency(
    val code: String,
    val name: String,
    val decimals: Int,
)

/**
 * Adyen's currency codes and minor units, from docs.adyen.com/development-resources/currency-codes. For CLP, CVE, IDR
 * and ISK Adyen's decimals differ from ISO 4217; as Adyen documents, its table is leading.
 *
 * Every currency in this table can be configured in the app. Amounts sent to the terminal are integers in the
 * currency's minor unit as defined here, so a wrong number of decimals would charge a multiple of ten too much or too
 * little.
 */
object AdyenCurrencies {
    /** Every supported currency, keyed by its upper-case code (see [get] for a lenient lookup). */
    val all: Map<String, AdyenCurrency> =
        listOf(
            AdyenCurrency("AED", "UAE Dirham", 2),
            AdyenCurrency("ALL", "Albanian Lek", 2),
            AdyenCurrency("AMD", "Armenian Dram", 2),
            AdyenCurrency("AOA", "Angolan Kwanza", 2),
            AdyenCurrency("ARS", "Nuevo Argentine Peso", 2),
            AdyenCurrency("AUD", "Australian Dollar", 2),
            AdyenCurrency("AWG", "Aruban Guilder", 2),
            AdyenCurrency("AZN", "Azerbaijani manat", 2),
            AdyenCurrency("BAM", "Bosnia and Herzegovina Convertible Marks", 2),
            AdyenCurrency("BBD", "Barbados Dollar", 2),
            AdyenCurrency("BDT", "Bangladesh Taka", 2),
            AdyenCurrency("BHD", "Bahraini Dinar", 3),
            AdyenCurrency("BMD", "Bermudian Dollar", 2),
            AdyenCurrency("BND", "Brunei Dollar", 2),
            AdyenCurrency("BOB", "Bolivia Boliviano", 2),
            AdyenCurrency("BRL", "Brazilian Real", 2),
            AdyenCurrency("BSD", "Bahamian Dollar", 2),
            AdyenCurrency("BWP", "Botswana Pula", 2),
            AdyenCurrency("BYN", "New Belarusian Ruble", 2),
            AdyenCurrency("BZD", "Belize Dollar", 2),
            AdyenCurrency("CAD", "Canadian Dollar", 2),
            AdyenCurrency("CHF", "Swiss Franc", 2),
            AdyenCurrency("CLP", "Chilean Peso", 2),
            AdyenCurrency("CNH", "Yuan Renminbi (offshore)", 2),
            AdyenCurrency("CNY", "Yuan Renminbi (onshore)", 2),
            AdyenCurrency("COP", "Colombian Peso", 2),
            AdyenCurrency("CRC", "Costa Rican Colon", 2),
            AdyenCurrency("CUP", "Cuban Peso", 2),
            AdyenCurrency("CVE", "Cape Verdi Escudo", 0),
            AdyenCurrency("CZK", "Czech Koruna", 2),
            AdyenCurrency("DJF", "Djibouti Franc", 0),
            AdyenCurrency("DKK", "Danish Krone", 2),
            AdyenCurrency("DOP", "Dominican Republic Peso", 2),
            AdyenCurrency("DZD", "Algerian Dinar", 2),
            AdyenCurrency("EGP", "Egyptian Pound", 2),
            AdyenCurrency("ETB", "Ethiopian Birr", 2),
            AdyenCurrency("EUR", "Euro", 2),
            AdyenCurrency("FJD", "Fiji Dollar", 2),
            AdyenCurrency("FKP", "Falkland Islands Pound", 2),
            AdyenCurrency("GBP", "Pound Sterling", 2),
            AdyenCurrency("GEL", "Georgian Lari", 2),
            AdyenCurrency("GHS", "Ghanaian Cedi (3rd)", 2),
            AdyenCurrency("GIP", "Gibraltar Pound", 2),
            AdyenCurrency("GMD", "Gambia Delasi", 2),
            AdyenCurrency("GNF", "Guinea Franc", 0),
            AdyenCurrency("GTQ", "Guatemala Quetzal", 2),
            AdyenCurrency("GYD", "Guyanese Dollar", 2),
            AdyenCurrency("HKD", "Hong Kong Dollar", 2),
            AdyenCurrency("HNL", "Honduras Lempira", 2),
            AdyenCurrency("HTG", "Haitian Gourde", 2),
            AdyenCurrency("HUF", "Hungarian Forint", 2),
            AdyenCurrency("IDR", "Indonesian Rupiah", 0),
            AdyenCurrency("ILS", "New Israeli Scheqel", 2),
            AdyenCurrency("INR", "Indian Rupee", 2),
            AdyenCurrency("IQD", "Iraqi Dinar", 3),
            AdyenCurrency("ISK", "Iceland Krona", 2),
            AdyenCurrency("JMD", "Jamaican Dollar", 2),
            AdyenCurrency("JOD", "Jordanian Dinar", 3),
            AdyenCurrency("JPY", "Japanese Yen", 0),
            AdyenCurrency("KES", "Kenyan Shilling", 2),
            AdyenCurrency("KGS", "Kyrgyzstan Som", 2),
            AdyenCurrency("KHR", "Cambodia Riel", 2),
            AdyenCurrency("KMF", "Comoro Franc", 0),
            AdyenCurrency("KRW", "South-Korean Won", 0),
            AdyenCurrency("KWD", "Kuwaiti Dinar", 3),
            AdyenCurrency("KYD", "Cayman Islands Dollar", 2),
            AdyenCurrency("KZT", "Kazakhstani Tenge", 2),
            AdyenCurrency("LAK", "Laos Kip", 2),
            AdyenCurrency("LKR", "Sri Lanka Rupee", 2),
            AdyenCurrency("LYD", "Libyan Dinar", 3),
            AdyenCurrency("MAD", "Moroccan Dirham", 2),
            AdyenCurrency("MDL", "Moldovia Leu", 2),
            AdyenCurrency("MKD", "Macedonian Denar", 2),
            AdyenCurrency("MMK", "Myanmar Kyat", 2),
            AdyenCurrency("MNT", "Mongolia Tugrik", 2),
            AdyenCurrency("MOP", "Macau Pataca", 2),
            AdyenCurrency("MRU", "Mauritania Ouguiya", 2),
            AdyenCurrency("MUR", "Mauritius Rupee", 2),
            AdyenCurrency("MVR", "Maldives Rufiyaa", 2),
            AdyenCurrency("MWK", "Malawi Kwacha", 2),
            AdyenCurrency("MXN", "Mexican Peso", 2),
            AdyenCurrency("MYR", "Malaysian Ringgit", 2),
            AdyenCurrency("MZN", "Mozambican Metical", 2),
            AdyenCurrency("NAD", "Namibian Dollar", 2),
            AdyenCurrency("NGN", "Nigerian Naira", 2),
            AdyenCurrency("NIO", "Nicaragua Cordoba Oro", 2),
            AdyenCurrency("NOK", "Norwegian Krone", 2),
            AdyenCurrency("NPR", "Nepalese Rupee", 2),
            AdyenCurrency("NZD", "New Zealand Dollar", 2),
            AdyenCurrency("OMR", "Rial Omani", 3),
            AdyenCurrency("PAB", "Panamanian Balboa", 2),
            AdyenCurrency("PEN", "Peruvian Nuevo Sol", 2),
            AdyenCurrency("PGK", "New Guinea Kina", 2),
            AdyenCurrency("PHP", "Philippine Peso", 2),
            AdyenCurrency("PKR", "Pakistan Rupee", 2),
            AdyenCurrency("PLN", "New Polish Zloty", 2),
            AdyenCurrency("PYG", "Paraguay Guarani", 0),
            AdyenCurrency("QAR", "Qatari Rial", 2),
            AdyenCurrency("RON", "New Romanian Lei", 2),
            AdyenCurrency("RSD", "Serbian Dinar", 2),
            AdyenCurrency("RUB", "Russian Ruble", 2),
            AdyenCurrency("RWF", "Rwanda Franc", 0),
            AdyenCurrency("SAR", "Saudi Riyal", 2),
            AdyenCurrency("SBD", "Solomon Island Dollar", 2),
            AdyenCurrency("SCR", "Seychelles Rupee", 2),
            AdyenCurrency("SEK", "Swedish Krone", 2),
            AdyenCurrency("SGD", "Singapore Dollar", 2),
            AdyenCurrency("SHP", "St. Helena Pound", 2),
            AdyenCurrency("SLE", "Sierra Leone Leone", 2),
            AdyenCurrency("SOS", "Somalia Shilling", 2),
            AdyenCurrency("SRD", "Surinamese dollar", 2),
            AdyenCurrency("STN", "Sao Tome & Principe Dobra", 2),
            AdyenCurrency("SVC", "El Salvador Colón", 2),
            AdyenCurrency("SZL", "Swaziland Lilangeni", 2),
            AdyenCurrency("THB", "Thai Baht", 2),
            AdyenCurrency("TND", "Tunisian Dinar", 3),
            AdyenCurrency("TOP", "Tonga Pa'anga", 2),
            AdyenCurrency("TRY", "New Turkish Lira", 2),
            AdyenCurrency("TTD", "Trinidad & Tobago Dollar", 2),
            AdyenCurrency("TWD", "New Taiwan Dollar", 2),
            AdyenCurrency("TZS", "Tanzanian Shilling", 2),
            AdyenCurrency("UAH", "Ukraine Hryvnia", 2),
            AdyenCurrency("UGX", "Uganda Shilling", 0),
            AdyenCurrency("USD", "US Dollars", 2),
            AdyenCurrency("UYU", "Peso Uruguayo", 2),
            AdyenCurrency("UZS", "Uzbekistani Som", 2),
            AdyenCurrency("VEF", "Venezuelan Bolívar", 2),
            AdyenCurrency("VND", "Vietnamese New Dong", 0),
            AdyenCurrency("VUV", "Vanuatu Vatu", 0),
            AdyenCurrency("WST", "Samoan Tala", 2),
            AdyenCurrency("XAF", "CFA Franc BEAC", 0),
            AdyenCurrency("XCG", "Caribbean Guilder", 2),
            AdyenCurrency("XCD", "East Caribbean Dollar", 2),
            AdyenCurrency("XOF", "CFA Franc BCEAO", 0),
            AdyenCurrency("XPF", "CFP Franc", 0),
            AdyenCurrency("YER", "Yemeni Rial", 2),
            AdyenCurrency("ZAR", "South African Rand", 2),
            AdyenCurrency("ZMW", "Zambian Kwacha", 2),
        ).associateBy { it.code }

    /** Looks up [code] ignoring surrounding whitespace and case; null when Adyen does not support the currency. */
    operator fun get(code: String): AdyenCurrency? = all[code.trim().uppercase()]

    /** Every currency, ordered by code (the currency picker's list when nothing has been searched for). */
    val sorted: List<AdyenCurrency> by lazy { all.values.sortedBy { it.code } }

    /**
     * The legal tender of [country] (ISO 3166 alpha-2, e.g. "SE" gives SEK), used as the default currency on a
     * device in that country. Null when the JDK does not know the country or Adyen does not support its currency.
     */
    fun forCountry(country: String): AdyenCurrency? =
        runCatching { Currency.getInstance(Locale.Builder().setRegion(country.trim()).build()) }
            .getOrNull()
            ?.let { get(it.currencyCode) }

    /**
     * Currencies whose code or name contains [query] (ignoring case), ordered by code with an exact code match first.
     * A blank query returns [sorted].
     */
    fun search(query: String): List<AdyenCurrency> {
        val q = query.trim()
        if (q.isEmpty()) return sorted
        return sorted
            .filter { it.code.contains(q, ignoreCase = true) || it.name.contains(q, ignoreCase = true) }
            .sortedByDescending { it.code.equals(q, ignoreCase = true) }
    }
}
