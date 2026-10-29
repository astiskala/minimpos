package app.minimpos.app.receipt

import app.minimpos.app.TestEnvironment
import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.core.receipt.TextStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU")
class SurchargeReceiptDeliveryTest {
    @get:Rule val env = TestEnvironment()

    @Test
    fun `captured receipt-tip sale includes its original fee once in the final amount`() {
        val sale =
            SaleEntity(
                id = "fee",
                createdAt = 0,
                currency = "AUD",
                taxMode = "INCLUSIVE",
                netMinor = 1000,
                taxMinor = 0,
                totalMinor = 1000,
                status = SaleStatus.APPROVED,
                merchantReference = "FEE",
                tipOnReceipt = true,
                tipMinor = 0,
                terminalPaidMinor = 1050,
                surchargeMinor = 50,
                capturedMinor = 1050,
                captureStatus = CaptureStatus.REQUESTED,
            )
        val document = env.container.receiptFactory.sale(SaleWithLines(sale, emptyList()), ReceiptSettings())
        val rows = document.elements.filterIsInstance<ReceiptElement.Row>()
        assertThat(rows).containsAtLeast(
            ReceiptElement.Row("Surcharge", "$0.50"),
            ReceiptElement.Row("Charged", "$10.50", TextStyle.BOLD),
        )
        assertThat(rows).doesNotContain(ReceiptElement.Row("TOTAL", "$10.00", TextStyle.BOLD))
        assertThat(rows).doesNotContain(ReceiptElement.Row("TOTAL", "$11.00", TextStyle.BOLD))
    }

    @Test
    fun `a changed capture of a surcharged sale prints its fee as unreported, like the sale screen`() {
        val sale =
            SaleEntity(
                id = "changed",
                createdAt = 0,
                currency = "AUD",
                taxMode = "INCLUSIVE",
                netMinor = 1000,
                taxMinor = 0,
                totalMinor = 1000,
                status = SaleStatus.APPROVED,
                merchantReference = "CHANGED",
                tipOnReceipt = true,
                tipMinor = 200,
                terminalPaidMinor = 1050,
                surchargeMinor = 50,
                capturedMinor = 1250,
                captureStatus = CaptureStatus.REQUESTED,
            )
        val document = env.container.receiptFactory.sale(SaleWithLines(sale, emptyList()), ReceiptSettings())
        val rows = document.elements.filterIsInstance<ReceiptElement.Row>()
        assertThat(rows).contains(ReceiptElement.Row("Surcharge", "Unreported"))
        assertThat(rows.map { it.left }).doesNotContain("Purchase")
    }
}
