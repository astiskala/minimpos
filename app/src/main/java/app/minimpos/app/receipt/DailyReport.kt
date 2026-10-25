package app.minimpos.app.receipt

import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.history.CurrencyHistoryTotals
import app.minimpos.app.history.HistoryReport
import app.minimpos.app.history.HistoryScope
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.receipt.Align
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.core.receipt.TextStyle
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Localized daily-summary labels, read at printing rather than retained in financial records. */
enum class ReportText {
    /** Local-summary document title. */
    TITLE,

    /** Generation timestamp label. */
    GENERATED,

    /** Approved sales and accepted captures. */
    SALES,

    /** Tips already included in sales. */
    TIPS,

    /** Reported surcharges already included in sales. */
    SURCHARGES,

    /** Purchase components known from reported surcharges. */
    PURCHASE,

    /** Count of sales with unreported surcharge components. */
    UNKNOWN_SURCHARGES,

    /** Accepted refund requests. */
    REFUNDS,

    /** Cash tenders included in sales, recorded locally and never in terminal totals. */
    CASH_SALES,

    /** Cash handed back, included in refunds. */
    CASH_REFUNDS,

    /** Surcharge already included in accepted refunds. */
    REFUND_SURCHARGES,

    /** Refunds whose fee component is unknown. */
    UNKNOWN_REFUND_SURCHARGES,

    /** Sales minus refunds. */
    NET,

    /** Newly authorized holds, not outstanding balance. */
    HOLDS,

    /** Unresolved-operation count. */
    UNRESOLVED,

    /** Paid links with no payment date. */
    ONLINE,

    /** Accepted captures with no processing date. */
    UNDATED,

    /** Undated amounts are retained-history totals excluded from the selected day. */
    UNDATED_NOTE,

    /** Retained local snapshot, not settlement confirmation. */
    NOTE,

    /** Unresolved outcomes can change totals. */
    ISSUE_NOTE,

    /** No real money moved in this section. */
    DEMO,

    /** Original environment unavailable. */
    UNKNOWN,

    /** Gift-card value activated, loaded or refunded onto cards, less loads undone; never sales. */
    GIFT_VALUE_ADDED,

    /** Gift-card value paid out by cash-out. */
    GIFT_VALUE_REMOVED,

    /** Card payments collecting gift-card value. */
    GIFT_FUNDING_CARD,

    /** Gift-card value funds staff confirmed were received outside the app. */
    GIFT_FUNDING_EXTERNAL,

    /** Gift-card funding refunded or given back because no value was delivered. */
    GIFT_FUNDING_RETURNED,

    /** Cash handed over for gift-card cash-outs, never in terminal totals. */
    GIFT_CASH_HANDED_OVER,
}

internal object DailyReport {
    fun document(
        report: HistoryReport,
        settings: ReceiptSettings,
        locale: Locale,
        text: (ReportText) -> String,
    ): ReceiptDocument {
        val elements =
            buildList {
                if (settings.businessName.isNotBlank()) add(ReceiptElement.Text(settings.businessName, Align.CENTER, TextStyle.BOLD))
                settings.addressLines
                    .lines()
                    .filter { it.isNotBlank() }
                    .forEach { add(ReceiptElement.Text(it, Align.CENTER)) }
                if (settings.phone.isNotBlank()) add(ReceiptElement.Text(settings.phone, Align.CENTER))
                add(ReceiptElement.Text(text(ReportText.TITLE), Align.CENTER, TextStyle.BOLD))
                add(
                    ReceiptElement.Text(
                        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(report.date),
                        Align.CENTER,
                    ),
                )
                val stamp =
                    DateTimeFormatter
                        .ofLocalizedDateTime(FormatStyle.SHORT)
                        .withLocale(locale)
                        .format(Instant.ofEpochMilli(report.generatedAt).atZone(report.zone))
                add(ReceiptElement.Text("${text(ReportText.GENERATED)}: $stamp"))
                add(ReceiptElement.Text(report.zone.id))
                report.sections.forEach { section ->
                    add(ReceiptElement.Divider)
                    add(
                        ReceiptElement.Text(
                            when (section.scope) {
                                HistoryScope.LIVE -> "LIVE"
                                HistoryScope.TEST -> "TEST"
                                HistoryScope.DEMO -> text(ReportText.DEMO)
                                HistoryScope.UNKNOWN -> text(ReportText.UNKNOWN)
                            },
                            style = TextStyle.BOLD,
                        ),
                    )
                    addAll(rows(section.totals, locale, text, dated = true))
                    listOf(ReportText.ONLINE to section.online, ReportText.UNDATED to section.undated).forEach { (label, totals) ->
                        if (totals.isNotEmpty()) {
                            add(ReceiptElement.Text(text(label), style = TextStyle.BOLD))
                            addAll(rows(totals, locale, text, dated = false))
                            add(ReceiptElement.Text(text(ReportText.UNDATED_NOTE)))
                        }
                    }
                }
                add(ReceiptElement.Divider)
                add(ReceiptElement.Text(text(ReportText.NOTE)))
                if (report.sections.any { it.totals.values.any { total -> total.unresolvedCount > 0 } }) {
                    add(ReceiptElement.Text(text(ReportText.ISSUE_NOTE), style = TextStyle.BOLD))
                }
            }
        return ReceiptDocument(elements)
    }

