package app.minimpos.app.history

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleTenderEntity

/**
 * How an approval was made without reaching Adyen online, as a stored sale records it ([SaleEntity.offlineMode]).
 */
enum class OfflineApproval {
    /** Approved by the card offline (Offline EMV). */
    OFFLINE_EMV,

    /** Approved by the terminal and forwarded to Adyen later (store-and-forward). */
    STORE_AND_FORWARD,
}

/** The card as the terminal reported it: the brand in upper case and the masked number; null when it reported neither. */
private fun cardText(
    brand: String?,
    maskedPan: String?,
): String? = listOfNotNull(brand?.uppercase(), maskedPan).joinToString(" ").ifBlank { null }

/** The card that paid this sale as the terminal reported it, such as `VISA 541333 **** 9999`; null when it reported neither. */
val SaleEntity.cardText: String? get() = cardText(paymentBrand, maskedPan)

/** The card that paid this tender as the terminal reported it, such as `MC 541333 **** 9999`; null when it reported neither. */
val SaleTenderEntity.cardText: String? get() = cardText(paymentBrand, maskedPan)

/** How this sale was approved offline; null when it was approved online, or not approved. */
val SaleEntity.offlineApproval: OfflineApproval?
    get() =
        when (offlineMode) {
            null -> null
            OfflineApproval.OFFLINE_EMV.name -> OfflineApproval.OFFLINE_EMV
            else -> OfflineApproval.STORE_AND_FORWARD
        }
