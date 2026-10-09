package app.minimpos.terminal.checkout

import app.minimpos.terminal.transport.AdyenHttp
import app.minimpos.terminal.transport.AdyenReply
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.adyenField
import app.minimpos.terminal.transport.decodeAdyenModel
import com.adyen.model.applicationinfo.ApplicationInfo
import com.adyen.model.checkout.Amount
import com.adyen.model.checkout.PaymentAmountUpdateRequest
import com.adyen.model.checkout.PaymentAmountUpdateResponse
import com.adyen.model.checkout.PaymentCaptureRequest
import com.adyen.model.checkout.PaymentCaptureResponse
import com.adyen.model.checkout.PaymentMethodsRequest
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
 * all (no connection, unknown host) and HTTP 4xx answers other than 408 and 429 fail with a fault that took no effect;
 * timeouts, 408, 429, 5xx and unreadable answers with one that may have.
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
    private val http = AdyenHttp(credentials.apiKey, baseClient, dispatcher)

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

    override suspend fun verify(): Fault? {
        val body = PaymentMethodsRequest().merchantAccount(credentials.merchantAccount).toJson()
        return post(listOf("paymentMethods"), body, idempotencyKey = null).failure()
    }

    private suspend fun modify(
        path: List<String>,
        body: String,
        idempotencyKey: String,
        success: (String) -> ModificationResult,
    ): ModificationResult =
        when (val reply = post(path, body, idempotencyKey)) {
            is AdyenReply.Failed -> ModificationResult.Failed(reply.fault)
            is AdyenReply.Answered if reply.ok -> success(reply.body)
            is AdyenReply.Answered -> ModificationResult.Failed(reply.checkoutFault())
        }

    private fun captured(text: String): ModificationResult {
        val response =
            decodeAdyenModel(text, PaymentCaptureResponse::class.java)
                ?: return ModificationResult.Failed(Fault.UnreadableReply())
        return when (response.status) {
            PaymentCaptureResponse.StatusEnum.RECEIVED -> ModificationResult.Received(response.pspReference)
            else -> ModificationResult.Failed(unexpectedStatus(text))
        }
    }

    private fun adjusted(text: String): ModificationResult {
        val response =
            decodeAdyenModel(text, PaymentAmountUpdateResponse::class.java)
                ?: return ModificationResult.Failed(Fault.UnreadableReply())
        return when (response.status) {
            PaymentAmountUpdateResponse.StatusEnum.RECEIVED -> {
                ModificationResult.Received(response.pspReference)
            }

            PaymentAmountUpdateResponse.StatusEnum.AUTHORISED -> {
                ModificationResult.Authorised(response.pspReference, response.adjustAuthorisationData)
            }

            PaymentAmountUpdateResponse.StatusEnum.REFUSED -> {
                ModificationResult.Refused(ExternalText.of(adyenField(text, "refusalReason")))
            }

            else -> {
                ModificationResult.Failed(unexpectedStatus(text))
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
