package app.minimpos.app.terminal

import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStore
import app.minimpos.app.data.security.SharedKeyGenerator
import app.minimpos.app.data.security.SharedKeyMaterial
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.SettingsRepository
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.core.money.PaymentContext
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

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
 * @property environment Where payments go: the installed Payments app's environment with [TerminalMode.PAYMENTS_APP],
 *   else the selected environment or this device's verified certificate
 *   ([app.minimpos.app.data.settings.TerminalSettings.environment]); null
 *   for the simulator and until known.
 * @property paymentsApps The environments of the Adyen Payments apps installed on the device when it was resolved.
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

    /** Whether read-only terminal discovery can propose connection fields for this destination. */
    val discoversTerminals: Boolean get() = destination.discoversTerminals

    internal val needsSharedKey: Boolean get() = Secret.TERMINAL_PASSPHRASE in destination.secrets

    internal val boardsPhone: Boolean get() = destination.boardsPhone

    internal val importPending: Boolean get() = apiSetup.problem == SetupProblem.TRANSFER_PENDING

    /** Whether Settings must ask the merchant for TEST or LIVE before API credentials or terminal details. */
    val selectsEnvironment: Boolean get() = destination.selectsEnvironment(onTerminal)

    internal val suppliesCertificateEnvironment: Boolean get() = onTerminal && destination == LocalTerminal

    /** Whether this device's verified terminal certificate must supply the still-unknown local environment. */
    val readsLocalEnvironment: Boolean get() = suppliesCertificateEnvironment && environment == null

    /** The terminal settings after this destination accepts [detected]; persistence validates its original setup. */
    fun learnedEnvironment(detected: DetectedEnvironment) = destination.learnedEnvironment(this, detected)

    /**
     * Whether printing is offered, as Settings › Receipts › Printer says: always, never, or detected, which the
     * [destination] decides with what terminals reported ([printers], by POIID; see [DestinationRules.printer]).
     */
    fun printerAvailable(printers: Map<String, Boolean>): Boolean =
        when (settings.receipt.printerMode) {
            PrinterMode.ON -> true
            PrinterMode.OFF -> false
            PrinterMode.AUTO -> destination.printer(settings, poiId, printers)
        }

    /**
     * Whether the connection is checked in the background (at startup and when the setup changes): whenever payments go
     * to something real rather than the simulator ([DestinationRules.checksConnection]).
     */
    val checksConnection: Boolean get() = destination.checksConnection

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

    /** Whether the resolved destination supports shopper-presented wallet codes, independently of account configuration. */
    val scannedWallets: Boolean get() = destination.scannedWallets

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
            )
        }

        /**
         * Whether the Adyen Payments app on [device] can be boarded (or revoked) for the merchant account in [settings]
         * with the Payments app API key, given which secrets are [saved] and the key as [read] (null when it is not
         * saved or can no longer be decrypted): the same device rules as for payments through it, then the merchant
         * account and the key.
         */
        fun boarding(
            settings: AppSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
            read: String?,
        ): BoardingSetup {
            val apps = device.paymentsApps
            val problem =
                paymentsAppProblem(device.isAdyenTerminal, apps)
                    ?: SetupProblem.MERCHANT_ACCOUNT.takeIf { settings.terminal.merchantAccount.isBlank() }
                    ?: SetupProblem.PAYMENTS_APP_API_KEY.takeIf { Secret.PAYMENTS_APP_API_KEY !in saved }
            return when {
                problem != null -> BoardingSetup.Blocked(problem)
                read == null -> BoardingSetup.Blocked(SetupProblem.UNREADABLE_PAYMENTS_APP_KEY)
                else -> BoardingSetup.Ready(apps.single(), read)
            }
        }

        /** What to enter again when [secret] can no longer be decrypted; null for the secrets no destination needs. */
        private fun unreadable(secret: Secret): SetupProblem? =
            when (secret) {
                Secret.TERMINAL_PASSPHRASE -> SetupProblem.UNREADABLE_PASSPHRASE
                Secret.ADYEN_API_KEY -> SetupProblem.UNREADABLE_API_KEY
                Secret.PAYMENTS_APP_API_KEY -> SetupProblem.UNREADABLE_PAYMENTS_APP_KEY
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

/** Whether Tap to Pay can be set up on this phone, see [TerminalSetup.boarding]. */
sealed interface BoardingSetup {
    /**
     * The Payments app can be boarded. [toString] leaves the API key out.
     *
     * @property environment The installed Payments app's, which the Management API is called in.
     * @property apiKey The Payments app API key, decrypted.
     */
    data class Ready(
        val environment: TerminalEnvironment,
        val apiKey: String,
    ) : BoardingSetup {
        override fun toString() = "Ready($environment)"
    }

    /**
     * Something must be entered or installed first.
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
 */
class TerminalSetupSource(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    val device: DeviceInfo,
    /** Pending confirmed history-switch journal, blocking new operations until destination recovery completes. */
    private val historySwitchPending: Flow<Boolean> = flowOf(false),
) {
    /** The setup with the stored settings now, without reading any secret. */
    suspend fun current(): TerminalSetup = reading(settings.current(), read = false).setup

    private suspend fun reading(
        current: AppSettings,
        read: Boolean,
        forValidation: Boolean = false,
    ): UnlockedSetup {
        val raw = TerminalSetup.resolve(current, secrets.configured.first(), device, verified = true, pendingImport = false)
        val requested = raw.secretsToRead(Secret.entries.toSet())
        val snapshot = secrets.setupSecrets(current.terminal.verificationKey(raw.poiId, raw.environment, raw.mode), requested, read)
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

    internal val keyRecovery = KeyCreationRecovery()

    internal inner class KeyCreationRecovery {
        private val generator = SharedKeyGenerator()

        fun generate(): SharedKeyMaterial = generator.generate()

        fun identity(key: String): String = generator.identity(key)

        suspend fun read(): String? = secrets.readKeyCreation()

        suspend fun exists(): Boolean = secrets.keyCreationExists.first()

        suspend fun write(value: String?) = secrets.writeKeyCreation(value)
    }

    internal val registrations = RegistrationRecovery()

    internal inner class RegistrationRecovery {
        fun access(
            current: AppSettings,
            values: Map<Secret, String>,
        ): BoardingSetup = TerminalSetup.boarding(current, values.keys, device, values[Secret.PAYMENTS_APP_API_KEY])

        suspend fun read(): String? = secrets.readBoarding()

        suspend fun exists(): Boolean = secrets.hasBoarding()

        suspend fun write(value: String?) = secrets.writeBoarding(value)
    }

    internal fun learnedCandidate(detected: DetectedEnvironment): AppSettings =
        detected.setup.settings.copy(terminal = detected.setup.learnedEnvironment(detected))

    internal suspend fun rememberVerified(tested: UnlockedSetup): Boolean {
        val current = reading(settings.current(), read = false, forValidation = true)
        val identity = tested.validationIdentity ?: return false
        if (identity != current.validationIdentity) return false
        settings.update { saved ->
            if (saved.terminal.verificationKey(current.setup.poiId, current.setup.environment, current.setup.mode) ==
                tested.setup.settings.terminal
                    .verificationKey(tested.setup.poiId, tested.setup.environment, tested.setup.mode)
            ) {
                saved.copy(verifiedSetup = identity)
            } else {
                saved
            }
        }
        return settings.current().verifiedSetup == identity && reading(settings.current(), read = false).validationIdentity == identity
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

    /** Whether Tap to Pay can be set up now with the stored settings, see [TerminalSetup.boarding]. */
    suspend fun boarding(): BoardingSetup {
        val saved = secrets.configured.first()
        val key = if (Secret.PAYMENTS_APP_API_KEY in saved) secrets.get(Secret.PAYMENTS_APP_API_KEY) else null
        return TerminalSetup.boarding(settings.current(), saved, device, key)
    }

    private val deviceReads = MutableStateFlow(0)

    /**
     * The setup each time the settings or the saved secrets change, or the device is read again ([readDevice]), without
     * reading any secret.
     */
    val changes: Flow<TerminalSetup> =
        combine(settings.settings, secrets.configured, deviceReads, historySwitchPending) { current, _, _, _ ->
            reading(current, read = false).setup
        }

    /** Resolves [changes] again with what the device says now: an Adyen Payments app may have been installed or removed. */
    fun readDevice() = deviceReads.update { it + 1 }
}
