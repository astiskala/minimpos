package io.github.astiskala.minimpos.terminal.transport

import com.adyen.model.management.ListTerminalsResponse
import com.adyen.model.management.MeApiCredential
import com.adyen.model.management.Terminal
import com.adyen.model.management.TerminalSettings
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
 * @property storeId Currently assigned store ID, never the pending reassignment target; blank when not assigned to a store.
 */
data class TerminalDetails(
    val id: String,
    val merchantAccount: String,
    val host: String,
    val storeId: String = "",
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
     * @property reason Typed setup failure for localized presentation.
     */
    data class Failed(
        val message: String,
        val reason: ManagementFailure = ManagementFailure.UNAVAILABLE,
    ) : TerminalListing
}

/** Non-secret reason a Management setup check could not complete. */
enum class ManagementFailure {
    /** API key rejected by the selected environment. */
    AUTHENTICATION,

    /** Credential lacks the required Management role or resource access. */
    PERMISSION,

    /** Network or server failure; retry in the same environment. */
    UNAVAILABLE,

    /** Successful answer did not contain the required fields. */
    UNREADABLE,
}

/** Read-only credential-role check; no secrets or raw API responses are exposed. */
sealed interface CredentialLookup {
    /** Credential has the required terminal-access role. */
    data object Allowed : CredentialLookup

    /** Credential could not be verified.
     * @property reason Authentication, permission, temporary failure or malformed answer.
     */
    data class Failed(
        val reason: ManagementFailure,
    ) : CredentialLookup
}

/** Optional Management reads; safe to call from any thread, with no Adyen configuration mutations. */
interface TerminalDetailsApi {
    /** Checks the required terminal-access role in [environment], without cross-environment fallback. */
    suspend fun credential(environment: TerminalEnvironment): CredentialLookup = CredentialLookup.Failed(ManagementFailure.UNAVAILABLE)

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

    override suspend fun credential(environment: TerminalEnvironment): CredentialLookup {
        val reply = http.get(baseUrl(environment).newBuilder().addPathSegment("me").build(), TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return CredentialLookup.Failed(reason(reply))
        val roles =
            runCatching {
                requireNotNull(decodeAdyenModel(reply.body, MeApiCredential::class.java)?.roles).map { requireNotNull(it) }
            }.getOrNull() ?: return CredentialLookup.Failed(ManagementFailure.UNREADABLE)
        return if (roles.any { it.replace("—", "-").replace("–", "-").replace(" ", "") == "ManagementAPI-Terminalactionsread" }) {
            CredentialLookup.Allowed
        } else {
            CredentialLookup.Failed(ManagementFailure.PERMISSION)
        }
    }

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
            failure != null -> TerminalListing.Failed(failure, reason(reply))
            next -> TerminalListing.Failed("The terminal list is too large; enter the details manually")
            else -> TerminalListing.Listed(terminals.distinctBy { it.id }, environment)
        }
    }

    override suspend fun sharedKey(
        id: String,
        environment: TerminalEnvironment,
    ): DiscoveredKey? {
        val url =
            baseUrl(environment)
                .newBuilder()
                .addPathSegment("terminals")
                .addPathSegment(id)
                .addPathSegment("terminalSettings")
                .build()
        val reply = http.get(url, TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return null
        return runCatching {
            val key = requireNotNull(decodeAdyenModel(reply.body, TerminalSettings::class.java)?.nexo?.encryptionKey)
            val identifier = key.identifier?.trim().orEmpty()
            val passphrase = key.passphrase.orEmpty()
            val version = requireNotNull(key.version)
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
            val response = requireNotNull(decodeAdyenModel(reply.body, ListTerminalsResponse::class.java))
            val terminals = requireNotNull(response.data).map(::terminal)
            val next = response.links?.next?.href
            terminals to !next.isNullOrBlank()
        }.getOrNull()
    }

    private fun reason(reply: AdyenReply): ManagementFailure =
        when {
            reply !is AdyenReply.Answered -> ManagementFailure.UNAVAILABLE
            reply.code == HTTP_UNAUTHORIZED -> ManagementFailure.AUTHENTICATION
            reply.code == HTTP_FORBIDDEN -> ManagementFailure.PERMISSION
            reply.ok -> ManagementFailure.UNREADABLE
            else -> ManagementFailure.UNAVAILABLE
        }

    private fun terminal(terminal: Terminal): TerminalDetails {
        val id =
            terminal.id
                ?.trim()
                .orEmpty()
                .also { require(it.isNotBlank()) }
        val ethernet =
            terminal.connectivity
                ?.ethernet
                ?.ipAddress
                ?.trim()
                .orEmpty()
        val wifi =
            terminal.connectivity
                ?.wifi
                ?.ipAddress
                ?.trim()
                .orEmpty()
        return TerminalDetails(
            id,
            terminal.assignment
                ?.merchantId
                ?.trim()
                .orEmpty(),
            ethernet.ifBlank { wifi },
            terminal.assignment
                ?.storeId
                ?.trim()
                .orEmpty(),
        )
    }

    private companion object {
        val TIMEOUT = 15.seconds
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 100
        val KEY_VERSIONS = 1..9_999
    }
}
