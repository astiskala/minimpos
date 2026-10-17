package app.minimpos.terminal.transport

import com.adyen.model.management.Dcc
import com.adyen.model.management.Gratuity
import com.adyen.model.management.Key
import com.adyen.model.management.ListTerminalsResponse
import com.adyen.model.management.MeApiCredential
import com.adyen.model.management.MinorUnitsMonetaryValue
import com.adyen.model.management.Moto
import com.adyen.model.management.Nexo
import com.adyen.model.management.OfflineProcessing
import com.adyen.model.management.StoreAndForward
import com.adyen.model.management.SupportedCardTypes
import com.adyen.model.management.Terminal
import com.adyen.model.management.TerminalSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.Locale
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
    /** Credential has every required role. */
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

/** Terminal-level gratuity settings for one [currency] (`terminalSettings.gratuities`).
 * @property currency ISO 4217 currency code.
 * @property usePredefinedEntries True when the terminal displays predefined buttons; false for a direct tip prompt.
 * @property predefinedEntries Up to four percentage (`"10%"`) or minor-unit (`"100"`) options.
 * @property allowCustomAmount Whether one button lets the shopper enter a custom tip amount.
 */
data class TerminalGratuity(
    val currency: String,
    val usePredefinedEntries: Boolean = false,
    val predefinedEntries: List<String> = emptyList(),
    val allowCustomAmount: Boolean = false,
)

/** Terminal-level MOTO configuration (`terminalSettings.moto`).
 * @property enabled Whether MOTO is enabled on the terminal.
 * @property maxAmount Maximum MOTO transaction amount in major units, or null when unset.
 */
data class TerminalMotoSettings(
    val enabled: Boolean = false,
    val maxAmount: Int? = null,
)

/** Terminal-level offline EMV and store-and-forward limits (`offlineProcessing` and `storeAndForward`).
 * @property chipFloorLimit Maximum chip offline EMV amount in major units, or null when unset.
 * @property maxStoreAndForwardMinor Maximum store-and-forward transaction amount in minor units by currency code.
 * @property maxPayments Maximum number of stored offline payments before forwarding, or null when unset.
 */
data class TerminalOfflineLimits(
    val chipFloorLimit: Int? = null,
    val maxStoreAndForwardMinor: Map<String, Long> = emptyMap(),
    val maxPayments: Int? = null,
) {
    /** Whether any offline EMV or store-and-forward limit is active for [currency]. */
    fun configuredFor(currency: String): Boolean =
        (chipFloorLimit != null && chipFloorLimit > 0) ||
            ((maxStoreAndForwardMinor[currency] ?: 0L) > 0L && (maxPayments == null || maxPayments > 0))
}

/** Non-secret terminal feature settings read from Management `terminalSettings`.
 * @property gratuities Configured gratuities by currency.
 * @property dccEnabled Whether Dynamic Currency Conversion is enabled (`dcc.enableDcc`).
 * @property surchargeConfigured Whether card surcharge rules or confirmation are configured (`surcharge`).
 * @property moto MOTO settings (`moto`).
 * @property offline Offline EMV and store-and-forward limits (`offlineProcessing`, `storeAndForward`).
 */
data class RemoteTerminalCapabilities(
    val gratuities: List<TerminalGratuity> = emptyList(),
    val dccEnabled: Boolean = false,
    val surchargeConfigured: Boolean = false,
    val moto: TerminalMotoSettings = TerminalMotoSettings(),
    val offline: TerminalOfflineLimits = TerminalOfflineLimits(),
) {
    /** The gratuity configuration for [currency], if any. */
    fun gratuityFor(currency: String): TerminalGratuity? = gratuities.firstOrNull { it.currency.equals(currency, ignoreCase = true) }
}

/** Result of reading capability sections from Management `terminalSettings`. */
sealed interface TerminalCapabilitiesLookup {
    /** Readable capability settings.
     * @property capabilities Current remote terminal feature settings.
     */
    data class Found(
        val capabilities: RemoteTerminalCapabilities,
    ) : TerminalCapabilitiesLookup

    /** Lookup could not complete.
     * @property fault Why.
     */
    data class Failed(
        val fault: Fault,
    ) : TerminalCapabilitiesLookup
}

