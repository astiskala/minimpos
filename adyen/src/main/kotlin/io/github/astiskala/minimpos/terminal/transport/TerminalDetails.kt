package io.github.astiskala.minimpos.terminal.transport

import com.adyen.model.management.Key
import com.adyen.model.management.ListTerminalsResponse
import com.adyen.model.management.MeApiCredential
import com.adyen.model.management.Nexo
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

    /** Terminal settings contain unsupported fields or values that cannot be safely preserved in a PATCH. */
    SETTINGS_UNREADABLE,

    /** An encryption-key object is present but lacks an identifier, version or passphrase; absence is not verified. */
    KEY_INCOMPLETE,

    /** An encryption-key object has a version outside the supported range. */
    KEY_INVALID,
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

/** Effective shared-key lookup; absence is distinct from denied, malformed or unavailable settings. */
sealed interface SharedKeyLookup {
    /** Valid key, possibly inherited from a higher account level.
     * @property key Secret values held only until encrypted storage.
     */
    class Found(
        val key: DiscoveredKey,
    ) : SharedKeyLookup

    /** Readable settings contain no encryption key. */
    data object Missing : SharedKeyLookup

    /** Settings could not be read; never grants permission to create or replace a key.
     * @property reason Non-secret typed failure.
     */
    data class Failed(
        val reason: ManagementFailure,
    ) : SharedKeyLookup
}

/** Result of an explicitly requested terminal-specific key creation. */
sealed interface SharedKeyUpdate {
    /** Read-back key after PATCH, or an existing key reused without PATCH.
     * @property key Effective secret values.
     * @property created Whether a PATCH was sent successfully.
     */
    class Ready(
        val key: DiscoveredKey,
        val created: Boolean,
    ) : SharedKeyUpdate

    /** Creation or verification failed; the caller retains its encrypted recovery record.
     * @property reason Non-secret failure.
     * @property uncertain Whether a PATCH may have taken effect without verified read-back.
     */
    data class Failed(
        val reason: ManagementFailure,
        val uncertain: Boolean = false,
    ) : SharedKeyUpdate
}

/** Management discovery and explicit key creation; main-safe, without silent retries or environment fallback. */
interface TerminalDetailsApi {
    /** Checks terminal-access, terminal-settings write and Advanced roles in [environment]. */
    suspend fun credential(environment: TerminalEnvironment): CredentialLookup = CredentialLookup.Failed(ManagementFailure.UNAVAILABLE)

    /** Reads all visible terminals, or only the exact [id] using a targeted substring search followed by exact matching. */
    suspend fun terminals(
        environment: TerminalEnvironment,
        id: String? = null,
    ): TerminalListing

    /** Reads the effective key, distinguishing verified absence from failed reads. */
    suspend fun sharedKey(
        id: String,
        environment: TerminalEnvironment,
    ): SharedKeyLookup

