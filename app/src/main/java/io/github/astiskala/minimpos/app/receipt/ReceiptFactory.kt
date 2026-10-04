package io.github.astiskala.minimpos.app.receipt

import io.github.astiskala.minimpos.app.data.db.RefundEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleWithLines
import io.github.astiskala.minimpos.app.data.repo.ReceiptLinesJson
import io.github.astiskala.minimpos.app.data.settings.ReceiptSettings
import io.github.astiskala.minimpos.app.refund.PaymentStanding
import io.github.astiskala.minimpos.app.refund.ReceiptStanding
import io.github.astiskala.minimpos.app.refund.RefundablePayment
import io.github.astiskala.minimpos.core.cart.AppliedTax
import io.github.astiskala.minimpos.core.cart.TaxBreakdown
import io.github.astiskala.minimpos.core.codec.RefundQrPayload
import io.github.astiskala.minimpos.core.money.CurrencySpec
import io.github.astiskala.minimpos.core.money.MoneyFormatter
import io.github.astiskala.minimpos.core.receipt.Align
import io.github.astiskala.minimpos.core.receipt.ReceiptBranding
import io.github.astiskala.minimpos.core.receipt.ReceiptBuilder
import io.github.astiskala.minimpos.core.receipt.ReceiptCopy
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import io.github.astiskala.minimpos.core.receipt.ReceiptElement
import io.github.astiskala.minimpos.core.receipt.ReceiptItem
import io.github.astiskala.minimpos.core.receipt.ReceiptLabels
import io.github.astiskala.minimpos.core.receipt.ReceiptOptions
import io.github.astiskala.minimpos.core.receipt.RefundReceipt
import io.github.astiskala.minimpos.core.receipt.SaleReceipt
import io.github.astiskala.minimpos.core.receipt.TextStyle
import io.github.astiskala.minimpos.core.receipt.TipLines
import io.github.astiskala.minimpos.core.receipt.UnpaidLink
import io.github.astiskala.minimpos.core.tax.TaxAmounts
import io.github.astiskala.minimpos.core.tax.TaxMode
import io.github.astiskala.minimpos.core.tax.TaxRates
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Localised product and tax names used only in the sample receipt, not in stored sales.
 *
 * @property coffee The sample's first product.
 * @property custom The sample's second product.
 * @property taxed The sample's 10% tax rate name.
 * @property zero The sample's 0% tax rate name.
 */
data class ReceiptSampleTexts(
    val coffee: String = "Flat white",
    val custom: String = "Custom item",
    val taxed: String = "GST",
    val zero: String = "GST-free",
)

/**
 * Turns stored sales and refunds into receipt documents using the current receipt settings. Dates and amounts use the
 * device's current locale and time zone, read on each call.
 */
