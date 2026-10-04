package io.github.astiskala.minimpos.terminal.transport

import com.adyen.Config
import com.adyen.constants.ApiConstants
import com.adyen.httpclient.ClientInterface
import com.adyen.model.RequestOptions
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPISecuredRequest
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import io.github.astiskala.minimpos.terminal.parse.FormEncoding
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * HTTP client for Adyen's [com.adyen.service.TerminalLocalAPI], built on OkHttp. The library's default Apache
 * HttpClient 5 does not run on Android (it calls `jdk.net.Sockets`), and the library lets callers plug in their own
 * [ClientInterface] for exactly this.
 *
 * It also turns replies that are not encrypted Terminal API responses (a terminal rejecting the request, or a
 * "Bad JSON" reply) into [TerminalRejectedException] with the terminal's explanation, instead of letting the library
 * fail on them.
 *
 * Every `request` overload posts the body unchanged and returns the response body. Calls block the calling thread; the
 * connect timeout and the timeout for the whole call come from the library's [Config]. Failures are thrown as
 * [TerminalUntrustedException] (certificate not accepted), [TerminalUnreachableException] (no connection),
 * [TerminalRejectedException] (reply not encrypted, e.g. a wrong shared key), [TerminalProtocolException] (HTTP error or
 * a reply that is not JSON), or OkHttp's own [java.io.IOException] (e.g. a timeout).
 */
class TerminalHttpClient(
    /** Decides which terminals are trusted; see [TerminalTls]. */
    tls: TerminalTls,
    /** Decrypts encrypted event notifications in a rejection so their reason can be shown; null leaves it out. */
    private val crypto: NexoCrypto? = null,
    /** The client to derive from, so an app can share one connection pool and dispatcher. */
    baseClient: OkHttpClient = OkHttpClient(),
) : ClientInterface {
    private val gson = TerminalAPIGsonBuilder.create()
    private val client =
        baseClient
            .newBuilder()
            .sslSocketFactory(tls.socketFactory, tls.trustManager)
            .hostnameVerifier(tls.hostnameVerifier)
            // A payment response only comes once the shopper is done, so only the call timeout (set per call) applies.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Silently re-sending a payment request could charge the shopper twice.
            .retryOnConnectionFailure(false)
            .build()

    override fun request(
        endpoint: String,
        requestBody: String,
        config: Config,
    ): String = post(endpoint, requestBody, config)

    override fun request(
        endpoint: String,
        requestBody: String,
        config: Config,
        isApiKeyRequired: Boolean,
    ): String = post(endpoint, requestBody, config)

    override fun request(
        endpoint: String,
        requestBody: String,
        config: Config,
        isApiKeyRequired: Boolean,
        requestOptions: RequestOptions?,
    ): String = post(endpoint, requestBody, config)

    override fun request(
        endpoint: String,
        requestBody: String,
        config: Config,
        isApiKeyRequired: Boolean,
        requestOptions: RequestOptions?,
        httpMethod: ApiConstants.HttpMethod?,
    ): String = post(endpoint, requestBody, config)

    override fun request(
        endpoint: String,
        requestBody: String,
        config: Config,
        isApiKeyRequired: Boolean,
        requestOptions: RequestOptions?,
        httpMethod: ApiConstants.HttpMethod?,
        params: Map<String, String>?,
    ): String = post(endpoint, requestBody, config)

    private fun post(
        endpoint: String,
        body: String,
        config: Config,
    ): String {
        val url = endpoint.toHttpUrl()
        val call =
            client
                .newBuilder()
                .connectTimeout(config.connectionTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout(config.readTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .post(body.toRequestBody(JSON))
                        .build(),
                )
        val text =
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) throw TerminalProtocolException("Terminal returned HTTP ${response.code}")
                    response.body.string()
                }
            } catch (e: SSLHandshakeException) {
                throw untrusted(e)
            } catch (e: SSLPeerUnverifiedException) {
                throw untrusted(e)
            } catch (e: ConnectException) {
                // With fast fallback, a refused IPv6 attempt can mask the real TLS failure on IPv4.
                tlsFailure(e)?.let { throw untrusted(it) }
                throw TerminalUnreachableException("Cannot connect to the terminal at ${url.host}:${url.port}", e)
            } catch (e: UnknownHostException) {
                throw TerminalUnreachableException("Unknown terminal host ${url.host}", e)
            } catch (e: NoRouteToHostException) {
                throw TerminalUnreachableException("No route to terminal host ${url.host}", e)
            }
        if (text.isNotBlank()) requireSecured(text)
        return text
    }

    private fun requireSecured(text: String) {
        val root =
            try {
                JsonParser.parseString(text)
            } catch (e: JsonParseException) {
                throw TerminalProtocolException("Unexpected response from the terminal", e)
            }
        val secured = (root as? JsonObject)?.objectAt("SaleToPOIResponse")
        if (secured?.has("NexoBlob") == true) return
        throw TerminalRejectedException(rejection(root))
    }

    /** Explains an unencrypted (or event-only) reply, e.g. a Reject event when the shared key does not match. */
    private fun rejection(root: JsonElement): String {
        val details =
            runCatching {
                when {
                    root.isJsonArray -> root.asJsonArray.firstOrNull()?.asString
                    root is JsonObject -> eventDetails(root)
                    else -> null
                }
            }.getOrNull()?.let { FormEncoding.decode(it)["message"] ?: it }
        val base = "Terminal rejected the request" + details?.let { ": $it" }.orEmpty()
        return if (details != null && KEY_HINTS.any { details.contains(it, ignoreCase = true) }) "$base. $KEY_ADVICE" else base
    }

    private fun eventDetails(root: JsonObject): String? {
        val request = root.objectAt("SaleToPOIRequest") ?: return null
        val plain =
            if (request.has("NexoBlob")) {
                val secured = gson.fromJson(root, TerminalAPISecuredRequest::class.java).saleToPOIRequest
                crypto?.decrypt(secured) ?: return null
            } else {
                root.toString()
            }
        return gson
            .fromJson(plain, TerminalAPIRequest::class.java)
            .saleToPOIRequest
            ?.eventNotification
            ?.eventDetails
    }

    private fun JsonObject.objectAt(name: String): JsonObject? = get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun tlsFailure(error: Throwable): Throwable? =
        generateSequence(error) { it.cause }
            .flatMap { sequenceOf(it) + it.suppressed.asSequence() }
            .firstOrNull { it is SSLHandshakeException || it is SSLPeerUnverifiedException }

    private fun untrusted(cause: Throwable) =
        TerminalUntrustedException("The device did not present a valid Adyen terminal certificate, so it is not trusted", cause)

    /** Messages shared with [AdyenLocalTransport]. */
    companion object {
        /** Appended to errors that point to a shared key mismatch, to tell the user what to check. */
        const val KEY_ADVICE = "Check the shared key identifier, version and passphrase in Terminal settings."
        private val KEY_HINTS = listOf("crypt", "hmac", "key", "security")
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
