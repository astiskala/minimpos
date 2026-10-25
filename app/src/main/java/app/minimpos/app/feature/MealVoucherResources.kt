package app.minimpos.app.feature

import android.content.Context
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.payment.MealVoucherBlock
import app.minimpos.core.voucher.MealVoucherScheme
import app.minimpos.core.voucher.VoucherRefund

/** Localized market name; enum names remain stable stored request facts. */
internal fun mealVoucherLabel(scheme: MealVoucherScheme): Int =
    when (scheme) {
        MealVoucherScheme.BRAZIL_PAT -> R.string.voucher_scheme_brazil
        MealVoucherScheme.EUROPE_CARD_SCHEME -> R.string.voucher_scheme_europe
        MealVoucherScheme.FRANCE_TITRES_RESTAURANT -> R.string.voucher_scheme_france
    }

/** The market's documented limits, said before payment. */
internal fun mealVoucherRules(scheme: MealVoucherScheme): Int =
    when (scheme) {
        MealVoucherScheme.BRAZIL_PAT -> R.string.voucher_rules_brazil
        MealVoucherScheme.EUROPE_CARD_SCHEME -> R.string.voucher_rules_europe
        MealVoucherScheme.FRANCE_TITRES_RESTAURANT -> R.string.voucher_rules_france
    }

/** Why a voucher cannot pay now, with its fix. */
internal fun mealVoucherBlocked(block: MealVoucherBlock): Int =
    when (block) {
        MealVoucherBlock.NOT_CONFIGURED -> R.string.voucher_blocked_configuration
        MealVoucherBlock.NO_MARKET -> R.string.voucher_blocked_market
        MealVoucherBlock.TERMINAL_SELECTS -> R.string.voucher_blocked_terminal
        MealVoucherBlock.CLOSED_TODAY -> R.string.voucher_blocked_today
        MealVoucherBlock.CHANGED_SETUP -> R.string.voucher_blocked_setup
        MealVoucherBlock.UNRESOLVED -> R.string.voucher_blocked_unresolved
        MealVoucherBlock.NOTHING_ELIGIBLE -> R.string.voucher_blocked_eligible
    }

/**
 * The requested market of a voucher tender and what the terminal reported, verbatim, kept apart; a Brazilian voucher
 * chosen on the terminal's own screen is recognized only from the reported variant. Empty for other tenders.
 */
internal fun Context.mealVoucherFacts(tender: SaleTenderEntity): List<String> {
    val scheme = MealVoucherScheme.stored(tender.mealVoucher)
    if (tender.mealVoucher == null && !MealVoucherScheme.reportedBrazilianVoucher(tender.paymentMethodVariant)) return emptyList()
    return buildList {
        tender.mealVoucher?.let { stored ->
            add(getString(R.string.voucher_requested, scheme?.let { getString(mealVoucherLabel(it)) } ?: stored))
        }
        listOfNotNull(tender.paymentMethodVariant, tender.fundingSource)
            .joinToString(" · ")
            .ifBlank { null }
            ?.let { add(getString(R.string.voucher_returned, it)) }
        if (tender.approved && scheme?.refund == VoucherRefund.NONE) add(getString(R.string.voucher_not_refundable))
    }
}
