package app.minimpos.app.terminal

import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.EncryptedJournal
import app.minimpos.app.data.security.JournalRead
import app.minimpos.app.data.security.RecoveryJournal
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.security.secretFingerprint
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.data.settings.VerifiedSetup
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Where payments go and what is still missing, resolved in one place ([resolve]) from the stored settings, which
 * secrets are saved and the device. It is the one reading of the terminal and Checkout API setup: [TerminalGateway]
 * sends requests (or reports [connectionProblem] or [problem]) with it, [AdyenApi] takes [apiSetup] from it, and
 * [TerminalStatus] publishes it. What each destination needs is its [DestinationRules], which [resolve] asks. Missing
 * setup is never thrown; it is a [problem] that says what to enter. A saved secret that can no longer be decrypted is
 * only found when it is read ([unlock]).
 *
 * @property settings The settings it was resolved from; the gateway takes the shared key and timeout from them.
 * @property destination Where payments go, with what it needs and can do.
 * @property onTerminal Whether the app runs on an Adyen terminal, which then is where payments go in terminal mode.
 * @property poiId On a terminal its own POIID; in the cloud and on the network the configured one; with the Payments app
 *   its boarded installation ID; for the simulator the simulator's. Null while one still has to be entered (or boarded).
 * @property host Where the Terminal API listens in terminal mode: [LOCALHOST] on a terminal, else the configured address;
 *   null in the other modes or while none is entered.
 * @property connectionProblem What must still be entered before the [destination] can be reached (connection checks,
 *   refunds, status checks, printing); null when nothing is missing (always for the simulator).
 * @property apiSetup How far the Checkout API is set up, which decides where captures go.
 * @property environment Where payments go: the selected environment or this device's verified certificate
 *   ([app.minimpos.app.data.settings.TerminalSettings.environment]); null
 *   for the simulator and until known.
 * @property paymentsApps The environments of the Adyen Payments apps installed on the device when it was resolved.
 * @property remoteStatus Readiness of status recovery for the selected destination and installed app version.
 */
