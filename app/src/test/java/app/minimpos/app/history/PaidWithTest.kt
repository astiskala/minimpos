package app.minimpos.app.history

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What paid a stored sale, read once for every screen and receipt. */
class PaidWithTest {
    private val sale =
        SaleEntity(
            id = "sale",
            createdAt = 0,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 1_000,
            taxMinor = 0,
            totalMinor = 1_000,
            status = SaleStatus.APPROVED,
            merchantReference = "260930-145811-VQ45",
        )

    @Test
    fun `the card joins the reported brand and masked number, and is absent when neither was reported`() {
        assertThat(sale.cardText).isNull()
        assertThat(sale.copy(paymentBrand = "visa", maskedPan = "541333 **** 9999").cardText).isEqualTo("VISA 541333 **** 9999")
        assertThat(sale.copy(maskedPan = "541333 **** 9999").cardText).isEqualTo("541333 **** 9999")
    }

    @Test
    fun `an offline approval is typed from the stored mode`() {
        assertThat(sale.offlineApproval).isNull()
        assertThat(sale.copy(offlineMode = "OFFLINE_EMV").offlineApproval).isEqualTo(OfflineApproval.OFFLINE_EMV)
        assertThat(sale.copy(offlineMode = "STORE_AND_FORWARD").offlineApproval).isEqualTo(OfflineApproval.STORE_AND_FORWARD)
    }
}
