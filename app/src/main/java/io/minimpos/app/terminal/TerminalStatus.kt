package io.minimpos.app.terminal

import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.AppSettings
import io.minimpos.app.data.settings.PrinterMode
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Where payments go and whether they, and printing, can work, as Home, Settings and the receipt screens show it.
 *
 * @property loaded False until the settings and the list of saved secrets have been read.
 * @property mode Where payments go: [TerminalMode.TERMINAL] or [TerminalMode.SIMULATOR], never [TerminalMode.AUTO].
 * @property onTerminal Whether the app runs on an Adyen terminal, which then is where payments go in terminal mode.
 * @property poiId The terminal's POIID (or the simulator's); null while one still has to be entered.
 * @property setupProblem What must still be entered before the terminal can be used, from
 *   [TerminalGateway.setupProblem]; null when nothing is missing (always in simulator mode).
 * @property connection The latest connection check, whose result stays until the next one.
 * @property printerAvailable Whether printing is offered, see [TerminalStatus].
 * @property environment The environment of the terminal's certificate, remembered from the last connection.
 */
data class TerminalState(
    val loaded: Boolean = false,
    val mode: TerminalMode = TerminalMode.SIMULATOR,
    val onTerminal: Boolean = false,
    val poiId: String? = null,
    val setupProblem: String? = null,
    val connection: TerminalConnection = TerminalConnection.Unknown,
    val printerAvailable: Boolean = false,
    val environment: TerminalEnvironment? = null,
)

/**
 * The terminal's status, kept up to date from the settings, the saved secrets and what the terminal reports, and
 * published as one [state].
 *
 * Printing is offered as Settings › Receipts › Printer says: always, never, or detected. Detected means the simulator's
 * own setting, or whether the terminal reported a printer (see [TerminalGateway.printers]); a terminal not asked yet is
 * assumed to have one when its POIID starts with S1F2 or S1F4, since models such as the S1E4Pro and S1F4Pro share the
 * same `Build.MODEL`.
 *
 * @param gateway Checks the connection and knows where payments go.
 * @param settings The stored settings, followed as they change.
 * @param secrets Tells whether a shared key passphrase is saved.
 * @param scope Keeps [state] up to date and runs the background checks started by [start].
 */
class TerminalStatus(
    private val gateway: TerminalGateway,
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    private val scope: CoroutineScope,
) {
    private val connection = MutableStateFlow<TerminalConnection>(TerminalConnection.Unknown)

    /** The current status; it holds the defaults (not [TerminalState.loaded]) until settings and secrets are read. */
    val state: StateFlow<TerminalState> =
        combine(settings.settings, secrets.configured, connection, gateway.printers) { appSettings, configured, checked, printers ->
            val terminal = appSettings.terminal
            val mode = gateway.effectiveMode(terminal)
            val poiId = gateway.poiId(terminal)
            TerminalState(
                loaded = true,
                mode = mode,
                onTerminal = gateway.device.isAdyenTerminal,
                poiId = poiId,
                setupProblem = gateway.setupProblem(terminal, Secret.TERMINAL_PASSPHRASE in configured),
                connection = checked,
                printerAvailable = printerAvailable(appSettings, mode, poiId, printers),
                environment = terminal.environment,
            )
        }.stateIn(scope, SharingStarted.Eagerly, TerminalState())

    /** Where payments go in [TerminalMode.AUTO] on this device, see [TerminalGateway.automaticMode]. */
    val automaticMode: TerminalMode get() = gateway.automaticMode

    /**
     * Starts the background work, once per process: a connection check at startup and whenever the terminal settings or
     * the saved passphrase change (typing is debounced into one check), while payments go to a terminal; and saving the
     * environment each connection reports into the settings.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            combine(settings.settings, secrets.configured) { current, configured ->
                current.terminal.copy(environment = null) to (Secret.TERMINAL_PASSPHRASE in configured)
            }.distinctUntilChanged()
                .debounce(CHECK_DELAY_MILLIS)
                .collectLatest {
                    if (gateway.effectiveMode(settings.current().terminal) == TerminalMode.TERMINAL) check()
                }
        }
        scope.launch {
            gateway.detectedEnvironment.filterNotNull().collect { environment ->
                settings.update { it.copy(terminal = it.terminal.copy(environment = environment)) }
            }
        }
    }

    /** Checks the connection now with the stored settings, publishes the result in [state] and returns it. */
    suspend fun check(): TerminalConnection {
        connection.value = TerminalConnection.Checking
        return gateway.diagnose().also { connection.value = it }
    }

    /** Checks again when the last check failed while payments go to a terminal, so a warning clears once it answers. */
    suspend fun recheckIfFailed() {
        if (state.value.mode == TerminalMode.TERMINAL && connection.value is TerminalConnection.Failed) check()
    }

    private fun printerAvailable(
        appSettings: AppSettings,
        mode: TerminalMode,
        poiId: String?,
        printers: Map<String, Boolean>,
    ): Boolean =
        when (appSettings.receipt.printerMode) {
            PrinterMode.ON -> true
            PrinterMode.OFF -> false
            PrinterMode.AUTO if mode == TerminalMode.SIMULATOR -> appSettings.simulator.hasPrinter
            PrinterMode.AUTO -> poiId != null && (printers[poiId] ?: PRINTER_MODEL_PREFIXES.any { poiId.startsWith(it, ignoreCase = true) })
        }

    private companion object {
        const val CHECK_DELAY_MILLIS = 1_000L
        val PRINTER_MODEL_PREFIXES = listOf("S1F2", "S1F4")
    }
}
