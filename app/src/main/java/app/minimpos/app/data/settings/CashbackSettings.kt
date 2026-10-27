package app.minimpos.app.data.settings

import app.minimpos.core.payment.PaymentContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/** Card program confirmed with Adyen Support for the assigned store; never inferred from currency or locale. */
@Serializable
enum class CashbackCard {
    /** Australian eftpos debit; purchase-linked and standalone cash-out. */
    EFTPOS,

    /** German girocard debit; purchase-linked only. */
    GIROCARD,

    /** European Visa; purchase-linked only, subject to explicit store enablement. */
    VISA,

    /** European Mastercard; purchase-linked only, with country enablement confirmed by Support. */
    MASTERCARD,
}

/**
 * Explicit merchant confirmations; the verified store country and current destination are checked separately.
 * @property enabled Support enabled purchase-linked cashback for this store and card program.
 * @property standalone Support separately confirmed standalone eftpos cash-out.
 * @property card Eligible card program staff must confirm before requesting cash.
 * @property limitMinor Merchant's local cash limit in the payment currency's minor units; zero blocks all requests.
 * @property context Assigned store/account/destination whose enablement the merchant confirmed; null until confirmed.
 * @property currency Payment currency in which [limitMinor] was configured; null until confirmed.
 * @property storeId Management store whose enablement was confirmed; null for an offline demonstration.
 * @property storeCountry Verified country at confirmation; never an editable country setting.
 */
@Serializable
data class CashbackSettings(
    val enabled: Boolean = false,
    val standalone: Boolean = false,
    val card: CashbackCard? = null,
    val limitMinor: Long = 0,
    val context: PaymentContext? = null,
    val currency: String? = null,
    val storeId: String? = null,
    val storeCountry: String? = null,
) {
    /** Selecting a different eligible [program] requires explicit enablement again for both cash workflows. */
    fun selecting(program: CashbackCard?): CashbackSettings =
        if (program == card) this else copy(card = program, enabled = false, standalone = false)

    /**
     * Binds explicit Support confirmation to verified [context], [storeId] and [storeCountry] in [currency]. A moved
     * store clears both enablements; a changed currency also clears the local limit, so old minor units cannot become
     * another currency's cash limit. The caller then enables only the workflow the merchant confirmed.
     */
    fun confirmedFor(
        context: PaymentContext?,
        storeId: String?,
        storeCountry: String?,
        currency: String,
    ): CashbackSettings {
        val sameStore = this.context == context && this.storeId == storeId && this.storeCountry == storeCountry
        val unchanged = sameStore && this.currency == currency
        return copy(
            context = context,
            storeId = storeId,
            storeCountry = storeCountry,
            currency = currency,
            enabled = enabled && unchanged,
            standalone = standalone && unchanged,
            limitMinor = limitMinor.takeIf { this.currency == currency } ?: 0,
        )
    }

    /** Canonical non-secret confirmation facts; opaque to the generic financial lifecycle and never a credential. */
    val identity: String get() = StorageJson.encodeToString(serializer<CashbackSettings>(), this)

    /** Checks current enablement and bounds again at send, on the original [paymentContext] in [paymentCurrency]. */
    fun permits(
        cashMinor: Long,
        totalMinor: Long,
        paymentCurrency: String,
        paymentContext: PaymentContext,
    ): Boolean {
        val standaloneRequest = cashMinor == totalMinor
        val enabledNow = if (standaloneRequest) standalone && card == CashbackCard.EFTPOS else enabled
        return enabledNow && card != null && cashMinor in 1..limitMinor && currency == paymentCurrency && context == paymentContext
    }
}
