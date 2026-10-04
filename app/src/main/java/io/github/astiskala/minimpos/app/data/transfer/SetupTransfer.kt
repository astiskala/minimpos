package io.github.astiskala.minimpos.app.data.transfer

import io.github.astiskala.minimpos.app.data.repo.CatalogRepository
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.repo.ImportSummary
import io.github.astiskala.minimpos.app.data.security.PinManager
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.security.SecretStoreException
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.ConnectionDestination
import io.github.astiskala.minimpos.app.data.settings.ConnectionSetup
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.core.catalogue.Catalogue
import io.github.astiskala.minimpos.core.codec.SealedSecrets
import io.github.astiskala.minimpos.core.codec.Transfer
import io.github.astiskala.minimpos.core.codec.TransferCodec
import io.github.astiskala.minimpos.core.codec.TransferFormatException
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/**
 * What an export includes.
 *
 * @property catalogue The products, categories and tax rates.
 * @property settings The settings another terminal of the same merchant account can use (see [SetupTransfer]).
 * @property secrets The secrets set on this terminal, sealed with a transfer code.
 */
data class TransferContents(
    val catalogue: Boolean = true,
    val settings: Boolean = true,
    val secrets: Boolean = true,
)

/**
 * An export, ready to be shown as QR codes.
 *
 * @property payload The encoded transfer, to split into QR codes.
 * @property code The transfer code the other terminal needs for the secrets; null when there are none.
 * @property catalogue The exported catalogue, for its counts; null when not included.
 * @property settings Whether the settings are included.
 * @property secrets The secrets included.
 */
data class TransferExport(
    val payload: String,
    val code: String?,
    val catalogue: Catalogue?,
    val settings: Boolean,
    val secrets: Set<Secret>,
)

/**
 * A scanned transfer whose settings and connection were read, ready to import.
 *
 * @property transfer What was scanned.
 * @property settings Its settings, read; null when none were transferred.
 * @property connection Its connection, read; null when none was transferred.
 */
class ReceivedTransfer internal constructor(
    val transfer: Transfer,
    internal val settings: TransferredSettings?,
    internal val connection: ConnectionSetup? = null,
) {
    /** The catalogue, or null when it was not transferred. */
    val catalogue: Catalogue? get() = transfer.catalogue

    /** Whether settings were transferred. */
    val hasSettings: Boolean get() = settings != null

    /** Whether a connection (from the setup helper web page) was transferred. */
    val hasConnection: Boolean get() = connection != null

    /** The helper's requested destination for review before import; null leaves the saved destination unchanged. */
    val connectionDestination: ConnectionDestination? get() = connection?.destination

    /** The helper's selected network/cloud environment; null leaves it unchanged or lets the device determine it. */
    val connectionEnvironment: TerminalEnvironment? get() = connection?.environment

    /** Whether the helper chose Automatic setup; discovery still requires a supported destination and a stored API key. */
    val automatic: Boolean get() = connection?.automatic == true

    /** Whether sealed secrets were transferred, which need the transfer code. */
    val hasSecrets: Boolean get() = transfer.sealedSecrets != null

    /**
     * Whether the catalogue's prices are in the currency this terminal will use after the import: the transferred
     * settings' when they choose one, else [localCurrency] (this terminal's). True without a catalogue; if false, prices
     * are imported as they are.
     */
    fun currencyMatches(localCurrency: String): Boolean = catalogue?.let { it.currencyCode == currencyAfterImport(localCurrency) } != false

    /** The currency code this terminal uses after the import: the transferred settings' when they choose one, else [localCurrency]. */
    fun currencyAfterImport(localCurrency: String): String =
        settings
            ?.settings
            ?.payment
            ?.currencyCode
            ?.takeIf { it.isNotBlank() } ?: localCurrency

    /**
     * Whether [code] can be tried on the secrets: always without secrets or with no code typed (the secrets are then
     * skipped), else only when it has the form of a transfer code ([TransferSeal.isValidCode]).
     */
    fun accepts(code: String): Boolean = !hasSecrets || code.isBlank() || TransferSeal.isValidCode(code)
}

/** What [SetupTransfer.import] did. */
sealed interface ImportOutcome {
    /**
     * Everything was imported.
     *
     * @property result What was imported.
     * @property secretsSkipped Whether secrets were transferred but not imported, because no code was typed.
     */
    data class Imported(
        val result: TransferResult,
        val secretsSkipped: Boolean,
    ) : ImportOutcome

    /** The transfer code did not open the secrets, so nothing was written. */
    data object WrongCode : ImportOutcome
}

