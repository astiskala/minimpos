package io.github.astiskala.minimpos.terminal.checkout

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.github.astiskala.minimpos.terminal.transport.AdyenHttp
import io.github.astiskala.minimpos.terminal.transport.AdyenReply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [PaymentLinkApi] over HTTPS with OkHttp, posting the Checkout API's JSON directly, like [CheckoutModifications]. The
 * API key goes in the `x-api-key` header and is never part of an error message.
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
) : PaymentLinkApi {
    private val http = AdyenHttp(credentials.apiKey, baseClient, dispatcher, "check the internet connection and the live URL prefix")

    override suspend fun create(
        request: PaymentLinkRequest,
        idempotencyKey: String,
    ): PaymentLinkResult = result(http.post(url(), body(request).toString(), timeout, idempotencyKey))

    override suspend fun status(linkId: String): PaymentLinkResult = result(http.get(url(linkId), timeout))

    override suspend fun expire(linkId: String): PaymentLinkResult {
        val body = JsonObject().apply { addProperty("status", "expired") }
        return result(http.patch(url(linkId), body.toString(), timeout))
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
        val json = parseObject(text)
        val id = json?.string("id")
        val url = json?.string("url")
        if (id == null || url == null) return PaymentLinkResult.Unknown("Unexpected response from Adyen")
        val status =
            when (json.string("status")) {
                "active" -> PaymentLinkStatus.ACTIVE
                "paymentPending" -> PaymentLinkStatus.PAYMENT_PENDING
                "completed", "paid" -> PaymentLinkStatus.COMPLETED
                "expired" -> PaymentLinkStatus.EXPIRED
                else -> return PaymentLinkResult.Unknown("Unexpected status from Adyen: ${json.string("status").orEmpty()}")
            }
        val expiresAt = json.string("expiresAt")?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }
        return PaymentLinkResult.Answered(PaymentLink(id, url, status, expiresAt))
    }

    private fun body(request: PaymentLinkRequest) =
        JsonObject().apply {
            addProperty("merchantAccount", credentials.merchantAccount)
            addProperty("reference", request.reference)
            add(
                "amount",
                JsonObject().apply {
                    addProperty("currency", request.amount.currency)
                    addProperty("value", request.amount.value)
                },
            )
            addProperty("expiresAt", EXPIRY_FORMAT.format(request.expiresAt.truncatedTo(ChronoUnit.SECONDS).atOffset(ZoneOffset.UTC)))
            if (request.lineItems.isNotEmpty()) add("lineItems", lineItems(request.lineItems))
            request.shopperEmail?.let { addProperty("shopperEmail", it) }
            request.shopperReference?.let { addProperty("shopperReference", it) }
            request.recurringProcessingModel?.takeIf { request.shopperReference != null }?.let {
                addProperty("storePaymentMethodMode", "askForConsent")
                addProperty("recurringProcessingModel", it)
            }
            request.shopperLocale?.let { addProperty("shopperLocale", it) }
            request.countryCode?.let { addProperty("countryCode", it) }
            if (request.metadata.isNotEmpty()) {
                add("metadata", JsonObject().apply { request.metadata.forEach { (key, value) -> addProperty(key, value) } })
            }
        }

    private fun lineItems(items: List<PaymentLinkLineItem>) =
        JsonArray().apply {
            items.forEach { item ->
                add(
                    JsonObject().apply {
                        addProperty("id", item.id)
                        addProperty("description", item.description)
                        addProperty("quantity", item.quantity)
                        addProperty("amountIncludingTax", item.amountIncludingTax)
                        addProperty("amountExcludingTax", item.amountExcludingTax)
                        addProperty("taxAmount", item.taxAmount)
                        addProperty("taxPercentage", item.taxPercentage)
                    },
                )
            }
        }

    private companion object {
        /** ISO 8601 with seconds and the offset, as Adyen's examples show it (`2026-10-03T09:30:00Z`). */
        val EXPIRY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
    }
}
