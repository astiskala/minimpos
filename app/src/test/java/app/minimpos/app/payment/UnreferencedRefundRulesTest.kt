package app.minimpos.app.payment

import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.UnreferencedRefundSettings
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.core.payment.PaymentContext
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Readiness comes only from verified store facts and explicit configuration; the market rule is Adyen's. */
class UnreferencedRefundRulesTest {
    private val context = PaymentContext("TERMINAL", "S1F2-1", "POS", "Merchant", "TEST")
    private val facts =
        WalletAvailability(
            context = context,
            identity = "id",
            storeCountry = "NL",
            storeId = "ST1",
            unreferencedRefundDestination = true,
            checked = true,
        )
    private val settings =
        UnreferencedRefundSettings(enabled = true, limitMinor = 5000, delayed = true)
            .confirmedFor(context, "ST1", "NL", "EUR")
            .copy(enabled = true, limitMinor = 5000, delayed = true)

    @Test
    fun `a verified store in a permitted country with explicit enablement is ready within the limit only`() {
        assertThat(UnreferencedRefunds.block(settings, facts, "EUR")).isNull()
        assertThat(UnreferencedRefunds.allows(settings, facts, "EUR", 5000)).isTrue()
        assertThat(UnreferencedRefunds.allows(settings, facts, "EUR", 5001)).isFalse()
        assertThat(UnreferencedRefunds.allows(settings, facts, "EUR", 0)).isFalse()
    }

    @Test
    fun `every documented excluded market blocks even with explicit enablement`() {
        assertThat(UnreferencedRefunds.excludedCountries).containsExactly("BR", "MX", "AU", "MY", "SG", "HK", "IN")
        UnreferencedRefunds.excludedCountries.forEach { country ->
            val there = facts.copy(storeCountry = country)
            val confirmed = settings.copy(storeCountry = country)
            assertThat(UnreferencedRefunds.block(confirmed, there, "EUR")).isEqualTo(UnreferencedRefundBlock.COUNTRY)
        }
    }

    @Test
    fun `blocks are explained in order and never inferred from currency or an unverified store`() {
        assertThat(UnreferencedRefunds.block(settings, facts.copy(unreferencedRefundDestination = false), "EUR"))
            .isEqualTo(UnreferencedRefundBlock.DESTINATION)
        assertThat(UnreferencedRefunds.block(UnreferencedRefundSettings(), facts, "EUR")).isEqualTo(UnreferencedRefundBlock.NOT_ENABLED)
        assertThat(UnreferencedRefunds.block(settings.copy(limitMinor = 0), facts, "EUR")).isEqualTo(UnreferencedRefundBlock.NO_LIMIT)
        listOf(
            facts.copy(checked = false),
            facts.copy(problem = SetupProblem.API_REQUIRED),
            facts.copy(context = null),
            facts.copy(identity = null),
            facts.copy(storeId = null),
            facts.copy(storeCountry = null),
        ).forEach { assertThat(UnreferencedRefunds.block(settings, it, "EUR")).isEqualTo(UnreferencedRefundBlock.NOT_VERIFIED) }
        listOf(
            facts.copy(storeId = "ST2"),
            facts.copy(storeCountry = "DE"),
            facts.copy(context = context.copy(poiId = "S1F2-2")),
        ).forEach { assertThat(UnreferencedRefunds.block(settings, it, "EUR")).isEqualTo(UnreferencedRefundBlock.CHANGED) }
        assertThat(UnreferencedRefunds.block(settings, facts, "GBP")).isEqualTo(UnreferencedRefundBlock.CHANGED)
    }

    @Test
    fun `the simulator is an explicit offline demonstration without store facts`() {
        val demo = facts.copy(simulated = true, storeId = null, storeCountry = null)
        val confirmed = UnreferencedRefundSettings(enabled = true, limitMinor = 100, context = context, currency = "AUD")
        assertThat(UnreferencedRefunds.block(confirmed, demo, "AUD")).isNull()
        assertThat(UnreferencedRefunds.block(confirmed, demo.copy(storeCountry = "AU"), "AUD")).isNull()
        assertThat(UnreferencedRefunds.block(confirmed, demo, "EUR")).isEqualTo(UnreferencedRefundBlock.CHANGED)
    }

    @Test
    fun `confirmation is bound to the store and currency and checked again at send`() {
        val moved = settings.confirmedFor(context, "ST2", "NL", "EUR")
        assertThat(moved.enabled).isFalse()
        assertThat(moved.delayed).isFalse()
        assertThat(moved.limitMinor).isEqualTo(5000)
        val recurrency = settings.confirmedFor(context, "ST1", "NL", "GBP")
        assertThat(recurrency.limitMinor).isEqualTo(0)
        val same = settings.confirmedFor(context, "ST1", "NL", "EUR")
        assertThat(same).isEqualTo(settings)
        assertThat(settings.permits(5000, "EUR", context)).isTrue()
        assertThat(settings.permits(5001, "EUR", context)).isFalse()
        assertThat(settings.permits(100, "GBP", context)).isFalse()
        assertThat(settings.permits(100, "EUR", context.copy(saleId = "OTHER"))).isFalse()
        assertThat(settings.copy(enabled = false).permits(100, "EUR", context)).isFalse()
        assertThat(settings.identity).isNotEqualTo(settings.copy(limitMinor = 4000).identity)
    }
}
