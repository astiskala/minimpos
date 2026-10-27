package app.minimpos.app.feature

import android.content.Context
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.repo.cashHandover
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter

/** Localized request, terminal answer and operator handover facts, also used at receipt delivery. */
internal fun Context.cashbackFacts(sale: SaleEntity): List<String> {
    val requested = sale.requestedCashBackMinor ?: return emptyList()
    val money = MoneyFormatter(CurrencySpec.of(sale.currency), resources.configuration.locales[0])
    val unknown = getString(R.string.mexican_unreported)
    return buildList {
        if (sale.totalMinor == 0L) add(getString(R.string.cashout_title))
        add(getString(R.string.cashback_requested) + ": " + money.format(requested))
        add(getString(R.string.cashback_approved) + ": " + (sale.cashBackMinor?.let(money::format) ?: unknown))
        add(getString(R.string.cashback_card_charge) + ": " + (sale.terminalPaidMinor?.let(money::format) ?: unknown))
        add(getString(if (sale.cashHandedOverAt != null) R.string.cashback_handed_over else R.string.cashback_not_handed_over))
        if (sale.status == SaleStatus.APPROVED && sale.cashHandover?.needsAttention == true &&
            sale.cashHandover?.canRecord != true
        ) {
            add(getString(R.string.cashback_missing_amount))
        }
    }
}
