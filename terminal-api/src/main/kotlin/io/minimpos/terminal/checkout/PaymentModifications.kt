package io.minimpos.terminal.checkout

import io.minimpos.terminal.transport.TerminalEnvironment

/**
 * An amount for a payment modification, as Adyen's Checkout API takes it.
 *
 * @property currency The ISO 4217 code of the original payment's currency.
 * @property value The amount in minor units of [currency] with Adyen's decimals (e.g. 1250 for EUR 12.50).
 */
data class ModificationAmount(
    val currency: String,
    val value: Long,
)

/**
 * How Adyen answered a capture or an authorisation adjustment. Network and HTTP errors are reported here, never
 * thrown.
 */
sealed interface ModificationResult {
    /**
     * Adyen accepted the request and processes it asynchronously (status `received`); the outcome only arrives in a
     * webhook, which this app has no server for, so it is shown in the Customer Area.
     *
     * @property pspReference Adyen's reference for the modification (not the payment's); null if it sent none.
     */
    data class Received(
        val pspReference: String?,
    ) : ModificationResult

    /**
     * A synchronous authorisation adjustment authorised the new amount (status `Authorised`).
     *
     * @property pspReference Adyen's reference for the adjustment; null if it sent none.
     * @property adjustAuthorisationData The new blob to send with the next adjustment; null if Adyen sent none, after
     *   which further adjustments fall back to the asynchronous flow.
     */
    data class Authorised(
        val pspReference: String?,
        val adjustAuthorisationData: String?,
    ) : ModificationResult

    /**
     * A synchronous authorisation adjustment was refused by the issuer (status `Refused`); the amount authorised before
     * still applies.
     *
     * @property reason Adyen's refusal reason, or a generic text when it gave none.
     */
    data class Refused(
        val reason: String,
    ) : ModificationResult

    /**
     * The request did not take effect: it never reached Adyen, or Adyen rejected it (HTTP 4xx, e.g. a wrong API key or
     * merchant account, or a payment that cannot be modified).
     *
     * @property message Why, in English, with Adyen's error code when it sent one.
     */
    data class NotProcessed(
        val message: String,
    ) : ModificationResult

    /**
     * It is not known whether the request took effect (timeout, broken connection, HTTP 5xx). Sending it again with the
     * same idempotency key is safe: Adyen then answers with the first result instead of acting twice.
     *
     * @property message Why, in English.
     */
    data class Unknown(
        val message: String,
    ) : ModificationResult
}

/**
 * Modifications of an authorised payment through Adyen's Checkout API: capturing it, and adjusting the amount a
 * pre-authorisation holds. Implementations never throw for network or API errors; they report them in the
 * [ModificationResult]. All functions are safe to call from any thread.
 */
interface PaymentModifications {
    /**
     * Captures [amount] of the payment [paymentPspReference] (`POST /payments/{paymentPspReference}/captures`), which
     * must have been authorised with manual capture. Adyen answers [ModificationResult.Received]; whether the capture
     * succeeds is only known later.
     *
     * @param paymentPspReference The PSP reference of the payment to capture.
     * @param amount What to capture; more than authorised is an overcapture, which Adyen has to allow.
     * @param reference The merchant's reference for the capture, shown in the Customer Area.
     * @param idempotencyKey Sent as `Idempotency-Key`, so repeating a capture whose outcome is unknown cannot capture
     *   twice.
     */
    suspend fun capture(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult

    /**
     * Changes the amount the pre-authorisation [paymentPspReference] holds to [amount], the new total (not the
     * difference), with reason `DelayedCharge` (`POST /payments/{paymentPspReference}/amountUpdates`). With
     * [adjustAuthorisationData] (the blob from the payment or the previous adjustment) the adjustment is synchronous
     * and answers [ModificationResult.Authorised] or [ModificationResult.Refused]; without it Adyen answers
     * [ModificationResult.Received] and decides later. The same amount as before extends the authorisation.
     *
     * @param paymentPspReference The PSP reference of the pre-authorisation.
     * @param amount The new total to hold.
     * @param reference The merchant's reference for the adjustment, shown in the Customer Area.
     * @param adjustAuthorisationData The latest blob, or null for an asynchronous adjustment.
     * @param idempotencyKey Sent as `Idempotency-Key`, so repeating an adjustment whose outcome is unknown is safe.
     */
    suspend fun updateAmount(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        adjustAuthorisationData: String?,
        idempotencyKey: String,
    ): ModificationResult

    /**
     * Checks that the API key is accepted for the merchant account without changing anything (a payment methods
     * request). Returns null when it is, else why not, in English.
     */
    suspend fun verify(): String?
}

/**
 * Access to Adyen's Checkout API for one merchant account. [toString] leaves out the API key, so credentials can be
 * logged. Constructing it with a blank API key or merchant account, or for [TerminalEnvironment.LIVE] without a
 * [liveUrlPrefix], throws [IllegalArgumentException].
 *
 * @property apiKey The API key of an API credential with the Checkout webservice role.
 * @property merchantAccount The merchant account the payments were taken on.
 * @property environment Where the payments were taken, which decides the endpoint.
 * @property liveUrlPrefix The company's live endpoint prefix (Customer Area: Developers › API URLs, e.g.
 *   `1797a841fbb37ca7-AdyenDemo`); only used for [TerminalEnvironment.LIVE].
 */
data class CheckoutCredentials(
    val apiKey: String,
    val merchantAccount: String,
    val environment: TerminalEnvironment,
    val liveUrlPrefix: String = "",
) {
    init {
        require(apiKey.isNotBlank()) { "An API key is required" }
        require(merchantAccount.isNotBlank()) { "A merchant account is required" }
        require(environment == TerminalEnvironment.TEST || liveUrlPrefix.isNotBlank()) { "LIVE needs the live URL prefix" }
    }

    /** The Checkout API base URL for [environment], without a trailing slash. */
    val baseUrl: String
        get() =
            when (environment) {
                TerminalEnvironment.TEST -> "https://checkout-test.adyen.com/$API_VERSION"
                TerminalEnvironment.LIVE -> "https://${liveUrlPrefix.trim()}-checkout-live.adyenpayments.com/checkout/$API_VERSION"
            }

    override fun toString() = "CheckoutCredentials($merchantAccount, $environment)"

    /** The API version the endpoints use. */
    companion object {
        /** The Checkout API version, the one the Adyen Java library uses. */
        const val API_VERSION = "v72"
    }
}
