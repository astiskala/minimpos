package io.minimpos.core.receipt

import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.TaxBreakdown
import io.minimpos.core.tax.TaxAmounts
import io.minimpos.core.tax.TaxMode
import io.minimpos.core.tax.TaxRates
import kotlinx.serialization.Serializable

/**
 * One line of Adyen-generated receipt data (PaymentReceipt.OutputText), already URL-decoded. Stored as JSON with the
 * sale, so the property names are persisted.
 *
 * @property key Adyen's identifier for the line, e.g. "header1" or "txdate"; null when the line has none.
 * @property name the label; printed centred on its own when there is no [value].
 * @property value the value printed flush right next to [name].
 * @property bold whether Adyen asked for the line in bold.
 */
@Serializable
data class CardReceiptLine(
    val key: String? = null,
    val name: String? = null,
    val value: String? = null,
    val bold: Boolean = false,
)

/**
 * The merchant's own receipt header and footer, from the receipt settings. Blank values are left out.
 *
 * @property businessName the trading name, printed bold at the top. When set, Adyen's own header lines (the name and
 *   address configured in the Customer Area) are dropped from the card receipt data.
 * @property addressLines the address, one entry per printed line.
 * @property taxIdLabel the name of the tax number, e.g. "ABN" or "VAT", printed before [taxId].
 * @property taxId the business's tax number; the tax id line is printed only when this is set.
 * @property phone a contact phone number.
 * @property title a heading under the header on sale receipts, e.g. "TAX INVOICE"; refund receipts use
 *   [ReceiptLabels.refundTitle] instead.
 * @property footer free text at the bottom, possibly several lines; lines are trimmed and empty ones dropped.
 */
data class ReceiptBranding(
    val businessName: String = "",
    val addressLines: List<String> = emptyList(),
    val taxIdLabel: String = "",
    val taxId: String = "",
    val phone: String = "",
    val title: String = "",
    val footer: String = "",
)

/**
 * Which optional parts receipts include, from the receipt settings.
 *
 * @property showTaxBreakdown list the tax per rate; when off, tax-exclusive receipts show one tax line and
 *   tax-inclusive receipts none.
 * @property showReferences print the merchant reference and, on sale receipts, the customer reference.
 * @property showRefundQr print the refund QR code on approved sales' customer copies.
 */
data class ReceiptOptions(
    val showTaxBreakdown: Boolean = true,
    val showReferences: Boolean = true,
    val showRefundQr: Boolean = true,
)

/**
 * Every user-visible string the builder emits, so the app can supply localised resources. The defaults are the
 * English texts. Format strings are applied with the [io.minimpos.core.money.MoneyFormatter]'s locale.
 *
 * @property date the label of the date and time row.
 * @property reference the label of the merchant reference row.
 * @property customer the label of the customer reference row.
 * @property subtotal the label of the net total on tax-exclusive receipts.
 * @property tax the label of the single tax line when the breakdown is off.
 * @property total the label of the amount paid.
 * @property includesTaxFormat the tax-inclusive breakdown line; `%s` is the rate's label, e.g. "Includes GST 10%".
 * @property quantityFormat the line under an item with a quantity other than one; `%d` is the quantity and `%s` the
 *   formatted unit price.
 * @property refundQrCaption the caption under the refund QR code.
 * @property merchantCopy the heading that marks the merchant's copy of a sale receipt.
 * @property refundTitle the heading of refund receipts.
 * @property originalReference the label of the refunded sale's reference on refund receipts.
 * @property refundTotal the label of the refunded amount.
 * @property partialRefund the text shown instead of items for a refund of an amount rather than of items.
 * @property cardSaved the note printed when the shopper's card was stored for future payments.
 * @property notCompleted the warning on receipts of payments that were not approved.
 * @property preAuthTitle the heading of pre-authorisation receipts, instead of [ReceiptBranding.title].
 * @property amountHeld the label of a pre-authorisation's total, instead of [total].
 * @property preAuthNote the note under a pre-authorisation's totals that nothing has been charged yet.
 * @property cancellationTitle the heading of the receipt of a cancelled payment that only held its amount.
 * @property cancelledReference the label of the cancelled payment's reference, instead of
 *   [originalReference].
 * @property cancellationNote the text shown instead of items on the receipt of a cancelled payment.
 * @property released the label of the amount a cancellation released, instead of [refundTotal].
 * @property amount the label of the bill on a receipt with tip lines, instead of [total].
 * @property tip the label of the tip line.
 * @property tipTotal the label of the bill plus the tip.
 * @property signature the label of the line the shopper signs on the merchant copy of a receipt awaiting a tip.
 * @property heldNow the label of the amount a pre-authorisation holds after an adjustment.
 * @property captured the label of the amount captured of a pre-authorisation.
 */
