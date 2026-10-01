package io.minimpos.core.receipt

import com.google.common.truth.Truth.assertThat
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.TaxBreakdown
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.money.MoneyFormatter
import io.minimpos.core.receipt.ReceiptElement.Blank
import io.minimpos.core.receipt.ReceiptElement.Divider
import io.minimpos.core.receipt.ReceiptElement.Qr
import io.minimpos.core.receipt.ReceiptElement.Row
import io.minimpos.core.receipt.ReceiptElement.Text
import io.minimpos.core.tax.TaxAmounts
import io.minimpos.core.tax.TaxMode
import org.junit.Test
import java.util.Locale

class ReceiptBuilderTest {
    private val money = MoneyFormatter(CurrencySpec("AUD", 2), Locale.forLanguageTag("en-AU"))
    private val gst = AppliedTax("GST", 10_000)
    private val free = AppliedTax("GST-free", 0)
    private val branding =
        ReceiptBranding(
            businessName = "Corner Cafe",
            addressLines = listOf("1 Main St", " ", "Sydney NSW"),
            taxIdLabel = "ABN",
            taxId = "12 345 678 901",
            phone = "02 9999 0000",
            title = "TAX INVOICE",
            footer = "Thank you!\n\n  Come again ",
        )
    private val cardLines =
        listOf(
            CardReceiptLine(key = "header1", name = "ADYEN CAFE"),
            CardReceiptLine(key = "filler"),
            CardReceiptLine(key = "txtype", name = "PAYMENT", bold = true),
            CardReceiptLine(key = "pan", name = "Card", value = "**** 9999"),
        )

    private fun sale(
        mode: TaxMode = TaxMode.INCLUSIVE,
        approved: Boolean = true,
        cardSaved: Boolean = false,
        customer: String? = "CUST-1",
    ) = SaleReceipt(
        reference = "MP-1",
        customerReference = customer,
        dateTime = "29/09/2026 21:03",
        mode = mode,
        items = listOf(ReceiptItem("Flat white", 2, 450, 900), ReceiptItem("Milk", 1, 300, 300)),
        amounts = TaxAmounts(1118, 82, 1200),
        breakdown = listOf(TaxBreakdown(gst, TaxAmounts(818, 82, 900)), TaxBreakdown(free, TaxAmounts(300, 0, 300))),
        approved = approved,
        cardReceipt = cardLines,
        refundQr = "MPR1*abc.def*1*1200*AUD",
        cardSaved = cardSaved,
    )

    private fun builder(options: ReceiptOptions = ReceiptOptions()) = ReceiptBuilder(branding, options, ReceiptLabels(), money)

    @Test
    fun `customer copy has header, items, inclusive tax, card data, footer and QR`() {
        val doc = builder().sale(sale())
        assertThat(doc.elements)
            .containsExactly(
                Text("Corner Cafe", Align.CENTER, TextStyle.BOLD),
                Text("1 Main St", Align.CENTER),
                Text("Sydney NSW", Align.CENTER),
                Text("ABN 12 345 678 901", Align.CENTER),
                Text("02 9999 0000", Align.CENTER),
                Blank,
                Text("TAX INVOICE", Align.CENTER, TextStyle.BOLD),
                Row("Date", "29/09/2026 21:03"),
                Row("Reference", "MP-1"),
                Row("Customer", "CUST-1"),
                Divider,
                Row("Flat white", "$9.00"),
                Text("  2 x $4.50"),
                Row("Milk", "$3.00"),
                Divider,
                Row("TOTAL", "$12.00", TextStyle.BOLD),
                Row("Includes GST 10%", "$0.82"),
                Divider,
                Blank,
                Text("PAYMENT", Align.CENTER, TextStyle.BOLD),
                Row("Card", "**** 9999"),
                Blank,
                Text("Thank you!", Align.CENTER),
                Text("Come again", Align.CENTER),
                Qr("MPR1*abc.def*1*1200*AUD", "Scan this code for returns"),
            ).inOrder()
    }

    @Test
    fun `exclusive receipts show subtotal and tax lines`() {
        val doc = builder().sale(sale(mode = TaxMode.EXCLUSIVE))
        assertThat(
            doc.elements,
        ).containsAtLeast(
            Row("Subtotal", "$11.18"),
            Row("GST 10%", "$0.82"),
            Row("TOTAL", "$12.00", TextStyle.BOLD),
        ).inOrder()
        val hidden = builder(ReceiptOptions(showTaxBreakdown = false)).sale(sale(mode = TaxMode.EXCLUSIVE))
        assertThat(hidden.elements).contains(Row("Tax", "$0.82"))
        assertThat(hidden.elements).doesNotContain(Row("GST 10%", "$0.82"))
    }

