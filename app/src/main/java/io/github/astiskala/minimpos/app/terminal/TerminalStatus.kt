package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.settings.SettingsRepository
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Where payments go and whether they, and printing, can work, as Home, Settings and the receipt screens show it.
 *
 * @property loaded False until the settings and the list of saved secrets have been read.
 * @property mode Where payments go: a terminal (local or in the cloud), the Payments app or the simulator, never
 *   [TerminalMode.AUTO].
 * @property onTerminal Whether the app runs on an Adyen terminal, which then is where payments go in terminal mode.
 * @property poiId The terminal's POIID (or the simulator's); null while one still has to be entered.
 * @property setupProblem What must still be entered before payments can be taken (the Checkout API included), from
 *   [TerminalSetup.problem]; null when nothing is missing (always in simulator mode).
 * @property connection The latest connection check, whose result stays until the next one.
 * @property printerAvailable Whether printing is offered, see [TerminalSetup.printerAvailable].
 * @property environment Where payments go, TEST or LIVE, as [TerminalSetup.environment] says.
 * @property apiSetup How far the Checkout API is set up, see [TerminalSetup.apiSetup]; captures follow the same decision.
 * @property paymentLinks Whether checkout offers payment links, see [TerminalSetup.paymentLinks].
 * @property paymentsApps The Adyen Payments apps installed, by environment, see [TerminalSetup.paymentsApps].
 */
data class TerminalState(
    val loaded: Boolean = false,
    val mode: TerminalMode = TerminalMode.SIMULATOR,
    val onTerminal: Boolean = false,
    val poiId: String? = null,
    val setupProblem: SetupProblem? = null,
    val connection: TerminalConnection = TerminalConnection.Unknown,
    val printerAvailable: Boolean = false,
    val environment: TerminalEnvironment? = null,
    val apiSetup: ApiSetup = ApiSetup.Simulated,
    val paymentLinks: Boolean = false,
    val paymentsApps: Set<TerminalEnvironment> = emptySet(),
) {
    /**
     * Whether receipts and payment links can be shared through Android's share sheet: on phones and tablets, not on an
     * Adyen terminal, which has no apps to share with (it emails instead).
     */
    val canShare: Boolean get() = !onTerminal

    /** What must still be entered before the Checkout API can be used ([ApiSetup.problem]); null when nothing is. */
    val apiProblem: SetupProblem? get() = apiSetup.problem
}

/**
 * The terminal's status, kept up to date from the [TerminalSetup] (which follows the settings and saved secrets) and
 * what the terminal reports, and published as one [state].
 *
 * @param setups Where payments go and what is missing, as the settings and secrets change.
 * @param gateway Checks the connection, and tells which terminals have a printer and which environment they are in.
 * @param settings The stored settings; a detected environment is saved into them.
 * @param scope Keeps [state] up to date and runs the background checks started by [start].
 */
class TerminalStatus(
    private val setups: TerminalSetupSource,
    private val gateway: TerminalGateway,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val connection = MutableStateFlow<TerminalConnection>(TerminalConnection.Unknown)

    /** The current status; it holds the defaults (not [TerminalState.loaded]) until settings and secrets are read. */
    val state: StateFlow<TerminalState> =
        combine(setups.changes, connection, gateway.printers) { setup, checked, printers ->
            TerminalState(
                loaded = true,
                mode = setup.mode,
                onTerminal = setup.onTerminal,
                poiId = setup.poiId,
                setupProblem = setup.problem,
                connection = checked,
                printerAvailable = setup.printerAvailable(printers),
                environment = setup.environment,
                apiSetup = setup.apiSetup,
                paymentLinks = setup.paymentLinks,
                paymentsApps = setup.paymentsApps,
            )
        }.stateIn(scope, SharingStarted.Eagerly, TerminalState())

    /** Where payments go in [TerminalMode.AUTO] on this device, see [TerminalSetup.automaticMode]. */
    val automaticMode: TerminalMode get() = TerminalSetup.automaticMode(setups.device)

    /**
     * Starts the background work, once per process: a connection check at startup and whenever the terminal settings or
     * the saved passphrase change (typing is debounced into one check), while [TerminalSetup.checksConnection]; and saving the
     * environment each connection reports into the settings.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            setups.changes
                .map { setup ->
                    Triple(
                        setup.settings.terminal.copy(environment = null, cloudRegion = null),
                        setup.checksConnection,
                        setup.connectionProblem,
                    )
                }.distinctUntilChanged()
                .debounce(CHECK_DELAY_MILLIS)
                .collectLatest { (_, checked) -> if (checked) check() }
        }
        scope.launch {
            gateway.detectedEnvironment.filterNotNull().collect { detected ->
                settings.update {
                    it.copy(terminal = it.terminal.copy(environment = detected.environment, cloudRegion = detected.cloudRegion))
                }
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
        if (connection.value is TerminalConnection.Failed && setups.current().checksConnection) check()
    }

    /**
     * Reads the device again, so [state] follows an Adyen Payments app installed or removed while the app was in the
     * background (see [TerminalSetupSource.readDevice]); called whenever the app comes back to the front.
     */
    fun readDevice() = setups.readDevice()

    /** The terminals connected in the cloud, for choosing one in Settings; see [TerminalGateway.connectedTerminals]. */
    suspend fun connectedTerminals(): ConnectedTerminals = gateway.connectedTerminals()

    private companion object {
        const val CHECK_DELAY_MILLIS = 1_000L
    }
}
