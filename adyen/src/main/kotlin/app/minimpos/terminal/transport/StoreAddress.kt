package app.minimpos.terminal.transport

import com.adyen.model.management.StoreLocation
import java.util.Locale
import java.util.Properties

/** Offline receipt address formatting using the bundled, attributed Google postal-format metadata. */
internal object StoreAddress {
    private val tokens = Regex("%[A-Z]|[^%]+")
    private val placeholders = Regex("%[A-Zn]")
    private val spaces = Regex("[\\t ]+")
    private val formats by lazy {
        Properties().apply {
            checkNotNull(StoreAddress::class.java.getResourceAsStream("/google-address-formats.properties"))
                .bufferedReader(Charsets.UTF_8)
                .use { load(it) }
        }
    }

    /**
     * Formats supplied components without validating or guessing them; null returns blank.
     * Uses the destination country's layout, Latin variants for Latin text, and an English country name.
     * Unknown countries retain component order and the original country text. Fields outside a country's
     * template remain on separate lines, rather than being discarded. Safe to call from any thread; no network I/O.
     */
    fun format(address: StoreLocation?): String {
        if (address == null) return ""
        val values =
            linkedMapOf(
                'A' to listOf(address.line1, address.line2, address.line3).map { it.clean() }.nonBlankLines(),
                'C' to address.city.clean(),
                'S' to address.stateOrProvince.clean(),
                'Z' to address.postalCode.clean(),
            )
        val originalCountry = address.country.clean()
        val country = originalCountry.uppercase(Locale.ROOT)
        val local = formats.getProperty(country)
        if (local == null || country == "ZZ") return (values.values + originalCountry).nonBlankLines()
        val countryName = Locale.forLanguageTag("und-$country").getDisplayCountry(Locale.ENGLISH)
        if (values.values.all(String::isBlank)) return countryName
        val native =
            values.values.any { text ->
                text.codePoints().anyMatch { Character.isLetter(it) && Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN }
            }
        val pattern = if (native) local else formats.getProperty("$country-Latn", local)
        includeCity(values, pattern, native)
        val lines = pattern.split("%n").map { renderLine(it, values) }
        val extra = values.filterKeys { !pattern.contains("%$it") }.values
        return (lines + extra + countryName).nonBlankLines()
    }

    private fun includeCity(
        values: MutableMap<Char, String>,
        pattern: String,
        native: Boolean,
    ) {
        val city = values.getValue('C')
        if (city.isBlank() || pattern.contains("%C")) return
        // Some layouts expect the municipality in the street block (notably Japan); Adyen supplies it separately.
        if (pattern.split(placeholders).none { it.trim().equals(city, ignoreCase = true) }) {
            val street = values.getValue('A')
            values['A'] = (if (native) listOf(city, street) else listOf(street, city)).nonBlankLines()
        }
        values['C'] = ""
    }

    private fun renderLine(
        pattern: String,
        values: Map<Char, String>,
    ): String {
        val parts = tokens.findAll(pattern).map { it.value }.toList()
        return parts
            .mapIndexed { index, part ->
                when {
                    part.startsWith("%") -> {
                        values[part[1]].orEmpty()
                    }

                    missing(parts.getOrNull(index - 1), values) || missing(parts.getOrNull(index + 1), values) -> {
                        if (part.any(Char::isWhitespace)) " " else ""
                    }

                    else -> {
                        part
                    }
                }
            }.joinToString("")
            .replace(spaces, " ")
            .trim()
    }

    private fun missing(
        token: String?,
        values: Map<Char, String>,
    ): Boolean = token != null && values[token[1]].isNullOrBlank()

    private fun String?.clean(): String = this?.trim().orEmpty()

    private fun Iterable<String>.nonBlankLines(): String = filter(String::isNotBlank).joinToString("\n")
}
