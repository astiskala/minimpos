package app.minimpos.terminal.transport

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
 * @property model Hardware model reported by Management; blank when absent, never inferred as a scanner-equipped variant.
 * @property countryCode Terminal country code; blank when absent, never replaced with the POS device's locale.
 */
data class TerminalDetails(
    val id: String,
    val merchantAccount: String,
    val host: String,
    val storeId: String = "",
    val model: String = "",
    val countryCode: String = "",
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
     * @property fault Why: the key, a missing role, a temporary failure, an unreadable answer or a list too long to read.
     */
    data class Failed(
        val fault: Fault,
    ) : TerminalListing
}

/** Read-only credential-role check; no secrets or raw API responses are exposed. */
sealed interface CredentialLookup {
    /** Credential has the required terminal-access role. */
    data object Allowed : CredentialLookup

    /** Credential could not be verified.
     * @property fault Authentication, permission, temporary failure or unreadable answer.
     */
    data class Failed(
        val fault: Fault,
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
     * @property fault Why, including [Fault.Malformed] settings or keys.
     */
    data class Failed(
        val fault: Fault,
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
     * @property fault Why.
     * @property uncertain Whether a PATCH may have taken effect without verified read-back.
     */
    data class Failed(
        val fault: Fault,
        val uncertain: Boolean = false,
    ) : SharedKeyUpdate
}

/** Management discovery and explicit key creation; main-safe, without silent retries or environment fallback. */
interface TerminalDetailsApi {
    /** Checks terminal-access, terminal-settings write and Advanced roles in [environment]. */
    suspend fun credential(environment: TerminalEnvironment): CredentialLookup = CredentialLookup.Failed(Fault.Unsupported)

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
    ): SharedKeyUpdate = SharedKeyUpdate.Failed(Fault.Unsupported)
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
        if (reply !is AdyenReply.Answered || !reply.ok) return CredentialLookup.Failed(reason(reply, MANAGEMENT_ROLES))
        val roles =
            runCatching {
                requireNotNull(decodeAdyenModel(reply.body, MeApiCredential::class.java)?.roles).map { requireNotNull(it) }
            }.getOrNull() ?: return CredentialLookup.Failed(Fault.UnreadableReply())
        val normalized = roles.map { it.replace("—", "-").replace("–", "-").replace(" ", "") }.toSet()
        return if (normalized.containsAll(REQUIRED_ROLES)) {
            CredentialLookup.Allowed
        } else {
            CredentialLookup.Failed(Fault.Permission(ApiKey.ADYEN, MANAGEMENT_ROLES))
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
            val parsed = parsePage(reply) ?: return TerminalListing.Failed(reason(reply))
            return TerminalListing.Listed(parsed.first.filter { it.id == id }.distinctBy { it.id }, environment)
        }
        var reply = page(environment, 1)
        val terminals = mutableListOf<TerminalDetails>()
        var number = 1
        var next = true
        var failure: Fault? = null
        while (next && number <= MAX_PAGES && failure == null) {
            val parsed = parsePage(reply)
            if (parsed == null) {
                failure = reason(reply)
            } else {
                terminals += parsed.first
                next = parsed.second
                if (next && ++number <= MAX_PAGES) reply = page(environment, number)
            }
        }
        return when {
            failure != null -> TerminalListing.Failed(failure)
            next -> TerminalListing.Failed(Fault.ListTooLarge)
            else -> TerminalListing.Listed(terminals.distinctBy { it.id }, environment)
        }
    }

    override suspend fun sharedKey(
        id: String,
        environment: TerminalEnvironment,
    ): SharedKeyLookup {
        val reply = http.get(settingsUrl(id, environment), TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return SharedKeyLookup.Failed(reason(reply))
        val settings = decodeTerminalSettings(reply.body) ?: return SharedKeyLookup.Failed(Fault.Malformed(MalformedPart.SETTINGS))
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
        val settings = decodeTerminalSettings(before.body) ?: return SharedKeyUpdate.Failed(Fault.Malformed(MalformedPart.SETTINGS))
        return when (val existing = lookup(settings)) {
            is SharedKeyLookup.Found -> SharedKeyUpdate.Ready(existing.key, created = false)
            is SharedKeyLookup.Failed -> SharedKeyUpdate.Failed(existing.fault)
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
            val fault = reason(reply)
            return SharedKeyUpdate.Failed(fault, fault.mayHaveTakenEffect)
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
                    SharedKeyUpdate.Failed(Fault.UnreadableReply(), uncertain = true)
                }
            }

            is SharedKeyLookup.Failed -> {
                SharedKeyUpdate.Failed(verified.fault, uncertain = true)
            }

            SharedKeyLookup.Missing -> {
                SharedKeyUpdate.Failed(Fault.UnreadableReply(), uncertain = true)
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
            identifier.isBlank() || passphrase.isBlank() || version == null -> SharedKeyLookup.Failed(Fault.Malformed(MalformedPart.KEY))
            version !in KEY_VERSIONS -> SharedKeyLookup.Failed(Fault.Malformed(MalformedPart.KEY_VERSION))
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

    /** Why [reply] gave no usable answer; a successful one was unreadable. A 403 lacks [role]. */
    private fun reason(
        reply: AdyenReply,
        role: String? = null,
    ): Fault =
        when (reply) {
            is AdyenReply.Failed -> reply.fault
            is AdyenReply.Answered if reply.ok -> Fault.UnreadableReply()
            is AdyenReply.Answered -> reply.fault(ApiKey.ADYEN, role, managementError(reply.body))
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
            terminal.model?.trim().orEmpty(),
            terminal.countryCode?.trim().orEmpty(),
        )
    }

    private companion object {
        val TIMEOUT = 15.seconds
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 100
        val KEY_VERSIONS = 1..9_999

        /** [REQUIRED_ROLES] as Adyen names them in the Customer Area. */
        const val MANAGEMENT_ROLES =
            "Management API—Terminal actions read, Terminal settings read and write, Terminal settings Advanced read and write"
        val REQUIRED_ROLES =
            setOf(
                "ManagementAPI-Terminalactionsread",
                "ManagementAPI-Terminalsettingsreadandwrite",
                "ManagementAPI-TerminalsettingsAdvancedreadandwrite",
            )
    }
}
