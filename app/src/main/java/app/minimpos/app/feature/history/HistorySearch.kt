package app.minimpos.app.feature.history

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.refund.methodCode
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.payment.PaymentMethods
import app.minimpos.core.payment.Wallet

/** A payment method history can be narrowed to: a card brand (whether in a wallet or not), or a mobile wallet. */
sealed interface PaymentMethodFilter {
    /** What the filter is called in the menu and on its chip; brand and wallet names are not translated. */
    val label: String

    /** Whether [sale] was paid with this payment method. */
    fun matches(sale: SaleEntity): Boolean

    /**
     * Cards of one brand.
     *
     * @property code Adyen's brand code in lower case ([PaymentMethods.normalizeBrand]), such as `visa` or `mc`.
     */
    data class Brand(
        val code: String,
    ) : PaymentMethodFilter {
        override val label: String get() = PaymentMethods.brandName(code)

        override fun matches(sale: SaleEntity): Boolean = sale.methodCode?.let(PaymentMethods::normalizeBrand) == code
    }

    /**
     * Cards presented in one mobile wallet, as the sale's `paymentMethodVariant` names it.
     *
     * @property wallet The wallet.
     */
    data class InWallet(
        val wallet: Wallet,
    ) : PaymentMethodFilter {
        override val label: String get() = wallet.displayName

        override fun matches(sale: SaleEntity): Boolean = PaymentMethods.wallet(sale.paymentMethodVariant) == wallet
    }

    /** Building the menu of payment methods. */
    companion object {
        /** The brands paid with in [sales], by name, then the wallets used, in [Wallet] order. */
        fun available(sales: List<SaleEntity>): List<PaymentMethodFilter> {
            val brands =
                sales
                    .mapNotNull { sale -> sale.methodCode?.let(PaymentMethods::normalizeBrand)?.takeIf { it.isNotEmpty() } }
                    .distinct()
                    .map(::Brand)
                    .sortedBy { it.label.lowercase() }
            val wallets =
                sales
                    .mapNotNull { PaymentMethods.wallet(it.paymentMethodVariant) }
                    .distinct()
                    .sorted()
                    .map(::InWallet)
            return brands + wallets
        }
    }
}

/**
 * What staff look for in history: words typed into the search field and a payment method.
 *
 * Every word of [text] must match the transaction somewhere, ignoring case: within its merchant reference, PSP
 * reference, authorisation code, customer reference, shopper reference or email, the card's last four digits, the brand
 * (code or name) or the wallet, or, when the word is an amount (`34`, `34.50`), exactly the amount as it stands or the
 * bill before a tip. A refund or cancellation matches on its own references and amount, and through the sale it
 * belongs to (when that sale is on this terminal) on that sale's references, shopper, card and payment method, but not
 * on the sale's amount.
 *
 * @param text The search field's text; blank finds everything.
 * @property method The payment method chosen, or null for any.
 */
class HistorySearch(
    text: String,
    val method: PaymentMethodFilter? = null,
) {
    private val terms = text.trim().split(WHITESPACE).filter(String::isNotEmpty)

    /** Whether anything narrows the list, so an empty result means no match rather than no history. */
    val isActive: Boolean get() = terms.isNotEmpty() || method != null

    /** Whether [item] matches; [sale] is the sale itself, or the local sale a refund belongs to (null if none). */
    fun matches(
        item: HistoryItem,
        sale: SaleEntity?,
    ): Boolean {
        if (method != null && (sale == null || !method.matches(sale))) return false
        if (terms.isEmpty()) return true
        val texts = sale?.let(::saleTexts).orEmpty() + if (item is HistoryItem.Refund) refundTexts(item) else emptyList()
        val (currency, amounts) =
            when (item) {
                is HistoryItem.Sale -> item.sale.currency to setOf(item.sale.amountMinor, item.sale.totalMinor)
                is HistoryItem.Refund -> item.refund.currency to setOf(item.refund.amountMinor)
            }
        val spec = runCatching { CurrencySpec.of(currency) }.getOrNull()
        return terms.all { term -> texts.any { it.contains(term, ignoreCase = true) } || spec?.parseMinor(term) in amounts }
    }

    private fun saleTexts(sale: SaleEntity): List<String> =
        listOfNotNull(
            sale.merchantReference,
            sale.pspReference,
            sale.authCode,
            sale.customerReference,
            sale.shopperReference,
            sale.shopperEmail,
            sale.maskedPan?.filter(Char::isDigit)?.takeLast(LAST_DIGITS),
            sale.paymentBrand,
            sale.methodCode?.let(PaymentMethods::brandName),
            sale.requestedWallet,
            sale.requestedWallet?.let(PaymentMethods::brandName),
            sale.paymentMethodVariant,
            PaymentMethods.wallet(sale.paymentMethodVariant)?.displayName,
        )

    private fun refundTexts(item: HistoryItem.Refund): List<String> =
        listOfNotNull(
            item.refund.merchantReference,
            item.refund.pspReference,
            item.refund.originalReference,
            item.refund.originalTransactionId,
        )

    private companion object {
        val WHITESPACE = Regex("\\s+")

        /** The digits of a masked card number that identify the card to staff and shoppers. */
        const val LAST_DIGITS = 4
    }
}
