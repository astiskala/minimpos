package io.minimpos.terminal.checkout

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.minimpos.terminal.transport.AdyenHttp
import io.minimpos.terminal.transport.AdyenReply
import io.minimpos.terminal.transport.HTTP_FORBIDDEN
import io.minimpos.terminal.transport.HTTP_UNAUTHORIZED
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [PaymentModifications] over HTTPS with OkHttp, posting the Checkout API's JSON directly (the Adyen library's
 * Checkout models need Jackson and keep rules for hundreds of classes on Android). The API key goes in the `x-api-key`
 * header and is never part of an error message.
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
            body(amount, reference),
            idempotencyKey,
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
            body(amount, reference).apply {
                addProperty("reason", "DelayedCharge")
                adjustAuthorisationData?.let { addProperty("adjustAuthorisationData", it) }
            },
            idempotencyKey,
        )

    override suspend fun verify(): String? {
        val body = JsonObject().apply { addProperty("merchantAccount", credentials.merchantAccount) }
        return when (val reply = post(listOf("paymentMethods"), body, idempotencyKey = null)) {
            is AdyenReply.Answered if reply.ok -> null
            is AdyenReply.Answered if reply.code == HTTP_UNAUTHORIZED -> "Adyen did not accept the API key (HTTP 401)"
            is AdyenReply.Answered if reply.code == HTTP_FORBIDDEN -> forbidden()
            is AdyenReply.Answered -> error(reply)
            is AdyenReply.Failed -> reply.message
        }
    }

    private fun forbidden() = "The API key may not use merchant account ${credentials.merchantAccount} (HTTP 403)"

    private suspend fun modify(
        path: List<String>,
        body: JsonObject,
        idempotencyKey: String,
    ): ModificationResult =
        when (val reply = post(path, body, idempotencyKey)) {
            is AdyenReply.Failed if reply.sent -> ModificationResult.Unknown(reply.message)
            is AdyenReply.Failed -> ModificationResult.NotProcessed(reply.message)
            is AdyenReply.Answered if reply.ok -> success(reply.body)
            is AdyenReply.Answered if reply.code in RETRYABLE || reply.code >= HTTP_SERVER_ERROR -> ModificationResult.Unknown(error(reply))
            is AdyenReply.Answered -> ModificationResult.NotProcessed(error(reply))
        }

    private fun success(text: String): ModificationResult {
        val json = parse(text) ?: return ModificationResult.Unknown("Unexpected response from Adyen")
        val psp = json.string("pspReference")
        return when (json.string("status")?.lowercase()) {
            "received" -> ModificationResult.Received(psp)
            "authorised" -> ModificationResult.Authorised(psp, json.string("adjustAuthorisationData"))
            "refused" -> ModificationResult.Refused(json.string("refusalReason") ?: "Refused by the card issuer")
            else -> ModificationResult.Unknown("Unexpected status from Adyen: ${json.string("status").orEmpty()}")
        }
    }

    /** Adyen's error message and code for a failed request, e.g. "Invalid amount (HTTP 422, code 137)". */
    private fun error(reply: AdyenReply.Answered): String {
        val json = parse(reply.body)
        val message = json?.string("message") ?: "Adyen returned an error"
        val code = json?.string("errorCode")?.let { ", code $it" }.orEmpty()
        return "$message (HTTP ${reply.code}$code)"
    }

    private fun body(
        amount: ModificationAmount,
        reference: String,
    ) = JsonObject().apply {
        addProperty("merchantAccount", credentials.merchantAccount)
        add(
            "amount",
            JsonObject().apply {
                addProperty("currency", amount.currency)
                addProperty("value", amount.value)
            },
        )
        addProperty("reference", reference)
    }

    private suspend fun post(
        path: List<String>,
        body: JsonObject,
        idempotencyKey: String?,
    ): AdyenReply {
        val url = baseUrl.newBuilder().apply { path.forEach { addPathSegment(it) } }.build()
        return http.post(url, body.toString(), timeout, idempotencyKey)
    }

    private fun parse(text: String): JsonObject? = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private companion object {
        /** Request timeout and rate limit, after which the request is sent again with the same idempotency key. */
        val RETRYABLE = setOf(408, 429)
        const val HTTP_SERVER_ERROR = 500
    }
}