    /** Creates [key] only after a fresh settings read finds no existing key; preserves the complete nexo object.
     * The caller must confirm the terminal/environment and persist recovery before calling. No atomic compare-and-set
     * is available, so another administrator must not edit the same settings concurrently.
     */
    suspend fun createSharedKey(
        id: String,
        environment: TerminalEnvironment,
        key: DiscoveredKey,
    ): SharedKeyUpdate = SharedKeyUpdate.Failed(ManagementFailure.UNAVAILABLE)
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
        val normalized = roles.map { it.replace("—", "-").replace("–", "-").replace(" ", "") }.toSet()
        return if (normalized.containsAll(REQUIRED_ROLES)) {
            CredentialLookup.Allowed
        } else {
            CredentialLookup.Failed(ManagementFailure.PERMISSION)
        }
    }

    override suspend fun terminals(
        environment: TerminalEnvironment,
        id: String?,
    ): TerminalListing {
        if (id != null) {
            val reply =
                http.get(
                    baseUrl(environment)
                        .newBuilder()
                        .addPathSegment("terminals")
                        .addQueryParameter("searchQuery", id)
                        .build(),
                    TIMEOUT,
                )
            val parsed = parsePage(reply) ?: return TerminalListing.Failed("Terminal lookup is unavailable", reason(reply))
            return TerminalListing.Listed(parsed.first.filter { it.id == id }.distinctBy { it.id }, environment)
        }
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
    ): SharedKeyLookup {
        val reply = http.get(settingsUrl(id, environment), TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return SharedKeyLookup.Failed(reason(reply))
        val settings = decodeTerminalSettings(reply.body) ?: return SharedKeyLookup.Failed(ManagementFailure.SETTINGS_UNREADABLE)
        return lookup(settings)
    }

    override suspend fun createSharedKey(
        id: String,
        environment: TerminalEnvironment,
        key: DiscoveredKey,
    ): SharedKeyUpdate {
        val url = settingsUrl(id, environment)
        val before = http.get(url, TIMEOUT)
        if (before !is AdyenReply.Answered || !before.ok) return SharedKeyUpdate.Failed(reason(before))
        val settings = decodeTerminalSettings(before.body) ?: return SharedKeyUpdate.Failed(ManagementFailure.SETTINGS_UNREADABLE)
        return when (val existing = lookup(settings)) {
            is SharedKeyLookup.Found -> SharedKeyUpdate.Ready(existing.key, created = false)
            is SharedKeyLookup.Failed -> SharedKeyUpdate.Failed(existing.reason)
            SharedKeyLookup.Missing -> patchKey(id, environment, key, settings)
        }
    }

    private suspend fun patchKey(
        id: String,
        environment: TerminalEnvironment,
        key: DiscoveredKey,
        settings: TerminalSettings,
    ): SharedKeyUpdate {
        val nexo = (settings.nexo ?: Nexo()).encryptionKey(Key().identifier(key.identifier).version(key.version).passphrase(key.passphrase))
        val json = TerminalSettings().nexo(nexo).toJson()
        val reply = http.patch(settingsUrl(id, environment), json, TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) {
            val uncertain =
                when (reply) {
                    is AdyenReply.Failed -> reply.sent
                    is AdyenReply.Answered -> reply.code >= HTTP_SERVER_ERROR || reply.code in UNCERTAIN_HTTP
                }
            return SharedKeyUpdate.Failed(reason(reply), uncertain)
        }
        return verifyKey(id, environment, key)
    }

    private suspend fun verifyKey(
        id: String,
        environment: TerminalEnvironment,
        key: DiscoveredKey,
    ): SharedKeyUpdate =
        when (val verified = sharedKey(id, environment)) {
            is SharedKeyLookup.Found -> {
                val same =
                    verified.key.identifier == key.identifier && verified.key.version == key.version &&
                        verified.key.passphrase == key.passphrase
                if (same) {
                    SharedKeyUpdate.Ready(
                        verified.key,
                        created = true,
                    )
                } else {
                    SharedKeyUpdate.Failed(ManagementFailure.UNREADABLE, uncertain = true)
                }
            }

            is SharedKeyLookup.Failed -> {
                SharedKeyUpdate.Failed(verified.reason, uncertain = true)
            }

            SharedKeyLookup.Missing -> {
                SharedKeyUpdate.Failed(ManagementFailure.UNREADABLE, uncertain = true)
            }
        }

    private fun settingsUrl(
        id: String,
        environment: TerminalEnvironment,
    ): HttpUrl =
        baseUrl(environment)
            .newBuilder()
            .addPathSegment("terminals")
            .addPathSegment(id)
            .addPathSegment("terminalSettings")
            .build()

    private fun lookup(settings: TerminalSettings): SharedKeyLookup {
        val key = settings.nexo?.encryptionKey ?: return SharedKeyLookup.Missing
        val identifier = key.identifier?.trim().orEmpty()
        val passphrase = key.passphrase.orEmpty()
        val version = key.version
        return when {
            identifier.isBlank() || passphrase.isBlank() || version == null -> SharedKeyLookup.Failed(ManagementFailure.KEY_INCOMPLETE)
            version !in KEY_VERSIONS -> SharedKeyLookup.Failed(ManagementFailure.KEY_INVALID)
            else -> SharedKeyLookup.Found(DiscoveredKey(identifier, version, passphrase))
        }
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
        const val HTTP_SERVER_ERROR = 500
        val UNCERTAIN_HTTP = setOf(408, 429)
        val REQUIRED_ROLES =
            setOf(
                "ManagementAPI-Terminalactionsread",
                "ManagementAPI-Terminalsettingsreadandwrite",
                "ManagementAPI-TerminalsettingsAdvancedreadandwrite",
            )
    }
}
