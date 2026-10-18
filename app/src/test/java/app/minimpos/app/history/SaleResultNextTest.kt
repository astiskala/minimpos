package app.minimpos.app.history

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.terminal.client.RetryAdvice
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** [saleResultNext]'s one reading of a stored sale's outcome, as the result screen offers it. */
class SaleResultNextTest {
    private fun sale(
        status: SaleStatus,
        paidMinor: Long = 0,
        errorCondition: String? = null,
    ): SaleEntity =
        SaleEntity(
            id = "sale",
            createdAt = 1,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 909,
            taxMinor = 91,
            totalMinor = 1_000,
            status = status,
            merchantReference = "M",
            paidMinor = paidMinor,
            errorCondition = errorCondition,
        )

    @Test
    fun `a partly paid sale collects its remaining balance first`() {
        assertThat(saleResultNext(sale(SaleStatus.APPROVED, paidMinor = 400))).isEqualTo(SaleResultNext.PAY_REMAINING)
    }

    @Test
    fun `an approved sale offers the next payment`() {
        assertThat(saleResultNext(sale(SaleStatus.APPROVED))).isEqualTo(SaleResultNext.NEW_SALE)
    }

    @Test
    fun `a failed or refused sale may be tried again`() {
        assertThat(saleResultNext(sale(SaleStatus.DECLINED, errorCondition = "Refusal"))).isEqualTo(SaleResultNext.TRY_AGAIN)
        assertThat(saleResultNext(sale(SaleStatus.CANCELLED, errorCondition = "Cancel"))).isEqualTo(SaleResultNext.TRY_AGAIN)
        assertThat(saleResultNext(sale(SaleStatus.FAILED))).isEqualTo(SaleResultNext.TRY_AGAIN)
    }

    @Test
    fun `a refusal the terminal says never to retry offers nothing`() {
        val approvedDuringCancel =
            sale(SaleStatus.DECLINED, errorCondition = "Cancel").copy(
                refusalReason = "Approved",
            )
        assertThat(approvedDuringCancel.decline?.advice).isEqualTo(RetryAdvice.DO_NOT_RETRY)
        assertThat(saleResultNext(approvedDuringCancel)).isEqualTo(SaleResultNext.NONE)
    }

    @Test
    fun `pending, unknown and expired sales offer nothing`() {
        assertThat(saleResultNext(sale(SaleStatus.PENDING))).isEqualTo(SaleResultNext.NONE)
        assertThat(saleResultNext(sale(SaleStatus.UNKNOWN))).isEqualTo(SaleResultNext.NONE)
        assertThat(saleResultNext(sale(SaleStatus.EXPIRED))).isEqualTo(SaleResultNext.NONE)
        assertThat(saleResultNext(sale(SaleStatus.AWAITING_PAYMENT))).isEqualTo(SaleResultNext.NONE)
    }
}