data class TerminalSetup(
    val settings: AppSettings,
    val destination: DestinationRules,
    val onTerminal: Boolean,
    val poiId: String?,
    val host: String?,
    val connectionProblem: SetupProblem?,
    val apiSetup: ApiSetup,
    val environment: TerminalEnvironment?,
    val paymentsApps: Set<TerminalEnvironment> = emptySet(),
    val remoteStatus: CapabilityStanding = CapabilityStanding.AVAILABLE,
) {
    /**
     * Where payments go: [TerminalMode.TERMINAL], [TerminalMode.CLOUD], [TerminalMode.PAYMENTS_APP] or
     * [TerminalMode.SIMULATOR], never [TerminalMode.AUTO].
     */
    val mode: TerminalMode get() = destination.mode

    /**
     * What must still be entered before payments can be taken; null when nothing is missing (always for the simulator):
     * the [connectionProblem], else what the Checkout API is missing, which every destination needs.
     */
    val problem: SetupProblem?
        get() = connectionProblem ?: apiSetup.problem

    internal fun importProblem(saved: Set<Secret>): SetupProblem? =
        connectionProblem?.takeUnless { it == SetupProblem.PAYMENTS_APP_NOT_BOARDED }
            ?: sharedKeyProblem(settings.terminal, saved).takeIf { connectionProblem == SetupProblem.PAYMENTS_APP_NOT_BOARDED }
            ?: apiSetup.problem

    /** What [destination] can do and asks of this setup, read once from its rules; see [DestinationCapabilities]. */
    val capabilities: DestinationCapabilities
        get() =
            DestinationCapabilities(
                discoversTerminals = destination.discoversTerminals,
                selectsEnvironment = destination.selectsEnvironment(onTerminal),
                checksConnection = destination.checksConnection,
                scannedWallets = destination.scannedWallets,
                supportsTerminalFeatures = destination.supportsTerminalFeatures,
                supportsOfflinePayments = destination.supportsOfflinePayments,
                supportsShopperDisplay = destination.supportsTerminalFeatures && (!onTerminal || destination.simulatesApi),
                supportsPrinting = destination.supportsPrinting,
                aborts = destination.aborts,
                diagnoses = destination.diagnoses,
                remoteStatus = remoteStatus,
                giftCards = destination.giftCards,
                mexicanInstallments = destination.mexicanInstallments,
            )

    internal val needsSharedKey: Boolean get() = Secret.TERMINAL_PASSPHRASE in destination.secrets

    internal val boardsPhone: Boolean get() = destination.boardsPhone

    internal val importPending: Boolean get() = apiSetup.problem == SetupProblem.TRANSFER_PENDING

    internal val suppliesCertificateEnvironment: Boolean get() = onTerminal && destination == LocalTerminal

    /** Whether this device's verified terminal certificate must supply the still-unknown local environment. */
    val readsLocalEnvironment: Boolean get() = suppliesCertificateEnvironment && environment == null

    /** The terminal settings after this destination accepts [detected]; persistence validates its original setup. */
    fun learnedEnvironment(detected: DetectedEnvironment) = destination.learnedEnvironment(this, detected)

    /**
     * Whether printing is supported and offered, as Settings › Receipts › Printer says: always, never, or detected, which the
     * [destination] decides with what terminals reported ([printers], by POIID; see [DestinationRules.printer]).
     */
    fun printerAvailable(printers: Map<String, Boolean>): Boolean =
        destination.supportsPrinting &&
            when (settings.receipt.printerMode) {
                PrinterMode.ON -> true
                PrinterMode.OFF -> false
                PrinterMode.AUTO -> destination.printer(settings, poiId, printers)
            }

    /** Printing readiness from destination support, reported hardware ([printers], by POIID), then local preferences. */
    fun printingStanding(printers: Map<String, Boolean>): CapabilityStanding =
        when {
            !destination.supportsPrinting -> CapabilityStanding.UNSUPPORTED
            printers[poiId] == false || (destination.simulatesApi && !settings.simulator.hasPrinter) -> CapabilityStanding.UNSUPPORTED
            printerAvailable(printers) -> CapabilityStanding.AVAILABLE
            else -> CapabilityStanding.NEEDS_CONFIGURATION
        }

    /**
     * Whether checkout offers payment links: whenever the Checkout API is set up or simulated.
     */
    val paymentLinks: Boolean get() = apiSetup.problem == null

    /** The secrets to read for this setup, of those [saved]: the [destination]'s and the Adyen API key. */
    fun secretsToRead(saved: Set<Secret>): Set<Secret> = (destination.secrets + Secret.ADYEN_API_KEY).intersect(saved)

    /**
     * This setup with the secrets [read] for it (see [secretsToRead]): each decrypted value, or null for one saved that
     * can no longer be decrypted, which becomes the [connectionProblem] (when the [destination] needs it and nothing
     * else is missing) or makes a complete [apiSetup] incomplete.
     */
    fun unlock(
        read: Map<Secret, String?>,
        identity: String? = null,
    ): UnlockedSetup {
        val unreadable = read.filterValues { it == null }.keys
        val problem = connectionProblem ?: destination.secrets.filter { it in unreadable }.firstNotNullOfOrNull(::unreadable)
        val api =
            if ((apiSetup == ApiSetup.Complete || apiSetup.problem == SetupProblem.SETUP_NOT_VERIFIED) &&
                Secret.ADYEN_API_KEY in unreadable
            ) {
                ApiSetup.Incomplete(SetupProblem.UNREADABLE_API_KEY)
            } else {
                apiSetup
            }
        return UnlockedSetup(
            copy(connectionProblem = problem, apiSetup = api),
            read
                .mapNotNull { (secret, value) ->
                    value?.let { secret to it }
                }.toMap(),
            identity,
        )
    }

    /** Non-secret identity for this resolved setup, with [detected] from its actual transport when known. */
    fun paymentContext(detected: TerminalEnvironment? = environment): PaymentContext =
        PaymentContext(
            destination = mode.name,
            poiId = poiId.orEmpty(),
            saleId = TerminalGateway.DEFAULT_SALE_ID,
            merchantAccount = settings.terminal.merchantAccount.trim(),
            environment = detected?.name,
            host = host,
            simulated = destination.simulatesApi,
        )

    /** Resolving the setup, and the fixed identities it uses. */
    companion object {
        /** The POIID the simulator reports, in the same `<model>-<serial>` form as a real one. */
        const val SIMULATOR_POI_ID = "SIMULATOR-000000001"

        /** Where the Terminal API listens when the app runs on the terminal itself. */
        const val LOCALHOST = "localhost"

        /** Where payments go in [TerminalMode.AUTO] on [device]: this terminal when running on one, the simulator elsewhere. */
        fun automaticMode(device: DeviceInfo): TerminalMode = DestinationRules.of(TerminalMode.AUTO, device).mode

        /** The setup with [settings] on [device], given which secrets are [saved]. */
        fun resolve(
            settings: AppSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
            verified: Boolean,
            pendingImport: Boolean,
        ): TerminalSetup {
            val terminal = settings.terminal
            val destination = DestinationRules.of(terminal.mode, device)
            val poiId = destination.poiId(terminal, device)
            val host = destination.host(terminal, device)
            val environment = destination.environment(terminal, device)
            val problem = destination.problem(terminal, saved, device, poiId, host)
            val api =
                apiSetup(
                    settings,
                    environment,
                    destination.simulatesApi,
                    keySaved = Secret.ADYEN_API_KEY in saved,
                    environmentProblem =
                        if (device.isAdyenTerminal &&
                            destination == LocalTerminal
                        ) {
                            SetupProblem.TERMINAL_ENVIRONMENT
                        } else {
                            SetupProblem.ENVIRONMENT
                        },
                )
            val gated =
                when {
                    pendingImport -> ApiSetup.Incomplete(SetupProblem.TRANSFER_PENDING)
                    api == ApiSetup.Complete && !verified -> ApiSetup.Incomplete(SetupProblem.SETUP_NOT_VERIFIED)
                    else -> api
                }
            return TerminalSetup(
                settings,
                destination,
                device.isAdyenTerminal,
                poiId,
                host,
                problem,
                gated,
                environment,
                device.paymentsApps,
                destination.remoteStatus(device, environment),
            )
        }

        /**
         * Whether Tap to Pay can be set up on [device] for the merchant account in [settings] with the Adyen API key as
         * [read] (absent when it is not saved, null when it can no longer be decrypted): the same device rules as for
         * payments through the Payments app, then the merchant account and the key, which creates this phone's own
         * credential for boarding ([TerminalSetupSource.boarding]).
         */
        fun boarding(
            settings: AppSettings,
            device: DeviceInfo,
            read: Map<Secret, String?>,
        ): BoardingOwner {
            val apps = device.paymentsApps
            val environment = settings.terminal.environment
            val problem =
                paymentsAppProblem(device.isAdyenTerminal, apps, environment)
                    ?: SetupProblem.MERCHANT_ACCOUNT.takeIf { settings.terminal.merchantAccount.isBlank() }
                    ?: SetupProblem.API_KEY.takeIf { Secret.ADYEN_API_KEY !in read }
            val key = read[Secret.ADYEN_API_KEY]
            return when {
                problem != null -> BoardingOwner.Blocked(problem)
                key == null -> BoardingOwner.Blocked(SetupProblem.UNREADABLE_API_KEY)
                else -> BoardingOwner.Ready(checkNotNull(environment), settings.terminal.merchantAccount.trim(), key)
            }
        }

        /** What to enter again when [secret] can no longer be decrypted; null for the secrets no destination needs. */
        private fun unreadable(secret: Secret): SetupProblem? =
            when (secret) {
                Secret.TERMINAL_PASSPHRASE -> SetupProblem.UNREADABLE_PASSPHRASE
                Secret.ADYEN_API_KEY -> SetupProblem.UNREADABLE_API_KEY
                Secret.SMTP_PASSWORD, Secret.PIN_VERIFIER, Secret.MANAGER_PIN_VERIFIER -> null
            }

        /**
         * How far the Checkout API is set up: simulated in simulator mode; else complete or what is missing (including
         * the [environment], until selected or read from the device).
         */
        private fun apiSetup(
            settings: AppSettings,
            environment: TerminalEnvironment?,
            simulator: Boolean,
            keySaved: Boolean,
            environmentProblem: SetupProblem,
        ): ApiSetup {
            val terminal = settings.terminal
            return when {
                simulator -> {
                    ApiSetup.Simulated
                }

                terminal.merchantAccount.isBlank() -> {
                    ApiSetup.Incomplete(SetupProblem.MERCHANT_ACCOUNT)
                }

                !keySaved -> {
                    ApiSetup.Incomplete(SetupProblem.API_KEY)
                }

                environment == null -> {
                    ApiSetup.Incomplete(environmentProblem)
                }

                environment == TerminalEnvironment.LIVE && terminal.liveUrlPrefix.isBlank() -> {
                    ApiSetup.Incomplete(SetupProblem.LIVE_PREFIX)
                }

                else -> {
                    ApiSetup.Complete
                }
            }
        }
    }
}

