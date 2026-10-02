package io.minimpos.app.terminal

import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.PrinterMode
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Where payments go and what is still missing, resolved in one place ([resolve]) from the stored settings, which
 * secrets are saved and the device. It is the one reading of the terminal and Checkout API setup: [TerminalGateway]
 * sends requests (or reports [problem]) with it, [AdyenApi] takes [apiSetup] from it, and [TerminalStatus] publishes it.
 * Missing setup is never thrown; it is a [problem] that says what to enter.
 *
 * @property settings The settings it was resolved from; the gateway takes the shared key and timeout from them.
 * @property mode Where payments go: [TerminalMode.TERMINAL], [TerminalMode.CLOUD], [TerminalMode.PAYMENTS_APP] or
 *   [TerminalMode.SIMULATOR], never [TerminalMode.AUTO].
 * @property onTerminal Whether the app runs on an Adyen terminal, which then is where payments go in terminal mode.
 * @property poiId On a terminal its own POIID; in the cloud and on the network the configured one; with the Payments app
 *   its boarded installation ID; for the simulator the simulator's. Null while one still has to be entered (or boarded).
 * @property host Where the Terminal API listens in terminal mode: [LOCALHOST] on a terminal, else the configured address;
 *   null in the other modes or while none is entered.
 * @property problem What must still be entered before requests can be sent; null when nothing is missing (always for the
 *   simulator). A saved passphrase or API key that can no longer be decrypted is only found when it is read.
 * @property apiSetup How far the Checkout API is set up, which decides where captures go.
 * @property environment Where payments go: the installed Payments app's environment with [TerminalMode.PAYMENTS_APP],
 *   else the one detected at the last connection ([io.minimpos.app.data.settings.TerminalSettings.environment]); null
 *   for the simulator and until known.
 */