class ReceiptFactory(
    labels: ReceiptLabels,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Reads receipt labels in the current language, rather than caching them for the process lifetime. */
    private val currentLabels: () -> ReceiptLabels = { labels },
    /** Product and tax names for the sample receipt in the current language. */
    private val sampleTexts: () -> ReceiptSampleTexts = { ReceiptSampleTexts() },
    /** Reads payment-standing labels in the current language at delivery. */
    private val standingText: (PaymentStanding) -> String? = { it.name.takeUnless { name -> name == "CHARGED" } },
    /** Reads refund-request outcome labels in the current language at delivery. */
    private val refundText: (RefundStatus) -> String? = { it.name },
    /** Reads the permanent demo warning for simulated links in the current language at delivery. */
    private val simulationText: () -> String = { "Simulation only. No money moved." },
    /** Labels a demo QR/address without inviting the shopper to pay. */
    private val demoLinkText: () -> String = { "Demo link" },
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
     * tip is entered (only on [paper]: an emailed receipt leaves them out), then the tip and the total with it. A sale
     * still awaiting its payment link's payment is an unpaid receipt with the link and when it stops working; one paid
     * through it says it was paid online.
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
        val standing = ReceiptStanding.of(sale)
        val receipt =
            SaleReceipt(
                reference = sale.merchantReference,
                customerReference = sale.customerReference,
                dateTime = formatDateTime(sale.createdAt),
                mode = runCatching { TaxMode.valueOf(sale.taxMode) }.getOrDefault(TaxMode.INCLUSIVE),
                items = lines.map { ReceiptItem(it.name, it.quantity, it.unitPriceMinor, it.grossMinor, it.taxRateMilliPercent) },
                amounts = TaxAmounts(sale.netMinor, sale.taxMinor, sale.totalMinor),
                breakdown = breakdown,
                approved = standing.approved,
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
                preAuthorisation = standing.preAuthorisation,
                tip =
                    when {
                        standing.tipMinor != null -> TipLines.Entered(standing.tipMinor)
                        standing.awaitingTip && paper -> TipLines.Blank
                        else -> null
                    },
                heldNow = standing.heldNowMinor,
                captured = standing.capturedMinor,
                unpaidLink = standing.unpaidLink?.let { UnpaidLink(it, standing.linkExpiresAt?.let(::formatDateTime)) },
                paidOnline = standing.paidOnline,
                standingNote = standingText(standing.standing),
                holdCancelled = standing.standing == PaymentStanding.HOLD_CANCELLED,
            )
        val simulated = standing.simulatedLink
        val document = builder(settings, currency, simulated).sale(receipt, copy)
        return if (simulated) {
            ReceiptDocument(listOf(ReceiptElement.Text(simulationText(), Align.CENTER, TextStyle.BOLD)) + document.elements)
        } else {
            document
        }
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
                        ReceiptItem(it.name, it.quantity, it.unitPriceMinor, it.grossMinor, it.taxRateMilliPercent)
                    },
                amount = refund.amountMinor,
                cardReceipt = ReceiptLinesJson.decode(refund.customerReceiptJson),
                cancellation = refund.cancellation,
                full = refund.full,
                standingNote = refundText(refund.status),
            )
        return builder(settings, CurrencySpec.of(refund.currency)).refund(receipt)
    }

    /**
     * A sample receipt with [settings] in [currency], as Settings previews and test-prints it: two items (one taxed at
     * 10%, one at the configured marked rate or tax-free), totals in tax [mode], a customer reference when
     * [withCustomerReference] (checkout asks for one) and a refund QR code, dated now. Gross amounts are fixed,
     * whatever the [mode]; the second item demonstrates the configured marker and explanation.
     */
    fun sample(
        settings: ReceiptSettings,
        currency: CurrencySpec,
        mode: TaxMode,
        withCustomerReference: Boolean,
    ): ReceiptDocument {
        val texts = sampleTexts()
        val markedRate = settings.markedTaxRateMilliPercent ?: 0
        val customAmounts = TaxRates.apply(300, markedRate, TaxMode.INCLUSIVE)
        val receipt =
            SaleReceipt(
                reference = SAMPLE_REFERENCE,
                customerReference = SAMPLE_CUSTOMER.takeIf { withCustomerReference },
                dateTime = formatDateTime(System.currentTimeMillis()),
                mode = mode,
                items = listOf(ReceiptItem(texts.coffee, 2, 450, 900, 10_000), ReceiptItem(texts.custom, 1, 300, 300, markedRate)),
                amounts = TaxAmounts(818, 82, 900) + customAmounts,
                breakdown =
                    listOf(
                        TaxBreakdown(AppliedTax(texts.taxed, 10_000), TaxAmounts(818, 82, 900)),
                        TaxBreakdown(AppliedTax(if (markedRate == 0) texts.zero else texts.taxed, markedRate), customAmounts),
                    ),
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
        simulated: Boolean = false,
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
        options =
            ReceiptOptions(
                showTaxBreakdown = settings.showTaxBreakdown,
                showReferences = settings.showReferences,
                showRefundQr = settings.showRefundQr,
                showTaxAmounts = settings.showTaxAmounts,
                showTaxRateTotals = settings.showTaxRateTotals,
                markedTaxRateMilliPercent = settings.markedTaxRateMilliPercent,
                markedTaxRateMarker = settings.markedTaxRateMarker,
            ),
        labels =
            currentLabels().copy(markedTaxRateNote = settings.markedTaxRateNote).let {
                if (simulated) {
                    it.copy(
                        payLinkCaption = demoLinkText(),
                        payLinkIntro = demoLinkText(),
                        payNow = demoLinkText(),
                        paidOnline = simulationText(),
                    )
                } else {
                    it
                }
            },
        money = MoneyFormatter(currency, locale()),
    )

    private companion object {
        const val SAMPLE_REFERENCE = "MP-SAMPLE-0001"
        const val SAMPLE_CUSTOMER = "CUST-001"
        const val SAMPLE_TRANSACTION_ID = "SAMP001234567890123.SAMPLEPSP0000001"
        val SAMPLE_TIMESTAMP: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val SAMPLE_TOTALS = TaxAmounts(1_118, 82, 1_200)
    }
}
