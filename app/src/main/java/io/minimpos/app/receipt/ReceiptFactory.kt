package io.minimpos.app.receipt

import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.ReceiptLinesJson
import io.minimpos.app.data.settings.ReceiptSettings
import io.minimpos.app.refund.RefundablePayment
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.TaxBreakdown
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.money.MoneyFormatter
import io.minimpos.core.receipt.ReceiptBranding
import io.minimpos.core.receipt.ReceiptBuilder
import io.minimpos.core.receipt.ReceiptCopy
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.receipt.ReceiptItem
import io.minimpos.core.receipt.ReceiptLabels
import io.minimpos.core.receipt.ReceiptOptions
import io.minimpos.core.receipt.RefundReceipt
import io.minimpos.core.receipt.SaleReceipt
import io.minimpos.core.tax.TaxAmounts
import io.minimpos.core.tax.TaxMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Turns stored sales and refunds into receipt documents using the current receipt settings. Dates and amounts use the
 * device's current locale and time zone, read on each call.
 */
class ReceiptFactory(
    private val labels: ReceiptLabels,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /** [epochMillis] as a short localised date and time, as printed on receipts and shown in history. */
    fun formatDateTime(epochMillis: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(locale())
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone()))

    /**
     * The receipt of [record]: its items, taxes per rate (highest rate first) and the card receipt lines of [copy].
     * A sale that can be refunded gets a refund QR code ([RefundablePayment.qrCode]); unapproved ones are marked as not
     * completed, and pre-authorisations are titled and totalled as an amount held.
     */
    fun sale(
        record: SaleWithLines,
        settings: ReceiptSettings,
        copy: ReceiptCopy = ReceiptCopy.CUSTOMER,
    ): ReceiptDocument {
        val sale = record.sale
        val currency = CurrencySpec.of(sale.currency)
        val lines = record.sortedLines
        val breakdown =
            lines
                .groupBy { AppliedTax(it.taxName, it.taxRateMilliPercent) }
                .map { (tax, group) ->
                    TaxBreakdown(tax, TaxAmounts(group.sumOf { it.netMinor }, group.sumOf { it.taxMinor }, group.sumOf { it.grossMinor }))
                }.sortedWith(compareByDescending<TaxBreakdown> { it.tax.rateMilliPercent }.thenBy { it.tax.name })
        val approved = sale.status == SaleStatus.APPROVED
        val receipt =
            SaleReceipt(
                reference = sale.merchantReference,
                customerReference = sale.customerReference,
                dateTime = formatDateTime(sale.createdAt),
                mode = runCatching { TaxMode.valueOf(sale.taxMode) }.getOrDefault(TaxMode.INCLUSIVE),
                items = lines.map { ReceiptItem(it.name, it.quantity, it.unitPriceMinor, it.grossMinor) },
                amounts = TaxAmounts(sale.netMinor, sale.taxMinor, sale.totalMinor),
                breakdown = breakdown,
                approved = approved,
                cardReceipt =
                    ReceiptLinesJson.decode(
                        if (copy == ReceiptCopy.CUSTOMER) {
                            sale.customerReceiptJson
                        } else {
                            sale.cashierReceiptJson
                        },
                    ),
                refundQr = RefundablePayment.qrCode(record),
                cardSaved = sale.storedPaymentMethodId != null,
                preAuthorisation = sale.kind == SaleKind.PRE_AUTHORISATION,
            )
        return builder(settings, currency).sale(receipt, copy)
    }

    /**
     * The receipt of [refund], referring to the original sale by its merchant reference (or, for a payment known only
     * from its QR code without one, its transaction ID). The cancellation of a pre-authorisation is labelled as one.
     */
    fun refund(
        refund: RefundEntity,
        settings: ReceiptSettings,
    ): ReceiptDocument {
        val receipt =
            RefundReceipt(
                reference = refund.merchantReference,
                originalReference = refund.originalReference ?: refund.originalTransactionId,
                dateTime = formatDateTime(refund.createdAt),
                items =
                    ReceiptLinesJson.decodeRefunded(refund.linesJson).map {
                        ReceiptItem(it.name, it.quantity, it.unitPriceMinor, it.grossMinor)
                    },
                amount = refund.amountMinor,
                cardReceipt = ReceiptLinesJson.decode(refund.customerReceiptJson),
                cancellation = refund.cancellation,
            )
        return builder(settings, CurrencySpec.of(refund.currency)).refund(receipt)
    }

    private fun builder(
        settings: ReceiptSettings,
        currency: CurrencySpec,
    ) = ReceiptBuilder(
        branding =
            ReceiptBranding(
                businessName = settings.businessName.trim(),
                addressLines = settings.addressLines.lines().map { it.trim() },
                taxIdLabel = settings.taxIdLabel.trim(),
                taxId = settings.taxId.trim(),
                phone = settings.phone.trim(),
                title = settings.title.trim(),
                footer = settings.footer,
            ),
        options = ReceiptOptions(settings.showTaxBreakdown, settings.showReferences, settings.showRefundQr),
        labels = labels,
        money = MoneyFormatter(currency, locale()),
    )
}
