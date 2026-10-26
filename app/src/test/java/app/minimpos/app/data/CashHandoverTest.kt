package app.minimpos.app.data

import app.minimpos.app.data.repo.CashHandover
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CashHandoverTest {
    @Test
    fun `handover needs known bounded funded cash and never repeats recorded cash`() {
        val approved = CashHandover(500, 500, 500, null)
        assertThat(approved.canRecord).isTrue()
        assertThat(approved.needsAttention).isTrue()
        assertThat(approved.copy(approvedMinor = 200).canRecord).isTrue()
        assertThat(approved.copy(recordedAt = 100).canRecord).isFalse()
        assertThat(approved.copy(recordedAt = 100).needsAttention).isFalse()
        listOf(
            approved.copy(approvedMinor = null),
            approved.copy(chargedMinor = null),
            approved.copy(approvedMinor = 0),
            approved.copy(approvedMinor = -1),
            approved.copy(approvedMinor = 501),
            approved.copy(chargedMinor = 499),
        ).forEach { assertThat(it.canRecord).isFalse() }
        assertThat(approved.copy(approvedMinor = null).needsAttention).isTrue()
        assertThat(approved.copy(approvedMinor = 0).needsAttention).isFalse()
    }
}
