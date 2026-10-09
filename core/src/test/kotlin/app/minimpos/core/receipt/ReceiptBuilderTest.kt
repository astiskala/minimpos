package app.minimpos.core.receipt

import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.TaxBreakdown
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.receipt.ReceiptElement.Blank
import app.minimpos.core.receipt.ReceiptElement.Divider
import app.minimpos.core.receipt.ReceiptElement.Qr
import app.minimpos.core.receipt.ReceiptElement.Row
import app.minimpos.core.receipt.ReceiptElement.Text
import app.minimpos.core.tax.TaxAmounts
import app.minimpos.core.tax.TaxMode
import com.google.common.truth.Truth.assertThat
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
    fun `taxable totals can be enabled independently of tax amounts and breakdown`() {
        val labels = ReceiptLabels(taxableGrossFormat = "%s対象（税込）", taxableNetFormat = "%s対象（税抜）")
        val taxed =
            sale().copy(
                breakdown =
                    listOf(
                        TaxBreakdown(AppliedTax("消費税", 10_000), TaxAmounts(1000, 100, 1100)),
                        TaxBreakdown(AppliedTax("消費税", 8000), TaxAmounts(1000, 80, 1080)),
                        TaxBreakdown(AppliedTax("税率0%", 0), TaxAmounts(300, 0, 300)),
                    ),
                amounts = TaxAmounts(2300, 180, 2480),
            )
        val builder = ReceiptBuilder(branding, ReceiptOptions(showTaxRateTotals = true, showTaxBreakdown = false), labels, money)
        assertThat(builder.sale(taxed).elements)
            .containsAtLeast(
                Row("10%対象（税込）", "$11.00"),
                Row("8%対象（税込）", "$10.80"),
                Row("0%対象（税込）", "$3.00"),
            ).inOrder()
        assertThat(builder.sale(taxed.copy(mode = TaxMode.EXCLUSIVE)).elements)
            .containsAtLeast(
                Row("10%対象（税抜）", "$10.00"),
                Row("8%対象（税抜）", "$10.00"),
                Row("0%対象（税抜）", "$3.00"),
            ).inOrder()
        val hidden = ReceiptBuilder(branding, ReceiptOptions(showTaxBreakdown = false), labels, money).sale(taxed)
        assertThat(hidden.elements.filterIsInstance<Row>().map { it.left }).doesNotContain("10%対象（税込）")
        val hiddenNet = ReceiptBuilder(branding, ReceiptOptions(), labels, money).sale(taxed.copy(mode = TaxMode.EXCLUSIVE))
        assertThat(hiddenNet.elements.filterIsInstance<Row>().map { it.left }).doesNotContain("10%対象（税抜）")
    }

    @Test
    fun `rate-only receipts combine numeric rates and omit tax amounts in both price styles`() {
        val labels = ReceiptLabels(taxableGrossFormat = "%s対象（税込）", taxableNetFormat = "%s対象（税抜）")
        val receipt =
            sale().copy(
                items = listOf(ReceiptItem("A", 1, 50, 50, 10_000), ReceiptItem("B", 1, 50, 50, 10_000)),
                amounts = TaxAmounts(90, 10, 100),
                breakdown =
                    listOf(
                        TaxBreakdown(AppliedTax("Wrong 8% label", 10_000), TaxAmounts(45, 5, 50)),
                        TaxBreakdown(AppliedTax("Another name", 10_000), TaxAmounts(45, 5, 50)),
                        TaxBreakdown(free, TaxAmounts(0, 0, 0)),
                    ),
            )
        val options = ReceiptOptions(showTaxAmounts = false, showTaxRateTotals = true)
        val builder = ReceiptBuilder(branding, options, labels, money)
        val inclusive = builder.sale(receipt).elements.filterIsInstance<Row>()
        assertThat(inclusive).containsAtLeast(Row("TOTAL", "$1.00", TextStyle.BOLD), Row("10%対象（税込）", "$1.00"))
        assertThat(inclusive.count { it.left == "10%対象（税込）" }).isEqualTo(1)
        assertThat(inclusive.map { it.left }).containsNoneOf("Includes Wrong 8% label", "Includes Another name 10%", "Tax")
        val exclusive = builder.sale(receipt.copy(mode = TaxMode.EXCLUSIVE)).elements.filterIsInstance<Row>()
        assertThat(exclusive).containsAtLeast(Row("Subtotal", "$0.90"), Row("10%対象（税抜）", "$0.90"))
        assertThat(exclusive.map { it.left }).containsNoneOf("Tax", "Wrong 8% label", "Another name 10%")
        TaxMode.entries.forEach { mode ->
            val hidden = ReceiptBuilder(branding, options.copy(showTaxRateTotals = false), labels, money).sale(receipt.copy(mode = mode))
            assertThat(hidden.elements.filterIsInstance<Row>().map { it.left })
                .containsNoneOf("Tax", "10%対象（税込）", "10%対象（税抜）")
        }
    }

    @Test
    fun `reduced-rate items have a mark and one legend on sales and refunds`() {
        val labels = ReceiptLabels(markedTaxRateNote = "※は軽減税率対象商品")
        val items =
            listOf(
                ReceiptItem("Food", 2, 100, 200, 8_000),
                ReceiptItem("Drink", 1, 100, 100, 8_000),
                ReceiptItem("Standard", 1, 100, 100, 10_000),
                ReceiptItem("Free", 1, 100, 100),
            )
        val builder = ReceiptBuilder(branding, ReceiptOptions(markedTaxRateMilliPercent = 8_000), labels, money)
        val sale = builder.sale(sale().copy(items = items))
        val refund = builder.refund(RefundReceipt("R-1", "MP-1", "date", items, 500, emptyList()))
        listOf(sale, refund).forEach { document ->
            assertThat(document.elements).containsAtLeast(
                Row("Food ※", "$2.00"),
                Row("Drink ※", "$1.00"),
                Row("Standard", "$1.00"),
                Row("Free", "$1.00"),
            )
            assertThat(document.elements.filterIsInstance<Text>().count { it.text == labels.markedTaxRateNote }).isEqualTo(1)
        }
        val unmarked = builder.sale(sale().copy(items = items.drop(2)))
        assertThat(unmarked.elements).doesNotContain(Text(labels.markedTaxRateNote))
        val disabled = this.builder().sale(sale().copy(items = items))
        assertThat(disabled.elements.filterIsInstance<Row>().map { it.left }).containsAtLeast("Food", "Drink")
    }

    @Test
    fun `any configured rate can use a custom marker and an optional explanation`() {
        val items = listOf(ReceiptItem("Reduced", 1, 100, 100, 5_500), ReceiptItem("Other", 1, 100, 100, 8_000))
        val options = ReceiptOptions(markedTaxRateMilliPercent = 5_500, markedTaxRateMarker = "*")
        val labels = ReceiptLabels(markedTaxRateNote = "* Reduced rate")
        val document = ReceiptBuilder(branding, options, labels, money).sale(sale().copy(items = items))
        assertThat(document.elements).containsAtLeast(Row("Reduced *", "$1.00"), Row("Other", "$1.00"), Text("* Reduced rate"))
        val noLegend = ReceiptBuilder(branding, options, labels.copy(markedTaxRateNote = ""), money).sale(sale().copy(items = items))
        assertThat(noLegend.elements).contains(Row("Reduced *", "$1.00"))
        assertThat(noLegend.elements).doesNotContain(Text(""))
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
    fun `a sale awaiting its payment link is unpaid, totalled as due, with the link as a QR code and an address`() {
        val url = "https://test.adyen.link/PL123"
        val doc =
            builder().sale(
                sale(approved = false).copy(cardReceipt = emptyList(), refundQr = null, unpaidLink = UnpaidLink(url, "03/10/2026 09:30")),
            )
        assertThat(doc.elements)
            .containsAtLeast(
                Row("AMOUNT DUE", "$12.00", TextStyle.BOLD),
                Blank,
                Text("UNPAID", Align.CENTER, TextStyle.BOLD),
                Qr(url, "Scan to pay"),
                Text("Or open this link:", Align.CENTER),
                ReceiptElement.Link(url, "Pay now"),
                Text("Link valid until 03/10/2026 09:30", Align.CENTER),
                Text("Thank you!", Align.CENTER),
            ).inOrder()
        assertThat(
            doc.elements,
        ).containsNoneOf(Row("TOTAL", "$12.00", TextStyle.BOLD), Text("PAYMENT NOT COMPLETED", Align.CENTER, TextStyle.BOLD))
        assertThat(doc.qrCodes).containsExactly(Qr(url, "Scan to pay"))
        val noExpiry = builder().sale(sale(approved = false).copy(unpaidLink = UnpaidLink(url, null)))
        assertThat(noExpiry.elements.filterIsInstance<Text>().map { it.text }).doesNotContain("Link valid until null")
        assertThat(noExpiry.elements.last()).isEqualTo(Text("Come again", Align.CENTER))
    }

    @Test
    fun `a sale paid through its payment link says so and has no card data`() {
        val doc = builder().sale(sale().copy(cardReceipt = emptyList(), refundQr = null, paidOnline = true))
        assertThat(doc.elements)
            .containsAtLeast(Row("TOTAL", "$12.00", TextStyle.BOLD), Text("Paid online", Align.CENTER), Text("Thank you!", Align.CENTER))
            .inOrder()
        assertThat(doc.qrCodes).isEmpty()
    }

    @Test
    fun `tax labels include the rate unless already present`() {
        assertThat(AppliedTax("VAT", 20_000).label()).isEqualTo("VAT 20%")
        assertThat(AppliedTax("GST 10%", 10_000).label()).isEqualTo("GST 10%")
        assertThat(AppliedTax("", 5_500).label()).isEqualTo("5.5%")
    }
}
