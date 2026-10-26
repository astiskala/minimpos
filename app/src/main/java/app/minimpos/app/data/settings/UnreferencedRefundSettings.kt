package app.minimpos.app.data.settings

import app.minimpos.core.payment.PaymentContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer

/**
 * Explicit merchant confirmations for unreferenced refunds; the verified store country and current destination are
 * checked separately, never inferred from currency, language or locale. Disabled by default.
 *
 * @property enabled Adyen enabled unreferenced refunds for this store or terminal ("Allow unreferenced refunds").
 * @property limitMinor Merchant's local per-refund limit in [currency]'s minor units; zero blocks every refund. Adyen's
 *   own unreferenced refund limit, set in the Customer Area or by Support, still applies.
 * @property delayed Adyen Support configured a refund delay, so a refund can be cancelled while it lasts.
 * @property context Assigned store/account/destination whose enablement the merchant confirmed; null until confirmed.
 * @property currency Currency in which [limitMinor] was configured; null until confirmed.
 * @property storeId Management store whose enablement was confirmed; null for an offline demonstration.
 * @property storeCountry Verified store country at confirmation; never an editable country setting.
 */
@Serializable
data class UnreferencedRefundSettings(
    val enabled: Boolean = false,
    val limitMinor: Long = 0,
    val delayed: Boolean = false,
    val context: PaymentContext? = null,
    val currency: String? = null,
    val storeId: String? = null,
    val storeCountry: String? = null,
) {
    /**
     * Binds the merchant's confirmation to verified [context], [storeId] and [storeCountry] in [currency]. A moved store
     * clears the enablement; a changed currency also clears the local limit, so old minor units cannot become another
     * currency's limit. The caller then enables only what the merchant confirmed.
     */
    fun confirmedFor(
        context: PaymentContext?,
        storeId: String?,
        storeCountry: String?,
        currency: String,
    ): UnreferencedRefundSettings {
        val unchanged =
            this.context == context && this.storeId == storeId && this.storeCountry == storeCountry && this.currency == currency
        return copy(
            context = context,
            storeId = storeId,
            storeCountry = storeCountry,
            currency = currency,
            enabled = enabled && unchanged,
            delayed = delayed && unchanged,
            limitMinor = limitMinor.takeIf { this.currency == currency } ?: 0,
        )
    }

    /** Canonical non-secret confirmation facts; opaque to the generic financial lifecycle and never a credential. */
    val identity: String get() = StorageJson.encodeToString(serializer<UnreferencedRefundSettings>(), this)

    /** Checks enablement and the local limit again at send, on the actual [paymentContext] in [paymentCurrency]. */
    fun permits(
        amountMinor: Long,
        paymentCurrency: String,
        paymentContext: PaymentContext,
    ): Boolean = enabled && amountMinor in 1..limitMinor && currency == paymentCurrency && context == paymentContext
}
