package app.minimpos.core.access

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManagerActionTest {
    @Test
    fun `routine collection and reading stay public by default while financial follow-ups stay protected`() {
        assertThat(ManagerAction.entries.filterNot { it.defaultRequired }).containsExactly(
            ManagerAction.SALE,
            ManagerAction.PRE_AUTHORISATION,
            ManagerAction.CASH_PAYMENT,
            ManagerAction.LINK_CREATE,
            ManagerAction.HISTORY,
            ManagerAction.RECEIPT,
            ManagerAction.TOTALS,
            ManagerAction.SAVED_CARD_LOOKUP,
            ManagerAction.GIFT_CARD_BALANCE,
        )
    }
}
