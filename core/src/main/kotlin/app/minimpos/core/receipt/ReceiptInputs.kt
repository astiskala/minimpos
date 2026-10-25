package app.minimpos.core.receipt

import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.TaxBreakdown
import app.minimpos.core.tax.TaxAmounts
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.tax.TaxRates
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
 * @property showTaxBreakdown List tax amounts per rate; when off, tax-exclusive receipts show one tax line if
 *   [showTaxAmounts], and tax-inclusive receipts none. Taxable totals are controlled by [showTaxRateTotals].
 * @property showReferences print the merchant reference and, on sale receipts, the customer reference.
 * @property showRefundQr print the refund QR code on approved sales' customer copies.
 * @property showTaxAmounts Print tax amounts without changing payment totals.
 * @property markedTaxRateMilliPercent Rate whose items get a mark, in thousandths of a percent; null marks none.
 * @property showTaxRateTotals Print taxable totals grouped by numeric rate, independently of tax amounts.
 * @property markedTaxRateMarker Text appended to marked item names.
 */
data class ReceiptOptions(
    val showTaxBreakdown: Boolean = true,
    val showReferences: Boolean = true,
    val showRefundQr: Boolean = true,
    val showTaxAmounts: Boolean = true,
    val markedTaxRateMilliPercent: Int? = null,
    val showTaxRateTotals: Boolean = false,
    val markedTaxRateMarker: String = "※",
)

/**
 * Every user-visible string the builder emits, so the app can supply localised resources. The defaults are the
 * English texts. Format strings are applied with the [app.minimpos.core.money.MoneyFormatter]'s locale.
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
 * @property fullRefund the text shown instead of items for a refund of the whole remaining amount without its items.
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
 * @property taxableGrossFormat Taxable total row on inclusive receipts; `%s` is the numeric tax rate with a percent sign.
 * @property taxableNetFormat The equivalent tax-exclusive row.
 * @property amountDue the label of the total of a sale still to be paid through a payment link, instead of [total].
 * @property unpaid the heading that marks the receipt of a sale still to be paid through a payment link.
 * @property payLinkCaption the caption over the payment link's QR code.
 * @property payLinkIntro the line above the payment link's address.
 * @property payNow the email button that opens the payment link.
 * @property linkValidFormat the line under the payment link; `%s` is when it stops working, already formatted.
 * @property paidOnline the note on the receipt of a sale paid through a payment link.
 * @property markedTaxRateNote Explanation of marked items, printed after them; blank omits the explanation.
 * @property surcharge Label of the fee component.
 * @property unreported Unavailable component, distinct from zero.
 * @property charged Total charged across original tenders.
 * @property purchase Purchase component excluding tips, surcharge and cash movement.
 * @property tenderCard Label of a card tender in the breakdown, followed by its brand and masked number.
 * @property tenderCash Label of a cash tender in the breakdown.
 * @property cashReceived Label of the cash received for a cash tender.
 * @property change Label of the change handed back for a cash tender.
 * @property balanceDue Label of the purchase balance still due on a partially paid sale.
 * @property cashReturned Note on the receipt of cash handed back for a cash tender, instead of a request outcome.
 * @property tenderGiftCard Label of a gift-card tender in the breakdown, followed by its provider and last digits.
 * @property giftCard Label of the gift card a stored-value receipt is for.
 * @property balance Label of a gift card's balance as last reported.
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
    val fullRefund: String = "Full refund",
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
    val taxableGrossFormat: String = "%s taxable (incl. tax)",
    val taxableNetFormat: String = "%s taxable (excl. tax)",
    val amountDue: String = "AMOUNT DUE",
    val unpaid: String = "UNPAID",
    val payLinkCaption: String = "Scan to pay",
    val payLinkIntro: String = "Or open this link:",
    val payNow: String = "Pay now",
    val linkValidFormat: String = "Link valid until %s",
    val paidOnline: String = "Paid online",
    val markedTaxRateNote: String = "※ Items at the marked tax rate",
    /** Fee component already included in the charged amount. */
    val surcharge: String = "Surcharge",
    /** A fee that was not recorded or reported, distinct from zero. */
    val unreported: String = "Unreported",
    /** Total charged across the original tenders. */
    val charged: String = "CHARGED",
    /** Purchase component excluding tip, fee and cash movement. */
    val purchase: String = "Purchase",
    /** A card tender in the breakdown. */
    val tenderCard: String = "Card",
    /** A cash tender in the breakdown. */
    val tenderCash: String = "Cash",
    /** Cash the shopper handed over for a cash tender. */
    val cashReceived: String = "Cash received",
    /** Change handed back for a cash tender. */
    val change: String = "Change",
    /** Purchase balance still to be paid on a partially paid sale. */
    val balanceDue: String = "BALANCE DUE",
    /** Note on the receipt of cash handed back for a cash tender. */
    val cashReturned: String = "Cash returned",
    /** A gift-card tender in the breakdown. */
    val tenderGiftCard: String = "Gift card",
    /** The gift card a stored-value receipt is for. */
    val giftCard: String = "Gift card",
    /** A gift card's balance as last reported. */
    val balance: String = "Balance",
)

