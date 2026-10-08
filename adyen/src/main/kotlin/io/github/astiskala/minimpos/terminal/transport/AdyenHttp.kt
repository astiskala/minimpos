package io.github.astiskala.minimpos.terminal.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runInterruptible
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** What one call to an Adyen API returned, see [AdyenHttp]. */
internal sealed interface AdyenReply {
    /**
     * Adyen answered with an HTTP status, successful or not.
     *
     * @property code The HTTP status code.
     * @property body The response body, possibly empty.
     */
    data class Answered(
        val code: Int,
        val body: String,
    ) : AdyenReply {
        /** Whether [code] is 2xx. */
        val ok: Boolean get() = code in HTTP_OK
    }

    /**
     * There was no HTTP answer.
     *
     * @property message Why, in English, without the API key.
     * @property sent Whether the request may have reached Adyen (a timeout or a dropped connection), so its effect is
     *   unknown; false when it never left the device (no connection, unknown host, no route).
     */
    data class Failed(
        val message: String,
        val sent: Boolean,
    ) : AdyenReply {
        /** This failure as a Terminal API [Delivery]: [Delivery.MaybeSent] when it was [sent], else [Delivery.NotSent]. */
        val delivery: Delivery get() = if (sent) Delivery.MaybeSent(message) else Delivery.NotSent(message)
    }
}

/**
 * Calls to Adyen's HTTPS APIs (Cloud device, Checkout and Management) with an API key, which goes in the `x-api-key`
 * header and never into a message. Requests are never re-sent silently: a payment or capture sent twice could charge
 * twice, so deliberate retries rely on idempotency keys. Each call is limited by its own timeout only (no read timeout,
 * since a cloud payment's answer comes once the shopper is done). Network failures are returned as
 * [AdyenReply.Failed], never thrown; blocking work runs on [dispatcher] and is interrupted when the coroutine is
 * cancelled.
 *
 * @param apiKey The API key.
 * @param baseClient The client to derive from, so an app can share one connection pool.
 * @param dispatcher Where the blocking calls run.
 * @param unknownHostAdvice What to check when a host is unknown, appended to that failure's message.
 */
internal class AdyenHttp(
    private val apiKey: String,
    baseClient: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
    private val unknownHostAdvice: String = "check the internet connection",
) {
    private val client =
        baseClient
            .newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

    /** `GET [url]`, waiting at most [timeout]. */
    suspend fun get(
        url: HttpUrl,
        timeout: Duration,
    ): AdyenReply = call(Request.Builder().url(url).get(), timeout)

    /** `POST [url]` with the JSON [json], waiting at most [timeout]; with [idempotencyKey] Adyen applies it at most once. */
    suspend fun post(
        url: HttpUrl,
        json: String,
        timeout: Duration,
        idempotencyKey: String? = null,
    ): AdyenReply =
        call(
            Request
                .Builder()
                .url(url)
                .apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
                .post(json.toRequestBody(JSON)),
            timeout,
        )

    /** `PATCH [url]` with the JSON [json], waiting at most [timeout]. */
    suspend fun patch(
        url: HttpUrl,
        json: String,
        timeout: Duration,
    ): AdyenReply = call(Request.Builder().url(url).patch(json.toRequestBody(JSON)), timeout)

    private suspend fun call(
        request: Request.Builder,
        timeout: Duration,
    ): AdyenReply =
        runInterruptible(dispatcher) {
            val built = request.header("x-api-key", apiKey).build()
            try {
                client
                    .newBuilder()
                    .callTimeout(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(built)
                    .execute()
                    .use { AdyenReply.Answered(it.code, it.body.string()) }
            } catch (e: IOException) {
                failure(built.url, e)
            }
        }

    private fun failure(
        url: HttpUrl,
        error: IOException,
    ): AdyenReply.Failed =
        when (error) {
            is ConnectException -> AdyenReply.Failed("Cannot connect to Adyen at ${url.host}", sent = false)
            is UnknownHostException -> AdyenReply.Failed("Unknown host ${url.host}; $unknownHostAdvice", sent = false)
            is NoRouteToHostException -> AdyenReply.Failed("No route to ${url.host}", sent = false)
            else -> AdyenReply.Failed(error.message ?: "No response from Adyen", sent = true)
        }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

/** The successful HTTP status codes. */
internal val HTTP_OK = 200..299

/** HTTP 401: the API key is not accepted. */
internal const val HTTP_UNAUTHORIZED = 401

/** HTTP 403: the API key may not do this. */
internal const val HTTP_FORBIDDEN = 403

/** HTTP 404: Adyen does not know the requested resource. */
internal const val HTTP_NOT_FOUND = 404