/** Scoped Management `terminalSettings` update for one capability section; never touches `nexo` or unrelated sections. */
sealed interface TerminalCapabilitiesPatch {
    /** Updates the gratuity entry for [currency], preserving other currencies' entries.
     * @property currency ISO 4217 currency code being updated.
     * @property gratuity New gratuity configuration for [currency], or null to remove its entry.
     */
    data class Gratuity(
        val currency: String,
        val gratuity: TerminalGratuity?,
    ) : TerminalCapabilitiesPatch

    /** Updates `dcc.enableDcc`.
     * @property enabled Whether DCC is enabled.
     */
    data class Dcc(
        val enabled: Boolean,
    ) : TerminalCapabilitiesPatch

    /** Updates `moto`.
     * @property settings New MOTO settings.
     */
    data class Moto(
        val settings: TerminalMotoSettings,
    ) : TerminalCapabilitiesPatch

    /** Updates `offlineProcessing` and `storeAndForward` for [currency], preserving other currencies' store-and-forward limits.
     * @property currency ISO 4217 currency code for [maxStoreAndForwardMinor].
     * @property chipFloorLimit Chip floor limit in major units, or null to clear.
     * @property maxStoreAndForwardMinor Maximum store-and-forward amount in minor units of [currency], or null to remove.
     * @property maxPayments Maximum number of stored payments, or null to leave unset.
     */
    data class Offline(
        val currency: String,
        val chipFloorLimit: Int?,
        val maxStoreAndForwardMinor: Int?,
        val maxPayments: Int?,
    ) : TerminalCapabilitiesPatch
}

/** Result of a scoped capability `PATCH` and read-back verification. */
sealed interface TerminalCapabilitiesUpdate {
    /** Updated capabilities verified from the read-back response.
     * @property capabilities Freshly read capability settings.
     */
    data class Ready(
        val capabilities: RemoteTerminalCapabilities,
    ) : TerminalCapabilitiesUpdate

    /** Update or read-back failed.
     * @property fault Why.
     * @property uncertain Whether the `PATCH` may have taken effect without verified read-back.
     */
    data class Failed(
        val fault: Fault,
        val uncertain: Boolean = false,
    ) : TerminalCapabilitiesUpdate
}

/** Management discovery and explicit key creation; main-safe, without silent retries or environment fallback. */
interface TerminalDetailsApi {
    /**
     * Checks that the credential has every [AdyenRole.required] role in [environment] (with the cloud device role when
     * payments go to a [cloud] terminal); a missing role fails with a [Fault.Permission] naming each one missing.
     */
    suspend fun credential(
        environment: TerminalEnvironment,
        cloud: Boolean = false,
    ): CredentialLookup = CredentialLookup.Failed(Fault.Unsupported)

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

    /** Reads non-secret capability sections from `/terminals/{id}/terminalSettings`. */
    suspend fun capabilities(
        id: String,
        environment: TerminalEnvironment,
    ): TerminalCapabilitiesLookup = TerminalCapabilitiesLookup.Failed(Fault.Unsupported)

    /** Applies a scoped [patch] to `/terminals/{id}/terminalSettings` and verifies read-back; never touches `nexo`. */
    suspend fun updateCapabilities(
        id: String,
        environment: TerminalEnvironment,
        patch: TerminalCapabilitiesPatch,
    ): TerminalCapabilitiesUpdate = TerminalCapabilitiesUpdate.Failed(Fault.Unsupported)
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

    override suspend fun credential(
        environment: TerminalEnvironment,
        cloud: Boolean,
    ): CredentialLookup {
        val required = AdyenRole.required(cloud)
        val reply = http.get(baseUrl(environment).newBuilder().addPathSegment("me").build(), TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) {
            return CredentialLookup.Failed(managementFault(reply, role = AdyenRole.names(required)))
        }
        val roles =
            runCatching {
                requireNotNull(decodeAdyenModel(reply.body, MeApiCredential::class.java)?.roles).map { requireNotNull(it) }
            }.getOrNull() ?: return CredentialLookup.Failed(Fault.UnreadableReply())
        val missing = AdyenRole.missing(roles, required)
        return if (missing.isEmpty()) {
            CredentialLookup.Allowed
        } else {
            CredentialLookup.Failed(Fault.Permission(ApiKey.ADYEN, AdyenRole.names(missing)))
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
            val parsed = (reply as? AdyenReply.Answered)?.takeIf { it.ok }?.let { parsePage(it.body) }
            return if (parsed == null) {
                TerminalListing.Failed(managementFault(reply))
            } else {
                TerminalListing.Listed(parsed.first.filter { it.id == id }.distinctBy { it.id }, environment)
            }
        }
        return when (val pages = collectManagementPages(fetch = { page(environment, it) }, parse = ::parsePage)) {
            is ManagementPages.Failed -> TerminalListing.Failed(pages.fault)
            is ManagementPages.Listed -> TerminalListing.Listed(pages.items.distinctBy { it.id }, environment)
        }
    }

