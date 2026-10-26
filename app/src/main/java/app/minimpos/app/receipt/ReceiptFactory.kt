package app.minimpos.app.receipt

import app.minimpos.app.data.db.GiftCardStage
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.TenderKind
import app.minimpos.app.data.repo.GiftCardActivity
import app.minimpos.app.data.repo.ReceiptLinesJson
import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.history.HistoryReport
import app.minimpos.app.history.PaymentStanding
import app.minimpos.app.history.ReceiptStanding
import app.minimpos.app.history.RefundablePayment
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.TaxBreakdown
import app.minimpos.core.codec.RefundQrPayload
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.receipt.CardReceiptLine
import app.minimpos.core.receipt.GiftCardReceipt
import app.minimpos.core.receipt.GiftCardReceiptLine
import app.minimpos.core.receipt.ReceiptBranding
import app.minimpos.core.receipt.ReceiptBuilder
import app.minimpos.core.receipt.ReceiptCopy
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptItem
import app.minimpos.core.receipt.ReceiptLabels
import app.minimpos.core.receipt.ReceiptOptions
import app.minimpos.core.receipt.ReceiptTender
import app.minimpos.core.receipt.ReceiptTenderKind
import app.minimpos.core.receipt.RefundReceipt
import app.minimpos.core.receipt.SaleReceipt
import app.minimpos.core.receipt.TipLines
import app.minimpos.core.receipt.UnpaidLink
import app.minimpos.core.tax.TaxAmounts
import app.minimpos.core.tax.TaxMode
import app.minimpos.core.tax.TaxRates
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
 * Localised receipt, report and sample texts for one resolved language and locale, read at delivery time.
 *
 * @property labels Standard receipt line and section labels.
 * @property locale Formatting locale for numbers, currency symbols and dates.
 * @property sample Product and tax names for the settings preview receipt.
 * @property simulationNote Permanent demo warning for simulated links and seeded samples.
 * @property demoLink Non-payable demo label for simulated link QR codes and captions.
 * @property standing Localised payment-standing note; null when no note is shown, including for a payment that was not
 *   approved, whose warning the receipt builder prints itself.
 * @property refund Localised refund-request status note.
 * @property report Localised daily-report heading and row labels.
 * @property giftCardStage Localised name of a stored-value stage, such as "Load".
 * @property giftCardTitle Localised stored-value receipt title.
 * @property cashoutTitle Localized title for standalone card cash-out receipts.
 * @property giftCardAttention Localised note on an activity that still needs a check, confirmation or repair.
 * @property mexicanPlan Localized requested and returned plan text for a tender, empty for ordinary history.
 * @property cashback Requested and approved cash, card charge and recorded handover, localized at delivery.
 * @property mealVoucher Localized requested market and returned voucher facts for a tender, empty for other tenders.
 * @property unreferenced Localized lines of an unreferenced refund: no original payment, its reason, any synchronous
 *   acquirer answer and any cancellation request; empty for referenced refunds.
 */
data class ReceiptTexts(
    val labels: ReceiptLabels = ReceiptLabels(),
    val locale: Locale = Locale.getDefault(),
    val sample: ReceiptSampleTexts = ReceiptSampleTexts(),
    val simulationNote: String = "Simulation only. No money moved.",
    val demoLink: String = "Demo link",
    val standing: (PaymentStanding) -> String?,
    val refund: (RefundStatus) -> String?,
    val report: (ReportText) -> String,
    val giftCardStage: (GiftCardStage) -> String = { it.name },
    val giftCardTitle: String = "GIFT CARD",
    val cashoutTitle: String = "CASHOUT",
    val giftCardAttention: String = "Needs attention",
    val mexicanPlan: (SaleTenderEntity) -> List<String> = { emptyList() },
    val cashback: (SaleEntity) -> List<String> = { emptyList() },
    val mealVoucher: (SaleTenderEntity) -> List<String> = { emptyList() },
    val unreferenced: (RefundEntity) -> List<String> = { emptyList() },
)

