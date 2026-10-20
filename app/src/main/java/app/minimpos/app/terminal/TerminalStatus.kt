package app.minimpos.app.terminal

import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.TerminalEnvironment
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
 * Independent read-only support checks, not payment-readiness verification.
 * @property api Current credential/account access result.
 * @property connection Diagnosis result, or setup-only validation where diagnosis is unsupported.
 * @property checkedAt Completion time in UTC epoch milliseconds.
 * @property facts Non-secret support fields using stable technical identifiers; empty values mean unavailable.
 */
data class SetupDiagnostics(
    val api: ApiCheck,
    val connection: TerminalConnection,
    val checkedAt: Long,
    val facts: Map<String, String>,
)

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
 * @property importPending Whether a verified import journal still blocks new financial operations until saving resumes.
 * @property capabilities What the destination can do and asks of its setup, see [TerminalSetup.capabilities].
 * @property connectionProblem What must still be entered before the destination can be reached at all (printing,
 *   refunds, connection checks), from [TerminalSetup.connectionProblem]; null when nothing is missing.
 * @property printingStanding Printing readiness from destination support, known hardware and local preferences.
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
    val importPending: Boolean = false,
    val capabilities: DestinationCapabilities = DestinationCapabilities(),
    val connectionProblem: SetupProblem? = null,
    val printingStanding: CapabilityStanding = CapabilityStanding.NEEDS_CONFIGURATION,
) {
    /**
     * Why printing cannot be tried now: what the destination still needs, else [SetupProblem.PRINTER] while printing is
     * not offered; null when a print can be sent.
     */
    val printBlocked: SetupProblem? get() = connectionProblem ?: SetupProblem.PRINTER.takeUnless { printerAvailable }

    /**
     * Why terminal totals cannot be checked or a period closed now: what the destination still needs, else
     * [SetupProblem.TOTALS] where it reports no totals (Tap to Pay); null when they can.
     */
    val totalsBlocked: SetupProblem? get() = connectionProblem ?: SetupProblem.TOTALS.takeUnless { capabilities.supportsTerminalFeatures }

    /**
     * Whether receipts and payment links can be shared through Android's share sheet: on phones and tablets, not on an
     * Adyen terminal, which has no apps to share with (it emails instead).
     */
    val canShare: Boolean get() = !onTerminal

    /** Receipt sharing needs another app on this device; Adyen terminals use email instead. */
    val receiptSharingStanding: CapabilityStanding
        get() = if (canShare) CapabilityStanding.AVAILABLE else CapabilityStanding.UNSUPPORTED

    /** What must still be entered before the Checkout API can be used ([ApiSetup.problem]); null when nothing is. */
    val apiProblem: SetupProblem? get() = apiSetup.problem

    /**
     * Whether payments still need setting up: something must be entered, or no connection check (the Settings test or
     * the background one) has verified the connection and Checkout API since the setup last changed
     * ([SetupProblem.SETUP_NOT_VERIFIED]), or the last check found something to set up. False for the simulator and
     * until [loaded]. Every action that reaches Adyen or a terminal waits for this; status checks of unfinished
     * operations never do.
     */
    val needsSetup: Boolean
        get() = loaded && (setupProblem != null || (capabilities.checksConnection && connection is TerminalConnection.NotSetUp))
}

/**
 * The terminal's status, kept up to date from the [TerminalSetup] (which follows the settings and saved secrets) and
 * what the terminal reports, and published as one [state].
 *
 * @param setups Where payments go and what is missing, as the settings and secrets change.
 * @param gateway Checks the connection, and tells which terminals have a printer and which environment they are in.
 * @param scope Keeps [state] up to date, runs background checks and forwards detections to [TerminalSetupSource.remember].
 * @param verifyApi Checks the exact unlocked candidate's credential and account when its cached verification is invalid.
 * @param verifyPhone Confirms existing phone registration against the candidate account/store; never registers automatically.
 * @param inspectPhone Refreshes advisory boarding-credential and app-status diagnostics only on an explicit connection test.
 */
