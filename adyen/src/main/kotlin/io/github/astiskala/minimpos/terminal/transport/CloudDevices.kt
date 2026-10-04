package io.github.astiskala.minimpos.terminal.transport

import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import io.github.astiskala.minimpos.terminal.parse.FormEncoding
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Adyen's live data centres for the Cloud device API. A terminal is best reached through the one closest to its store;
 * Adyen's table assigns each country to one ([forCountry]).
 */
enum class CloudRegion(
    /** The part of the host name after `device-api-`, e.g. `live-au`. */
    val prefix: String,
) {
    /** Europe, the Middle East and the default for countries Adyen does not list. */
    EU("live"),

    /** The Americas. */
    US("live-us"),

    /** Australia and New Zealand. */
    AU("live-au"),

    /** Northeast Asia, including Hong Kong, Japan, Malaysia and Singapore. */
    NEA("live-nea"),
    ;

    /** Adyen's data centre table. */
    companion object {
        private val COUNTRIES =
            mapOf(
                US to setOf("US", "CA", "MX", "BR", "PR"),
                AU to setOf("AU", "NZ"),
                NEA to setOf("HK", "JP", "MY", "SG"),
            )

        /** The data centre Adyen assigns to [country] (ISO 3166-1 alpha-2, any case); [EU] for any other country. */
        fun forCountry(country: String): CloudRegion = COUNTRIES.entries.firstOrNull { country.uppercase() in it.value }?.key ?: EU
    }
}

/**
 * One Cloud device API endpoint: TEST (one for all regions) or a LIVE data centre.
 *
 * Constructing it for [TerminalEnvironment.LIVE] without a [region], or for TEST with one, throws
 * [IllegalArgumentException].
 *
 * @property environment Where the API key and the terminals belong.
 * @property region The live data centre; null for TEST.
 */
data class CloudEndpoint(
    val environment: TerminalEnvironment,
    val region: CloudRegion? = null,
) {
    init {
        require((environment == TerminalEnvironment.LIVE) == (region != null)) { "Only LIVE endpoints have a region" }
    }

    /** The endpoint's base URL, without a trailing slash. */
    val baseUrl: String
        get() = "https://device-api-${region?.prefix ?: "test"}.adyen.com"

    /** The TEST endpoint. */
    companion object {
        /** The one TEST endpoint, for all regions. */
        val TEST = CloudEndpoint(TerminalEnvironment.TEST)
    }
}

/**
 * The credentials for the Cloud device API. [toString] leaves out the API key, so credentials can be logged.
 * Constructing them with a blank API key or merchant account throws [IllegalArgumentException].
 *
 * @property apiKey An API key with the Cloud Device API role (the app also uses it for the Checkout API).
 * @property merchantAccount The merchant account the terminals belong to.
 */
data class CloudCredentials(
    val apiKey: String,
    val merchantAccount: String,
) {
    init {
        require(apiKey.isNotBlank()) { "An API key is required" }
        require(merchantAccount.isNotBlank()) { "A merchant account is required" }
    }

    override fun toString() = "CloudCredentials($merchantAccount)"
}

/** Which Cloud device API endpoint an API key works with, from [CloudDevices.detect]. */
sealed interface CloudDetection {
    /**
     * The key works with [endpoint].
     *
     * @property endpoint Where to send requests.
     * @property devices The POIIDs of the terminals connected there now.
     */
    data class Found(
        val endpoint: CloudEndpoint,
        val devices: List<String>,
    ) : CloudDetection

    /**
     * No endpoint accepted the key, or none could be reached.
     *
     * @property message Why, in English.
     */
    data class Failed(
        val message: String,
    ) : CloudDetection
}

/** The terminals connected to one Cloud device API endpoint, from [AdyenCloudDevices.connectedDevices]. */
sealed interface CloudListing {
    /**
     * Adyen listed them.
     *
     * @property devices Their POIIDs, as Adyen sent them.
     */
    data class Listed(
        val devices: List<String>,
    ) : CloudListing

    /**
     * Adyen refused the request.
     *
     * @property code The HTTP status code, e.g. 401 for a key the endpoint does not know.
     * @property message Why, in English.
     */
    data class Refused(
        val code: Int,
        val message: String,
    ) : CloudListing

    /**
     * Adyen could not be reached, or answered with something unusable.
     *
     * @property message Why, in English.
     */
    data class Failed(
        val message: String,
    ) : CloudListing
}

/**
 * Terminals reached over the internet through Adyen's Cloud device API, for one merchant account and API key. Unlike
 * the local Terminal API, the environment is supplied by the merchant: [detect] only finds its data centre.
 * All functions are safe to call from any thread.
 */