/**
 * The payment link of a sale that is still to be paid through it.
 *
 * @property url the link the shopper opens.
 * @property validUntil when it stops working, already formatted for the locale; null when unknown.
 */
data class UnpaidLink(
    val url: String,
    val validUntil: String?,
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
 * @property taxRateMilliPercent Applied rate in thousandths of a percent; 0 for items with no tax.
 */
data class ReceiptItem(
    val name: String,
    val quantity: Int,
    val unitPrice: Long,
    val gross: Long,
    val taxRateMilliPercent: Int = 0,
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
 * @property breakdown the per-rate tax totals; tax rows with no tax are omitted, but optional per-rate amount rows
 *   still include those rates.
 * @property approved false for payments that failed or whose outcome is unknown, which get a warning and no refund
 *   QR code.
 * @property cardReceipt Adyen's receipt lines for this copy (customer or cashier receipt), printed verbatim.
 * @property refundQr the encoded `RefundQrPayload` to print on the customer copy, or null for none.
 * @property cardSaved whether the shopper's card was stored for future payments, which adds a note.
 * @property preAuthorisation whether the payment only held the amount (a pre-authorisation, captured later): the
 *   receipt is titled [ReceiptLabels.preAuthTitle], shows the [ReceiptLabels.amountHeld] and a refund QR code only once [captured].
 * @property tip the tip lines of a sale taken for tipping on the receipt, or null for none.
 * @property heldNow what a pre-authorisation holds after an adjustment, in minor units; null when it was not adjusted.
 * @property captured what was captured of a pre-authorisation, in minor units; null until it is captured, after which
 *   the note that nothing has been charged is left out.
 * @property unpaidLink the payment link of a sale still to be paid through it, or null: the receipt is then marked
 *   [ReceiptLabels.unpaid] (rather than not completed), totalled as [ReceiptLabels.amountDue], and shows the link and
 *   its QR code.
 * @property paidOnline whether the sale was paid through a payment link, which adds [ReceiptLabels.paidOnline].
 * @property standingNote Current localized payment standing; null for a normal charged sale.
 * @property holdCancelled The original amount is no longer held.
 * @property notice A warning printed first, in bold, such as a demo receipt's note that no money moved; null for none.
 * @property surchargeMinor Reported fee in minor units; null when unreported.
 * @property chargedAmount Total authorized across tenders; null for old or non-terminal receipts.
 * @property purchaseMinor Known purchase component in minor units; null when unreported.
 * @property discloseSurcharge Whether the original fee component, including unknown, must be shown.
 * @property tenders Approved tenders in payment order, printed as a breakdown when there are several or any is cash.
 * @property balanceDueMinor Purchase balance still due in minor units, printed as [ReceiptLabels.balanceDue]; null
 *   when nothing is due.
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
    val unpaidLink: UnpaidLink? = null,
    val paidOnline: Boolean = false,
    /** Current localized payment standing, distinct from the terminal's original answer; null for a normal paid sale. */
    val standingNote: String? = null,
    /** The hold was canceled, so its original amount is no longer shown as held. */
    val holdCancelled: Boolean = false,
    val notice: String? = null,
    /** Reported fee component, in minor units; null is unknown when [chargedAmount] is present. */
    val surchargeMinor: Long? = null,
    /** Authorized total including all components; null when not supplied, preserving historical totals. */
    val chargedAmount: Long? = null,
    /** Known purchase component of the charged amount; null when fee breakdown is unavailable. */
    val purchaseMinor: Long? = null,
    /** Show an explicit unknown fee for historical terminal payments. */
    val discloseSurcharge: Boolean = false,
    val tenders: List<ReceiptTender> = emptyList(),
    val balanceDueMinor: Long? = null,
)

/** How one tender of a sale was paid, as its receipt breakdown names it. */
enum class ReceiptTenderKind {
    /** A card or wallet on the terminal, whose own Adyen receipt lines follow verbatim. */
    CARD,

    /** Cash recorded locally, with what was received and the change. */
    CASH,

    /** A gift card redeemed on the terminal, whose own Adyen receipt lines follow verbatim. */
    GIFT_CARD,
}

/**
 * One approved tender in a sale receipt's breakdown.
 *
 * @property kind How it was paid.
 * @property detail Card brand and masked number as the terminal reported them; null for cash or when unreported.
 * @property amountMinor Amount the tender collected (for a card including its tip and fee), in minor units.
 * @property receivedMinor Cash received; null for a card.
 * @property changeMinor Change handed back; null for a card.
 */
data class ReceiptTender(
    val kind: ReceiptTenderKind,
    val detail: String?,
    val amountMinor: Long,
    val receivedMinor: Long? = null,
    val changeMinor: Long? = null,
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
 * @property full Whether the whole local remaining amount was reversed.
 * @property standingNote Current localized request outcome; null for none.
 * @property surchargeMinor Fee included in the returned amount; null when unknown.
 * @property tipMinor Tip included in the returned amount; null when unknown.
 */
data class RefundReceipt(
    val reference: String,
    val originalReference: String,
    val dateTime: String,
    val items: List<ReceiptItem>,
    val amount: Long,
    val cardReceipt: List<CardReceiptLine>,
    val cancellation: Boolean = false,
    /** True when the whole locally known remaining payment was reversed. */
    val full: Boolean = false,
    /** Current localized request outcome; null for no additional note. */
    val standingNote: String? = null,
    /** Surcharge included in [amount]; null for unknown historical allocations. */
    val surchargeMinor: Long? = null,
    /** Tip included in [amount]; null for unknown historical allocations. */
    val tipMinor: Long? = null,
)

/** "GST 10%" - or the name as-is when the merchant already put the rate in it. */
fun AppliedTax.label(): String = if ('%' in name) name else "$name ${TaxRates.format(rateMilliPercent)}%".trim()

/**
 * One amount line on a stored-value receipt.
 *
 * @property label What it is, already localized, such as the value loaded or the cash handed over.
 * @property amountMinor In minor units of the receipt's currency.
 * @property bold Whether it is the receipt's main amount.
 */
data class GiftCardReceiptLine(
    val label: String,
    val amountMinor: Long,
    val bold: Boolean = false,
)

/**
 * The data for a stored-value (gift-card) receipt: never a sale or refund receipt.
 *
 * @property title The activity, already localized, such as "GIFT CARD LOAD".
 * @property reference The activity's merchant reference.
 * @property dateTime When it began, already formatted for the locale.
 * @property card Provider and last digits of the gift card; null when unknown.
 * @property lines Its amounts in order.
 * @property balanceMinor The card's balance as last reported, in minor units; null when unreported.
 * @property standingNote Current localized outcome; null for none.
 * @property cardReceipts Adyen's receipt lines of each answered stage, printed verbatim in order.
 */
data class GiftCardReceipt(
    val title: String,
    val reference: String,
    val dateTime: String,
    val card: String?,
    val lines: List<GiftCardReceiptLine>,
    val balanceMinor: Long?,
    val standingNote: String?,
    val cardReceipts: List<List<CardReceiptLine>>,
)