/**
 * What a destination can do and asks of its setup, read once from its [DestinationRules] by
 * [TerminalSetup.capabilities] and carried whole by [TerminalState.capabilities], so a new capability is added in one
 * place. Non-secret, and holds no settings.
 *
 * @property discoversTerminals Whether read-only terminal discovery can propose connection fields.
 * @property selectsEnvironment Whether Settings must ask for TEST or LIVE before API credentials or terminal details.
 * @property checksConnection Whether the connection is checked in the background, at startup and when the setup changes:
 *   whenever payments go to something real rather than the simulator.
 * @property scannedWallets Whether shopper-presented wallet codes are supported, independently of account configuration.
 * @property supportsTerminalFeatures Whether physical-terminal features (terminal tipping, Giving, MOTO/MKE, totals,
 *   display) are supported.
 * @property supportsOfflinePayments Whether local offline EMV and store-and-forward processing are supported.
 * @property supportsShopperDisplay Whether shopper-facing display and input requests are supported (an external
 *   terminal or the simulator).
 * @property supportsPrinting Whether print requests are supported, independently of hardware and local preferences.
 * @property aborts Whether the POS can request cancellation of a running terminal transaction.
 * @property diagnoses Whether terminal diagnosis requests are supported.
 * @property remoteStatus Whether status recovery is available, needs verification/enablement, or is unsupported.
 * @property giftCards Whether gift-card requests can be sent; provider onboarding is configured separately.
 * @property mexicanInstallments Whether Mexican POS routing can be sent; store and program eligibility are separate.
 */
