package io.github.astiskala.minimpos.terminal.transport

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds

/** Read-only terminal assignment and last reported network address; empty optional fields need manual entry.
 * @property id Terminal POIID.
 * @property merchantAccount Assigned merchant account, never the reassignment target.
 * @property host Last reported Ethernet address, otherwise Wi-Fi address; possibly stale or blank.
 */
data class TerminalDetails(
    val id: String,
    val merchantAccount: String,
    val host: String,
)

/** A discovered encryption key, kept only in memory until encrypted storage; string conversion never reveals it.
 * @property identifier Shared-key identifier.
 * @property version Shared-key version.
 * @property passphrase Secret passphrase, never suitable for display or logging.
 */
class DiscoveredKey(
    val identifier: String,
    val version: Int,
    val passphrase: String,
)

/** Complete terminal list, or an unavailable lookup without partial results. */
sealed interface TerminalListing {
    /** Terminals visible to the credential.
     * @property terminals All pages, deduplicated by POIID.
     * @property environment Environment requested by the caller.
     */
    data class Listed(
        val terminals: List<TerminalDetails>,
        val environment: TerminalEnvironment,
    ) : TerminalListing

    /** Lookup could not complete; manual setup remains possible.
     * @property message Non-secret explanation.
     */
    data class Failed(
        val message: String,
    ) : TerminalListing
}

/** Optional Management reads; safe to call from any thread, with no Adyen configuration mutations. */
interface TerminalDetailsApi {
    /** Reads all visible terminals in [environment], without a merchant account or cross-environment fallback. */
    suspend fun terminals(environment: TerminalEnvironment): TerminalListing

    /** Reads the terminal's effective encryption key; null on denied, absent or malformed settings. */
    suspend fun sharedKey(
        id: String,
        environment: TerminalEnvironment,
    ): DiscoveredKey?
}

/** Management v3 terminal discovery with bounded pagination and no automatic request retries.
 * @param apiKey Credential sent only in the authentication header.
 * @param baseUrl Endpoint supplier; tests use local servers.
 * @param baseClient Shared HTTP client to derive from.
 * @param dispatcher Runs interruptible network calls.
 */
class AdyenTerminalDetails(
    apiKey: String,
    private val baseUrl: (TerminalEnvironment) -> HttpUrl = { AdyenStoreDetails.endpoint(it).toHttpUrl() },
    baseClient: OkHttpClient = OkHttpClient(),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : TerminalDetailsApi {
    private val http = AdyenHttp(apiKey, baseClient, dispatcher)

    override suspend fun terminals(environment: TerminalEnvironment): TerminalListing {
        var reply = page(environment, 1)
        val terminals = mutableListOf<TerminalDetails>()
        var number = 1
        var next = true
        var failure: String? = null
        while (next && number <= MAX_PAGES && failure == null) {
            val parsed = parsePage(reply)
            if (parsed == null) {
                failure = "Terminal discovery is unavailable; enter the details manually"
            } else {
                terminals += parsed.first
                next = parsed.second
                if (next && ++number <= MAX_PAGES) reply = page(environment, number)
            }
        }
        return when {
            failure != null -> TerminalListing.Failed(failure)
            next -> TerminalListing.Failed("The terminal list is too large; enter the details manually")
            else -> TerminalListing.Listed(terminals.distinctBy { it.id }, environment)
        }
    }

    override suspend fun sharedKey(
        id: String,
        environment: TerminalEnvironment,
    ): DiscoveredKey? {
        val url =
            baseUrl(
                environment,
            ).newBuilder().addPathSegment("terminals").addPathSegment(id).addPathSegment("terminalSettings").build()
        val reply = http.get(url, TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return null
        return runCatching {
            val key =
                JsonParser
                    .parseString(reply.body)
                    .asJsonObject
                    .getAsJsonObject("nexo")
                    .getAsJsonObject("encryptionKey")
            val identifier = key.text("identifier")
            val passphrase = key.text("passphrase", trim = false)
            val version = requireNotNull(key.get("version")?.asString?.toIntOrNull())
            if (identifier.isBlank() || passphrase.isBlank() ||
                version !in KEY_VERSIONS
            ) {
                null
            } else {
                DiscoveredKey(identifier, version, passphrase)
            }
        }.getOrNull()
    }

    private suspend fun page(
        environment: TerminalEnvironment,
        number: Int,
    ): AdyenReply =
        http.get(
            baseUrl(environment)
                .newBuilder()
                .addPathSegment("terminals")
                .addQueryParameter("pageSize", PAGE_SIZE.toString())
                .addQueryParameter("pageNumber", number.toString())
                .build(),
            TIMEOUT,
        )

    private fun parsePage(reply: AdyenReply): Pair<List<TerminalDetails>, Boolean>? {
        if (reply !is AdyenReply.Answered || !reply.ok) return null
        return runCatching {
            val json = JsonParser.parseString(reply.body).asJsonObject
            val terminals = requireNotNull(json.getAsJsonArray("data")).map { terminal(it.asJsonObject) }
            val next = json.getAsJsonObject("_links")?.getAsJsonObject("next")?.text("href")
            terminals to !next.isNullOrBlank()
        }.getOrNull()
    }

    private fun terminal(json: JsonObject): TerminalDetails {
        val id = json.text("id").also { require(it.isNotBlank()) }
        val connectivity = json.getAsJsonObject("connectivity")
        val ethernet = connectivity?.getAsJsonObject("ethernet")?.text("ipAddress").orEmpty()
        val wifi = connectivity?.getAsJsonObject("wifi")?.text("ipAddress").orEmpty()
        return TerminalDetails(id, json.getAsJsonObject("assignment")?.text("merchantId").orEmpty(), ethernet.ifBlank { wifi })
    }

    private companion object {
        val TIMEOUT = 15.seconds
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 100
        val KEY_VERSIONS = 1..9_999
    }
}
