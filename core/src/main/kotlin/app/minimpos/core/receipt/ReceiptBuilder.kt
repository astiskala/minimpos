package app.minimpos.core.receipt

import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.receipt.ReceiptElement.Blank
import app.minimpos.core.receipt.ReceiptElement.Divider
import app.minimpos.core.receipt.ReceiptElement.Qr
import app.minimpos.core.receipt.ReceiptElement.Row
import app.minimpos.core.receipt.ReceiptElement.Text
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.tax.TaxRates

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
     * code. A pre-authorisation is titled and totalled as an amount held instead, with the refund QR code only once it
     * is captured, followed by what it holds after an adjustment and what was captured. [SaleReceipt.tip] adds the tip
     * lines after the totals: blank ones to fill in (and on the merchant copy a line to sign), or the tip entered and the
     * total with it. A sale still to be paid through its payment link ([SaleReceipt.unpaidLink]) is marked unpaid and
     * totalled as the amount due, with the link's QR code, its address and how long it works; one paid through it says
     * so. [SaleReceipt.notice] comes first. A note the receipt already carries is never printed again, so standings and
     * notices that say the same thing appear once.
     */
    fun sale(
        receipt: SaleReceipt,
        copy: ReceiptCopy = ReceiptCopy.CUSTOMER,
    ): ReceiptDocument {
        val out = mutableListOf<ReceiptElement>()
        receipt.notice?.let { note(out, it) }
        header(out, title = if (receipt.preAuthorisation) labels.preAuthTitle else branding.title)
        if (copy == ReceiptCopy.MERCHANT) out += Text(labels.merchantCopy, Align.CENTER, TextStyle.BOLD)
        if (out.isNotEmpty() && out.last() != Blank) out += Blank
        out += Row(labels.date, receipt.dateTime)
        if (options.showReferences) {
            out += Row(labels.reference, receipt.reference)
            receipt.customerReference?.takeIf { it.isNotBlank() }?.let { out += Row(labels.customer, it) }
        }
        out += Divider
        items(out, receipt.items)
        out += Divider
        totals(out, receipt)
        modifications(out, receipt, copy)
        standing(out, receipt)
        if (receipt.cardSaved) note(out, labels.cardSaved, TextStyle.NORMAL)
        cardReceipt(out, receipt.cardReceipt)
        footer(out)
        val refundable = !receipt.preAuthorisation || receipt.captured != null
        val showsRefundQr = copy == ReceiptCopy.CUSTOMER && receipt.approved && refundable && options.showRefundQr
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
        if (out.isNotEmpty() && out.last() != Blank) out += Blank
        out += Row(labels.date, receipt.dateTime)
        if (options.showReferences) out += Row(labels.reference, receipt.reference)
        val original = receipt.originalReference
        original?.let { out += Row(if (cancellation) labels.cancelledReference else labels.originalReference, it) }
        out += Divider
        refundBody(out, receipt)
        out += Divider
        out += Row(if (cancellation) labels.released else labels.refundTotal, money.format(receipt.amount), TextStyle.BOLD)
        // An unreferenced refund returns no recorded payment's surcharge or tip.
        if (!cancellation && original != null) {
            out += Row(labels.surcharge, receipt.surchargeMinor?.let(money::format) ?: labels.unreported)
            receipt.tipMinor?.let { out += Row(labels.tip, money.format(it)) }
        }
        receipt.standingNote?.let { note(out, it) }
        cardReceipt(out, receipt.cardReceipt)
        footer(out)
        return ReceiptDocument(out)
    }

    // What was returned: the cancellation note, an unreferenced refund's notes, its items, or a full or partial refund.
    private fun refundBody(
        out: MutableList<ReceiptElement>,
        receipt: RefundReceipt,
    ) {
        when {
            receipt.cancellation -> out += Text(labels.cancellationNote)
            receipt.originalReference == null -> receipt.unreferencedNotes.forEach { out += Text(it) }
            receipt.items.isEmpty() -> out += Text(if (receipt.full) labels.fullRefund else labels.partialRefund)
            else -> items(out, receipt.items)
        }
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
        var hasMarkedTaxRate = false
        for (item in items) {
            val name =
                if (item.taxRateMilliPercent ==
                    options.markedTaxRateMilliPercent
                ) {
                    hasMarkedTaxRate = true
                    "${item.name} ${options.markedTaxRateMarker}"
                } else {
                    item.name
                }
            out += Row(name, money.format(item.gross))
            if (item.quantity != 1) {
                out += Text(labels.quantityFormat.format(money.locale, item.quantity, money.format(item.unitPrice)))
            }
        }
        if (hasMarkedTaxRate && labels.markedTaxRateNote.isNotBlank()) {
            out += Text(labels.markedTaxRateNote)
        }
    }

    private fun totals(
        out: MutableList<ReceiptElement>,
        receipt: SaleReceipt,
    ) {
        val taxed = receipt.breakdown.filter { it.amounts.tax != 0L }
        val label =
            when {
                receipt.preAuthorisation && !receipt.holdCancelled -> labels.amountHeld
                receipt.tip != null -> labels.amount
                receipt.unpaidLink != null -> labels.amountDue
                else -> labels.total
            }
        val total = Row(label, money.format(receipt.amounts.gross), TextStyle.BOLD)
        when (receipt.mode) {
            TaxMode.EXCLUSIVE -> {
                out += Row(labels.subtotal, money.format(receipt.amounts.net))
                if (options.showTaxBreakdown && options.showTaxAmounts) {
                    taxed.forEach { out += Row(it.tax.label(), money.format(it.amounts.tax)) }
                } else if (options.showTaxAmounts && receipt.amounts.tax != 0L) {
                    out += Row(labels.tax, money.format(receipt.amounts.tax))
                }
                out += total
            }

            TaxMode.INCLUSIVE -> {
                out += total
                if (options.showTaxBreakdown && options.showTaxAmounts) {
                    taxed.forEach {
                        out +=
                            Row(labels.includesTaxFormat.format(money.locale, it.tax.label()), money.format(it.amounts.tax))
                    }
                }
            }
        }
        taxRateTotals(out, receipt)
    }

    private fun taxRateTotals(
        out: MutableList<ReceiptElement>,
        receipt: SaleReceipt,
    ) {
        if (!options.showTaxRateTotals) return
        val inclusive = receipt.mode == TaxMode.INCLUSIVE
        val format = if (inclusive) labels.taxableGrossFormat else labels.taxableNetFormat
        receipt.breakdown.groupBy { it.tax.rateMilliPercent }.toSortedMap(compareByDescending { it }).forEach { (rate, groups) ->
            out +=
                Row(
                    format.format(money.locale, "${TaxRates.format(rate)}%"),
                    money.format(groups.sumOf { if (inclusive) it.amounts.gross else it.amounts.net }),
                )
        }
    }

    /** A pre-authorisation's adjusted and captured amounts, and the tip lines. */
    private fun modifications(
        out: MutableList<ReceiptElement>,
        receipt: SaleReceipt,
        copy: ReceiptCopy,
    ) {
        if (receipt.discloseSurcharge) {
            receipt.purchaseMinor?.let { out += Row(labels.purchase, money.format(it)) }
            out += Row(labels.surcharge, receipt.surchargeMinor?.let(money::format) ?: labels.unreported)
        }
        receipt.heldNow?.let { out += Row(labels.heldNow, money.format(it)) }
        receipt.captured?.let { out += Row(labels.captured, money.format(it), TextStyle.BOLD) }
        when (val tip = receipt.tip) {
            null -> {}

            TipLines.Blank -> {
                // Blank lines leave room to write on the paper.
                out += Blank
                out += Row(labels.tip, WRITE_IN)
                out += Blank
                out += Row(labels.tipTotal, WRITE_IN, TextStyle.BOLD)
                if (copy == ReceiptCopy.MERCHANT) {
                    out += Blank
                    out += Blank
                    out += Row(labels.signature, SIGN_HERE)
                }
            }

            is TipLines.Entered -> {
                out += Row(labels.tip, money.format(tip.tip))
                if (receipt.chargedAmount == null) {
                    out += Row(labels.tipTotal, money.format(Math.addExact(receipt.amounts.gross, tip.tip)), TextStyle.BOLD)
                }
            }
        }
        receipt.chargedAmount?.let { out += Row(labels.charged, money.format(it), TextStyle.BOLD) }
        tenders(out, receipt)
    }

    /** The tender breakdown of a split or cash sale, and any balance still due. */
    private fun tenders(
        out: MutableList<ReceiptElement>,
        receipt: SaleReceipt,
    ) {
        val tenders = receipt.tenders
        if (tenders.size > 1 || tenders.any { it.kind != ReceiptTenderKind.CARD }) {
            tenders.forEach { tender ->
                val label =
                    when (tender.kind) {
                        ReceiptTenderKind.CARD -> listOfNotNull(labels.tenderCard, tender.detail).joinToString(" ")
                        ReceiptTenderKind.CASH -> labels.tenderCash
                        ReceiptTenderKind.GIFT_CARD -> listOfNotNull(labels.tenderGiftCard, tender.detail).joinToString(" ")
                        ReceiptTenderKind.MEAL_VOUCHER -> listOfNotNull(labels.tenderMealVoucher, tender.detail).joinToString(" ")
                    }
                out += Row(label, money.format(tender.amountMinor))
                tender.receivedMinor?.let { out += Row(labels.cashReceived, money.format(it)) }
                tender.changeMinor?.let { out += Row(labels.change, money.format(it)) }
            }
        }
        receipt.cardPlans.forEach { out += Text(it) }
        receipt.balanceDueMinor?.takeIf { it > 0 }?.let { out += Row(labels.balanceDue, money.format(it), TextStyle.BOLD) }
    }

    /**
     * What the payment's state adds under the totals: the payment link of an unpaid sale, the warning on one not
     * approved, the note that a pre-authorisation charged nothing yet, or that the sale was paid online.
     */
    private fun standing(
        out: MutableList<ReceiptElement>,
        receipt: SaleReceipt,
    ) {
        val link = receipt.unpaidLink
        when {
            link != null -> {
                unpaid(out, link)
            }

            !receipt.approved -> {
                note(out, labels.notCompleted, blankBefore = true)
            }

            receipt.preAuthorisation && receipt.captured == null && !receipt.holdCancelled && receipt.standingNote == null -> {
                note(out, labels.preAuthNote, TextStyle.NORMAL)
            }
        }
        receipt.standingNote?.let { note(out, it) }
        if (receipt.paidOnline) note(out, labels.paidOnline, TextStyle.NORMAL)
    }

    /**
     * Adds the centred note [text] in [style] (after a blank line when [blankBefore]), unless the receipt already says
     * exactly that: one meaning, such as a payment that was not completed, is printed once however many parts of the
     * sale's standing report it. Adyen's card receipt lines are added later and printed verbatim.
     */
    private fun note(
        out: MutableList<ReceiptElement>,
        text: String,
        style: TextStyle = TextStyle.BOLD,
        blankBefore: Boolean = false,
    ) {
        if (text.isBlank() || out.any { it is Text && it.text.trim() == text.trim() }) return
        if (blankBefore) out += Blank
        out += Text(text, Align.CENTER, style)
    }

    /** The unpaid mark, then the payment [link] as a QR code to scan and as an address to open, and how long it works. */
    private fun unpaid(
        out: MutableList<ReceiptElement>,
        link: UnpaidLink,
    ) {
        out += Blank
        out += Text(labels.unpaid, Align.CENTER, TextStyle.BOLD)
        out += Qr(link.url, labels.payLinkCaption)
        out += Text(labels.payLinkIntro, Align.CENTER)
        out += ReceiptElement.Link(link.url, labels.payNow)
        link.validUntil?.let { out += Text(labels.linkValidFormat.format(money.locale, it), Align.CENTER) }
    }

    /** A stored-value receipt: the activity, its card and amounts, the balance, its outcome and Adyen's receipts. */
    fun giftCard(receipt: GiftCardReceipt): ReceiptDocument {
        val out = mutableListOf<ReceiptElement>()
        header(out, title = receipt.title)
        if (out.isNotEmpty() && out.last() != Blank) out += Blank
        out += Row(labels.date, receipt.dateTime)
        if (options.showReferences) out += Row(labels.reference, receipt.reference)
        receipt.card?.let { out += Row(labels.giftCard, it) }
        out += Divider
        receipt.lines.forEach { out += Row(it.label, money.format(it.amountMinor), if (it.bold) TextStyle.BOLD else TextStyle.NORMAL) }
        receipt.balanceMinor?.let { out += Row(labels.balance, money.format(it)) }
        receipt.standingNote?.let { note(out, it, blankBefore = true) }
        receipt.cardReceipts.forEach { cardReceipt(out, it) }
        footer(out)
        return ReceiptDocument(out)
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
}

/** Merchant name/address lines configured in the Customer Area; redundant when we print our own header. */
private val ADYEN_HEADER_KEYS = setOf("header1", "header2")

/** A line to write an amount on; fits next to its label on a 24-character roll. */
private val WRITE_IN = "_".repeat(14)

/** A line to sign on. */
private val SIGN_HERE = "_".repeat(20)
