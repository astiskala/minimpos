package io.minimpos.app.data.transfer

import io.minimpos.app.data.repo.CatalogRepository
import io.minimpos.app.data.repo.ImportMode
import io.minimpos.app.data.repo.ImportSummary
import io.minimpos.app.data.security.PinManager
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.security.SecretStoreException
import io.minimpos.app.data.security.TransferSeal
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.EmailSettings
import io.minimpos.app.data.settings.HistorySettings
import io.minimpos.app.data.settings.PaymentSettings
import io.minimpos.app.data.settings.ReceiptSettings
import io.minimpos.app.data.settings.SecuritySettings
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.core.catalogue.Catalogue
import io.minimpos.core.codec.SealedSecrets
import io.minimpos.core.codec.Transfer
import io.minimpos.core.codec.TransferCodec
import io.minimpos.core.codec.TransferFormatException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
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
 * A scanned transfer whose settings were read, ready to import.
 *
 * @property transfer What was scanned.
 * @property settings Its settings, read; null when none were transferred.
 */
class ReceivedTransfer internal constructor(
    val transfer: Transfer,
    internal val settings: TransferredSettings?,
) {
    /** The catalogue, or null when it was not transferred. */
    val catalogue: Catalogue? get() = transfer.catalogue

    /** Whether settings were transferred. */
    val hasSettings: Boolean get() = settings != null

    /** Whether sealed secrets were transferred, which need the transfer code. */
    val hasSecrets: Boolean get() = transfer.sealedSecrets != null

    /** The currency the transferred settings choose; null without settings, and blank when it follows the country. */
    val currencyCode: String? get() = settings?.payment?.currencyCode
}

/**
 * What an import changed.
 *
 * @property catalogue What the catalogue import added and updated; null when no catalogue was imported.
 * @property settings Whether the settings were applied.
 * @property secrets The secrets stored.
 * @property secretsError Why secrets could not be stored on this device; null when they were (or there were none).
 */
data class TransferResult(
    val catalogue: ImportSummary?,
    val settings: Boolean,
    val secrets: Set<Secret>,
    val secretsError: String? = null,
)

/**
 * Sets up another terminal of the same merchant account like this one, through QR codes ([TransferCodec]): the
 * catalogue, the settings and the secrets.
 *
 * The settings leave out what belongs to the device: where payments go, the terminal's address and POIID, its
 * detected TEST/LIVE environment and the simulator. Everything else travels, and on import replaces this terminal's
 * value (a setting at its default replaces it with the default). The default tax rate travels as its name and rate,
 * and is looked up among this terminal's rates after the catalogue import. The secrets (shared key passphrase,
 * Checkout API key, SMTP password and the admin PIN's verifier, so the same PIN works) are sealed with a transfer code
 * by [TransferSeal].
 *
 * @param catalog The catalogue exported and imported.
 * @param settings The settings exported and replaced.
 * @param secrets The secrets exported and stored.
 * @param seal Seals and opens the secrets.
 * @param cpu Where the slow key derivation runs.
 */
class SetupTransfer(
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val seal: TransferSeal = TransferSeal(),
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
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
        val settingsText = if (contents.settings) TransferJson.encodeToString(SETTINGS, snapshot(settings.current())) else null
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
     * Reads the settings of a scanned [transfer].
     *
     * @throws TransferFormatException if they cannot be read.
     */
    fun receive(transfer: Transfer): ReceivedTransfer {
        val read =
            transfer.settings?.let {
                try {
                    TransferJson.decodeFromString(SETTINGS, it)
                } catch (e: SerializationException) {
                    throw TransferFormatException("Unreadable settings", e)
                } catch (e: IllegalArgumentException) {
                    throw TransferFormatException("Unreadable settings", e)
                }
            }
        return ReceivedTransfer(transfer, read)
    }

    /**
     * Opens the secrets of [received] with the transfer [code] the other terminal shows; null when the code is wrong
     * (or there are no secrets). Unknown secrets and an unusable PIN verifier are left out.
     */
    suspend fun unlock(
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
            .filter { (secret, value) -> value.isNotEmpty() && (secret != Secret.PIN_VERIFIER || PinManager.isValidVerifier(value)) }
            .toMap()
    }

    /**
     * Imports [received]: its catalogue with [mode], then its settings, then [unlocked] secrets (from [unlock]). The
     * catalogue import is one transaction; a secret this device cannot store is reported, not thrown.
     */
    suspend fun import(
        received: ReceivedTransfer,
        mode: ImportMode,
        unlocked: Map<Secret, String> = emptyMap(),
    ): TransferResult {
        val summary = received.catalogue?.let { catalog.import(it, mode) }
        received.settings?.let { apply(it) }
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
        return TransferResult(summary, received.hasSettings, stored, error)
    }

    private suspend fun snapshot(current: AppSettings): TransferredSettings {
        val rates = catalog.taxRates.first()
        val default = rates.firstOrNull { it.id == current.payment.defaultTaxRateId }
        return TransferredSettings(
            terminal = current.terminal.withDeviceFieldsOf(TerminalSettings()),
            payment = current.payment.copy(defaultTaxRateId = null),
            receipt = current.receipt,
            email = current.email,
            security = current.security,
            history = current.history,
            defaultTaxRate = default?.let { TaxRateRef(it.name, it.rateMilliPercent) },
        )
    }

    private suspend fun apply(transferred: TransferredSettings) {
        val rate =
            transferred.defaultTaxRate?.let { wanted ->
                catalog.taxRates.first().firstOrNull {
                    it.name.equals(wanted.name, ignoreCase = true) &&
                        it.rateMilliPercent == wanted.rateMilliPercent
                }
            }
        settings.update { current ->
            current.copy(
                // The repository brings every number within its limits.
                terminal = transferred.terminal.withDeviceFieldsOf(current.terminal),
                payment = transferred.payment.copy(defaultTaxRateId = rate?.id),
                receipt = transferred.receipt,
                email = transferred.email,
                security = transferred.security,
                history = transferred.history,
            )
        }
    }

    private companion object {
        /** Leaves out values at their default, so the codes stay small; the receiver reads them back as defaults. */
        val TransferJson =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                encodeDefaults = false
                explicitNulls = false
            }
        val SETTINGS = serializer<TransferredSettings>()
        val SECRETS = serializer<Map<String, String>>()
    }
}

/**
 * The settings in a transfer: [AppSettings] without what belongs to the device. Read leniently, like stored settings:
 * missing values take their default and unknown ones (from newer versions) are ignored.
 *
 * @property terminal The terminal settings without those of the sending device, which are left at their defaults (see
 *   [TerminalSettings.withDeviceFieldsOf]): the shared key, SaleID, timeout and Checkout API settings.
 * @property payment The payment settings; their default tax rate ID is left out, see [defaultTaxRate].
 * @property receipt The receipt settings.
 * @property email The SMTP settings (the password is a secret).
 * @property security The admin area's automatic lock (the PIN is a secret).
 * @property history How long transactions are kept.
 * @property defaultTaxRate The default tax rate for new products, by name and rate; null for the first rate.
 */
@Serializable
internal data class TransferredSettings(
    val terminal: TerminalSettings = TerminalSettings(),
    val payment: PaymentSettings = PaymentSettings(),
    val receipt: ReceiptSettings = ReceiptSettings(),
    val email: EmailSettings = EmailSettings(),
    val security: SecuritySettings = SecuritySettings(),
    val history: HistorySettings = HistorySettings(),
    val defaultTaxRate: TaxRateRef? = null,
)

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