/**
 * What an import changed.
 *
 * @property catalogue What the catalogue import added and updated; null when no catalogue was imported.
 * @property settings Whether the settings were applied.
 * @property secrets The secrets stored.
 * @property secretsError Why secrets could not be stored on this device; null when they were (or there were none).
 * @property connection Whether a connection was applied.
 * @property discoverTerminals Whether to offer discovery next, only for a supported Automatic setup whose API key was stored.
 */
data class TransferResult(
    val catalogue: ImportSummary?,
    val settings: Boolean,
    val secrets: Set<Secret>,
    val secretsError: String? = null,
    val connection: Boolean = false,
    val discoverTerminals: Boolean = false,
)

/**
 * Sets up another terminal of the same merchant account like this one, through QR codes ([TransferCodec]): the
 * catalogue, the settings and the secrets. It also imports the codes of the setup helper web page: a connection
 * ([ConnectionSetup], which only sets what it holds) and its secrets.
 *
 * Shared settings omit device-bound fields as defined by [AppSettings.withDeviceFieldsOf]; imported shared values
 * replace this device's values, including defaults. The default tax rate travels as its name and rate and is matched
 * to a local rate after catalogue import. All configured secrets, including both API credentials, the shared-key
 * passphrase, SMTP password and both PIN verifiers, are sealed separately with a transfer code by [TransferSeal].
 * Transfers do not include transaction history or synchronize subsequent changes.
 *
 * @param catalog The catalogue exported and imported.
 * @param settings The settings exported and replaced.
 * @param secrets The secrets exported and stored.
 * @param seal Seals and opens the secrets.
 * @param cpu Where the slow key derivation runs.
 * @param onTerminal Whether this device is an Adyen terminal, which decides what a connection's destination does.
 */
class SetupTransfer(
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val seal: TransferSeal = TransferSeal(),
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val onTerminal: Boolean = false,
) {
    /** The secrets set on this terminal, which an export can include. */
    suspend fun configuredSecrets(): Set<Secret> = secrets.configured.first()

    /**
     * Builds an export of [contents] with prices in [currencyCode]. Asking for secrets when none are set leaves them out.
     *
     * @throws IllegalArgumentException if nothing would be exported.
     */
    suspend fun export(
        contents: TransferContents,
        currencyCode: String,
    ): TransferExport {
        val catalogue = if (contents.catalogue) catalog.export(currencyCode) else null
        val settingsText = if (contents.settings) snapshot(settings.current()) else null
        val values = if (contents.secrets) configuredSecrets().associateWith { secrets.get(it) }.filterValues { it != null } else emptyMap()
        val code = if (values.isEmpty()) null else seal.newCode()
        val sealed =
            code?.let {
                val plaintext =
                    TransferJson.encodeToString(
                        SECRETS,
                        values.entries.associate { (key, value) ->
                            key.name to value.orEmpty()
                        },
                    )
                withContext(cpu) { SealedSecrets(seal.seal(plaintext.toByteArray(), it)) }
            }
        val payload = TransferCodec.encode(Transfer(catalogue, settingsText, sealed))
        return TransferExport(payload, code, catalogue, settingsText != null, values.keys)
    }

    /**
     * Reads the settings and the connection of a scanned [transfer].
     *
     * @throws TransferFormatException if they cannot be read.
     */
    fun receive(transfer: Transfer): ReceivedTransfer =
        ReceivedTransfer(
            transfer,
            transfer.settings?.let { readable("settings") { TransferredSettings.decode(it) } },
            transfer.connection?.let { readable("connection") { TransferJson.decodeFromString(CONNECTION, it) } },
        )

    private fun <T> readable(
        what: String,
        read: () -> T,
    ): T =
        try {
            read()
        } catch (e: SerializationException) {
            throw TransferFormatException("Unreadable $what", e)
        } catch (e: IllegalArgumentException) {
            throw TransferFormatException("Unreadable $what", e)
        }

    /**
     * Opens the secrets of [received] with the transfer [code] the other terminal shows; null when the code is wrong
     * (or there are no secrets). Unknown secrets and an unusable PIN verifier are left out.
     */
    internal suspend fun unlock(
        received: ReceivedTransfer,
        code: String,
    ): Map<Secret, String>? {
        val sealed = received.transfer.sealedSecrets ?: return null
        val plaintext = withContext(cpu) { seal.open(sealed.toByteArray(), code) } ?: return null
        val values =
            try {
                TransferJson.decodeFromString(SECRETS, plaintext.decodeToString())
            } catch (ignored: SerializationException) {
                return null
            }
        return values
            .mapNotNull { (name, value) -> Secret.entries.firstOrNull { it.name == name }?.let { it to value } }
            .filter { (secret, value) ->
                value.isNotEmpty() &&
                    (secret !in setOf(Secret.PIN_VERIFIER, Secret.MANAGER_PIN_VERIFIER) || PinManager.isValidVerifier(value))
            }.toMap()
    }

    /**
     * Imports [received]: its catalogue with [mode], then its settings and its connection, then its secrets when a
     * transfer [code] was typed. A code that does not open the secrets ([ReceivedTransfer.accepts], then [unlock]) is
     * [ImportOutcome.WrongCode] before anything is written; without a code the secrets are skipped. The catalogue
     * import is one transaction; a secret this device cannot store is reported, not thrown.
     */
    suspend fun import(
        received: ReceivedTransfer,
        mode: ImportMode,
        code: String = "",
    ): ImportOutcome {
        val withSecrets = received.hasSecrets && code.isNotBlank()
        if (!received.accepts(code)) return ImportOutcome.WrongCode
        val unlocked = (if (withSecrets) unlock(received, code) else emptyMap()) ?: return ImportOutcome.WrongCode
        val summary = received.catalogue?.let { catalog.import(it, mode) }
        received.settings?.let { apply(it) }
        received.connection?.let { connection -> settings.update { it.copy(terminal = connection.appliedTo(it.terminal, onTerminal)) } }
        val stored = mutableSetOf<Secret>()
        val error =
            try {
                unlocked.forEach { (secret, value) ->
                    secrets.set(secret, value)
                    stored += secret
                }
                null
            } catch (e: SecretStoreException) {
                e.message ?: "Secrets could not be stored"
            }
        return ImportOutcome.Imported(
            TransferResult(
                summary,
                received.hasSettings,
                stored,
                error,
                received.hasConnection,
                received.connection?.requestsDiscovery(onTerminal) == true && Secret.ADYEN_API_KEY in stored,
            ),
            received.hasSecrets && !withSecrets,
        )
    }

    private suspend fun snapshot(current: AppSettings): String {
        val default = catalog.taxRates.first().firstOrNull { it.id == current.payment.defaultTaxRateId }
        return TransferredSettings(current.shared(), default?.let { TaxRateRef(it.name, it.rateMilliPercent) }).encode()
    }

    private suspend fun apply(transferred: TransferredSettings) {
        val rate =
            transferred.defaultTaxRate?.let { wanted ->
                catalog.taxRates.first().firstOrNull {
                    it.name.equals(wanted.name, ignoreCase = true) &&
                        it.rateMilliPercent == wanted.rateMilliPercent
                }
            }
        // The repository brings every number within its limits.
        settings.update { it.takingOver(transferred.settings, rate?.id) }
    }

    private companion object {
        val SECRETS = serializer<Map<String, String>>()
        val CONNECTION = serializer<ConnectionSetup>()
    }
}

