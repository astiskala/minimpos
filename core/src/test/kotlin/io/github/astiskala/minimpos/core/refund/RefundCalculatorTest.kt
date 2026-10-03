package io.github.astiskala.minimpos.core.refund

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RefundCalculatorTest {
    // 3 units for 1.09 in total: per unit 0.3633...
    private val fractional = RefundableLine(lineId = 1, quantity = 3, refundedQuantity = 0, gross = 109)
    private val simple = RefundableLine(lineId = 2, quantity = 2, refundedQuantity = 0, gross = 900)

    @Test
    fun `refunds whole units of a line`() {
        assertThat(RefundCalculator.amountFor(listOf(simple, fractional), mapOf(2L to 1), 1009)).isEqualTo(450)
    }

    @Test
    fun `cumulative apportioning never loses a cent`() {
        val first = RefundCalculator.amountFor(listOf(fractional, simple), mapOf(1L to 1), 1009)
        val second =
            RefundCalculator.amountFor(
                listOf(fractional.copy(refundedQuantity = 1), simple),
                mapOf(1L to 1),
                1009 - first,
            )
        val third =
            RefundCalculator.amountFor(
                listOf(fractional.copy(refundedQuantity = 2), simple),
                mapOf(1L to 1),
                1009 - first - second,
            )
        assertThat(listOf(first, second, third)).containsExactly(36L, 37L, 36L).inOrder()
        assertThat(first + second + third).isEqualTo(109)
    }

    @Test
    fun `selecting everything refunds the remaining amount`() {
        assertThat(
            RefundCalculator.amountFor(listOf(fractional, simple), mapOf(1L to 3, 2L to 2), 1000),
        ).isEqualTo(1000)
    }

    @Test
    fun `never exceeds what is still refundable`() {
        assertThat(RefundCalculator.amountFor(listOf(fractional, simple), mapOf(2L to 2), 500)).isEqualTo(500)
        assertThat(RefundCalculator.amountFor(listOf(simple), mapOf(2L to 2), -5)).isEqualTo(0)
    }

    @Test
    fun `ignores unknown lines and clamps quantities`() {
        assertThat(
            RefundCalculator.amountFor(listOf(simple, fractional), mapOf(99L to 5, 2L to 7), 1009),
        ).isEqualTo(900)
        assertThat(RefundCalculator.amountFor(emptyList(), emptyMap(), 100)).isEqualTo(0)
        assertThat(
            RefundCalculator.amountFor(listOf(simple.copy(refundedQuantity = 5)), mapOf(2L to 1), 100),
        ).isEqualTo(0)
    }

    @Test
    fun `line amounts apportion per line and clamp`() {
        assertThat(RefundCalculator.lineAmount(fractional, 1)).isEqualTo(36)
        assertThat(RefundCalculator.lineAmount(fractional.copy(refundedQuantity = 1), 2)).isEqualTo(73)
        assertThat(RefundCalculator.lineAmount(fractional, 9)).isEqualTo(109)
        assertThat(RefundCalculator.lineAmount(fractional, 0)).isEqualTo(0)
        assertThat(RefundCalculator.lineAmount(fractional.copy(refundedQuantity = 3), 1)).isEqualTo(0)
    }

    @Test
    fun `lines with no quantity contribute nothing`() {
        val empty = RefundableLine(lineId = 3, quantity = 0, refundedQuantity = 0, gross = 100)
        assertThat(empty.remainingQuantity).isEqualTo(0)
        assertThat(RefundCalculator.amountFor(listOf(empty, simple), mapOf(3L to 1), 1000)).isEqualTo(0)
    }
}
