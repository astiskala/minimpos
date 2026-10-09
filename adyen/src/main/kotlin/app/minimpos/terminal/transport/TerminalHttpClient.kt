package app.minimpos.terminal.transport

import app.minimpos.terminal.parse.FormEncoding
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
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * HTTP client for Adyen's [com.adyen.service.TerminalLocalAPI], built on OkHttp. The library's default Apache
 * HttpClient 5 does not run on Android (it calls `jdk.net.Sockets`), and the library lets callers plug in their own
 * [ClientInterface] for exactly this.
 *
 * It also turns replies that are not encrypted Terminal API responses (a terminal rejecting the request, or a
 * "Bad JSON" reply) into [Fault.KeyRejected] or [Fault.TerminalRejected] with the terminal's explanation, instead of
 * letting the library fail on them.
 *
 * Every `request` overload posts the body unchanged and returns the response body. Calls block the calling thread; the
 * connect timeout and the timeout for the whole call come from the library's [Config]. Failures are thrown as a
 * [FaultException] ([Fault.Untrusted], [Fault.Unreachable], [Fault.UnknownHost], a rejection, [Fault.TerminalHttp] or
 * [Fault.UnreadableReply]) or as OkHttp's own [java.io.IOException] (e.g. a timeout).
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
                    if (!response.isSuccessful) throw FaultException(Fault.TerminalHttp(response.code))
                    response.body.string()
                }
            } catch (ignored: SSLHandshakeException) {
                throw FaultException(Fault.Untrusted(url.host))
            } catch (ignored: SSLPeerUnverifiedException) {
                throw FaultException(Fault.Untrusted(url.host))
            } catch (e: ConnectException) {
                // With fast fallback, a refused IPv6 attempt can mask the real TLS failure on IPv4.
                throw FaultException(
                    if (tlsFailure(e)) Fault.Untrusted(url.host) else Fault.Unreachable("${url.host}:${url.port}", terminal = true),
                )
            } catch (ignored: UnknownHostException) {
                throw FaultException(Fault.UnknownHost(url.host, terminal = true))
            } catch (ignored: NoRouteToHostException) {
                throw FaultException(Fault.Unreachable("${url.host}:${url.port}", terminal = true))
            }
        if (text.isNotBlank()) requireSecured(text)
        return text
    }

    private fun requireSecured(text: String) {
        val root =
            try {
                JsonParser.parseString(text)
            } catch (ignored: JsonParseException) {
                throw FaultException(Fault.UnreadableReply())
            }
        val secured = (root as? JsonObject)?.objectAt("SaleToPOIResponse")
        if (secured?.has("NexoBlob") == true) return
        throw FaultException(rejection(root))
    }

    /**
     * The fault of an unencrypted (or event-only) reply, e.g. a Reject event when the shared key does not match: a key
     * rejection when the terminal's explanation mentions the key or encryption, else another rejection.
     */
    private fun rejection(root: JsonElement): Fault {
        val details =
            runCatching {
                when {
                    root.isJsonArray -> root.asJsonArray.firstOrNull()?.asString
                    root is JsonObject -> eventDetails(root)
                    else -> null
                }
            }.getOrNull()?.let { FormEncoding.decode(it)["message"] ?: it }
        val said = ExternalText.of(details)
        return if (details != null &&
            KEY_HINTS.any { details.contains(it, ignoreCase = true) }
        ) {
            Fault.KeyRejected(said)
        } else {
            Fault.TerminalRejected(said)
        }
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

    /** Whether a TLS failure hides in [error]'s causes or suppressed exceptions. */
    private fun tlsFailure(error: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        return generateSequence(error) { it.cause }
            .takeWhile { seen.add(it) }
            .flatMap { sequenceOf(it) + it.suppressed.asSequence() }
            .any { it is SSLHandshakeException || it is SSLPeerUnverifiedException }
    }

    private companion object {
        /** Words in a terminal's rejection that point to the shared key. */
        val KEY_HINTS = listOf("crypt", "hmac", "key", "security")
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
