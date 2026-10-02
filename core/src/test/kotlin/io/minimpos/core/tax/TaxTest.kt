package io.minimpos.core.tax

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TaxTest {
    @Test
    fun `inclusive tax is extracted from the gross amount`() {
        assertThat(TaxRates.apply(1100, 10_000, TaxMode.INCLUSIVE)).isEqualTo(TaxAmounts(1000, 100, 1100))
        // 4.50 incl. 10% GST -> 0.409 -> 0.41
        assertThat(TaxRates.apply(450, 10_000, TaxMode.INCLUSIVE)).isEqualTo(TaxAmounts(409, 41, 450))
        // 20% VAT on 9.99 -> 1.665 -> 1.67 (half up)
        assertThat(TaxRates.apply(999, 20_000, TaxMode.INCLUSIVE)).isEqualTo(TaxAmounts(832, 167, 999))
    }

    @Test
    fun `exclusive tax is added on top`() {
        // 8.875% on 10.00 -> 0.8875 -> 0.89
        assertThat(TaxRates.apply(1000, 8_875, TaxMode.EXCLUSIVE)).isEqualTo(TaxAmounts(1000, 89, 1089))
        assertThat(TaxRates.apply(0, 10_000, TaxMode.EXCLUSIVE)).isEqualTo(TaxAmounts.ZERO)
    }

    @Test
    fun `zero rate yields no tax`() {
        assertThat(TaxRates.apply(1234, 0, TaxMode.INCLUSIVE)).isEqualTo(TaxAmounts(1234, 0, 1234))
    }

    @Test
    fun `rejects invalid rates`() {
        assertThrows(IllegalArgumentException::class.java) { TaxRates.apply(100, -1, TaxMode.INCLUSIVE) }
        assertThrows(IllegalArgumentException::class.java) { TaxRates.apply(100, 100_001, TaxMode.EXCLUSIVE) }
    }

    @Test
    fun `parses and formats percentages`() {
        assertThat(TaxRates.parse("10")).isEqualTo(10_000)
        // Common one-decimal rates (UAE/Canada 5%, Switzerland 8.1%, Finland 25.5%) and US combined sales tax.
        assertThat(TaxRates.parse("5")).isEqualTo(5_000)
        assertThat(TaxRates.parse("8.1")).isEqualTo(8_100)
        assertThat(TaxRates.parse("25.5")).isEqualTo(25_500)
        assertThat(TaxRates.parse("7.25")).isEqualTo(7_250)
        assertThat(TaxRates.format(25_500)).isEqualTo("25.5")
        assertThat(TaxRates.parse(" 8.875 % ")).isEqualTo(8_875)
        assertThat(TaxRates.parse("12,5")).isEqualTo(12_500)
        assertThat(TaxRates.parse("10.0000")).isEqualTo(10_000)
        assertThat(TaxRates.parse("0")).isEqualTo(0)
        assertThat(TaxRates.parse("100")).isEqualTo(100_000)
        assertThat(TaxRates.parse("100.001")).isNull()
        assertThat(TaxRates.parse("1.2345")).isNull()
        assertThat(TaxRates.parse("-1")).isNull()
        assertThat(TaxRates.parse("abc")).isNull()
        assertThat(TaxRates.parse("99999999999")).isNull()
        assertThat(TaxRates.format(10_000)).isEqualTo("10")
        assertThat(TaxRates.format(8_875)).isEqualTo("8.875")
        assertThat(TaxRates.format(0)).isEqualTo("0")
    }

    @Test
    fun `tax amounts add up`() {
        assertThat(TaxAmounts(1, 2, 3) + TaxAmounts(10, 20, 30)).isEqualTo(TaxAmounts(11, 22, 33))
    }

    @Test
    fun `a new installation starts with its country's standard rate, included in prices`() {
        assertThat(StarterTax.forCountry("AU")).isEqualTo(StarterTax(10_000))
        assertThat(StarterTax.forCountry(" de ")).isEqualTo(StarterTax(19_000, null, TaxMode.INCLUSIVE))
        assertThat(StarterTax.forCountry("FI").standardMilliPercent).isEqualTo(25_500)
        // Japanese receipts total the reduced rate separately, so it is there from the start.
        assertThat(StarterTax.forCountry("JP")).isEqualTo(StarterTax(10_000, 8_000, TaxMode.INCLUSIVE))
    }

    @Test
    fun `without a national rate or a known country, only a zero rate is started with`() {
        // Sales tax depends on the state or province, and is added on top.
        assertThat(StarterTax.forCountry("US")).isEqualTo(StarterTax(null, null, TaxMode.EXCLUSIVE))
        assertThat(StarterTax.forCountry("ca").mode).isEqualTo(TaxMode.EXCLUSIVE)
        assertThat(StarterTax.forCountry("")).isEqualTo(StarterTax.NONE)
        assertThat(StarterTax.forCountry("HK")).isEqualTo(StarterTax.NONE)
        assertThat(StarterTax.NONE.mode).isEqualTo(TaxMode.INCLUSIVE)
    }

    @Test
    fun `every starter rate is a valid rate`() {
        val rates = ('A'..'Z').flatMap { a -> ('A'..'Z').map { b -> StarterTax.forCountry("$a$b") } }
        assertThat(rates.flatMap { listOfNotNull(it.standardMilliPercent, it.reducedMilliPercent) }.all(TaxRates::isValid)).isTrue()
    }
}