    override suspend fun sharedKey(
        id: String,
        environment: TerminalEnvironment,
    ): SharedKeyLookup {
        val reply = http.get(settingsUrl(id, environment), TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return SharedKeyLookup.Failed(managementFault(reply))
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
        if (before !is AdyenReply.Answered || !before.ok) return SharedKeyUpdate.Failed(managementFault(before))
        val settings = decodeTerminalSettings(before.body) ?: return SharedKeyUpdate.Failed(Fault.Malformed(MalformedPart.SETTINGS))
        return when (val existing = lookup(settings)) {
            is SharedKeyLookup.Found -> SharedKeyUpdate.Ready(existing.key, created = false)
            is SharedKeyLookup.Failed -> SharedKeyUpdate.Failed(existing.fault)
            SharedKeyLookup.Missing -> patchKey(id, environment, key, settings)
        }
    }

    override suspend fun capabilities(
        id: String,
        environment: TerminalEnvironment,
    ): TerminalCapabilitiesLookup {
        val reply = http.get(settingsUrl(id, environment), TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) return TerminalCapabilitiesLookup.Failed(managementFault(reply))
        val settings =
            decodeCapabilitySettings(reply.body) ?: return TerminalCapabilitiesLookup.Failed(Fault.Malformed(MalformedPart.SETTINGS))
        return TerminalCapabilitiesLookup.Found(parseCapabilities(settings))
    }

    override suspend fun updateCapabilities(
        id: String,
        environment: TerminalEnvironment,
        patch: TerminalCapabilitiesPatch,
    ): TerminalCapabilitiesUpdate {
        val url = settingsUrl(id, environment)
        val before =
            if (patch is TerminalCapabilitiesPatch.Gratuity || patch is TerminalCapabilitiesPatch.Offline) {
                val reply = http.get(url, TIMEOUT)
                if (reply !is AdyenReply.Answered || !reply.ok) return TerminalCapabilitiesUpdate.Failed(managementFault(reply))
                decodeCapabilitySettings(reply.body)
                    ?: return TerminalCapabilitiesUpdate.Failed(Fault.Malformed(MalformedPart.SETTINGS))
            } else {
                null
            }
        val body = buildCapabilitiesPatch(patch, before)
        val reply = http.patch(url, body, TIMEOUT)
        if (reply !is AdyenReply.Answered || !reply.ok) {
            val fault = managementFault(reply)
            return TerminalCapabilitiesUpdate.Failed(fault, fault.mayHaveTakenEffect)
        }
        return when (val verified = capabilities(id, environment)) {
            is TerminalCapabilitiesLookup.Failed -> {
                TerminalCapabilitiesUpdate.Failed(verified.fault, uncertain = true)
            }

            is TerminalCapabilitiesLookup.Found -> {
                if (matchesPatch(patch, verified.capabilities)) {
                    TerminalCapabilitiesUpdate.Ready(verified.capabilities)
                } else {
                    TerminalCapabilitiesUpdate.Failed(Fault.UnreadableReply(), uncertain = true)
                }
            }
        }
    }

    private fun buildCapabilitiesPatch(
        patch: TerminalCapabilitiesPatch,
        before: TerminalSettings?,
    ): String =
        when (patch) {
            is TerminalCapabilitiesPatch.Gratuity -> {
                val retained =
                    before
                        ?.gratuities
                        .orEmpty()
                        .filterNotNull()
                        .filterNot { it.currency.equals(patch.currency, ignoreCase = true) }
                val updated =
                    patch.gratuity?.let { item ->
                        Gratuity()
                            .currency(patch.currency.uppercase())
                            .usePredefinedTipEntries(item.usePredefinedEntries)
                            .predefinedTipEntries(item.predefinedEntries)
                            .allowCustomAmount(item.allowCustomAmount)
                    }
                TerminalSettings().gratuities(if (updated == null) retained else retained + updated).toJson()
            }

            is TerminalCapabilitiesPatch.Dcc -> {
                TerminalSettings().dcc(Dcc().enableDcc(patch.enabled)).toJson()
            }

            is TerminalCapabilitiesPatch.Moto -> {
                TerminalSettings()
                    .moto(Moto().enableMoto(patch.settings.enabled).maxAmount(patch.settings.maxAmount))
                    .toJson()
            }

            is TerminalCapabilitiesPatch.Offline -> {
                val retained =
                    before
                        ?.storeAndForward
                        ?.maxAmount
                        .orEmpty()
                        .filterNotNull()
                        .filterNot { it.currencyCode.equals(patch.currency, ignoreCase = true) }
                val updated =
                    patch.maxStoreAndForwardMinor?.let { amount ->
                        MinorUnitsMonetaryValue().currencyCode(patch.currency.uppercase()).amount(amount)
                    }
                val amounts = if (updated == null) retained else retained + updated
                val cardTypes =
                    before?.storeAndForward?.supportedCardTypes
                        ?: SupportedCardTypes()
                            .credit(true)
                            .debit(true)
                            .prepaid(true)
                            .takeIf { amounts.isNotEmpty() }
                TerminalSettings()
                    .offlineProcessing(OfflineProcessing().chipFloorLimit(patch.chipFloorLimit))
                    .storeAndForward(
                        StoreAndForward()
                            .maxAmount(amounts)
                            .maxPayments(patch.maxPayments)
                            .supportedCardTypes(cardTypes),
                    ).toJson()
            }
        }

    private fun matchesPatch(
        patch: TerminalCapabilitiesPatch,
        actual: RemoteTerminalCapabilities,
    ): Boolean =
        when (patch) {
            is TerminalCapabilitiesPatch.Gratuity -> {
                val found = actual.gratuityFor(patch.currency)
                if (patch.gratuity == null) {
                    found == null
                } else {
                    found ==
                        patch.gratuity.copy(
                            currency = patch.currency.trim().uppercase(),
                            predefinedEntries = if (patch.gratuity.usePredefinedEntries) patch.gratuity.predefinedEntries else emptyList(),
                        )
                }
            }

            is TerminalCapabilitiesPatch.Dcc -> {
                actual.dccEnabled == patch.enabled
            }

            is TerminalCapabilitiesPatch.Moto -> {
                actual.moto.enabled == patch.settings.enabled &&
                    (patch.settings.maxAmount == null || actual.moto.maxAmount == patch.settings.maxAmount)
            }

            is TerminalCapabilitiesPatch.Offline -> {
                (patch.chipFloorLimit == null || actual.offline.chipFloorLimit == patch.chipFloorLimit) &&
                    actual.offline.maxStoreAndForwardMinor[patch.currency.uppercase()] == patch.maxStoreAndForwardMinor?.toLong() &&
                    (patch.maxPayments == null || actual.offline.maxPayments == patch.maxPayments)
            }
        }

    private fun parseCapabilities(settings: TerminalSettings): RemoteTerminalCapabilities {
        val surcharge = settings.surcharge
        val moto = settings.moto
        return RemoteTerminalCapabilities(
            gratuities = parseGratuities(settings),
            dccEnabled = settings.dcc?.enableDcc == true,
            surchargeConfigured = surcharge?.askConfirmation == true || !surcharge?.configurations.isNullOrEmpty(),
            moto = TerminalMotoSettings(enabled = moto?.enableMoto == true, maxAmount = moto?.maxAmount),
            offline =
                TerminalOfflineLimits(
                    chipFloorLimit = settings.offlineProcessing?.chipFloorLimit,
                    maxStoreAndForwardMinor = parseStoreAndForward(settings),
                    maxPayments = settings.storeAndForward?.maxPayments,
                ),
        )
    }

    private fun parseGratuities(settings: TerminalSettings): List<TerminalGratuity> =
        settings.gratuities
            .orEmpty()
            .filterNotNull()
            .mapNotNull { item ->
                val currency =
                    item.currency
                        ?.trim()
                        ?.uppercase()
                        .orEmpty()
                if (currency.isEmpty()) {
                    null
                } else {
                    val usePredefined = item.usePredefinedTipEntries == true
                    val entries =
                        if (usePredefined) {
                            item.predefinedTipEntries
                                .orEmpty()
                                .mapNotNull { entry -> entry?.trim()?.takeIf { it.isNotEmpty() } }
                        } else {
                            emptyList()
                        }
                    TerminalGratuity(currency, usePredefined, entries, item.allowCustomAmount == true)
                }
            }

    private fun parseStoreAndForward(settings: TerminalSettings): Map<String, Long> =
        settings.storeAndForward
            ?.maxAmount
            .orEmpty()
            .filterNotNull()
            .mapNotNull { entry ->
                val code =
                    entry.currencyCode
                        ?.trim()
                        ?.uppercase()
                        .orEmpty()
                val amount = entry.amount?.toLong()
                if (code.isNotEmpty() && amount != null && amount >= 0) code to amount else null
            }.toMap()

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
            val fault = managementFault(reply)
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
                .addQueryParameter("pageSize", MANAGEMENT_PAGE_SIZE.toString())
                .addQueryParameter("pageNumber", number.toString())
                .build(),
            TIMEOUT,
        )

