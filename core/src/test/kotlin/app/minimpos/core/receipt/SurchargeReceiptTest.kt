package app.minimpos.core.receipt

import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.tax.TaxAmounts
import app.minimpos.core.tax.TaxMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class SurchargeReceiptTest {
    private val builder =
        ReceiptBuilder(
            ReceiptBranding(),
            ReceiptOptions(),
            ReceiptLabels(),
            MoneyFormatter(CurrencySpec.of("AUD"), Locale.forLanguageTag("en-AU")),
        )
    private val sale =
        SaleReceipt(
            "FEE",
            null,
            "24/10/2026",
            TaxMode.INCLUSIVE,
            emptyList(),
            TaxAmounts(1000, 0, 1000),
            emptyList(),
            true,
            emptyList(),
            null,
            false,
        )

    @Test
    fun `receipt discloses components without adding fees or tips to the charged total again`() {
        val receipt =
            sale.copy(
                surchargeMinor = 50,
                chargedAmount = 1150,
                purchaseMinor = 1000,
                discloseSurcharge = true,
                tip = TipLines.Entered(100),
            )
        val rows = builder.sale(receipt).elements.filterIsInstance<ReceiptElement.Row>()
        assertThat(rows)
            .containsAtLeast(
                ReceiptElement.Row("Purchase", "$10.00"),
                ReceiptElement.Row("Surcharge", "$0.50"),
                ReceiptElement.Row("TIP", "$1.00"),
                ReceiptElement.Row("CHARGED", "$11.50", TextStyle.BOLD),
            ).inOrder()
        assertThat(rows.count { it.right == "$11.50" }).isEqualTo(1)
        assertThat(rows.map { it.right }).doesNotContain("$12.00")
    }

    @Test
    fun `receipts hide absent and zero fees without inventing purchase amounts`() {
        val unknown = builder.sale(sale.copy(discloseSurcharge = true, chargedAmount = 1000)).elements
        assertThat(unknown.filterIsInstance<ReceiptElement.Row>().map { it.left }).doesNotContain("Surcharge")
        assertThat(unknown.filterIsInstance<ReceiptElement.Row>().map { it.left }).doesNotContain("Purchase")
        val zero = builder.sale(sale.copy(discloseSurcharge = true, surchargeMinor = 0, chargedAmount = 1000)).elements
        assertThat(zero.filterIsInstance<ReceiptElement.Row>().map { it.left }).doesNotContain("Surcharge")
    }

    @Test
    fun `refund fee and tip are included components while unknown allocations and cancellations remain distinct`() {
        val refund = RefundReceipt("R", "FEE", "24/10/2026", emptyList(), 1150, emptyList(), surchargeMinor = 50, tipMinor = 100)
        val rows = builder.refund(refund).elements.filterIsInstance<ReceiptElement.Row>()
        assertThat(rows).containsAtLeast(
            ReceiptElement.Row("REFUND TOTAL", "$11.50", TextStyle.BOLD),
            ReceiptElement.Row("Surcharge", "$0.50"),
            ReceiptElement.Row("TIP", "$1.00"),
        )
        val unknown = builder.refund(refund.copy(surchargeMinor = null, tipMinor = null)).elements
        assertThat(unknown.filterIsInstance<ReceiptElement.Row>().map { it.left }).doesNotContain("Surcharge")
        val zero = builder.refund(refund.copy(surchargeMinor = 0)).elements.filterIsInstance<ReceiptElement.Row>()
        assertThat(zero.map { it.left }).doesNotContain("Surcharge")
        val cancelled = builder.refund(refund.copy(cancellation = true)).elements.filterIsInstance<ReceiptElement.Row>()
        assertThat(cancelled.map { it.left }).containsNoneOf("Surcharge", "TIP")
    }
}