/** Leaves out values at their default, so the codes stay small; the receiver reads them back as defaults. */
private val TransferJson =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = false
        explicitNulls = false
    }

/**
 * The settings in a transfer: [AppSettings.shared] (read leniently, like stored settings: missing values take their
 * default and unknown ones, from newer versions, are ignored), and the default tax rate by name and rate. In the code
 * they are one JSON object, the settings' sections with a `defaultTaxRate` beside them.
 *
 * @property settings The shared settings.
 * @property defaultTaxRate The default tax rate for new products, by name and rate; null for the first rate.
 */
internal data class TransferredSettings(
    val settings: AppSettings,
    val defaultTaxRate: TaxRateRef?,
) {
    /** These settings as the JSON text a transfer carries. */
    fun encode(): String {
        val sections = TransferJson.encodeToJsonElement(APP_SETTINGS, settings).jsonObject
        val rate = defaultTaxRate?.let { DEFAULT_TAX_RATE to TransferJson.encodeToJsonElement(TAX_RATE, it) }
        return JsonObject(sections + listOfNotNull(rate)).toString()
    }

    /** Reading the JSON text a transfer carries. */
    companion object {
        private const val DEFAULT_TAX_RATE = "defaultTaxRate"
        private val APP_SETTINGS = serializer<AppSettings>()
        private val TAX_RATE = serializer<TaxRateRef>()

        /**
         * The settings in [text].
         *
         * @throws SerializationException if it is not JSON settings.
         * @throws IllegalArgumentException if a value cannot be read.
         */
        fun decode(text: String): TransferredSettings {
            val json = TransferJson.parseToJsonElement(text).jsonObject
            return TransferredSettings(
                TransferJson.decodeFromJsonElement(APP_SETTINGS, json),
                json[DEFAULT_TAX_RATE]?.let { TransferJson.decodeFromJsonElement(TAX_RATE, it) },
            )
        }
    }
}

/**
 * A tax rate identified by what it is rather than by row ID, which differs between terminals.
 *
 * @property name The rate's name, matched ignoring case.
 * @property rateMilliPercent The rate in thousandths of a percent.
 */
@Serializable
internal data class TaxRateRef(
    val name: String,
    val rateMilliPercent: Int,
)