data class DestinationCapabilities(
    val discoversTerminals: Boolean = false,
    val selectsEnvironment: Boolean = false,
    val checksConnection: Boolean = false,
    val scannedWallets: Boolean = false,
    val supportsTerminalFeatures: Boolean = true,
    val supportsOfflinePayments: Boolean = false,
    val supportsShopperDisplay: Boolean = true,
    val supportsPrinting: Boolean = true,
    val aborts: Boolean = true,
    val diagnoses: Boolean = true,
    val remoteStatus: CapabilityStanding = CapabilityStanding.AVAILABLE,
    val giftCards: Boolean = true,
    val mexicanInstallments: Boolean = true,
)

/**
 * A [TerminalSetup] with the secrets it needs decrypted, once, by [TerminalSetupSource.unlocked]: what the destinations,
 * the Checkout API and Tap to Pay are opened with, so none of them reads a secret itself. [toString] leaves the secrets
 * out.
 *
 * @property setup The setup, whose [TerminalSetup.problem] and [TerminalSetup.apiSetup] already say when a secret it
 *   needs can no longer be decrypted.
 * @param values The decrypted secrets, by which secret they are.
 * @property validationIdentity Fingerprint of the exact stored secret snapshot and connection fields; null for unsaved candidates.
 */
class UnlockedSetup(
    val setup: TerminalSetup,
    private val values: Map<Secret, String>,
    internal val validationIdentity: String? = null,
) {
    /**
     * The shared key with the saved passphrase; null when none could be read, or the key identifier or version is not
     * valid (which [TerminalSetup.problem] says first).
     */
    val terminalKey: TerminalKey?
        get() {
            val terminal = setup.settings.terminal
            val passphrase = values[Secret.TERMINAL_PASSPHRASE]
            if (passphrase.isNullOrEmpty() || terminal.keyIdentifier.isBlank() || terminal.keyVersion < 1) return null
            return TerminalKey(terminal.keyIdentifier.trim(), passphrase, terminal.keyVersion)
        }

    internal val hasSharedPassphrase: Boolean get() = Secret.TERMINAL_PASSPHRASE in values

    /** The Adyen API key (also the cloud's); null when none could be read. */
    val apiKey: String? get() = values[Secret.ADYEN_API_KEY]

    override fun toString() = "UnlockedSetup($setup, ${values.keys})"
}

