package app.minimpos.core.payment

import app.minimpos.core.access.ManagerAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardFeaturesTest {
    @Test
    fun `exclusions read the same in both directions`() {
        CardFeature.entries.forEach { first ->
            CardFeature.entries.forEach { second ->
                assertThat(CardFeatures.excludes(first, second)).isEqualTo(CardFeatures.excludes(second, first))
            }
        }
        assertThat(CardFeatures.excludes(CardFeature.CASHBACK, CardFeature.TERMINAL_TIP)).isTrue()
        assertThat(CardFeatures.excludes(CardFeature.CASHBACK, CardFeature.SHOPPER_DISPLAY)).isFalse()
    }

    @Test
    fun `a feature never excludes itself`() {
        CardFeature.entries.forEach { assertThat(CardFeatures.excludes(it, it)).isFalse() }
    }

    @Test
    fun `a conflict names two features that cannot be combined`() {
        assertThat(CardFeatures.conflict(setOf(CardFeature.TERMINAL_TIP, CardFeature.SHOPPER_DISPLAY, CardFeature.GIVING))).isNull()
        val conflict = CardFeatures.conflict(setOf(CardFeature.GIVING, CardFeature.MEXICAN_PLAN, CardFeature.SHOPPER_DISPLAY))
        assertThat(conflict?.toList()).containsExactly(CardFeature.GIVING, CardFeature.MEXICAN_PLAN)
    }

    @Test
    fun `a feature is allowed only beside features it combines with`() {
        assertThat(CardFeatures.allows(CardFeature.AUDIO_GUIDANCE, setOf(CardFeature.AUDIO_GUIDANCE, CardFeature.TOKENIZATION))).isTrue()
        assertThat(CardFeatures.allows(CardFeature.AUDIO_GUIDANCE, setOf(CardFeature.SPECIAL_ENTRY))).isFalse()
        assertThat(CardFeatures.allows(CardFeature.SCANNED_WALLET, setOf(CardFeature.PRE_AUTHORISATION))).isFalse()
    }

    @Test
    fun `wallet, routing and cash requests require the verified store they were offered for`() {
        assertThat(CardFeature.entries.filter { it.needsVerifiedStore })
            .containsExactly(CardFeature.SCANNED_WALLET, CardFeature.MEAL_VOUCHER, CardFeature.MEXICAN_PLAN, CardFeature.CASHBACK)
        assertThat(CardFeature.entries.filter { it.configuredApproval })
            .containsExactly(CardFeature.CASHBACK, CardFeature.UNREFERENCED_REFUND)
        assertThat(CardFeature.entries.filter { it.onlyOnTerminal })
            .containsExactly(CardFeature.AUDIO_GUIDANCE, CardFeature.MEXICAN_PLAN, CardFeature.CASHBACK)
    }

    @Test
    fun `Manager permissions follow the purchase and each feature that has one`() {
        assertThat(CardFeatures.managerActions(setOf(CardFeature.TERMINAL_TIP), ManagerAction.SALE)).containsExactly(ManagerAction.SALE)
        assertThat(CardFeatures.managerActions(setOf(CardFeature.CASHBACK, CardFeature.SPECIAL_ENTRY), ManagerAction.SALE))
            .containsExactly(ManagerAction.SALE, ManagerAction.CASHOUT, ManagerAction.MANUAL_ENTRY)
        assertThat(CardFeatures.managerActions(setOf(CardFeature.CASHBACK), null)).containsExactly(ManagerAction.CASHOUT)
        assertThat(CardFeatures.managerActions(setOf(CardFeature.UNREFERENCED_REFUND), null))
            .containsExactly(ManagerAction.UNREFERENCED_REFUND)
    }
}
