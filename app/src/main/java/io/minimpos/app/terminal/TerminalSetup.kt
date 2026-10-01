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
 * @property mode Where payments go: [TerminalMode.TERMINAL] or [TerminalMode.SIMULATOR], never [TerminalMode.AUTO].
 * @property onTerminal Whether the app runs on an Adyen terminal, which then is where payments go in terminal mode.
 * @property poiId On a terminal its own POIID; elsewhere the configured one (or the simulator's); null while one still
 *   has to be entered.
 * @property host Where the Terminal API listens in terminal mode: [LOCALHOST] on a terminal, else the configured address;
 *   null for the simulator or while none is entered.
 * @property problem What must still be entered before requests can be sent; null when nothing is missing (always for the
 *   simulator). A saved passphrase that can no longer be decrypted is only found when it is read.
 * @property apiSetup How far the Checkout API is set up, which decides where captures go.
 */
data class TerminalSetup(
    val settings: AppSettings,
    val mode: TerminalMode,
    val onTerminal: Boolean,
    val poiId: String?,
    val host: String?,
    val problem: String?,
    val apiSetup: ApiSetup,
) {
    /**
     * Whether printing is offered, as Settings › Receipts › Printer says: always, never, or detected. Detected means the
     * simulator's own setting, or whether the terminal reported a printer ([printers], by POIID); a terminal not asked yet
     * is assumed to have one when its POIID starts with S1F2 or S1F4, since models such as the S1E4Pro and S1F4Pro share
     * the same `Build.MODEL`.
     */
    fun printerAvailable(printers: Map<String, Boolean>): Boolean =
        when (settings.receipt.printerMode) {
            PrinterMode.ON -> true
            PrinterMode.OFF -> false
            PrinterMode.AUTO if mode == TerminalMode.SIMULATOR -> settings.simulator.hasPrinter
            PrinterMode.AUTO -> poiId != null && (printers[poiId] ?: PRINTER_MODEL_PREFIXES.any { poiId.startsWith(it, ignoreCase = true) })
        }

    /** Resolving the setup, and the fixed identities it uses. */
    companion object {
        /** The POIID the simulator reports, in the same `<model>-<serial>` form as a real one. */
        const val SIMULATOR_POI_ID = "SIMULATOR-000000001"

        /** Where the Terminal API listens when the app runs on the terminal itself. */
        const val LOCALHOST = "localhost"

        private val PRINTER_MODEL_PREFIXES = listOf("S1F2", "S1F4")

        /** Where payments go in [TerminalMode.AUTO] on [device]: this terminal when running on one, the simulator elsewhere. */
        fun automaticMode(device: DeviceInfo): TerminalMode = if (device.isAdyenTerminal) TerminalMode.TERMINAL else TerminalMode.SIMULATOR

        /** The setup with [settings] on [device], given which secrets are [saved], using [texts] for missing fields. */
        fun resolve(
            settings: AppSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
            texts: TerminalTexts,
        ): TerminalSetup {
            val terminal = settings.terminal
            val mode = if (terminal.mode == TerminalMode.AUTO) automaticMode(device) else terminal.mode
            val simulator = mode == TerminalMode.SIMULATOR
            val poiId =
                device.detectedPoiId
                    ?: terminal.poiIdOverride.trim().ifEmpty { null }
                    ?: SIMULATOR_POI_ID.takeIf { simulator }
            val host =
                when {
                    simulator -> null
                    device.isAdyenTerminal -> LOCALHOST
                    else -> terminal.host.trim().ifEmpty { null }
                }
            val problem =
                when {
                    poiId == null -> texts.poiId
                    simulator -> null
                    host == null -> texts.host
                    terminal.keyIdentifier.isBlank() -> texts.keyIdentifier
                    Secret.TERMINAL_PASSPHRASE !in saved -> texts.passphrase
                    terminal.keyVersion < 1 -> texts.keyVersion
                    else -> null
                }
            val api = apiSetup(settings, simulator, keySaved = Secret.CHECKOUT_API_KEY in saved, texts)
            return TerminalSetup(settings, mode, device.isAdyenTerminal, poiId, host, problem, api)
        }

        /**
         * How far the Checkout API is set up: simulated in simulator mode; else the Customer Area while nothing of it is
         * entered, and once anything is, complete or what is missing.
         */
        private fun apiSetup(
            settings: AppSettings,
            simulator: Boolean,
            keySaved: Boolean,
            texts: TerminalTexts,
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
                    ApiSetup.Incomplete(texts.merchantAccount)
                }

                !keySaved -> {
                    ApiSetup.Incomplete(texts.apiKey)
                }

                terminal.environment == null -> {
                    ApiSetup.Incomplete(texts.environment)
                }

                terminal.environment == TerminalEnvironment.LIVE && terminal.liveUrlPrefix.isBlank() -> {
                    ApiSetup.Incomplete(texts.livePrefix)
                }

                else -> {
                    ApiSetup.Complete
                }
            }
        }
    }
}

/**
 * Reads what the [TerminalSetup] is resolved from (the stored settings and which secrets are saved) for the modules that
 * use it, so none of them reads those inputs itself.
 *
 * @param settings The stored settings.
 * @param secrets Tells which secrets are saved.
 * @property device The device the app runs on.
 * @param texts Supplies messages in the current language whenever the setup is resolved.
 */
class TerminalSetupSource(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    val device: DeviceInfo,
    private val texts: () -> TerminalTexts = { TerminalTexts() },
) {
    /** Messages in the current language, also used by the gateway and Checkout API. */
    val messages: TerminalTexts get() = texts()

    /** The setup with the stored settings now. */
    suspend fun current(): TerminalSetup = TerminalSetup.resolve(settings.current(), secrets.configured.first(), device, messages)

    /** The setup each time the settings or the saved secrets change. */
    val changes: Flow<TerminalSetup> =
        combine(settings.settings, secrets.configured) { current, saved -> TerminalSetup.resolve(current, saved, device, messages) }
}
