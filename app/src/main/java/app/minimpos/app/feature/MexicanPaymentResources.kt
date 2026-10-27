package app.minimpos.app.feature

import android.content.Context
import app.minimpos.app.R
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.terminal.client.MexicanPayment

/** Localized shopper choice; enum names remain stable stored request facts. */
internal fun mexicanPaymentLabel(choice: MexicanPayment): Int =
    when (choice) {
        MexicanPayment.DEBIT -> R.string.mexican_debit
        MexicanPayment.CREDIT -> R.string.mexican_credit
        MexicanPayment.MONTHS_3 -> R.string.mexican_3
        MexicanPayment.MONTHS_6 -> R.string.mexican_6
        MexicanPayment.MONTHS_9 -> R.string.mexican_9
        MexicanPayment.MONTHS_12 -> R.string.mexican_12
        MexicanPayment.MONTHS_18 -> R.string.mexican_18
    }

/** Requested plan stays distinct from the terminal's answer; omitted answer facts remain unreported. */
internal fun Context.mexicanPlan(tender: SaleTenderEntity): List<String> =
    buildList {
        tender.mexicanPayment?.let { stored ->
            val choice = MexicanPayment.entries.firstOrNull { it.name == stored }
            add(getString(R.string.mexican_requested, choice?.let { getString(mexicanPaymentLabel(it)) } ?: stored))
        }
        if (listOf(
                tender.mexicanPayment,
                tender.returnedPaymentType,
                tender.returnedInstallments,
                tender.returnedPlanId,
            ).any { it != null }
        ) {
            add(
                getString(
                    R.string.mexican_returned,
                    listOfNotNull(
                        tender.returnedPaymentType,
                        tender.returnedInstallments ?: getString(R.string.mexican_unreported).takeIf {
                            tender.mexicanPayment?.startsWith("MONTHS_") == true || tender.returnedPaymentType != null
                        },
                        tender.returnedPlanId,
                    ).joinToString(" · ").ifBlank { getString(R.string.mexican_unreported) },
                ),
            )
        }
    }