interface CloudDevices {
    /**
     * Finds an endpoint in [environment], never trying the other environment. For LIVE, tries the data centre for
     * [country], then the others, preferring one where [poiId] is connected. Never throws; failures are [CloudDetection.Failed].
     *
     * @param environment The selected TEST or LIVE environment.
     * @param poiId The terminal that will be used, or null when none is chosen yet.
     * @param country The device's country (ISO 3166-1 alpha-2), which picks the first live data centre to try.
     */
    suspend fun detect(
        environment: TerminalEnvironment,
        poiId: String?,
        country: String,
    ): CloudDetection

    /** Sends Terminal API requests through [endpoint] (see [CloudTransport]). */
    fun transport(endpoint: CloudEndpoint): TerminalTransport
}

/**
 * [CloudDevices] over HTTPS with OkHttp. The API key goes in the `x-api-key` header and is never part of a message.
 *
 * @param credentials The API key and merchant account.
 * @param baseUrl Where each endpoint lives; tests point it at a local server.
 * @param baseClient The client to derive from, so an app can share one connection pool.
 * @param dispatcher Where the blocking calls run.
 */
class AdyenCloudDevices(
    private val credentials: CloudCredentials,
    private val baseUrl: (CloudEndpoint) -> HttpUrl = { it.baseUrl.toHttpUrl() },
    baseClient: OkHttpClient = OkHttpClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CloudDevices {
    private val http = AdyenHttp(credentials.apiKey, baseClient, dispatcher)

    override suspend fun detect(
        environment: TerminalEnvironment,
        poiId: String?,
        country: String,
    ): CloudDetection =
        when (environment) {
            TerminalEnvironment.TEST -> listingAt(CloudEndpoint.TEST)
            TerminalEnvironment.LIVE -> detectLive(poiId, CloudRegion.forCountry(country))
        }

    /**
     * Tries the live data centres, [first] first: the first one listing [poiId] (any, without one) wins; else the first
     * that answered. The first failure ends the search while no data centre has answered.
     */
    private suspend fun detectLive(
        poiId: String?,
        first: CloudRegion,
    ): CloudDetection {
        var result: CloudDetection? = null
        var done = false
        val regions = (listOf(first) + (CloudRegion.entries - first)).iterator()
        while (!done && regions.hasNext()) {
            val attempt = listingAt(CloudEndpoint(TerminalEnvironment.LIVE, regions.next()))
            val connected = attempt is CloudDetection.Found && (poiId == null || poiId in attempt.devices)
            if (connected || result == null) result = attempt
            done = connected || result is CloudDetection.Failed
        }
        return checkNotNull(result)
    }

    private suspend fun listingAt(endpoint: CloudEndpoint): CloudDetection =
        when (val live = connectedDevices(endpoint)) {
            is CloudListing.Listed -> {
                CloudDetection.Found(endpoint, live.devices)
            }

            is CloudListing.Refused if live.code == HTTP_UNAUTHORIZED -> {
                CloudDetection.Failed("Adyen did not accept the API key for ${endpoint.environment.name} (HTTP 401)")
            }

            is CloudListing.Refused -> {
                CloudDetection.Failed(live.message)
            }

            is CloudListing.Failed -> {
                CloudDetection.Failed(live.message)
            }
        }

    /** The terminals connected to [endpoint] (`GET …/connectedDevices`). Never throws. */
    suspend fun connectedDevices(endpoint: CloudEndpoint): CloudListing =
        when (val reply = http.get(merchant(endpoint).addPathSegment("connectedDevices").build(), LISTING_TIMEOUT)) {
            is AdyenReply.Answered -> listing(reply.code, reply.body)
            is AdyenReply.Failed -> CloudListing.Failed(reply.message)
        }

    override fun transport(endpoint: CloudEndpoint): TerminalTransport = CloudTransport(credentials, merchant(endpoint).build(), http)

    private fun listing(
        code: Int,
        body: String,
    ): CloudListing {
        if (code !in HTTP_OK) return CloudListing.Refused(code, httpError(code, body, credentials.merchantAccount))
        val devices =
            runCatching {
                JsonParser
                    .parseString(body)
                    .asJsonObject
                    .getAsJsonArray("uniqueDeviceIds")
                    ?.map { it.asString }
                    .orEmpty()
            }.getOrNull() ?: return CloudListing.Failed("Unexpected response from Adyen")
        return CloudListing.Listed(devices)
    }

    private fun merchant(endpoint: CloudEndpoint): HttpUrl.Builder =
        baseUrl(endpoint)
            .newBuilder()
            .addPathSegment(API_VERSION)
            .addPathSegment("merchants")
            .addPathSegment(credentials.merchantAccount)

    /** Fixed values of the Cloud device API. */
    companion object {
        /** The Cloud device API version in every path. */
        private const val API_VERSION = "v1"

        /** Adyen: cloud payment requests need a timeout of more than 150 seconds. */
        val MIN_TRANSACTION_TIMEOUT: Duration = 160.seconds
        private val LISTING_TIMEOUT = 30.seconds
    }
}

/**
 * Terminal API requests through the Cloud device API's synchronous endpoint
 * (`POST /v1/merchants/{merchantAccount}/devices/{POIID}/sync`), which forwards each one to the terminal named by its
 * POIID and holds the connection open until the terminal answers. Requests travel as plain JSON over TLS, authenticated
 * by the API key; they are not encrypted with the terminal's shared key.
 *
 * No connection, an unknown terminal (HTTP 404), a terminal Adyen reports as not connected and a refused key or
 * request (other HTTP 4xx) are [Delivery.NotSent]; a call that may have reached Adyen without an answer, HTTP 5xx or
 * 408, an unreadable reply and any other event notification Adyen answers with instead of a response (e.g. that the
 * terminal did not answer in time) are [Delivery.MaybeSent], after which the outcome is unknown.
 */
class CloudTransport internal constructor(
    private val credentials: CloudCredentials,
    /** The merchant account's base URL, `…/v1/merchants/{merchantAccount}`. */
    private val merchantUrl: HttpUrl,
    private val http: AdyenHttp,
) : TerminalTransport {
    private val gson = TerminalAPIGsonBuilder.create()

    override suspend fun send(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): Delivery {
        val poiId = request.saleToPOIRequest?.messageHeader?.poiid ?: return Delivery.NotSent("The request names no terminal")
        val url =
            merchantUrl
                .newBuilder()
                .addPathSegment("devices")
                .addPathSegment(poiId)
                .addPathSegment("sync")
                .build()
        return when (val reply = http.post(url, gson.toJson(request), timeout)) {
            is AdyenReply.Failed -> reply.delivery
            is AdyenReply.Answered if !reply.ok -> failure(reply.code, reply.body, poiId)
            is AdyenReply.Answered -> reply(reply.body, poiId)
        }
    }

    private fun failure(
        code: Int,
        body: String,
        poiId: String,
    ): Delivery {
        val message = httpError(code, body, credentials.merchantAccount, poiId)
        return if (code in HTTP_CLIENT_ERROR && code != HTTP_TIMEOUT) Delivery.NotSent(message) else Delivery.MaybeSent(message)
    }

    private fun reply(
        body: String,
        poiId: String,
    ): Delivery {
        // An abort is acknowledged with a bare "ok".
        val root =
            try {
                JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            } catch (ignored: JsonParseException) {
                null
            } ?: return Delivery.Answered(null)
        if (root.has("SaleToPOIResponse")) return Delivery.Answered(gson.fromJson(root, TerminalAPIResponse::class.java))
        val details = eventDetails(root) ?: return Delivery.MaybeSent("Unexpected response from Adyen")
        val message = FormEncoding.decode(details)["message"] ?: details
        return if (NOT_CONNECTED.any { message.contains(it, ignoreCase = true) }) {
            Delivery.NotSent("Terminal $poiId is not connected to Adyen: $message")
        } else {
            Delivery.MaybeSent("Adyen did not get an answer from terminal $poiId: $message")
        }
    }

    private fun eventDetails(root: JsonObject): String? =
        runCatching {
            gson
                .fromJson(root, TerminalAPIRequest::class.java)
                .saleToPOIRequest
                ?.eventNotification
                ?.eventDetails
        }.getOrNull()

    private companion object {
        /** How Adyen words that a terminal cannot be reached at all, so the request never got to it. */
        val NOT_CONNECTED = listOf("not connected", "offline", "unknown device", "not found")
    }
}

private val HTTP_CLIENT_ERROR = 400..499
private const val HTTP_NOT_FOUND = 404
private const val HTTP_TIMEOUT = 408

/** Adyen's explanation of an HTTP error, with the status code, e.g. "Invalid API key (HTTP 401)". */
internal fun httpError(
    code: Int,
    body: String,
    merchantAccount: String,
    poiId: String? = null,
): String {
    val message = runCatching { JsonParser.parseString(body).asJsonObject["message"]?.asString }.getOrNull()
    val fallback =
        when (code) {
            HTTP_UNAUTHORIZED -> "Adyen did not accept the API key"
            HTTP_FORBIDDEN -> "The API key may not use merchant account $merchantAccount, or lacks the Cloud Device API role"
            HTTP_NOT_FOUND -> "Adyen does not know terminal ${poiId.orEmpty()} in merchant account $merchantAccount"
            else -> "Adyen returned an error"
        }
    return "${message ?: fallback} (HTTP $code)"
}