    @Test
    fun `exclusive receipts without tax omit the tax line`() {
        val untaxed = sale(mode = TaxMode.EXCLUSIVE).copy(amounts = TaxAmounts(300, 0, 300), breakdown = emptyList())
        val doc = builder(ReceiptOptions(showTaxBreakdown = false)).sale(untaxed)
        assertThat(doc.elements.filterIsInstance<Row>().map { it.left }).doesNotContain("Tax")
    }

    @Test
    fun `receipts awaiting a tip have lines to fill in, and the merchant copy a line to sign`() {
        val write = "_".repeat(14)
        val customer = builder().sale(sale().copy(tip = TipLines.Blank))
        assertThat(customer.elements)
            .containsAtLeast(
                Row("AMOUNT", "$12.00", TextStyle.BOLD),
                Row("Includes GST 10%", "$0.82"),
                Blank,
                Row("TIP", write),
                Blank,
                Row("TOTAL", write, TextStyle.BOLD),
                Divider,
            ).inOrder()
        assertThat(customer.elements.filterIsInstance<Row>().map { it.left }).doesNotContain("SIGNATURE")
        assertThat(customer.qrCodes).hasSize(1)
        val merchant = builder().sale(sale().copy(tip = TipLines.Blank), ReceiptCopy.MERCHANT)
        assertThat(merchant.elements)
            .containsAtLeast(Row("TOTAL", write, TextStyle.BOLD), Blank, Blank, Row("SIGNATURE", "_".repeat(20)), Divider)
            .inOrder()
    }

    @Test
    fun `an entered tip is printed with the total it makes`() {
        val doc = builder().sale(sale(mode = TaxMode.EXCLUSIVE).copy(tip = TipLines.Entered(250)), ReceiptCopy.MERCHANT)
        assertThat(doc.elements)
            .containsAtLeast(
                Row("Subtotal", "$11.18"),
                Row("AMOUNT", "$12.00", TextStyle.BOLD),
                Row("TIP", "$2.50"),
                Row("TOTAL", "$14.50", TextStyle.BOLD),
            ).inOrder()
        assertThat(doc.elements.filterIsInstance<Row>().map { it.left }).doesNotContain("SIGNATURE")
    }

    @Test
    fun `an adjusted and captured pre-authorisation shows both amounts and drops the not charged note`() {
        val preAuth = sale().copy(preAuthorisation = true, heldNow = 1_500, captured = 1_450)
        val doc = builder().sale(preAuth)
        assertThat(doc.elements)
            .containsAtLeast(
                Row("AMOUNT HELD", "$12.00", TextStyle.BOLD),
                Row("HELD NOW", "$15.00"),
                Row("CAPTURED", "$14.50", TextStyle.BOLD),
            ).inOrder()
        assertThat(doc.elements).doesNotContain(Text("Held on the card, not charged yet", Align.CENTER))
        assertThat(builder().sale(sale().copy(preAuthorisation = true)).elements)
            .contains(Text("Held on the card, not charged yet", Align.CENTER))
    }

    @Test
    fun `merchant copy is labelled and has no QR`() {
        val doc = builder().sale(sale(), ReceiptCopy.MERCHANT)
        assertThat(doc.elements).contains(Text("MERCHANT COPY", Align.CENTER, TextStyle.BOLD))
        assertThat(doc.qrCodes).isEmpty()
    }

    @Test
    fun `declined sales are flagged and get no QR`() {
        val doc = builder().sale(sale(approved = false))
        assertThat(doc.elements).contains(Text("PAYMENT NOT COMPLETED", Align.CENTER, TextStyle.BOLD))
        assertThat(doc.qrCodes).isEmpty()
    }

    @Test
    fun `options hide references, breakdown and QR`() {
        val doc =
            builder(
                ReceiptOptions(showTaxBreakdown = false, showReferences = false, showRefundQr = false),
            ).sale(sale(cardSaved = true))
        val rows = doc.elements.filterIsInstance<Row>().map { it.left }
        assertThat(rows).containsNoneOf("Reference", "Customer", "Includes GST 10%")
        assertThat(doc.qrCodes).isEmpty()
        assertThat(doc.elements).contains(Text("Card saved for future payments", Align.CENTER))
    }

