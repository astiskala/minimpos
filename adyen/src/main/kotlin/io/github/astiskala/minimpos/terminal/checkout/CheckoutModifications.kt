package io.github.astiskala.minimpos.terminal.checkout

import com.adyen.model.applicationinfo.ApplicationInfo
import com.adyen.model.checkout.Amount
import com.adyen.model.checkout.PaymentAmountUpdateRequest
import com.adyen.model.checkout.PaymentAmountUpdateResponse
import com.adyen.model.checkout.PaymentCaptureRequest
import com.adyen.model.checkout.PaymentCaptureResponse
import com.adyen.model.checkout.PaymentMethodsRequest
import io.github.astiskala.minimpos.terminal.transport.AdyenHttp
import io.github.astiskala.minimpos.terminal.transport.AdyenReply
import io.github.astiskala.minimpos.terminal.transport.HTTP_FORBIDDEN
import io.github.astiskala.minimpos.terminal.transport.HTTP_UNAUTHORIZED
import io.github.astiskala.minimpos.terminal.transport.adyenField
import io.github.astiskala.minimpos.terminal.transport.decodeAdyenModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [PaymentModifications] over HTTPS with OkHttp, using the Adyen library's typed Checkout request models and JSON
 * serialization. [AdyenHttp] owns delivery uncertainty, timeouts and idempotency headers; the library's HTTP client
 * is not used. The API key goes in the `x-api-key` header and is never part of an error message.
 *
 * Blocking calls run on [dispatcher]; cancelling the coroutine interrupts them. A request that could not be sent at
 * all (no connection, unknown host) and HTTP 4xx answers other than 408 and 429 are
 * [ModificationResult.NotProcessed]; timeouts, 408, 429 and 5xx are [ModificationResult.Unknown].
 */
class CheckoutModifications(
    private val credentials: CheckoutCredentials,
    /** Where the API lives; tests point it at a local server. */
    private val baseUrl: HttpUrl = credentials.baseUrl.toHttpUrl(),
    /** The client to derive from, so an app can share one connection pool. */
    baseClient: OkHttpClient = OkHttpClient(),
    /** The limit for each whole request. */
    private val timeout: Duration = 30.seconds,
    /** Where the blocking calls run. */
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val application: ApplicationInfo? = null,
) : PaymentModifications {
    private val http = AdyenHttp(credentials.apiKey, baseClient, dispatcher, "check the internet connection and the live URL prefix")

    override suspend fun capture(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult =
        modify(
            listOf("payments", paymentPspReference, "captures"),
            PaymentCaptureRequest()
                .merchantAccount(credentials.merchantAccount)
                .amount(amount.checkoutAmount())
                .applicationInfo(application?.checkoutInfo())
                .reference(reference)
                .toJson(),
            idempotencyKey,
            ::captured,
        )

    override suspend fun updateAmount(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        adjustAuthorisationData: String?,
        idempotencyKey: String,
    ): ModificationResult =
        modify(
            listOf("payments", paymentPspReference, "amountUpdates"),
            PaymentAmountUpdateRequest()
                .merchantAccount(credentials.merchantAccount)
                .amount(amount.checkoutAmount())
                .applicationInfo(application?.checkoutInfo())
                .reference(reference)
                .industryUsage(PaymentAmountUpdateRequest.IndustryUsageEnum.DELAYEDCHARGE)
                .apply { adjustAuthorisationData?.let { adjustAuthorisationData(it) } }
                .toJson(),
            idempotencyKey,
            ::adjusted,
        )

    override suspend fun verify(): String? {
        val body = PaymentMethodsRequest().merchantAccount(credentials.merchantAccount).toJson()
        return when (val reply = post(listOf("paymentMethods"), body, idempotencyKey = null)) {
            is AdyenReply.Answered if reply.ok -> null
            is AdyenReply.Answered if reply.code == HTTP_UNAUTHORIZED -> "Adyen did not accept the API key (HTTP 401)"
            is AdyenReply.Answered if reply.code == HTTP_FORBIDDEN -> forbidden()
            is AdyenReply.Answered -> adyenError(reply)
            is AdyenReply.Failed -> reply.message
        }
    }

    private fun forbidden() = "The API key may not use merchant account ${credentials.merchantAccount} (HTTP 403)"

    private suspend fun modify(
        path: List<String>,
        body: String,
        idempotencyKey: String,
        success: (String) -> ModificationResult,
    ): ModificationResult =
        when (val reply = post(path, body, idempotencyKey)) {
            is AdyenReply.Failed if reply.sent -> ModificationResult.Unknown(reply.message)
            is AdyenReply.Failed -> ModificationResult.NotProcessed(reply.message)
            is AdyenReply.Answered if reply.ok -> success(reply.body)
            is AdyenReply.Answered if reply.outcomeUnknown() -> ModificationResult.Unknown(adyenError(reply))
            is AdyenReply.Answered -> ModificationResult.NotProcessed(adyenError(reply))
        }

    private fun captured(text: String): ModificationResult {
        val response =
            decodeAdyenModel(text, PaymentCaptureResponse::class.java)
                ?: return ModificationResult.Unknown("Unexpected response from Adyen")
        return when (response.status) {
            PaymentCaptureResponse.StatusEnum.RECEIVED -> ModificationResult.Received(response.pspReference)
            else -> ModificationResult.Unknown(unexpectedStatus(text))
        }
    }

    private fun adjusted(text: String): ModificationResult {
        val response =
            decodeAdyenModel(text, PaymentAmountUpdateResponse::class.java)
                ?: return ModificationResult.Unknown("Unexpected response from Adyen")
        return when (response.status) {
            PaymentAmountUpdateResponse.StatusEnum.RECEIVED -> {
                ModificationResult.Received(response.pspReference)
            }

            PaymentAmountUpdateResponse.StatusEnum.AUTHORISED -> {
                ModificationResult.Authorised(response.pspReference, response.adjustAuthorisationData)
            }

            PaymentAmountUpdateResponse.StatusEnum.REFUSED -> {
                ModificationResult.Refused(adyenField(text, "refusalReason") ?: "Refused by the card issuer")
            }

            else -> {
                ModificationResult.Unknown(unexpectedStatus(text))
            }
        }
    }

    private fun ModificationAmount.checkoutAmount(): Amount = Amount().currency(currency).value(value)

    private suspend fun post(
        path: List<String>,
        body: String,
        idempotencyKey: String?,
    ): AdyenReply {
        val url = baseUrl.newBuilder().apply { path.forEach { addPathSegment(it) } }.build()
        return http.post(url, body, timeout, idempotencyKey)
    }
}