    private fun rows(
        totals: Map<String, CurrencyHistoryTotals>,
        locale: Locale,
        text: (ReportText) -> String,
        dated: Boolean,
    ) = buildList {
        totals.forEach { (code, total) ->
            val money = MoneyFormatter(CurrencySpec.of(code), locale)
            add(ReceiptElement.Text(code, style = TextStyle.BOLD))
            add(ReceiptElement.Row("${text(ReportText.SALES)} (${total.saleCount})", money.format(total.salesMinor)))
            add(ReceiptElement.Row(text(ReportText.TIPS), money.format(total.tipsMinor)))
            add(ReceiptElement.Row(text(ReportText.PURCHASE), money.format(total.purchaseMinor)))
            add(ReceiptElement.Row(text(ReportText.SURCHARGES), money.format(total.surchargesMinor)))
            add(ReceiptElement.Row(text(ReportText.UNKNOWN_SURCHARGES), total.unknownSurchargeCount.toString()))
            if (total.cashSalesMinor != 0L) add(ReceiptElement.Row(text(ReportText.CASH_SALES), money.format(total.cashSalesMinor)))
            if (dated) {
                add(ReceiptElement.Row("${text(ReportText.REFUNDS)} (${total.refundCount})", money.format(total.refundsMinor)))
                if (total.cashRefundsMinor !=
                    0L
                ) {
                    add(ReceiptElement.Row(text(ReportText.CASH_REFUNDS), money.format(total.cashRefundsMinor)))
                }
                add(ReceiptElement.Row(text(ReportText.REFUND_SURCHARGES), money.format(total.refundedSurchargesMinor)))
                add(ReceiptElement.Row(text(ReportText.UNKNOWN_REFUND_SURCHARGES), total.unknownRefundSurchargeCount.toString()))
                add(ReceiptElement.Row(text(ReportText.NET), money.format(total.netMinor), TextStyle.BOLD))
                add(ReceiptElement.Row("${text(ReportText.HOLDS)} (${total.holdCount})", money.format(total.heldMinor)))
                add(ReceiptElement.Row(text(ReportText.UNRESOLVED), total.unresolvedCount.toString()))
                giftCardRows(total, money, text).forEach(::add)
            }
        }
    }

    /** Stored-value movements, only when any moved; separate from sales, refunds and their net. */
    private fun giftCardRows(
        total: CurrencyHistoryTotals,
        money: MoneyFormatter,
        text: (ReportText) -> String,
    ) = buildList {
        val gift = total.giftCards
        listOf(
            ReportText.GIFT_VALUE_ADDED to gift.valueAddedMinor,
            ReportText.GIFT_VALUE_REMOVED to gift.valueRemovedMinor,
            ReportText.GIFT_FUNDING_CARD to gift.cardFundingMinor,
            ReportText.GIFT_FUNDING_EXTERNAL to gift.externalFundingMinor,
            ReportText.GIFT_FUNDING_RETURNED to gift.fundingReturnedMinor,
            ReportText.GIFT_CASH_HANDED_OVER to gift.cashHandedOverMinor,
        ).filter { it.second != 0L }.forEach { (label, amount) -> add(ReceiptElement.Row(text(label), money.format(amount))) }
    }
}