    @Test
    fun `blank customer reference is omitted`() {
        val rows =
            builder()
                .sale(sale(customer = " "))
                .elements
                .filterIsInstance<Row>()
                .map { it.left }
        assertThat(rows).doesNotContain("Customer")
    }

    @Test
    fun `adyen header lines are kept when there is no own header`() {
        val bare = ReceiptBuilder(ReceiptBranding(), ReceiptOptions(), ReceiptLabels(), money)
        val doc = bare.sale(sale().copy(cardReceipt = cardLines, refundQr = null))
        assertThat(doc.elements.first()).isEqualTo(Row("Date", "29/09/2026 21:03"))
        assertThat(doc.elements).contains(Text("ADYEN CAFE", Align.CENTER))
        assertThat(doc.qrCodes).isEmpty()
    }

    @Test
    fun `refund receipts list items or a partial refund`() {
        val refund =
            RefundReceipt(
                reference = "MP-R1",
                originalReference = "MP-1",
                dateTime = "30/09/2026 10:00",
                items = listOf(ReceiptItem("Flat white", 1, 450, 450)),
                amount = 450,
                cardReceipt = emptyList(),
            )
        val doc = builder().refund(refund)
        assertThat(doc.elements).containsAtLeast(
            Text("REFUND", Align.CENTER, TextStyle.BOLD),
            Row("Reference", "MP-R1"),
            Row("Original sale", "MP-1"),
            Row("Flat white", "$4.50"),
            Row("REFUND TOTAL", "$4.50", TextStyle.BOLD),
        )
        val partial =
            ReceiptBuilder(branding.copy(footer = ""), ReceiptOptions(showReferences = false), ReceiptLabels(), money)
                .refund(refund.copy(items = emptyList()))
        assertThat(partial.elements).contains(Text("Partial refund"))
        assertThat(partial.elements.filterIsInstance<Row>().map { it.left }).doesNotContain("Reference")
    }

    @Test
    fun `pre-authorisation receipts are titled, show the amount held and never get a refund QR`() {
        val doc = builder().sale(sale().copy(preAuthorisation = true))
        assertThat(doc.elements)
            .containsAtLeast(
                Text("PRE-AUTHORIZATION", Align.CENTER, TextStyle.BOLD),
                Row("AMOUNT HELD", "$12.00", TextStyle.BOLD),
                Text("Held on the card, not charged yet", Align.CENTER),
            ).inOrder()
        assertThat(doc.elements).doesNotContain(Text("TAX INVOICE", Align.CENTER, TextStyle.BOLD))
        assertThat(doc.elements).doesNotContain(Row("TOTAL", "$12.00", TextStyle.BOLD))
        assertThat(doc.qrCodes).isEmpty()
        val exclusive = builder().sale(sale(mode = TaxMode.EXCLUSIVE).copy(preAuthorisation = true))
        assertThat(exclusive.elements).contains(Row("AMOUNT HELD", "$12.00", TextStyle.BOLD))
    }

    @Test
    fun `cancellation receipts say the pre-authorisation was released`() {
        val cancellation =
            RefundReceipt(
                reference = "C-1",
                originalReference = "MP-1",
                dateTime = "30/09/2026 10:00",
                items = emptyList(),
                amount = 20_000,
                cardReceipt = emptyList(),
                cancellation = true,
            )
        val doc = builder().refund(cancellation)
        assertThat(doc.elements)
            .containsAtLeast(
                Text("CANCELLATION", Align.CENTER, TextStyle.BOLD),
                Row("Canceled payment", "MP-1"),
                Text("Payment canceled"),
                Row("RELEASED", "$200.00", TextStyle.BOLD),
            ).inOrder()
        assertThat(doc.elements).containsNoneOf(Text("Partial refund"), Text("REFUND", Align.CENTER, TextStyle.BOLD))
    }

    @Test
    fun `tax labels include the rate unless already present`() {
        assertThat(AppliedTax("VAT", 20_000).label()).isEqualTo("VAT 20%")
        assertThat(AppliedTax("GST 10%", 10_000).label()).isEqualTo("GST 10%")
        assertThat(AppliedTax("", 5_500).label()).isEqualTo("5.5%")
    }
}