/** How much of the setup work began on must still be stored for its result to apply, see [TerminalSetupSource.SetupFence]. */
enum class SetupFreshness {
    /** The connection fields ([TerminalSettings]) only. */
    CONNECTION,

    /** The connection fields and the Adyen API key, so a passphrase the work saves itself does not count as a change. */
    CREDENTIAL,

    /** The connection fields and the exact stored secret snapshot, for readings cached against the verified setup. */
    SECRETS,

    /** The connection fields, selected Payments app environment and its separate boarding credential. */
    BOARDING,
}

/** Whether the Adyen API key can create this phone's own Tap to Pay credential, see [TerminalSetup.boarding]. */
sealed interface BoardingOwner {
    /**
     * It can. [toString] leaves the API key out.
     *
     * @property environment The selected environment, which the credential is created in.
     * @property merchantAccount The merchant account it is created for, trimmed.
     * @property apiKey The Adyen API key, decrypted.
     */
    data class Ready(
        val environment: TerminalEnvironment,
        val merchantAccount: String,
        val apiKey: String,
    ) : BoardingOwner {
        /** Non-secret fingerprint of [apiKey], which the credentials it creates are stored with instead of the key. */
        val fingerprint: String get() = secretFingerprint(apiKey)

        override fun toString() = "Ready($environment, $merchantAccount)"
    }

    /**
     * Something must be entered or installed first.
     *
     * @property problem What.
     */
    data class Blocked(
        val problem: SetupProblem,
    ) : BoardingOwner
}

/** Whether the Payments app can be boarded on this phone, see [TerminalSetupSource.boarding]. */
sealed interface BoardingSetup {
    /**
     * It can, with this phone's own credential. [toString] leaves the API key out.
     *
     * @property environment The selected environment, which the Management API is called in.
     * @property apiKey The API key of this phone's own credential, decrypted.
     */
    data class Ready(
        val environment: TerminalEnvironment,
        val apiKey: String,
    ) : BoardingSetup {
        override fun toString() = "Ready($environment)"
    }

    /**
     * Something must be entered, installed or set up first.
     *
     * @property problem What.
     */
    data class Blocked(
        val problem: SetupProblem,
    ) : BoardingSetup
}

/**
 * Reads what the [TerminalSetup] is resolved from (the stored settings and which secrets are saved) for the modules that
 * use it, and the secrets it needs ([unlocked], [boarding]), so none of them reads those inputs itself.
 *
 * @param settings The stored settings.
 * @param secrets Tells which secrets are saved, and decrypts them.
 * @property device The device the app runs on.
 * @param historySwitchPending Confirmed deletion journal that must finish before new operations.
 * @param onVerified Completes what waited for a verified setup, called once a tested setup is remembered as verified:
 *   in the app, [SharedKeySetup.complete] for pending shared-key recovery records.
 */
