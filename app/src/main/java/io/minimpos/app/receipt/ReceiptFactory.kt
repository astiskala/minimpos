package io.minimpos.app.receipt

import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.ReceiptLinesJson
import io.minimpos.app.data.settings.ReceiptSettings
import io.minimpos.app.refund.RefundablePayment
import io.minimpos.app.refund.standing
import io.minimpos.core.cart.AppliedTax
import io.minimpos.core.cart.TaxBreakdown
import io.minimpos.core.codec.RefundQrPayload
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
import io.minimpos.core.receipt.TipLines
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
     * completed, and pre-authorisations are titled and totalled as an amount held, with what they hold after an
     * adjustment and what was captured. A sale taken for tipping on the receipt gets tip lines to fill in until its
     * tip is entered (only on [paper]: an emailed receipt leaves them out), then the tip and the total with it.
     */
    fun sale(
        record: SaleWithLines,
        settings: ReceiptSettings,
        copy: ReceiptCopy = ReceiptCopy.CUSTOMER,
        paper: Boolean = true,
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
                tip =
                    when {
                        !sale.tipOnReceipt -> null
                        sale.tipMinor != null -> TipLines.Entered(sale.tipMinor)
                        approved && paper -> TipLines.Blank
                        else -> null
                    },
                heldNow = sale.authorisedMinor?.takeIf { sale.kind == SaleKind.PRE_AUTHORISATION && it != sale.totalMinor },
                captured =
                    sale.capturedMinor?.takeIf { sale.kind == SaleKind.PRE_AUTHORISATION && sale.standing.captured },
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

    /**
     * A sample receipt with [settings] in [currency], as Settings previews and test-prints it: two items (one taxed at
     * 10%, one tax-free), totals in tax [mode], a customer reference when [withCustomerReference] (checkout asks for
     * one) and a refund QR code, dated now. The amounts are fixed, whatever the [mode].
     */
    fun sample(
        settings: ReceiptSettings,
        currency: CurrencySpec,
        mode: TaxMode,
        withCustomerReference: Boolean,
    ): ReceiptDocument {
        val receipt =
            SaleReceipt(
                reference = SAMPLE_REFERENCE,
                customerReference = SAMPLE_CUSTOMER.takeIf { withCustomerReference },
                dateTime = formatDateTime(System.currentTimeMillis()),
                mode = mode,
                items = SAMPLE_ITEMS,
                amounts = SAMPLE_TOTALS,
                breakdown = SAMPLE_BREAKDOWN,
                approved = true,
                cardReceipt = emptyList(),
                refundQr =
                    RefundQrPayload(SAMPLE_TRANSACTION_ID, SAMPLE_TIMESTAMP, SAMPLE_TOTALS.gross, currency.code, SAMPLE_REFERENCE).encode(),
                cardSaved = false,
            )
        return builder(settings, currency).sale(receipt, ReceiptCopy.CUSTOMER)
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

    private companion object {
        const val SAMPLE_REFERENCE = "MP-SAMPLE-0001"
        const val SAMPLE_CUSTOMER = "CUST-001"
        const val SAMPLE_TRANSACTION_ID = "SAMP001234567890123.SAMPLEPSP0000001"
        val SAMPLE_TIMESTAMP: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val SAMPLE_ITEMS = listOf(ReceiptItem("Flat white", 2, 450, 900), ReceiptItem("Custom item", 1, 300, 300))
        val SAMPLE_TOTALS = TaxAmounts(1_118, 82, 1_200)
        val SAMPLE_BREAKDOWN =
            listOf(
                TaxBreakdown(AppliedTax("GST", 10_000), TaxAmounts(818, 82, 900)),
                TaxBreakdown(AppliedTax("GST-free", 0), TaxAmounts(300, 0, 300)),
            )
    }
}
