package io.minimpos.app.terminal

import io.minimpos.app.data.db.SetupProblem
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.PrinterMode
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.terminal.transport.TerminalEnvironment
import io.minimpos.terminal.transport.TerminalKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Where payments go and what is still missing, resolved in one place ([resolve]) from the stored settings, which
 * secrets are saved and the device. It is the one reading of the terminal and Checkout API setup: [TerminalGateway]
 * sends requests (or reports [problem]) with it, [AdyenApi] takes [apiSetup] from it, and [TerminalStatus] publishes it.
 * What each destination needs is its [DestinationRules], which [resolve] asks. Missing setup is never thrown; it is a
 * [problem] that says what to enter. A saved secret that can no longer be decrypted is only found when it is read
 * ([unlock]).
 *
 * @property settings The settings it was resolved from; the gateway takes the shared key and timeout from them.
 * @property destination Where payments go, with what it needs and can do.
 * @property onTerminal Whether the app runs on an Adyen terminal, which then is where payments go in terminal mode.
 * @property poiId On a terminal its own POIID; in the cloud and on the network the configured one; with the Payments app
 *   its boarded installation ID; for the simulator the simulator's. Null while one still has to be entered (or boarded).
 * @property host Where the Terminal API listens in terminal mode: [LOCALHOST] on a terminal, else the configured address;
 *   null in the other modes or while none is entered.
 * @property problem What must still be entered before requests can be sent; null when nothing is missing (always for the
 *   simulator).
 * @property apiSetup How far the Checkout API is set up, which decides where captures go.
 * @property environment Where payments go: the installed Payments app's environment with [TerminalMode.PAYMENTS_APP],
 *   else the one detected at the last connection ([io.minimpos.app.data.settings.TerminalSettings.environment]); null
 *   for the simulator and until known.
 */
data class TerminalSetup(
    val settings: AppSettings,
    val destination: DestinationRules,
    val onTerminal: Boolean,
    val poiId: String?,
    val host: String?,
    val problem: SetupProblem?,
    val apiSetup: ApiSetup,
    val environment: TerminalEnvironment?,
) {
    /**
     * Where payments go: [TerminalMode.TERMINAL], [TerminalMode.CLOUD], [TerminalMode.PAYMENTS_APP] or
     * [TerminalMode.SIMULATOR], never [TerminalMode.AUTO].
     */
    val mode: TerminalMode get() = destination.mode

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
     * Whether checkout offers payment links: switched on in Settings › Payments and the Checkout API is set up. They are
     * never simulated, so not while payments go to the simulator.
     */
    val paymentLinks: Boolean get() = settings.payment.paymentLinks && apiSetup == ApiSetup.Complete

    /** The secrets to read for this setup, of those [saved]: the [destination]'s and the Checkout API key. */
    fun secretsToRead(saved: Set<Secret>): Set<Secret> = (destination.secrets + Secret.CHECKOUT_API_KEY).intersect(saved)

    /**
     * This setup with the secrets [read] for it (see [secretsToRead]): each decrypted value, or null for one saved that
     * can no longer be decrypted, which becomes the [problem] (when the [destination] needs it and nothing else is
     * missing) or makes a complete [apiSetup] incomplete.
     */
    fun unlock(read: Map<Secret, String?>): UnlockedSetup {
        val unreadable = read.filterValues { it == null }.keys
        val problem = problem ?: destination.secrets.filter { it in unreadable }.firstNotNullOfOrNull(::unreadable)
        val api =
            if (apiSetup == ApiSetup.Complete && Secret.CHECKOUT_API_KEY in unreadable) {
                ApiSetup.Incomplete(SetupProblem.UNREADABLE_API_KEY)
            } else {
                apiSetup
            }
        return UnlockedSetup(
            copy(problem = problem, apiSetup = api),
            read
                .mapNotNull { (secret, value) ->
                    value?.let { secret to it }
                }.toMap(),
        )
    }

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
            val api = apiSetup(settings, environment, destination.simulatesApi, keySaved = Secret.CHECKOUT_API_KEY in saved)
            return TerminalSetup(settings, destination, device.isAdyenTerminal, poiId, host, problem, api, environment)
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
                Secret.CHECKOUT_API_KEY -> SetupProblem.UNREADABLE_API_KEY
                Secret.PAYMENTS_APP_API_KEY -> SetupProblem.UNREADABLE_PAYMENTS_APP_KEY
                Secret.SMTP_PASSWORD, Secret.PIN_VERIFIER -> null
            }

        /**
         * How far the Checkout API is set up: simulated in simulator mode; else the Customer Area while nothing of it is
         * entered, and once anything is, complete or what is missing (including the [environment], until detected).
         */
        private fun apiSetup(
            settings: AppSettings,
            environment: TerminalEnvironment?,
            simulator: Boolean,
            keySaved: Boolean,
        ): ApiSetup {
            val terminal = settings.terminal
            return when {
                simulator -> {
                    ApiSetup.Simulated
                }

                !keySaved && terminal.merchantAccount.isBlank() -> {
                    ApiSetup.CustomerArea
                }

                terminal.merchantAccount.isBlank() -> {
                    ApiSetup.Incomplete(SetupProblem.MERCHANT_ACCOUNT)
                }

                !keySaved -> {
                    ApiSetup.Incomplete(SetupProblem.API_KEY)
                }

                environment == null -> {
                    ApiSetup.Incomplete(SetupProblem.ENVIRONMENT)
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

    /** The Checkout API key (also the cloud's); null when none could be read. */
    val apiKey: String? get() = values[Secret.CHECKOUT_API_KEY]

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

    /** The setup each time the settings or the saved secrets change, without reading any secret. */
    val changes: Flow<TerminalSetup> =
        combine(settings.settings, secrets.configured) { current, saved -> TerminalSetup.resolve(current, saved, device) }
}
