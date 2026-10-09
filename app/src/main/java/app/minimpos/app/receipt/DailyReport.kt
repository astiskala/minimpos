package app.minimpos.app.receipt

import app.minimpos.app.data.settings.ReceiptSettings
import app.minimpos.app.refund.CurrencyHistoryTotals
import app.minimpos.app.refund.HistoryReport
import app.minimpos.app.refund.HistoryScope
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

    /** Accepted refund requests. */
    REFUNDS,

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
            if (dated) {
                add(ReceiptElement.Row("${text(ReportText.REFUNDS)} (${total.refundCount})", money.format(total.refundsMinor)))
                add(ReceiptElement.Row(text(ReportText.NET), money.format(total.netMinor), TextStyle.BOLD))
                add(ReceiptElement.Row("${text(ReportText.HOLDS)} (${total.holdCount})", money.format(total.heldMinor)))
                add(ReceiptElement.Row(text(ReportText.UNRESOLVED), total.unresolvedCount.toString()))
            }
        }
    }
}