class TerminalStatus(
    private val setups: TerminalSetupSource,
    private val gateway: TerminalGateway,
    private val scope: CoroutineScope,
    private val verifyApi: suspend (UnlockedSetup) -> ApiInspection,
    private val verifyPhone: suspend (TerminalSetup) -> TapToPayOutcome,
    private val inspectPhone: suspend () -> Unit,
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
                importPending = setup.importPending,
                capabilities = setup.capabilities,
                connectionProblem = setup.connectionProblem,
                printingStanding = setup.printingStanding(printers),
            )
        }.stateIn(scope, SharingStarted.Eagerly, TerminalState())

    /** Where payments go in [TerminalMode.AUTO] on this device, see [TerminalSetup.automaticMode]. */
    val automaticMode: TerminalMode get() = TerminalSetup.automaticMode(setups.device)

    /**
     * Starts the background work, once per process: a connection check at startup and whenever the terminal settings or
     * the saved passphrase change (typing is debounced into one check), while [DestinationCapabilities.checksConnection]; and saving the
     * environment each connection reports into the settings.
     */
    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            setups.changes
                .map { setup ->
                    Triple(
                        setup.settings.terminal.selectEnvironment(setup.environment.takeIf { setup.capabilities.selectsEnvironment }),
                        setup.capabilities.checksConnection,
                        setup.connectionProblem,
                    )
                }.distinctUntilChanged()
                .debounce(CHECK_DELAY_MILLIS)
                .collectLatest { (_, checked) -> if (checked) check() }
        }
        scope.launch {
            gateway.detectedEnvironment.filterNotNull().collect(setups::remember)
        }
    }

    /**
     * Checks the stored connection, publishes the result in [state] and returns it.
     * [includeDiagnostics] forces fresh API verification and adds advisory Payments app checks for an explicit Test
     * connection action. Background checks leave it false, so ordinary updates never open the Payments app or require
     * extra Management permissions.
     */
    suspend fun check(includeDiagnostics: Boolean = false): TerminalConnection {
        connection.value = TerminalConnection.Checking
        val result =
            if (setups.current().importPending) {
                TerminalConnection.NotSetUp(
                    SetupProblem.TRANSFER_PENDING,
                )
            } else {
                checkActive(includeDiagnostics)
            }
        val actual = setups.current()
        if (includeDiagnostics && actual.boardsPhone && !actual.importPending) inspectPhone()
        connection.value = result
        return result
    }

    /**
     * Refreshes independent API, diagnosis and supported phone checks without boarding, key changes or payment requests.
     * Returns null if the originating setup changed while checking. Does not mark setup verified.
     */
    suspend fun inspect(): SetupDiagnostics? {
        gateway.refreshEnvironment()
        val candidate = setups.unlocked(forValidation = true)
        val actual = candidate.setup
        val fence = setups.SetupFence(candidate, SetupFreshness.SECRETS)
        val pending = actual.importPending
        val inspection = if (pending) ApiInspection(ApiCheck.NotSetUp(SetupProblem.TRANSFER_PENDING)) else verifyApi(candidate)
        val checked = if (pending) TerminalConnection.NotSetUp(SetupProblem.TRANSFER_PENDING) else gateway.diagnose()
        if (actual.boardsPhone && !pending) inspectPhone()
        if (!fence.stillCurrent()) return null
        val terminal = actual.settings.terminal
        val device = setups.device
        return SetupDiagnostics(
            inspection.api,
            checked,
            System.currentTimeMillis(),
            buildMap {
                put("integration", actual.mode.name)
                put("environment", actual.environment?.name.orEmpty())
                val roles = inspection.roles
                put("apiHasRequiredRoles", roles?.complete?.toString() ?: "NOT_CHECKED")
                put("apiRolesRequired", "[${AdyenRole.names(AdyenRole.required())}]")
                put("apiRolesPresent", roles?.let { "[${AdyenRole.names(it.present)}]" } ?: "NOT_CHECKED")
                put("apiRolesMissing", roles?.let { "[${AdyenRole.names(it.missing)}]" } ?: "NOT_CHECKED")
                put("onTerminal", actual.onTerminal.toString())
                put("merchantAccount", terminal.merchantAccount)
                put("poiId", actual.poiId.orEmpty())
                put("host", actual.host.orEmpty())
                put("storeId", terminal.storeId)
                put("installationId", terminal.paymentsAppInstallationId)
                put("keyIdentifier", terminal.keyIdentifier)
                put("keyVersion", terminal.keyVersion.toString())
                put("liveUrlPrefix", terminal.liveUrlPrefix)
                put("cloudRegion", terminal.cloudRegion?.name.orEmpty())
                put("androidVersion", device.osVersion)
                put("deviceModel", device.model)
                put("paymentsApps", device.paymentsApps.joinToString { it.name })
                actual.environment?.let { device.paymentsAppVersion(it) }?.let { put("paymentsAppVersion", it) }
                put("supportsDiagnosis", actual.capabilities.diagnoses.toString())
                put("supportsPrinting", actual.capabilities.supportsPrinting.toString())
                put("supportsRemoteStatus", actual.capabilities.supportsRemoteStatus.toString())
                put("setupProblem", actual.problem?.name.orEmpty())
                put("connectionProblem", actual.connectionProblem?.name.orEmpty())
            },
        )
    }

    private suspend fun checkActive(forceApi: Boolean): TerminalConnection {
        gateway.refreshEnvironment()
        val actual = setups.current()
        val candidate = setups.unlocked(forValidation = true)
        val complete = candidate.setup.apiSetup == ApiSetup.Complete
        val blocked =
            if (forceApi || (complete && actual.apiSetup != ApiSetup.Complete)) credentialCheck(candidate) else null
        val result = blocked ?: gateway.diagnose()
        if (result is TerminalConnection.Connected && complete) {
            setups.rememberVerified(candidate)
        }
        return result
    }

    private suspend fun credentialCheck(candidate: UnlockedSetup): TerminalConnection? =
        when (val checked = verifyApi(candidate).api) {
            is ApiCheck.NotSetUp -> TerminalConnection.NotSetUp(checked.problem)
            is ApiCheck.Failed -> TerminalConnection.Failed(checked.failure)
            ApiCheck.Works -> if (candidate.setup.boardsPhone) phoneCheck(candidate.setup) else null
        }

    private suspend fun phoneCheck(setup: TerminalSetup): TerminalConnection? =
        when (val phone = verifyPhone(setup)) {
            is TapToPayOutcome.Boarded -> null
            is TapToPayOutcome.NotSetUp -> TerminalConnection.NotSetUp(phone.problem)
            is TapToPayOutcome.Failed -> TerminalConnection.Failed(phone.failure)
            TapToPayOutcome.Unregistered -> TerminalConnection.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED)
        }

    /** Checks again when the last check failed while payments go to a terminal, so a warning clears once it answers. */
    suspend fun recheckIfFailed() {
        if (connection.value is TerminalConnection.Failed && setups.current().capabilities.checksConnection) check()
    }

    /**
     * Reads the device again, so [state] follows an Adyen Payments app installed or removed while the app was in the
     * background (see [TerminalSetupSource.readDevice]); called whenever the app comes back to the front.
     */
    fun readDevice() = setups.readDevice()

    private companion object {
        const val CHECK_DELAY_MILLIS = 1_000L
    }
}
