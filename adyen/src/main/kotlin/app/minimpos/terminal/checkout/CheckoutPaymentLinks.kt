package app.minimpos.terminal.checkout

import app.minimpos.terminal.transport.AdyenHttp
import app.minimpos.terminal.transport.AdyenReply
import app.minimpos.terminal.transport.decodeAdyenModel
import com.adyen.model.applicationinfo.ApplicationInfo
import com.adyen.model.checkout.Amount
import com.adyen.model.checkout.LineItem
import com.adyen.model.checkout.PaymentLinkResponse
import com.adyen.model.checkout.UpdatePaymentLinkRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.adyen.model.checkout.PaymentLinkRequest as AdyenPaymentLinkRequest

/**
 * [PaymentLinkApi] over HTTPS with OkHttp, using the Adyen library's typed Checkout models, like [CheckoutModifications].
 * The API key goes in the `x-api-key` header and is never part of an error message.
 *
 * Blocking calls run on [dispatcher]; cancelling the coroutine interrupts them. A request that could not be sent at
 * all and HTTP 4xx answers other than 408 and 429 are [PaymentLinkResult.NotProcessed]; timeouts, 408, 429, 5xx and
 * answers without a link are [PaymentLinkResult.Unknown].
 */
class CheckoutPaymentLinks(
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
) : PaymentLinkApi {
    private val http = AdyenHttp(credentials.apiKey, baseClient, dispatcher, "check the internet connection and the live URL prefix")

    override suspend fun create(
        request: PaymentLinkRequest,
        idempotencyKey: String,
    ): PaymentLinkResult = result(http.post(url(), body(request).toJson(), timeout, idempotencyKey))

    override suspend fun status(linkId: String): PaymentLinkResult = result(http.get(url(linkId), timeout))

    override suspend fun expire(linkId: String): PaymentLinkResult {
        val body = UpdatePaymentLinkRequest().status(UpdatePaymentLinkRequest.StatusEnum.EXPIRED)
        return result(http.patch(url(linkId), body.toJson(), timeout))
    }

    private fun url(linkId: String? = null): HttpUrl =
        baseUrl
            .newBuilder()
            .addPathSegment("paymentLinks")
            .apply { linkId?.let(::addPathSegment) }
            .build()

    private fun result(reply: AdyenReply): PaymentLinkResult =
        when (reply) {
            is AdyenReply.Failed if reply.sent -> PaymentLinkResult.Unknown(reply.message)
            is AdyenReply.Failed -> PaymentLinkResult.NotProcessed(reply.message)
            is AdyenReply.Answered if reply.ok -> link(reply.body)
            is AdyenReply.Answered if reply.outcomeUnknown() -> PaymentLinkResult.Unknown(adyenError(reply))
            is AdyenReply.Answered -> PaymentLinkResult.NotProcessed(adyenError(reply))
        }

    private fun link(text: String): PaymentLinkResult {
        val response = decodeAdyenModel(text, PaymentLinkResponse::class.java)
        val id = response?.id
        val url = response?.url
        if (id == null || url == null) return PaymentLinkResult.Unknown("Unexpected response from Adyen")
        val status =
            when (response.status) {
                PaymentLinkResponse.StatusEnum.ACTIVE -> PaymentLinkStatus.ACTIVE
                PaymentLinkResponse.StatusEnum.PAYMENTPENDING -> PaymentLinkStatus.PAYMENT_PENDING
                PaymentLinkResponse.StatusEnum.COMPLETED, PaymentLinkResponse.StatusEnum.PAID -> PaymentLinkStatus.COMPLETED
                PaymentLinkResponse.StatusEnum.EXPIRED -> PaymentLinkStatus.EXPIRED
                else -> return PaymentLinkResult.Unknown(unexpectedStatus(text))
            }
        return PaymentLinkResult.Answered(PaymentLink(id, url, status, response.expiresAt?.toInstant()))
    }

    private fun body(request: PaymentLinkRequest): AdyenPaymentLinkRequest =
        AdyenPaymentLinkRequest()
            .merchantAccount(credentials.merchantAccount)
            .reference(request.reference)
            .amount(Amount().currency(request.amount.currency).value(request.amount.value))
            .applicationInfo(application?.checkoutInfo())
            .expiresAt(request.expiresAt.truncatedTo(EXPIRY_PRECISION).atOffset(ZoneOffset.UTC))
            .shopperEmail(request.shopperEmail)
            .shopperReference(request.shopperReference)
            .shopperLocale(request.shopperLocale)
            .countryCode(request.countryCode)
            .apply {
                if (request.lineItems.isNotEmpty()) lineItems(request.lineItems.map(::lineItem))
                if (request.metadata.isNotEmpty()) metadata(request.metadata)
                request.recurringProcessingModel?.takeIf { request.shopperReference != null }?.let {
                    storePaymentMethodMode(AdyenPaymentLinkRequest.StorePaymentMethodModeEnum.ASKFORCONSENT)
                    recurringProcessingModel(AdyenPaymentLinkRequest.RecurringProcessingModelEnum.fromValue(it))
                }
            }

    private fun lineItem(item: PaymentLinkLineItem): LineItem =
        LineItem()
            .id(item.id)
            .description(item.description)
            .quantity(item.quantity.toLong())
            .amountIncludingTax(item.amountIncludingTax)
            .amountExcludingTax(item.amountExcludingTax)
            .taxAmount(item.taxAmount)
            .taxPercentage(item.taxPercentage)

    private companion object {
        /** Expiry timestamps use whole seconds, as Adyen's examples show (`2026-10-03T09:30:00Z`). */
        val EXPIRY_PRECISION: ChronoUnit = ChronoUnit.SECONDS
    }
}