data class ReceiptLabels(
    val date: String = "Date",
    val reference: String = "Reference",
    val customer: String = "Customer",
    val subtotal: String = "Subtotal",
    val tax: String = "Tax",
    val total: String = "TOTAL",
    val includesTaxFormat: String = "Includes %s",
    val quantityFormat: String = "  %d x %s",
    val refundQrCaption: String = "Scan this code for returns",
    val merchantCopy: String = "MERCHANT COPY",
    val refundTitle: String = "REFUND",
    val originalReference: String = "Original sale",
    val refundTotal: String = "REFUND TOTAL",
    val partialRefund: String = "Partial refund",
    val cardSaved: String = "Card saved for future payments",
    val notCompleted: String = "PAYMENT NOT COMPLETED",
    val preAuthTitle: String = "PRE-AUTHORIZATION",
    val amountHeld: String = "AMOUNT HELD",
    val preAuthNote: String = "Held on the card, not charged yet",
    val cancellationTitle: String = "CANCELLATION",
    val cancelledReference: String = "Canceled payment",
    val cancellationNote: String = "Payment canceled",
    val released: String = "RELEASED",
    val amount: String = "AMOUNT",
    val tip: String = "TIP",
    val tipTotal: String = "TOTAL",
    val signature: String = "SIGNATURE",
    val heldNow: String = "HELD NOW",
    val captured: String = "CAPTURED",
)

/** The tip lines of a receipt for tipping on the receipt. */
sealed interface TipLines {
    /**
     * Empty tip and total lines for the shopper to fill in, and on the merchant copy a line to sign; the bill is
     * labelled [ReceiptLabels.amount].
     */
    data object Blank : TipLines

    /**
     * The tip written on the receipt and the resulting total.
     *
     * @property tip the tip in minor units; 0 when the shopper gave none.
     */
    data class Entered(
        val tip: Long,
    ) : TipLines
}

/**
 * An item line as printed; amounts are in the receipt currency's minor units.
 *
 * @property name the item name.
 * @property quantity the number of units; a quantity line is added when it is not one.
 * @property unitPrice the price of one unit, shown on the quantity line.
 * @property gross the tax-inclusive amount for the whole line, shown next to the name.
 */
data class ReceiptItem(
    val name: String,
    val quantity: Int,
    val unitPrice: Long,
    val gross: Long,
)

/** Which copy of a sale receipt to build; they differ in the Adyen card receipt data and the refund QR code. */
enum class ReceiptCopy {
    /** The shopper's copy, with the refund QR code when enabled. */
    CUSTOMER,

    /** The merchant's copy, marked with [ReceiptLabels.merchantCopy] and without a refund QR code. */
    MERCHANT,
}

/**
 * The data for a sale receipt; amounts are in the receipt currency's minor units.
 *
 * @property reference the merchant reference sent to Adyen.
 * @property customerReference the customer reference entered at checkout, or null (a blank one is not printed either).
 * @property dateTime the sale's date and time, already formatted for the locale.
 * @property mode whether prices included tax, which decides how totals and tax are laid out.
 * @property items the items sold.
 * @property amounts the sale's net, tax and gross totals.
 * @property breakdown the per-rate tax totals; rates with no tax are not printed.
 * @property approved false for payments that failed or whose outcome is unknown, which get a warning and no refund
 *   QR code.
 * @property cardReceipt Adyen's receipt lines for this copy (customer or cashier receipt), printed verbatim.
 * @property refundQr the encoded `RefundQrPayload` to print on the customer copy, or null for none.
 * @property cardSaved whether the shopper's card was stored for future payments, which adds a note.
 * @property preAuthorisation whether the payment only held the amount (a pre-authorisation, captured later): the
 *   receipt is titled [ReceiptLabels.preAuthTitle], shows the [ReceiptLabels.amountHeld] and never a refund QR code.
 * @property tip the tip lines of a sale taken for tipping on the receipt, or null for none.
 * @property heldNow what a pre-authorisation holds after an adjustment, in minor units; null when it was not adjusted.
 * @property captured what was captured of a pre-authorisation, in minor units; null until it is captured, after which
 *   the note that nothing has been charged is left out.
 */
data class SaleReceipt(
    val reference: String,
    val customerReference: String?,
    val dateTime: String,
    val mode: TaxMode,
    val items: List<ReceiptItem>,
    val amounts: TaxAmounts,
    val breakdown: List<TaxBreakdown>,
    val approved: Boolean,
    val cardReceipt: List<CardReceiptLine>,
    val refundQr: String?,
    val cardSaved: Boolean,
    val preAuthorisation: Boolean = false,
    val tip: TipLines? = null,
    val heldNow: Long? = null,
    val captured: Long? = null,
)

/**
 * The data for a refund receipt.
 *
 * @property reference the refund's own merchant reference.
 * @property originalReference the refunded sale's merchant reference (or its transaction id when that is unknown).
 * @property dateTime the refund's date and time, already formatted for the locale.
 * @property items the refunded items; empty for a refund of an amount, which prints [ReceiptLabels.partialRefund].
 * @property amount the refunded amount in minor units.
 * @property cardReceipt Adyen's receipt lines for the refund, printed verbatim.
 * @property cancellation whether this cancelled a payment that only held its amount (a pre-authorisation or a sale
 *   awaiting its tip) rather than refunding a sale, which the title, labels and [ReceiptLabels.cancellationNote]
 *   (instead of items) say.
 */
data class RefundReceipt(
    val reference: String,
    val originalReference: String,
    val dateTime: String,
    val items: List<ReceiptItem>,
    val amount: Long,
    val cardReceipt: List<CardReceiptLine>,
    val cancellation: Boolean = false,
)

/** "GST 10%" - or the name as-is when the merchant already put the rate in it. */
fun AppliedTax.label(): String = if ('%' in name) name else "$name ${TaxRates.format(rateMilliPercent)}%".trim()
