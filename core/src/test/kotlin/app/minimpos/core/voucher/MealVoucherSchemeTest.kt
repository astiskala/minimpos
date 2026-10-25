package app.minimpos.core.voucher

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class MealVoucherSchemeTest {
    @Test
    fun `markets come only from verified store countries`() {
        assertThat(MealVoucherScheme.forCountry("br")).isEqualTo(MealVoucherScheme.BRAZIL_PAT)
        assertThat(MealVoucherScheme.forCountry("FR")).isEqualTo(MealVoucherScheme.FRANCE_TITRES_RESTAURANT)
        listOf("BE", "FI", "DE", "LU", "ES").forEach {
            assertThat(MealVoucherScheme.forCountry(it)).isEqualTo(MealVoucherScheme.EUROPE_CARD_SCHEME)
        }
        // Lunch Check (Switzerland) and other countries have no documented Terminal API voucher request.
        listOf("CH", "NL", "MX", "PT", "", null).forEach { assertThat(MealVoucherScheme.forCountry(it)).isNull() }
        assertThat(MealVoucherScheme.demonstrable("EUR"))
            .containsExactly(MealVoucherScheme.EUROPE_CARD_SCHEME, MealVoucherScheme.FRANCE_TITRES_RESTAURANT)
        assertThat(MealVoucherScheme.demonstrable("BRL")).containsExactly(MealVoucherScheme.BRAZIL_PAT)
        assertThat(MealVoucherScheme.demonstrable("AUD")).isEmpty()
        assertThat(MealVoucherScheme.stored("BRAZIL_PAT")).isEqualTo(MealVoucherScheme.BRAZIL_PAT)
        assertThat(MealVoucherScheme.stored("OTHER")).isNull()
        assertThat(MealVoucherScheme.stored(null)).isNull()
    }

    @Test
    fun `each market keeps its documented selection approval and refund behavior`() {
        val brazil = MealVoucherScheme.BRAZIL_PAT
        assertThat(brazil.selection).isEqualTo(VoucherSelection.ACCOUNT_TYPE)
        assertThat(brazil.productEligibility).isFalse()
        assertThat(brazil.partialAuthorisation).isFalse()
        assertThat(brazil.refund).isEqualTo(VoucherRefund.ORIGINAL_VOUCHER)
        val europe = MealVoucherScheme.EUROPE_CARD_SCHEME
        assertThat(europe.selection).isEqualTo(VoucherSelection.TERMINAL)
        assertThat(europe.productEligibility).isTrue()
        assertThat(europe.partialAuthorisation).isTrue()
        assertThat(europe.saleLimitMinor).isNull()
        val france = MealVoucherScheme.FRANCE_TITRES_RESTAURANT
        assertThat(france.partialAuthorisation).isFalse()
        assertThat(france.saleLimitMinor).isEqualTo(2_500)
        assertThat(france.refund).isEqualTo(VoucherRefund.NONE)
        assertThat(france.openOn(LocalDate.of(2026, 10, 25))).isFalse()
        assertThat(france.openOn(LocalDate.of(2026, 10, 26))).isTrue()
        assertThat(europe.openOn(LocalDate.of(2026, 10, 25))).isTrue()
    }

    @Test
    fun `limits cover eligible items, market caps and earlier voucher tenders without going negative`() {
        val europe = MealVoucherScheme.EUROPE_CARD_SCHEME
        assertThat(europe.limitMinor(unpaidMinor = 3_000, eligibleMinor = 2_000, collectedMinor = 0)).isEqualTo(2_000)
        assertThat(europe.limitMinor(3_000, 2_000, 1_500)).isEqualTo(500)
        assertThat(europe.limitMinor(300, 2_000, 0)).isEqualTo(300)
        assertThat(europe.limitMinor(3_000, 0, 0)).isEqualTo(0)
        assertThat(europe.limitMinor(3_000, 1_000, 2_000)).isEqualTo(0)
        val france = MealVoucherScheme.FRANCE_TITRES_RESTAURANT
        assertThat(france.limitMinor(6_000, 4_000, 0)).isEqualTo(2_500)
        assertThat(france.limitMinor(6_000, 4_000, 2_000)).isEqualTo(500)
        assertThat(france.limitMinor(6_000, 4_000, 3_000)).isEqualTo(0)
        val brazil = MealVoucherScheme.BRAZIL_PAT
        assertThat(brazil.limitMinor(5_000, 0, 0)).isEqualTo(5_000)
        assertThat(brazil.limitMinor(5_000, 0, 4_000)).isEqualTo(5_000)
    }

    @Test
    fun `only documented Brazilian voucher variants are recognized from an answer`() {
        listOf("elo_voucher_br", "VISA_VOUCHER_BR", " mc_voucher_br", "ticket", "sodexo", "vale_refeicao_prepaid").forEach {
            assertThat(MealVoucherScheme.reportedBrazilianVoucher(it)).isTrue()
        }
        listOf("visa", "mc", "elo", null, "").forEach { assertThat(MealVoucherScheme.reportedBrazilianVoucher(it)).isFalse() }
    }
}
