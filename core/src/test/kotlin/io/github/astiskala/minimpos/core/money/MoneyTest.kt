package io.github.astiskala.minimpos.core.money

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale

class MoneyTest {
    @Test
    fun `accounting environment follows original simulation flag rather than current destination`() {
        val context = PaymentContext("TERMINAL", "POI", "POS", "Merchant", "LIVE")
        assertThat(context.historyEnvironment).isEqualTo("LIVE")
        assertThat(context.copy(environment = "TEST").historyEnvironment).isEqualTo("TEST")
        assertThat(context.copy(environment = null).historyEnvironment).isNull()
        assertThat(context.copy(simulated = true).historyEnvironment).isEqualTo("SIMULATOR")
    }

    @Test
    fun `currency spec uses Adyen's decimals`() {
        assertThat(CurrencySpec.of(" aud ")).isEqualTo(CurrencySpec("AUD", 2))
        assertThat(CurrencySpec.of("JPY")).isEqualTo(CurrencySpec("JPY", 0))
        assertThat(CurrencySpec.of("BHD")).isEqualTo(CurrencySpec("BHD", 3))
        // Where Adyen differs from ISO 4217, Adyen's table is leading.
        assertThat(CurrencySpec.of("ISK").fractionDigits).isEqualTo(2)
        assertThat(CurrencySpec.of("CLP").fractionDigits).isEqualTo(2)
        assertThat(CurrencySpec.of("IDR").fractionDigits).isEqualTo(0)
        assertThat(CurrencySpec.of("CVE").fractionDigits).isEqualTo(0)
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec.of("XAU") }
        assertThat(AdyenCurrencies.all).hasSize(138)
        assertThat(AdyenCurrencies["gbp"]!!.name).isEqualTo("Pound Sterling")
    }

    @Test
    fun `currencies default from the country and can be searched`() {
        assertThat(AdyenCurrencies.forCountry("AU")!!.code).isEqualTo("AUD")
        assertThat(AdyenCurrencies.forCountry(" se ")!!.code).isEqualTo("SEK")
        assertThat(AdyenCurrencies.forCountry("DE")!!.code).isEqualTo("EUR")
        assertThat(AdyenCurrencies.forCountry("JP")!!.decimals).isEqualTo(0)
        // No country, an unknown one, a territory without a currency, and a currency Adyen doesn't support.
        assertThat(AdyenCurrencies.forCountry("")).isNull()
        assertThat(AdyenCurrencies.forCountry("ZZ")).isNull()
        assertThat(AdyenCurrencies.forCountry("AQ")).isNull()
        assertThat(AdyenCurrencies.forCountry("KP")).isNull()

        assertThat(AdyenCurrencies.sorted).hasSize(138)
        assertThat(AdyenCurrencies.sorted.map { it.code }).isInOrder()
        assertThat(AdyenCurrencies.search(" ")).isEqualTo(AdyenCurrencies.sorted)
        assertThat(AdyenCurrencies.search("kron").map { it.code }).containsExactly("DKK", "ISK", "NOK", "SEK").inOrder()
        // An exact code comes first, before names that merely contain it.
        assertThat(AdyenCurrencies.search("sek").first().code).isEqualTo("SEK")
        assertThat(AdyenCurrencies.search("aud").first().code).isEqualTo("AUD")
        assertThat(AdyenCurrencies.search("dollar").map { it.code }).containsAtLeast("AUD", "NZD", "USD")
        assertThat(AdyenCurrencies.search("no such money")).isEmpty()
    }

    @Test
    fun `currency spec rejects invalid codes and exponents`() {
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("aud", 2) }
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("AU", 2) }
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("AUD", 5) }
        assertThrows(IllegalArgumentException::class.java) { CurrencySpec("AUD", -1) }
    }

    @Test
    fun `converts between minor and major units`() {
        val aud = CurrencySpec("AUD", 2)
        assertThat(aud.toMajor(1234)).isEqualTo(BigDecimal("12.34"))
        assertThat(aud.toMinor(BigDecimal("12.345"))).isEqualTo(1235)
        val jpy = CurrencySpec("JPY", 0)
        assertThat(jpy.toMajor(500)).isEqualTo(BigDecimal("500"))
        assertThat(jpy.toMinor(BigDecimal("499.5"))).isEqualTo(500)
    }

    @Test
    fun `reads typed amounts`() {
        val aud = CurrencySpec("AUD", 2)
        assertThat(aud.parseMinor("34")).isEqualTo(3400)
        assertThat(aud.parseMinor(" 34.5 ")).isEqualTo(3450)
        assertThat(aud.parseMinor("34,50")).isEqualTo(3450)
        assertThat(aud.parseMinor("$34.50")).isEqualTo(3450)
        assertThat(aud.parseMinor("€0.05")).isEqualTo(5)
        listOf("34.505", "1,000.00", "C-1", "34.", ".5", "$", "", "abc", "99999999999999999999").forEach {
            assertThat(aud.parseMinor(it)).isNull()
        }
        val jpy = CurrencySpec("JPY", 0)
        assertThat(jpy.parseMinor("500")).isEqualTo(500)
        assertThat(jpy.parseMinor("500.0")).isNull()
    }

    @Test
    fun `formats amounts for the locale`() {
        val formatter = MoneyFormatter(CurrencySpec("AUD", 2), Locale.forLanguageTag("en-AU"))
        assertThat(formatter.format(123_456)).isEqualTo("$1,234.56")
        assertThat(formatter.formatPlain(5)).isEqualTo("0.05")
        val yen = MoneyFormatter(CurrencySpec("JPY", 0), Locale.US)
        assertThat(yen.formatPlain(1500)).isEqualTo("1,500")
        assertThat(MoneyFormatter(CurrencySpec.of("ISK"), Locale.US).formatPlain(100_050)).isEqualTo("1,000.50")
        // Codes the JDK does not know fall back to the code itself.
        assertThat(MoneyFormatter(CurrencySpec.of("CNH"), Locale.US).format(1250)).isEqualTo("CNH 12.50")
    }

    @Test
    fun `amount entry shifts digits in from the right`() {
        val entry =
            AmountEntry()
                .append(0)
                .append(1)
                .append(2)
                .append(5)
                .append(0)
        assertThat(entry.minor).isEqualTo(1250)
        assertThat(entry.backspace().minor).isEqualTo(125)
        assertThat(entry.appendDoubleZero().minor).isEqualTo(125_000)
    }

    @Test
    fun `amount entry caps digits and rejects non digits`() {
        var entry = AmountEntry(maxDigits = 3)
        repeat(5) { entry = entry.append(9) }
        assertThat(entry.minor).isEqualTo(999)
        assertThrows(IllegalArgumentException::class.java) { AmountEntry().append(10) }
    }
}
