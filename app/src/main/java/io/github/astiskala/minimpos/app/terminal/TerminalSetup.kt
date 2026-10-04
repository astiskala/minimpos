package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.PrinterMode
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.core.money.PaymentContext
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
 *   ([io.github.astiskala.minimpos.app.data.settings.TerminalSettings.environment]); null
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

    /** Whether Settings must ask the merchant for TEST or LIVE before API credentials or terminal details. */
    val selectsEnvironment: Boolean get() = destination.selectsEnvironment(onTerminal)

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
    val paymentLinks: Boolean get() = apiSetup == ApiSetup.Complete || apiSetup == ApiSetup.Simulated

    /** The secrets to read for this setup, of those [saved]: the [destination]'s and the Adyen API key. */
    fun secretsToRead(saved: Set<Secret>): Set<Secret> = (destination.secrets + Secret.ADYEN_API_KEY).intersect(saved)

    /**
     * This setup with the secrets [read] for it (see [secretsToRead]): each decrypted value, or null for one saved that
     * can no longer be decrypted, which becomes the [connectionProblem] (when the [destination] needs it and nothing
     * else is missing) or makes a complete [apiSetup] incomplete.
     */
    fun unlock(read: Map<Secret, String?>): UnlockedSetup {
        val unreadable = read.filterValues { it == null }.keys
        val problem = connectionProblem ?: destination.secrets.filter { it in unreadable }.firstNotNullOfOrNull(::unreadable)
        val api =
            if (apiSetup == ApiSetup.Complete && Secret.ADYEN_API_KEY in unreadable) {
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
        )
    }

    /** Non-secret identity for this resolved setup, with [detected] from its actual transport when known. */
    fun paymentContext(detected: TerminalEnvironment? = environment): PaymentContext =
        PaymentContext(
            destination = mode.name,
            poiId = poiId.orEmpty(),
            saleId =
                settings.terminal.saleId
                    .trim()
                    .ifEmpty { TerminalGateway.DEFAULT_SALE_ID },
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
            return TerminalSetup(settings, destination, device.isAdyenTerminal, poiId, host, problem, api, environment, device.paymentsApps)
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
 */
class UnlockedSetup(
    val setup: TerminalSetup,
    private val values: Map<Secret, String>,
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
 */
class TerminalSetupSource(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    val device: DeviceInfo,
) {
    /** The setup with the stored settings now, without reading any secret. */
    suspend fun current(): TerminalSetup = TerminalSetup.resolve(settings.current(), secrets.configured.first(), device)

    /**
     * Reads this device's verified terminal certificate through [read], without a shared key. Only needed on an Adyen
     * terminal in local mode whose environment is unknown. A changed setup cannot receive a stale result.
     */
    suspend fun readLocalEnvironment(read: suspend () -> TerminalEnvironment?) {
        val setup = current()
        if (!setup.onTerminal || setup.destination != LocalTerminal || setup.environment != null) return
        val environment = read() ?: return
        settings.update {
            if (it.terminal == setup.settings.terminal) it.copy(terminal = it.terminal.copy(environment = environment)) else it
        }
    }

    /** The setup now, with the secrets it needs decrypted ([TerminalSetup.unlock]). */
    suspend fun unlocked(): UnlockedSetup {
        val saved = secrets.configured.first()
        val setup = TerminalSetup.resolve(settings.current(), saved, device)
        return setup.unlock(setup.secretsToRead(saved).associateWith { secrets.get(it) })
    }

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
        combine(settings.settings, secrets.configured, deviceReads) { current, saved, _ -> TerminalSetup.resolve(current, saved, device) }

    /** Resolves [changes] again with what the device says now: an Adyen Payments app may have been installed or removed. */
    fun readDevice() = deviceReads.update { it + 1 }
}