data class TerminalSetup(
    val settings: AppSettings,
    val mode: TerminalMode,
    val onTerminal: Boolean,
    val poiId: String?,
    val host: String?,
    val problem: SetupProblem?,
    val apiSetup: ApiSetup,
    val environment: TerminalEnvironment?,
) {
    /**
     * Whether printing is offered, as Settings › Receipts › Printer says: always, never, or detected. Detected means the
     * simulator's own setting, no printer for the Payments app, or whether the terminal reported a printer ([printers],
     * by POIID); a terminal not asked yet is assumed to have one when its POIID starts with S1F2 or S1F4, since models
     * such as the S1E4Pro and S1F4Pro share the same `Build.MODEL`.
     */
    fun printerAvailable(printers: Map<String, Boolean>): Boolean =
        when (settings.receipt.printerMode) {
            PrinterMode.ON -> true
            PrinterMode.OFF -> false
            PrinterMode.AUTO if mode == TerminalMode.SIMULATOR -> settings.simulator.hasPrinter
            PrinterMode.AUTO if mode == TerminalMode.PAYMENTS_APP -> false
            PrinterMode.AUTO -> poiId != null && (printers[poiId] ?: PRINTER_MODEL_PREFIXES.any { poiId.startsWith(it, ignoreCase = true) })
        }

    /**
     * Whether the connection is checked in the background (at startup and when the setup changes): whenever payments go
     * to something real rather than the simulator.
     */
    val checksConnection: Boolean get() = mode != TerminalMode.SIMULATOR

    /** Resolving the setup, and the fixed identities it uses. */
    companion object {
        /** The POIID the simulator reports, in the same `<model>-<serial>` form as a real one. */
        const val SIMULATOR_POI_ID = "SIMULATOR-000000001"

        /** Where the Terminal API listens when the app runs on the terminal itself. */
        const val LOCALHOST = "localhost"

        private val PRINTER_MODEL_PREFIXES = listOf("S1F2", "S1F4")

        /** Where payments go in [TerminalMode.AUTO] on [device]: this terminal when running on one, the simulator elsewhere. */
        fun automaticMode(device: DeviceInfo): TerminalMode = if (device.isAdyenTerminal) TerminalMode.TERMINAL else TerminalMode.SIMULATOR

        /** The setup with [settings] on [device], given which secrets are [saved]. */
        fun resolve(
            settings: AppSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
        ): TerminalSetup {
            val terminal = settings.terminal
            val mode = if (terminal.mode == TerminalMode.AUTO) automaticMode(device) else terminal.mode
            val poiId = poiId(mode, settings, device)
            val host =
                when {
                    mode != TerminalMode.TERMINAL -> null
                    device.isAdyenTerminal -> LOCALHOST
                    else -> terminal.host.trim().ifEmpty { null }
                }
            val paymentsApps = if (mode == TerminalMode.PAYMENTS_APP) device.paymentsApps else emptySet()
            val environment =
                when (mode) {
                    TerminalMode.SIMULATOR -> null
                    TerminalMode.PAYMENTS_APP -> paymentsApps.singleOrNull()
                    TerminalMode.TERMINAL, TerminalMode.CLOUD, TerminalMode.AUTO -> terminal.environment
                }
            val problem =
                when (mode) {
                    TerminalMode.SIMULATOR, TerminalMode.AUTO -> {
                        null
                    }

                    TerminalMode.TERMINAL -> {
                        terminalProblem(settings, saved, poiId, host)
                    }

                    TerminalMode.CLOUD -> {
                        cloudProblem(settings, saved, poiId)
                    }

                    TerminalMode.PAYMENTS_APP -> {
                        paymentsAppProblem(device.isAdyenTerminal, paymentsApps)
                            ?: sharedKeyProblem(settings, saved)
                            ?: SetupProblem.PAYMENTS_APP_NOT_BOARDED.takeIf { poiId == null }
                    }
                }
            val api = apiSetup(settings, environment, mode == TerminalMode.SIMULATOR, keySaved = Secret.CHECKOUT_API_KEY in saved)
            return TerminalSetup(settings, mode, device.isAdyenTerminal, poiId, host, problem, api, environment)
        }

        /** The POIID requests go to in [mode], as [TerminalSetup.poiId] describes it. */
        private fun poiId(
            mode: TerminalMode,
            settings: AppSettings,
            device: DeviceInfo,
        ): String? {
            val configured =
                settings.terminal.poiIdOverride
                    .trim()
                    .ifEmpty { null }
            return when (mode) {
                TerminalMode.CLOUD -> {
                    configured
                }

                TerminalMode.PAYMENTS_APP -> {
                    settings.terminal.paymentsAppInstallationId
                        .trim()
                        .ifEmpty { null }
                }

                TerminalMode.SIMULATOR -> {
                    device.detectedPoiId ?: configured ?: SIMULATOR_POI_ID
                }

                TerminalMode.TERMINAL, TerminalMode.AUTO -> {
                    device.detectedPoiId ?: configured
                }
            }
        }

        /** What is missing for a terminal on this device or the network. */
        private fun terminalProblem(
            settings: AppSettings,
            saved: Set<Secret>,
            poiId: String?,
            host: String?,
        ): SetupProblem? =
            when {
                poiId == null -> SetupProblem.POI_ID
                host == null -> SetupProblem.HOST
                else -> sharedKeyProblem(settings, saved)
            }

        /** What is missing for a terminal in the cloud: its POIID, and the merchant account and API key. */
        private fun cloudProblem(
            settings: AppSettings,
            saved: Set<Secret>,
            poiId: String?,
        ): SetupProblem? =
            when {
                settings.terminal.merchantAccount.isBlank() -> SetupProblem.MERCHANT_ACCOUNT
                Secret.CHECKOUT_API_KEY !in saved -> SetupProblem.CLOUD_API_KEY
                poiId == null -> SetupProblem.POI_ID
                else -> null
            }

        /**
         * Whether the Adyen Payments app on [device] can be boarded (or revoked) for the merchant account in [settings]
         * with the Payments app API key, given which secrets are [saved]: the same device rules as for payments
         * through it, then the merchant account and the key. A saved key that can no longer be decrypted is only found
         * when it is read.
         */
        fun boarding(
            settings: AppSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
        ): BoardingSetup {
            val apps = device.paymentsApps
            val problem =
                paymentsAppProblem(device.isAdyenTerminal, apps)
                    ?: SetupProblem.MERCHANT_ACCOUNT.takeIf { settings.terminal.merchantAccount.isBlank() }
                    ?: SetupProblem.PAYMENTS_APP_API_KEY.takeIf { Secret.PAYMENTS_APP_API_KEY !in saved }
            return problem?.let(BoardingSetup::Blocked) ?: BoardingSetup.Ready(apps.single())
        }

        /** What is wrong with the device for the Payments app: it must be a phone with exactly one Payments app. */
        private fun paymentsAppProblem(
            onTerminal: Boolean,
            paymentsApps: Set<TerminalEnvironment>,
        ): SetupProblem? =
            when {
                onTerminal -> SetupProblem.PAYMENTS_APP_ON_TERMINAL
                paymentsApps.isEmpty() -> SetupProblem.PAYMENTS_APP_MISSING
                paymentsApps.size > 1 -> SetupProblem.PAYMENTS_APP_AMBIGUOUS
                else -> null
            }

        private fun sharedKeyProblem(
            settings: AppSettings,
            saved: Set<Secret>,
        ): SetupProblem? =
            when {
                settings.terminal.keyIdentifier.isBlank() -> SetupProblem.KEY_IDENTIFIER
                Secret.TERMINAL_PASSPHRASE !in saved -> SetupProblem.PASSPHRASE
                settings.terminal.keyVersion < 1 -> SetupProblem.KEY_VERSION
                else -> null
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

/** Whether Tap to Pay can be set up on this phone, see [TerminalSetup.boarding]. */
sealed interface BoardingSetup {
    /**
     * The Payments app can be boarded.
     *
     * @property environment The installed Payments app's, which the Management API is called in.
     */
    data class Ready(
        val environment: TerminalEnvironment,
    ) : BoardingSetup

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
 * use it, so none of them reads those inputs itself.
 *
 * @param settings The stored settings.
 * @param secrets Tells which secrets are saved.
 * @property device The device the app runs on.
 * @property describe Words a [SetupProblem] in the current language, for the messages stored with a transaction or
 *   capture (the screens word problems themselves); by default its name, for tests.
 */
class TerminalSetupSource(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    val device: DeviceInfo,
    val describe: (SetupProblem) -> String = { it.name },
) {
    /** The setup with the stored settings now. */
    suspend fun current(): TerminalSetup = TerminalSetup.resolve(settings.current(), secrets.configured.first(), device)

    /** Whether Tap to Pay can be set up now with the stored settings, see [TerminalSetup.boarding]. */
    suspend fun boarding(): BoardingSetup = TerminalSetup.boarding(settings.current(), secrets.configured.first(), device)

    /** The setup each time the settings or the saved secrets change. */
    val changes: Flow<TerminalSetup> =
        combine(settings.settings, secrets.configured) { current, saved -> TerminalSetup.resolve(current, saved, device) }
}
