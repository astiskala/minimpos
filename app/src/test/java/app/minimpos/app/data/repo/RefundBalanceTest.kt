package app.minimpos.app.data.repo

import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.SaleWithLines
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Plain JUnit: what a sale and each tender can still return, read the same by review and the storing transaction. */
class RefundBalanceTest {
    private val sale =
        SaleEntity(
            id = "s1",
            createdAt = 0,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 1_000,
            taxMinor = 0,
            totalMinor = 1_000,
            status = SaleStatus.APPROVED,
            merchantReference = "MP-1",
            terminalPaidMinor = 1_000,
        )
    private val first = SaleTenderEntity("t1", "s1", 1, 400, 400, poiTransactionId = "FIRST.PSP1")
    private val second = SaleTenderEntity("t2", "s1", 2, 600, 600, poiTransactionId = "SECOND.PSP2")

    private fun refund(
        id: String,
        tender: SaleTenderEntity?,
        amountMinor: Long,
        status: RefundStatus,
        cancellation: Boolean = false,
    ) = RefundEntity(
        id = id,
        saleId = "s1",
        createdAt = 0,
        merchantReference = "R-$id",
        originalTransactionId = tender?.poiTransactionId,
        originalTimestamp = null,
        originalReference = null,
        currency = "AUD",
        amountMinor = amountMinor,
        full = false,
        status = status,
        cancellation = cancellation,
        tenderId = tender?.id,
    )

    @Test
    fun `accepted returns are applied, possibly sent ones reserved, failed ones and cancellations ignored`() {
        val record =
            SaleWithLines(
                sale.copy(refundedMinor = 100),
                emptyList(),
                tenders = listOf(first, second),
                refunds =
                    listOf(
                        refund("a", first, 100, RefundStatus.REQUESTED),
                        refund("b", second, 200, RefundStatus.UNKNOWN),
                        refund("c", second, 300, RefundStatus.FAILED),
                        refund("d", null, 50, RefundStatus.PENDING, cancellation = true),
                    ),
            )
        val balance = RefundBalance(record)
        assertThat(balance.returns.map { it.id }).containsExactly("a", "b")
        assertThat(balance.reservedMinor).isEqualTo(200)
        assertThat(balance.remainingMinor).isEqualTo(700)
        assertThat(balance.of(first).acceptedMinor).isEqualTo(100)
        assertThat(balance.of(first).remainingMinor).isEqualTo(300)
        assertThat(balance.of(second).reservedMinor).isEqualTo(200)
        assertThat(balance.of(second).remainingMinor).isEqualTo(400)
    }

    @Test
    fun `a whole reversal needs an untouched tender without cash handed out`() {
        val untouched = RefundBalance(SaleWithLines(sale, emptyList(), tenders = listOf(first, second)))
        assertThat(untouched.of(first).whole(400)).isTrue()
        assertThat(untouched.of(first).whole(399)).isFalse()
        val withCash = first.copy(cashBackMinor = 100)
        assertThat(RefundBalance(SaleWithLines(sale, emptyList(), tenders = listOf(withCash))).of(withCash).whole(400)).isFalse()
        val returned =
            SaleWithLines(sale, emptyList(), tenders = listOf(first), refunds = listOf(refund("a", first, 1, RefundStatus.PENDING)))
        assertThat(RefundBalance(returned).of(first).whole(400)).isFalse()
    }
}
