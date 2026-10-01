package io.minimpos.core.receipt

import io.minimpos.core.money.MoneyFormatter
import io.minimpos.core.receipt.ReceiptElement.Blank
import io.minimpos.core.receipt.ReceiptElement.Divider
import io.minimpos.core.receipt.ReceiptElement.Qr
import io.minimpos.core.receipt.ReceiptElement.Row
import io.minimpos.core.receipt.ReceiptElement.Text
import io.minimpos.core.tax.TaxMode

/**
 * Builds combined receipts: merchant header, line items and tax, then the Adyen card receipt data verbatim
 * (scheme-compliant), footer and the refund QR code.
 *
 * It takes the merchant's header and footer ([ReceiptBranding]), which optional parts to include ([ReceiptOptions]),
 * the texts to use ([ReceiptLabels]) and a [MoneyFormatter] for the receipt's currency and locale.
 */
class ReceiptBuilder(
    private val branding: ReceiptBranding,
    private val options: ReceiptOptions,
    private val labels: ReceiptLabels,
    private val money: MoneyFormatter,
) {
    /**
     * Builds the [copy] of a sale receipt: header, date and references, items, totals and tax, a warning if the
     * payment was not approved, Adyen's card receipt lines, the footer and, on approved customer copies, the refund QR
     * code. A pre-authorisation is titled and totalled as an amount held instead, with no refund QR code.
     */
    fun sale(
        receipt: SaleReceipt,
        copy: ReceiptCopy = ReceiptCopy.CUSTOMER,
    ): ReceiptDocument {
        val out = mutableListOf<ReceiptElement>()
        header(out, title = if (receipt.preAuthorisation) labels.preAuthTitle else branding.title)
        if (copy == ReceiptCopy.MERCHANT) out += Text(labels.merchantCopy, Align.CENTER, TextStyle.BOLD)
        out += Row(labels.date, receipt.dateTime)
        if (options.showReferences) {
            out += Row(labels.reference, receipt.reference)
            receipt.customerReference?.takeIf { it.isNotBlank() }?.let { out += Row(labels.customer, it) }
        }
        out += Divider
        items(out, receipt.items)
        out += Divider
        totals(out, receipt)
        if (!receipt.approved) {
            out += Blank
            out += Text(labels.notCompleted, Align.CENTER, TextStyle.BOLD)
        } else if (receipt.preAuthorisation) {
            out += Text(labels.preAuthNote, Align.CENTER)
        }
        if (receipt.cardSaved) out += Text(labels.cardSaved, Align.CENTER)
        cardReceipt(out, receipt.cardReceipt)
        footer(out)
        val showsRefundQr = copy == ReceiptCopy.CUSTOMER && receipt.approved && !receipt.preAuthorisation && options.showRefundQr
        receipt.refundQr?.takeIf { showsRefundQr }?.let { out += Qr(it, labels.refundQrCaption) }
        return ReceiptDocument(out)
    }

    /**
     * Builds a refund receipt: header titled [ReceiptLabels.refundTitle], date and references, the refunded items (or
     * [ReceiptLabels.partialRefund]), the refunded total, Adyen's card receipt lines and the footer. The cancellation
     * of a pre-authorisation is laid out the same way with its own title and labels.
     */
    fun refund(receipt: RefundReceipt): ReceiptDocument {
        val out = mutableListOf<ReceiptElement>()
        val cancellation = receipt.cancellation
        header(out, title = if (cancellation) labels.cancellationTitle else labels.refundTitle)
        out += Row(labels.date, receipt.dateTime)
        if (options.showReferences) out += Row(labels.reference, receipt.reference)
        out += Row(if (cancellation) labels.cancelledReference else labels.originalReference, receipt.originalReference)
        out += Divider
        when {
            cancellation -> out += Text(labels.cancellationNote)
            receipt.items.isEmpty() -> out += Text(labels.partialRefund)
            else -> items(out, receipt.items)
        }
        out += Divider
        out += Row(if (cancellation) labels.released else labels.refundTotal, money.format(receipt.amount), TextStyle.BOLD)
        cardReceipt(out, receipt.cardReceipt)
        footer(out)
        return ReceiptDocument(out)
    }

    private fun header(
        out: MutableList<ReceiptElement>,
        title: String,
    ) {
        val start = out.size
        branding.businessName.takeIf { it.isNotBlank() }?.let { out += Text(it, Align.CENTER, TextStyle.BOLD) }
        branding.addressLines.filter { it.isNotBlank() }.forEach { out += Text(it, Align.CENTER) }
        if (branding.taxId.isNotBlank()) out += Text("${branding.taxIdLabel} ${branding.taxId}".trim(), Align.CENTER)
        branding.phone.takeIf { it.isNotBlank() }?.let { out += Text(it, Align.CENTER) }
        if (out.size > start) out += Blank
        if (title.isNotBlank()) out += Text(title, Align.CENTER, TextStyle.BOLD)
    }

    private fun items(
        out: MutableList<ReceiptElement>,
        items: List<ReceiptItem>,
    ) {
        for (item in items) {
            out += Row(item.name, money.format(item.gross))
            if (item.quantity != 1) {
                out += Text(labels.quantityFormat.format(money.locale, item.quantity, money.format(item.unitPrice)))
            }
        }
    }

    private fun totals(
        out: MutableList<ReceiptElement>,
        receipt: SaleReceipt,
    ) {
        val taxed = receipt.breakdown.filter { it.amounts.tax != 0L }
        val total =
            Row(if (receipt.preAuthorisation) labels.amountHeld else labels.total, money.format(receipt.amounts.gross), TextStyle.BOLD)
        when (receipt.mode) {
            TaxMode.EXCLUSIVE -> {
                out += Row(labels.subtotal, money.format(receipt.amounts.net))
                if (options.showTaxBreakdown) {
                    taxed.forEach { out += Row(it.tax.label(), money.format(it.amounts.tax)) }
                } else if (receipt.amounts.tax != 0L) {
                    out += Row(labels.tax, money.format(receipt.amounts.tax))
                }
                out += total
            }

            TaxMode.INCLUSIVE -> {
                out += total
                if (options.showTaxBreakdown) {
                    taxed.forEach {
                        out +=
                            Row(labels.includesTaxFormat.format(money.locale, it.tax.label()), money.format(it.amounts.tax))
                    }
                }
            }
        }
    }

    private fun cardReceipt(
        out: MutableList<ReceiptElement>,
        lines: List<CardReceiptLine>,
    ) {
        if (lines.isEmpty()) return
        out += Divider
        val ownHeader = branding.businessName.isNotBlank()
        for (line in lines) {
            if (ownHeader && line.key in ADYEN_HEADER_KEYS) continue
            val style = if (line.bold) TextStyle.BOLD else TextStyle.NORMAL
            val name = line.name.orEmpty()
            val value = line.value.orEmpty()
            out +=
                when {
                    name.isBlank() && value.isBlank() -> Blank
                    value.isBlank() -> Text(name, Align.CENTER, style)
                    else -> Row(name, value, style)
                }
        }
    }

    private fun footer(out: MutableList<ReceiptElement>) {
        val lines =
            branding.footer
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return
        out += Blank
        lines.forEach { out += Text(it, Align.CENTER) }
    }

    private companion object {
        /** Merchant name/address lines configured in the Customer Area; redundant when we print our own header. */
        val ADYEN_HEADER_KEYS = setOf("header1", "header2")
    }
}
