package app.minimpos.core.split

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class TenderSplitTest {
    @Test
    fun `equal shares place remainders on the first shares and total exactly`() {
        assertThat(TenderSplit.equalShares(1000, 3)).containsExactly(334L, 333L, 333L).inOrder()
        assertThat(TenderSplit.equalShares(1001, 3)).containsExactly(334L, 334L, 333L).inOrder()
        assertThat(TenderSplit.equalShares(0, 2)).containsExactly(0L, 0L)
        assertThat(TenderSplit.equalShares(7, 20).sum()).isEqualTo(7)
    }

    @Test
    fun `share counts and bases are validated`() {
        assertThrows(IllegalArgumentException::class.java) { TenderSplit.equalShares(100, 1) }
        assertThrows(IllegalArgumentException::class.java) { TenderSplit.equalShares(100, TenderSplit.MAX_SHARES + 1) }
        assertThrows(IllegalArgumentException::class.java) { TenderSplit.equalShares(-1, 2) }
    }

    @Test
    fun `a share is capped by the unpaid balance and the last share takes what remains`() {
        assertThat(TenderSplit.shareAmount(1000, 3, 0, 1000)).isEqualTo(334)
        assertThat(TenderSplit.shareAmount(1000, 3, 1, 200)).isEqualTo(200)
        // An earlier share was only partially approved, so the last one collects the rest.
        assertThat(TenderSplit.shareAmount(1000, 3, 2, 500)).isEqualTo(500)
        assertThat(TenderSplit.shareAmount(1000, 3, 2, -5)).isEqualTo(0)
        assertThat(TenderSplit.shareAmount(1000, 3, 3, 500)).isEqualTo(0)
        assertThat(TenderSplit.shareAmount(1000, 3, -1, 500)).isEqualTo(0)
    }

    @Test
    fun `items keep sold gross amounts and apportion units cumulatively`() {
        val lines = listOf(AllocatableLine(1, 3, 0, 1000), AllocatableLine(2, 1, 0, 250))
        assertThat(TenderSplit.itemsAmount(lines, mapOf(1L to 1), 1250)).isEqualTo(333)
        val later = listOf(AllocatableLine(1, 3, 1, 1000), AllocatableLine(2, 1, 0, 250))
        assertThat(TenderSplit.lineAmount(later[0], 2)).isEqualTo(667)
        assertThat(TenderSplit.itemsAmount(later, mapOf(1L to 1, 9L to 4), 917)).isEqualTo(334)
        // Everything still unallocated takes the whole unpaid balance, so no remainder is stranded.
        assertThat(TenderSplit.itemsAmount(later, mapOf(1L to 2, 2L to 1), 917)).isEqualTo(917)
        assertThat(later[0].remainingQuantity).isEqualTo(2)
        assertThat(AllocatableLine(3, 1, 4, 10).remainingQuantity).isEqualTo(0)
    }

    @Test
    fun `cash change is what was received beyond the tender`() {
        assertThat(CashPayment(1250, 2000).changeMinor).isEqualTo(750)
        assertThat(CashPayment(1250, 1250).changeMinor).isEqualTo(0)
        assertThrows(IllegalArgumentException::class.java) { CashPayment(0, 10) }
        assertThrows(IllegalArgumentException::class.java) { CashPayment(100, 99) }
    }
}