class TerminalSetupSource(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    val device: DeviceInfo,
    /** Pending confirmed history-switch journal, blocking new operations until destination recovery completes. */
    private val historySwitchPending: Flow<Boolean> = flowOf(false),
    private val onVerified: suspend (UnlockedSetup) -> Unit = {},
) {
    internal val boardingCredentials = journal(RecoveryJournal.BOARDING_CREDENTIAL, serializer<List<BoardingCredential>>())

    /** The setup with the stored settings now, without reading any secret. */
    suspend fun current(): TerminalSetup = reading(settings.current(), read = false).setup

    private suspend fun reading(
        current: AppSettings,
        read: Boolean,
        forValidation: Boolean = false,
    ): UnlockedSetup {
        val raw = TerminalSetup.resolve(current, secrets.configured.first(), device, verified = true, pendingImport = false)
        val requested = raw.secretsToRead(Secret.entries.toSet())
        val snapshot =
            secrets.setupSecrets(
                VerifiedSetup.connectionKey(current.terminal, raw.poiId, raw.environment, raw.mode),
                requested,
                read,
            )
        val setup =
            TerminalSetup.resolve(
                current,
                snapshot.configured,
                device,
                verified = forValidation || current.verifiedSetup == snapshot.identity,
                pendingImport = (snapshot.pending || historySwitchPending.first()) && !forValidation,
            )
        return setup.unlock(snapshot.values, snapshot.identity)
    }

    internal fun candidate(
        current: AppSettings,
        values: Map<Secret, String>,
    ): UnlockedSetup = TerminalSetup.resolve(current, values.keys, device, verified = true, pendingImport = false).unlock(values)

    /**
     * Whether the Adyen API key can create this phone's own credential ([TerminalSetup.boarding]): the saved one with the
     * stored settings now, or that of the [candidate] setup.
     */
    internal suspend fun owner(candidate: UnlockedSetup? = null): BoardingOwner {
        val current = candidate?.setup?.settings ?: settings.current()
        val read =
            if (candidate == null) {
                boardingSecrets(current, read = true).values
            } else {
                candidate.apiKey?.let { mapOf(Secret.ADYEN_API_KEY to it) }.orEmpty()
            }
        return TerminalSetup.boarding(current, device, read)
    }

    private suspend fun boardingSecrets(
        current: AppSettings,
        read: Boolean,
    ) = secrets.setupSecrets(
        current.terminal.toString() + device.paymentsApps.sortedBy { it.name },
        setOf(Secret.ADYEN_API_KEY),
        read,
        RecoveryJournal.BOARDING_CREDENTIAL,
    )

    internal fun <T> journal(
        kind: RecoveryJournal,
        serializer: KSerializer<T>,
        validate: (T) -> Boolean = { true },
    ): EncryptedJournal<T> = secrets.journal(kind, serializer, validate = validate)

    internal fun learnedCandidate(detected: DetectedEnvironment): AppSettings =
        detected.setup.settings.copy(terminal = detected.setup.learnedEnvironment(detected))

    /**
     * What work begun on one setup reading checks before its result applies: whether that setup is still stored, to the
     * [SetupFreshness] the work needs, and settings writes that apply only then. Made from this source, so callers never
     * compare connection fields, API keys or secret fingerprints themselves; a stale result becomes
     * [SetupProblem.SETUP_CHANGED].
     *
     * @property origin The setup the work began on.
     * @param freshness What must still be the same.
     * @param apiKey The API key the work began with, for [SetupFreshness.CREDENTIAL]; never shown or logged.
     * @param secrets The fingerprint of the stored secrets the work began with, for [SetupFreshness.SECRETS].
     */
    inner class SetupFence internal constructor(
        val origin: TerminalSetup,
        private val freshness: SetupFreshness,
        private val apiKey: String?,
        private val secrets: String?,
    ) {
        /** A fence for work begun on [unlocked] that needs [freshness] to hold before its result applies. */
        constructor(unlocked: UnlockedSetup, freshness: SetupFreshness) :
            this(unlocked.setup, freshness, unlocked.apiKey, unlocked.validationIdentity)

        /** A fence for work begun on [origin] that needs only its connection fields to stay the same. */
        constructor(origin: TerminalSetup) : this(origin, SetupFreshness.CONNECTION, null, null)

        /** A fence for the exact separate [boarding] credential in the selected environment. */
        internal constructor(origin: TerminalSetup, boarding: BoardingSetup.Ready) :
            this(origin, SetupFreshness.BOARDING, boarding.apiKey, null)

        /**
         * A non-secret fingerprint of the stored secrets and connection fields the work began on, to keep a cached
         * reading for; null for an unsaved candidate or a connection-only fence.
         */
        val identity: String? get() = secrets

        /** Whether the setup the work began on is still stored, to this fence's [SetupFreshness]. */
        suspend fun stillCurrent(): Boolean =
            when (freshness) {
                SetupFreshness.CONNECTION -> {
                    settings.current().terminal == origin.settings.terminal
                }

                SetupFreshness.CREDENTIAL -> {
                    unlocked(forValidation = true).let { it.apiKey == apiKey && it.began() }
                }

                SetupFreshness.SECRETS -> {
                    unlocked().let { it.validationIdentity == secrets && it.began() }
                }

                SetupFreshness.BOARDING -> {
                    boarding().let {
                        it is BoardingSetup.Ready && it.apiKey == apiKey && it.environment == origin.environment &&
                            settings.current().terminal == origin.settings.terminal
                    }
                }
            }

        /** [SetupProblem.SETUP_CHANGED] when the setup the work began on is no longer stored; null while it is. */
        suspend fun changed(): SetupProblem? = SetupProblem.SETUP_CHANGED.takeUnless { stillCurrent() }

        /**
         * Applies [transform] to the stored settings only while the setup the work began on is still stored: the
         * connection fields atomically with the write, the rest of this fence's [SetupFreshness] just before it. Returns
         * whether it was applied; [transform] must not have side effects.
         */
        suspend fun update(transform: (AppSettings) -> AppSettings): Boolean {
            if (freshness != SetupFreshness.CONNECTION && !stillCurrent()) return false
            var applied = false
            settings.update {
                applied = it.terminal == origin.settings.terminal
                if (applied) transform(it) else it
            }
            return applied
        }

        /** Strong fence for the exact [key] just saved by this work; null if its fields, passphrase or original credential changed. */
        internal suspend fun withSharedKey(key: DiscoveredKey): SetupFence? {
            val actual = unlocked(forValidation = true)
            val expected = origin.settings.terminal.copy(keyIdentifier = key.identifier, keyVersion = key.version)
            if (actual.setup.settings.terminal != expected || actual.apiKey != apiKey) return null
            if (actual.terminalKey != TerminalKey(key.identifier.trim(), key.passphrase, key.version)) return null
            return SetupFence(actual, SetupFreshness.SECRETS)
        }

        private fun UnlockedSetup.began() = setup.settings.terminal == origin.settings.terminal
    }

    internal suspend fun rememberVerified(tested: UnlockedSetup): Boolean {
        val current = reading(settings.current(), read = false, forValidation = true)
        val identity = tested.validationIdentity ?: return false
        if (identity != current.validationIdentity) return false
        settings.update { saved ->
            if (VerifiedSetup.connectionKey(saved.terminal, current.setup.poiId, current.setup.environment, current.setup.mode) ==
                VerifiedSetup.connectionKey(tested.setup.settings.terminal, tested.setup.poiId, tested.setup.environment, tested.setup.mode)
            ) {
                saved.copy(verifiedSetup = identity)
            } else {
                saved
            }
        }
        val remembered =
            settings.current().verifiedSetup == identity && reading(settings.current(), read = false).validationIdentity == identity
        if (remembered) onVerified(tested)
        return remembered
    }

    /**
     * Reads this device's verified terminal certificate through [read], without a shared key. Only needed on an Adyen
     * terminal in local mode whose environment is unknown. A changed setup cannot receive a stale result.
     */
    suspend fun readLocalEnvironment(read: suspend () -> TerminalEnvironment?) {
        val setup = current()
        if (!setup.readsLocalEnvironment) return
        val environment = read() ?: return
        remember(DetectedEnvironment(setup, environment))
    }

    /**
     * Persists [detected] only while its originating terminal settings remain current. Destination rules decide which
     * fields may be learned; unrelated settings writes are preserved. Main-safe and atomic with the staleness check.
     */
    suspend fun remember(detected: DetectedEnvironment) {
        settings.update {
            if (it.terminal == detected.setup.settings.terminal) {
                it.copy(terminal = detected.setup.learnedEnvironment(detected))
            } else {
                it
            }
        }
    }

    /** The setup now, with the secrets it needs decrypted ([TerminalSetup.unlock]). */
    suspend fun unlocked(forValidation: Boolean = false): UnlockedSetup =
        reading(settings.current(), read = true, forValidation = forValidation)

    /**
     * Whether the Payments app can be boarded (or revoked) with this phone's own credential, which the Adyen API key
     * created for the merchant account and the selected environment ([owner]): now with the stored
     * settings, or with the [candidate] setup. [SetupProblem.PAYMENTS_APP_API_KEY] until Tap to Pay setup creates it,
     * and [SetupProblem.UNREADABLE_PAYMENTS_APP_KEY] when it can no longer be decrypted.
     */
    suspend fun boarding(candidate: UnlockedSetup? = null): BoardingSetup {
        val key =
            when (val owner = owner(candidate)) {
                is BoardingOwner.Blocked -> return BoardingSetup.Blocked(owner.problem)
                is BoardingOwner.Ready -> owner
            }
        val records =
            when (val read = boardingCredentials.read()) {
                JournalRead.Absent -> emptyList()
                JournalRead.Unreadable -> return BoardingSetup.Blocked(SetupProblem.UNREADABLE_PAYMENTS_APP_KEY)
                is JournalRead.Present -> read.value
            }
        val credential = records.firstOrNull { it.matches(key) }?.apiKey ?: return BoardingSetup.Blocked(SetupProblem.PAYMENTS_APP_API_KEY)
        return BoardingSetup.Ready(key.environment, credential)
    }

    private val deviceReads = MutableStateFlow(0)

    private val readings: Flow<UnlockedSetup> =
        combine(settings.settings, secrets.configured, deviceReads, historySwitchPending) { current, _, _, _ ->
            reading(current, read = false)
        }

    /**
     * The setup each time the settings or the saved secrets change, or the device is read again ([readDevice]), without
     * reading any secret.
     */
    val changes: Flow<TerminalSetup> = readings.map { it.setup }

    /**
     * Distinct changes of what [AdyenApi.target] is made from, the saved API key's fingerprint included, without
     * reading any secret: unrelated settings writes, such as typing receipt text, do not decrypt the key again.
     */
    internal val apiChanges: Flow<*> =
        readings
            .map { Triple(it.setup.settings.terminal, it.setup.apiSetup, it.setup.paymentContext()) to it.validationIdentity }
            .distinctUntilChanged()

    /**
     * Distinct changes of what [boarding] is read from (the connection fields, the installed Payments app, the saved
     * Adyen API key and this phone's own credential), for advisory checks; without decrypting any secret.
     */
    internal val boardingChanges: Flow<*> =
        combine(settings.settings, secrets.configured, deviceReads) { current, _, _ ->
            boardingSecrets(current, read = false).identity
        }.distinctUntilChanged()

    /** Distinct setup-identity changes that invalidate cached Management wallet and capability readings. */
    internal val discoveryChanges: Flow<*> =
        changes
            .map { setup ->
                Triple(setup.settings.terminal to setup.settings.verifiedSetup, setup.paymentContext(), setup.problem)
            }.distinctUntilChanged()

    /** Resolves [changes] again with what the device says now: an Adyen Payments app may have been installed or removed. */
    fun readDevice() = deviceReads.update { it + 1 }
}
