package io.github.astiskala.minimpos.core.money

import kotlinx.serialization.Serializable

/**
 * Non-secret identity of the destination that received an operation; no credentials are retained.
 * @property destination Resolved destination name.
 * @property poiId Original terminal identity.
 * @property saleId Original nexo SaleID.
 * @property merchantAccount Original Adyen merchant account.
 * @property environment TEST or LIVE; null until selected or established by the device.
 * @property host Original network address; null for other transports.
 * @property simulated Whether no real payment took place.
 */
@Serializable
data class PaymentContext(
    val destination: String,
    val poiId: String,
    val saleId: String,
    val merchantAccount: String,
    val environment: String?,
    val host: String? = null,
    val simulated: Boolean = false,
) {
    /** Original accounting environment: SIMULATOR when no money moved, TEST/LIVE otherwise, or null while unknown. */
    val historyEnvironment: String? get() = if (simulated) "SIMULATOR" else environment

    /** Whether [current] addresses the original Adyen account/environment, or the same simulator. */
    fun matchesApi(current: PaymentContext): Boolean =
        simulated == current.simulated &&
            (simulated || (environment != null && environment == current.environment && merchantAccount == current.merchantAccount))

    /** Whether [current] addresses the original terminal and nexo SaleID. */
    fun matchesTerminal(current: PaymentContext): Boolean =
        destination == current.destination && poiId == current.poiId && saleId == current.saleId && matchesApi(current)
}