    private fun parsePage(body: String): Pair<List<TerminalDetails>, Boolean>? =
        runCatching {
            val response = requireNotNull(decodeAdyenModel(body, ListTerminalsResponse::class.java))
            val terminals = requireNotNull(response.data).map(::terminal)
            val next = response.links?.next?.href
            terminals to !next.isNullOrBlank()
        }.getOrNull()

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
        private val TIMEOUT = 15.seconds
        private val KEY_VERSIONS = 1..9_999
    }
}

/**
 * An API credential role every real Mini mPOS setup requires beyond Adyen's default ones, as the Customer Area names it
 * ([displayName]). [CLOUD_DEVICE] is required only while payments go to a cloud terminal.
 *
 * @property displayName The role's name in the Customer Area, shown verbatim when it is missing.
 * @property alternatives Other spellings Adyen has used for the same role.
 */
enum class AdyenRole(
    val displayName: String,
    private val alternatives: List<String> = emptyList(),
) {
    /** Lists terminals and their assignment: discovery and terminal access checks. */
    TERMINAL_ACTIONS_READ("Management API - Terminal actions read"),

    /** Reads and updates terminal settings: capabilities. */
    TERMINAL_SETTINGS("Management API - Terminal settings read and write"),

    /** Reads and creates shared keys. */
    TERMINAL_SETTINGS_ADVANCED("Management API - Terminal settings Advanced read and write"),

    /** Reads payment method settings: scanned wallets and gift cards. */
    PAYMENT_METHODS_READ("Management API - Payment methods read"),

    /** Reads the assigned store's receipt details. */
    STORES_READ("Management API - Stores read"),

    /** Reads the merchant account's legal name for receipts. */
    ACCOUNT_READ("Management API - Account read", listOf("Management API - Accounts read")),

    /** Lists a shopper's saved cards. */
    MERCHANT_RECURRING("Merchant Recurring role"),

    /** Reaches cloud terminals; only for that destination. */
    CLOUD_DEVICE("Cloud Device API role"),
    ;

    /** Role checks and names. */
    companion object {
        /** The roles a real setup requires; [cloud] adds [CLOUD_DEVICE]. */
        fun required(cloud: Boolean): List<AdyenRole> = entries.filter { cloud || it != CLOUD_DEVICE }

        /**
         * Which of [required] the credential's [granted] role names lack, compared without case, spaces or the kind of
         * dash, since Adyen's own spellings vary.
         */
        fun missing(
            granted: List<String>,
            required: List<AdyenRole>,
        ): List<AdyenRole> {
            val have = granted.map(::normalized).toSet()
            return required.filter { role -> (listOf(role.displayName) + role.alternatives).none { normalized(it) in have } }
        }

        /** [roles] by their Customer Area names, as one comma-separated text. */
        fun names(roles: List<AdyenRole>): String = roles.joinToString(", ") { it.displayName }

        private fun normalized(name: String): String =
            name
                .replace('—', '-')
                .replace('–', '-')
                .filterNot(Char::isWhitespace)
                .lowercase(Locale.ROOT)
    }
}
