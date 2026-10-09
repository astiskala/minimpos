package app.minimpos.terminal.transport

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
     * @property fault Why, without the API key; it states whether the request may have reached Adyen.
     */
    data class Failed(
        val fault: Fault,
    ) : AdyenReply
}

/**
 * The fault an unsuccessful HTTP answer means, sent with [key]: 401 and 403 name the key (and the [role] a 403 lacks),
 * 408, 429 and 5xx leave the outcome unknown, any other code is a rejection. [error] is Adyen's error message and code,
 * read with the API's own error model.
 */
internal fun AdyenReply.Answered.fault(
    key: ApiKey,
    role: String? = null,
    error: AdyenError? = null,
): Fault =
    when {
        code == HTTP_UNAUTHORIZED -> Fault.Credential(key)
        code == HTTP_FORBIDDEN -> Fault.Permission(key, role)
        code in UNCERTAIN_HTTP || code >= HTTP_SERVER_ERROR -> Fault.AdyenUnavailable(code, error?.code, error?.said)
        else -> Fault.AdyenRejected(code, error?.code, error?.said)
    }

/**
 * Adyen's explanation of a failed request, from the API's error model.
 *
 * @property said Its message; null when it sent none.
 * @property code Its error code; null when it sent none.
 */
internal data class AdyenError(
    val said: ExternalText?,
    val code: String?,
)

/**
 * Calls to Adyen's HTTPS APIs (Cloud device, Checkout and Management) with an API key, which goes in the `x-api-key`
 * header and never into a fault. Requests are never re-sent silently: a payment or capture sent twice could charge
 * twice, so deliberate retries rely on idempotency keys. Each call is limited by its own timeout only (no read timeout,
 * since a cloud payment's answer comes once the shopper is done). Network failures are returned as
 * [AdyenReply.Failed], never thrown: no connection, no route and an unknown host did not reach Adyen; a timeout or any
 * other failure may have. Blocking work runs on [dispatcher] and is interrupted when the coroutine is cancelled.
 *
 * @param apiKey The API key.
 * @param baseClient The client to derive from, so an app can share one connection pool.
 * @param dispatcher Where the blocking calls run.
 */
internal class AdyenHttp(
    private val apiKey: String,
    baseClient: OkHttpClient,
    private val dispatcher: CoroutineDispatcher,
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
                AdyenReply.Failed(failure(built.url, e))
            }
        }

    private fun failure(
        url: HttpUrl,
        error: IOException,
    ): Fault =
        when (error) {
            is ConnectException, is NoRouteToHostException -> Fault.Unreachable(url.host, terminal = false)
            is UnknownHostException -> Fault.UnknownHost(url.host, terminal = false)
            else -> error.fault()
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

/** Request timeout and rate limit: the request may still have been processed, or can be sent again. */
private val UNCERTAIN_HTTP = setOf(408, 429)

/** The first server error code; from here on the outcome is unknown. */
private const val HTTP_SERVER_ERROR = 500