/**
 * Turns stored sales and refunds into receipt documents using the current receipt settings. Dates and amounts use the
 * device's current locale and time zone, read on each call.
 *
 * @param texts Resolves localized receipt texts and formatting locale for a language tag (empty for the current app language).
 * @param zone Time zone used to format transaction and report timestamps.
 */
class ReceiptFactory(
    private val texts: (languageTag: String) -> ReceiptTexts,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val current: ReceiptTexts get() = texts("")

    /** Reuses [paper] for sharing unless receipt-tip blanks require a distinct electronic document. */
    fun electronic(
        record: SaleWithLines,
        settings: ReceiptSettings,
        paper: ReceiptDocument,
    ): ReceiptDocument =
        if (ReceiptStanding.of(record.sale).awaitingTip) sale(record, settings, surface = ReceiptSurface.ELECTRONIC) else paper

    /** Summary of retained processing-day activity; no payment receipt, QR, or settlement claim. */
    fun dailyReport(
        report: HistoryReport,
        settings: ReceiptSettings,
    ): ReceiptDocument {
        val resolved = current
        return DailyReport.document(report, settings, resolved.locale, resolved.report)
    }

    /** [epochMillis] as a short localised date and time, as printed on receipts and shown in history. */
    fun formatDateTime(epochMillis: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(current.locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone()))

    /**
     * The receipt of [record]: its items, taxes per rate (highest rate first) and the card receipt lines of [copy].
     * A sale that can be refunded gets a refund QR code ([RefundablePayment.qrCode]); unapproved ones are marked as not
     * completed, and pre-authorisations are titled and totalled as an amount held, with what they hold after an
     * adjustment and what was captured. A sale taken for tipping on the receipt gets tip lines to fill in until its
     * tip is entered (only on [ReceiptSurface.PAPER]: an emailed or shared receipt leaves them out), then the tip and the
     * total with it. A sale
     * still awaiting its payment link's payment is an unpaid receipt with the link and when it stops working; one paid
     * through it says it was paid online.
     */
    fun sale(
        record: SaleWithLines,
        settings: ReceiptSettings,
        copy: ReceiptCopy = ReceiptCopy.CUSTOMER,
        surface: ReceiptSurface = ReceiptSurface.PAPER,
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
        val resolved = current
        val standing = ReceiptStanding.of(sale)
        val simulated = standing.simulatedLink || sale.sample
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
                cardReceipt = cardReceipt(record, copy),
                refundQr = RefundablePayment.qrCode(record),
                cardSaved = sale.storedPaymentMethodId != null,
                preAuthorisation = standing.preAuthorisation,
                tip =
                    when {
                        standing.tipMinor != null -> TipLines.Entered(standing.tipMinor)
                        standing.awaitingTip && surface == ReceiptSurface.PAPER -> TipLines.Blank
                        else -> null
                    },
                heldNow = standing.heldNowMinor,
                captured = standing.capturedMinor,
                unpaidLink = standing.unpaidLink?.let { UnpaidLink(it, standing.linkExpiresAt?.let(::formatDateTime)) },
                paidOnline = standing.paidOnline,
                standingNote = resolved.standing(standing.standing),
                holdCancelled = standing.standing == PaymentStanding.HOLD_CANCELLED,
                notice = resolved.simulationNote.takeIf { simulated },
                surchargeMinor = sale.amountSurchargeMinor,
                chargedAmount = sale.amountMinor.takeIf { sale.terminalPaidMinor != null && standing.standing.charged },
                purchaseMinor =
                    sale.amountSurchargeMinor?.let {
                        (sale.amountMinor - it - (sale.tipMinor ?: 0) - (sale.cashBackMinor ?: 0)).coerceAtLeast(0)
                    },
                discloseSurcharge = standing.approved && !sale.paymentLink && !sale.savedCard,
                cardPlans =
                    resolved.cashback(sale) +
                        record.tenders.sortedBy { it.sequence }.flatMap { resolved.mexicanPlan(it) + resolved.mealVoucher(it) },
                tenders = receiptTenders(record),
                balanceDueMinor = sale.remainingMinor.takeIf { sale.isPartiallyPaid },
            )
        val branding = if (ReceiptKind.of(standing) == ReceiptKind.CASHOUT) settings.copy(title = resolved.cashoutTitle) else settings
        return builder(branding, currency, resolved, simulated).sale(receipt, copy)
    }

    // Every approved card's original fields, falling back to the sale body for historical or refused payments.
    private fun cardReceipt(
        record: SaleWithLines,
        copy: ReceiptCopy,
    ): List<CardReceiptLine> =
        record.tenders
            .filter { it.approved && it.kind == TenderKind.CARD }
            .sortedBy { it.sequence }
            .flatMap {
                ReceiptLinesJson.decode(if (copy == ReceiptCopy.CUSTOMER) it.customerReceiptJson else it.cashierReceiptJson)
            }.ifEmpty {
                val sale = record.sale
                ReceiptLinesJson.decode(if (copy == ReceiptCopy.CUSTOMER) sale.customerReceiptJson else sale.cashierReceiptJson)
            }

    /**
     * The receipt of [refund], referring to the original sale by its merchant reference (or, for a payment known only
     * from its QR code without one, its transaction ID). The cancellation of a pre-authorisation is labelled as one; an
     * unreferenced refund names no original, only that it has none and why it was made.
     */
    fun refund(
        refund: RefundEntity,
        settings: ReceiptSettings,
    ): ReceiptDocument {
        val resolved = current
        val receipt =
            RefundReceipt(
                reference = refund.merchantReference,
                originalReference = (refund.originalReference ?: refund.originalTransactionId).takeUnless { refund.unreferenced },
                dateTime = formatDateTime(refund.createdAt),
                items =
                    ReceiptLinesJson.decodeRefunded(refund.linesJson).map {
                        ReceiptItem(it.name, it.quantity, it.unitPriceMinor, it.grossMinor, it.taxRateMilliPercent)
                    },
                amount = refund.amountMinor,
                cardReceipt = ReceiptLinesJson.decode(refund.customerReceiptJson),
                cancellation = refund.cancellation,
                full = refund.full,
                standingNote = if (refund.cash) resolved.labels.cashReturned else resolved.refund(refund.status),
                surchargeMinor = refund.surchargeMinor,
                tipMinor = refund.tipMinor,
                unreferencedNotes = if (refund.unreferenced) resolved.unreferenced(refund) else emptyList(),
            )
        return builder(settings, CurrencySpec.of(refund.currency), resolved).refund(receipt)
    }

    /**
     * The receipt of stored-value [activity]: never a sale receipt. Lists each completed stage's amount, the card's
     * last reported balance and Adyen's receipt lines of every answered stage verbatim.
     */
    fun giftCard(
        activity: GiftCardActivity,
        settings: ReceiptSettings,
    ): ReceiptDocument {
        val resolved = current
        val main = activity.change ?: activity.stages.first()
        val receipt =
            GiftCardReceipt(
                title = "${resolved.giftCardTitle} · ${resolved.giftCardStage(activity.kind)}",
                reference = main.merchantReference,
                dateTime = formatDateTime(activity.createdAt),
                card =
                    listOfNotNull(
                        activity.stages.firstNotNullOfOrNull { it.providerCode }?.uppercase(),
                        activity.cardLastFour?.let { "**** $it" },
                    ).joinToString(" ").ifBlank { null },
                lines =
                    activity.stages.filter { it.status == GiftCardStatus.SUCCEEDED }.map {
                        GiftCardReceiptLine(resolved.giftCardStage(it.stage), it.movedMinor, bold = it.id == main.id)
                    },
                balanceMinor = activity.balanceMinor,
                standingNote = resolved.giftCardAttention.takeIf { activity.needsAttention },
                cardReceipts = activity.stages.map { ReceiptLinesJson.decode(it.customerReceiptJson) }.filter { it.isNotEmpty() },
            )
        return builder(settings, CurrencySpec.of(activity.currency), resolved).giftCard(receipt)
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
        languageTag: String = "",
    ): ReceiptDocument {
        val resolved = texts(languageTag)
        val sample = resolved.sample
        val markedRate = settings.markedTaxRateMilliPercent ?: 0
        val customAmounts = TaxRates.apply(300, markedRate, TaxMode.INCLUSIVE)
        val dateTime =
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(resolved.locale)
                .format(Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone()))
        val receipt =
            SaleReceipt(
                reference = SAMPLE_REFERENCE,
                customerReference = SAMPLE_CUSTOMER.takeIf { withCustomerReference },
                dateTime = dateTime,
                mode = mode,
                items = listOf(ReceiptItem(sample.coffee, 2, 450, 900, 10_000), ReceiptItem(sample.custom, 1, 300, 300, markedRate)),
                amounts = TaxAmounts(818, 82, 900) + customAmounts,
                breakdown =
                    listOf(
                        TaxBreakdown(AppliedTax(sample.taxed, 10_000), TaxAmounts(818, 82, 900)),
                        TaxBreakdown(AppliedTax(if (markedRate == 0) sample.zero else sample.taxed, markedRate), customAmounts),
                    ),
                approved = true,
                cardReceipt = emptyList(),
                refundQr =
                    RefundQrPayload(SAMPLE_TRANSACTION_ID, SAMPLE_TIMESTAMP, SAMPLE_TOTALS.gross, currency.code, SAMPLE_REFERENCE).encode(),
                cardSaved = false,
            )
        return builder(settings, currency, resolved).sale(receipt, ReceiptCopy.CUSTOMER)
    }

    private fun builder(
        settings: ReceiptSettings,
        currency: CurrencySpec,
        resolved: ReceiptTexts,
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
            resolved.labels.copy(markedTaxRateNote = settings.markedTaxRateNote).let {
                if (simulated) {
                    it.copy(
                        payLinkCaption = resolved.demoLink,
                        payLinkIntro = resolved.demoLink,
                        payNow = resolved.demoLink,
                        paidOnline = resolved.simulationNote,
                    )
                } else {
                    it
                }
            },
        money = MoneyFormatter(currency, resolved.locale),
    )

    private companion object {
        const val SAMPLE_REFERENCE = "MP-SAMPLE-0001"
        const val SAMPLE_CUSTOMER = "CUST-001"
        const val SAMPLE_TRANSACTION_ID = "SAMP001234567890123.SAMPLEPSP0000001"
        val SAMPLE_TIMESTAMP: Instant = Instant.parse("2026-01-01T00:00:00Z")
        val SAMPLE_TOTALS = TaxAmounts(1_118, 82, 1_200)
    }
}

/** [record]'s approved tenders in payment order, as its receipt breakdown lists them. */
internal fun receiptTenders(record: SaleWithLines): List<ReceiptTender> =
    record.tenders.filter { it.approved }.sortedBy { it.sequence }.map { tender ->
        if (tender.kind == TenderKind.CASH) {
            ReceiptTender(ReceiptTenderKind.CASH, null, tender.requestedMinor, tender.cashReceivedMinor, tender.changeMinor)
        } else {
            val detail = listOfNotNull(tender.paymentBrand?.uppercase(), tender.maskedPan).joinToString(" ").ifBlank { null }
            val kind =
                when {
                    tender.giftCard != null -> ReceiptTenderKind.GIFT_CARD
                    tender.mealVoucher != null -> ReceiptTenderKind.MEAL_VOUCHER
                    else -> ReceiptTenderKind.CARD
                }
            ReceiptTender(kind, detail, tender.authorisedMinor ?: tender.requestedMinor)
        }
    }
