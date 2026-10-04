package io.github.astiskala.minimpos.app.terminal

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.provider.Settings
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.SecretStore
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.core.money.PaymentContext
import io.github.astiskala.minimpos.terminal.client.DiagnosisResult
import io.github.astiskala.minimpos.terminal.client.PaymentParams
import io.github.astiskala.minimpos.terminal.client.PosApplication
import io.github.astiskala.minimpos.terminal.client.PrintJob
import io.github.astiskala.minimpos.terminal.client.PrintOutcome
import io.github.astiskala.minimpos.terminal.client.RefundParams
import io.github.astiskala.minimpos.terminal.client.TerminalClient
import io.github.astiskala.minimpos.terminal.client.TransactionKind
import io.github.astiskala.minimpos.terminal.client.TransactionOutcome
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppLinks
import io.github.astiskala.minimpos.terminal.transport.AdyenCloudDevices
import io.github.astiskala.minimpos.terminal.transport.AdyenLocalTransport
import io.github.astiskala.minimpos.terminal.transport.CloudCredentials
import io.github.astiskala.minimpos.terminal.transport.CloudDevices
import io.github.astiskala.minimpos.terminal.transport.CloudEndpoint
import io.github.astiskala.minimpos.terminal.transport.CloudRegion
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalKey
import io.github.astiskala.minimpos.terminal.transport.TerminalTls
import io.github.astiskala.minimpos.terminal.transport.TerminalTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Facts about the device the app runs on; tests supply their own. */
interface DeviceInfo {
    /** The terminal's POIID (`<model>-<serial>`) when running on an Adyen Android terminal, else null. */
    val detectedPoiId: String?

    /** The device model (`Build.MODEL`), for display. */
    val model: String

    /** The Android version (`Build.VERSION.RELEASE`), sent to Adyen in the application info. */
    val osVersion: String

    /** Whether this is an Adyen terminal, which is decided by a POIID having been detected. */
    val isAdyenTerminal: Boolean get() = detectedPoiId != null

    /**
     * The device's country (ISO 3166-1 alpha-2, possibly empty), which picks the Cloud device API's data centre and the
     * tax a new installation starts with.
     */
    val country: String get() = Locale.getDefault().country

    /**
     * The environments of the Adyen Payments apps installed (there is one app per environment). Read each time, since
     * the Payments app can be installed while the app runs.
     */
    val paymentsApps: Set<TerminalEnvironment> get() = emptySet()
}

/**
 * [DeviceInfo] of the real device. Adyen terminals publish their POIID as the device name
 * (`Settings.Global.DEVICE_NAME`); on other devices that name does not look like a POIID, so none is detected.
 */
class AndroidDeviceInfo(
    private val context: Context,
) : DeviceInfo {
    override val detectedPoiId: String? =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
            ?.trim()
            ?.takeIf { POI_ID.matches(it) }
    override val model: String = Build.MODEL.orEmpty()
    override val osVersion: String = Build.VERSION.RELEASE.orEmpty()
    override val country: String
        get() {
            val locales =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.getSystemService(LocaleManager::class.java)?.systemLocales
                } else {
                    null
                } ?: Resources.getSystem().configuration.locales
            return locales.get(0)?.country.orEmpty()
        }

    // The manifest's <queries> make the Payments apps visible.
    override val paymentsApps: Set<TerminalEnvironment>
        get() =
            TerminalEnvironment.entries
                .filter { context.packageManager.getLaunchIntentForPackage(PaymentsAppLinks.packageName(it)) != null }
                .toSet()

    /** The POIID format. */
    companion object {
        /** `[device model]-[serial number]`, as in the terminal certificates Adyen's library validates. */
        val POI_ID = Regex("^[A-Za-z0-9]{3,}-[A-Za-z0-9]{9,15}$")
    }
}

/** What a connection check found out about the payment terminal. */
sealed interface TerminalConnection {
    /** No check has run yet. */
    data object Unknown : TerminalConnection

    /** A check is running. */
    data object Checking : TerminalConnection

    /**
     * The terminal (or the simulator) answered a diagnosis request.
     *
     * @property diagnosis What it reported, including whether it has a printer.
     */
    data class Connected(
        val diagnosis: DiagnosisResult,
    ) : TerminalConnection

    /**
     * Something must be set up first (e.g. the shared key), so the terminal was not contacted.
     *
     * @property problem What to enter ([TerminalSetup.problem], or a saved secret that cannot be read).
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : TerminalConnection

    /**
     * The terminal could not be reached or did not answer properly.
     *
     * @property message Why, as the terminal or Adyen worded it; null when it did not answer at all.
     */
    data class Failed(
        val message: String?,
    ) : TerminalConnection
}

/** Collects what the simulator "prints", so it can be shown on screen. Thread-safe. */
class VirtualPrinter {
    private val _jobs = MutableStateFlow<List<PrintJob>>(emptyList())

    /** Everything printed since the last [clear], oldest first. */
    val jobs: StateFlow<List<PrintJob>> = _jobs.asStateFlow()

    /** Adds [job] to [jobs]; the simulator calls this for each print request. */
    fun print(job: PrintJob) = _jobs.update { it + job }

    /** Discards the printed jobs, when the operator dismisses the printout. */
    fun clear() {
        _jobs.value = emptyList()
    }
}

/**
 * Where a connection found that payments go.
 *
 * @property environment TEST or LIVE: from this device's certificate, or the cloud's selected environment.
 * @property cloudRegion The Cloud device API's live data centre; null for TEST and for a terminal on the network.
 */
data class DetectedEnvironment(
    val environment: TerminalEnvironment,
    val cloudRegion: CloudRegion? = null,
)

/**
 * What [TerminalGateway] did with a request: sent it where payments go (or found that destination unreachable), with
 * [T] what came of it, or sent nothing because something must be set up first.
 */
sealed interface Attempt<out T> {
    /**
     * It was sent, or the destination could not be reached.
     *
     * @param T What came of it.
     * @property result The destination's answer, or why it could not be reached, as Adyen or the client worded it.
     */
    data class Made<out T>(
        val result: T,
    ) : Attempt<T>

    /**
     * Nothing was sent.
     *
     * @property problem What must be entered, installed or fixed first.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : Attempt<Nothing>
}

/** The terminals connected to the merchant account in the cloud, from [TerminalGateway.connectedTerminals]. */
sealed interface ConnectedTerminals {
    /**
     * Adyen listed them.
     *
     * @property poiIds Their POIIDs, sorted.
     */
    data class Listed(
        val poiIds: List<String>,
    ) : ConnectedTerminals

    /**
     * They cannot be listed before something is entered.
     *
     * @property problem What to enter first.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : ConnectedTerminals

    /**
     * They could not be listed.
     *
     * @property message Adyen's answer, or why it could not be reached.
     */
    data class Failed(
        val message: String,
    ) : ConnectedTerminals
}

/**
 * The payment terminal as the rest of the app sees it: the Terminal API operations, sent to the [Destination] the
 * [TerminalSetup] at the time of each call says (this terminal, one on the network or in the cloud, the Adyen Payments
 * app on this phone, or the built-in simulator). What a destination can do (abort, diagnose, recover a missing answer,
 * how long payments wait) is the destination's; the gateway asks it rather than the mode.
 *
 * Missing setup (no POIID, IP address or shared key, [TerminalSetup.connectionProblem]; for payments also the Checkout
 * API, [TerminalSetup.problem]) never throws: every operation reports it typed, as [Attempt.NotSetUp] (or what a
 * connection check or status check returns without one). What terminals report
 * about themselves (the environment and whether they have a printer) is remembered in [detectedEnvironment] and
 * [printers].
 */
class TerminalGateway(
    /** Where payments go now, what is missing, and the secrets to reach it with. */
    private val setups: TerminalSetupSource,
    /** The built-in simulator, whose Checkout API simulation [AdyenApi] shares. */
    simulator: SimulatedTerminal,
    /** Identifies Mini mPOS to Adyen on every payment and refund. */
    private val application: PosApplication,
    /** Reaches the Adyen Payments app of an environment with a shared key; the container plugs in its App Links. */
    paymentsApp: (key: TerminalKey, environment: TerminalEnvironment) -> TerminalTransport,
    /** Connects to a real terminal; tests replace it. */
    connect: (host: String, key: TerminalKey, tls: TerminalTls) -> TerminalTransport = ::AdyenLocalTransport,
    /** Reaches terminals in the cloud with an API key; tests replace it. */
    cloud: (CloudCredentials) -> CloudDevices = { AdyenCloudDevices(it) },
    /** Reads this device's terminal certificate before credentials are entered; tests replace the TLS connection. */
    private val localEnvironment: suspend () -> TerminalEnvironment? = { TerminalTls().readEnvironment(TerminalSetup.LOCALHOST) },
) {
    private val cloudTerminal = CloudTerminal(cloud, setups.device, ::cloudDetected)
    private val destinations =
        listOf(simulator, LocalTerminal(connect, ::environmentDetected), cloudTerminal, PaymentsAppDestination(paymentsApp))

    private val _detectedEnvironment = MutableStateFlow<DetectedEnvironment?>(null)

    /** Certificate environment or selected cloud endpoint from the last connection. */
    val detectedEnvironment: StateFlow<DetectedEnvironment?> = _detectedEnvironment.asStateFlow()

    private val _printers = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * Whether a terminal has a printer, by POIID, as far as it has told: a diagnosis reports a printer status only when
     * one is fitted, and a print request can be refused because there is none. Terminals not asked yet are absent.
     */
    val printers: StateFlow<Map<String, Boolean>> = _printers.asStateFlow()

    /**
     * Takes a card payment, see [TerminalClient.pay]. [onSending] is called with the terminal's POIID right before the
     * request is sent; without complete setup, the Checkout API included, nothing is sent ([Attempt.NotSetUp]), and an
     * unreachable destination is [TransactionOutcome.NotProcessed].
     */
    suspend fun pay(
        params: PaymentParams,
        serviceId: String,
        onContext: suspend (PaymentContext) -> Unit = {},
        onSending: suspend (poiId: String) -> Unit = {},
    ): Attempt<TransactionOutcome> = send(serviceId, paying = true, onSending, onContext) { it.pay(params, serviceId) }

    /**
     * Refunds an earlier payment, see [TerminalClient.refund]; [onSending] and missing setup are handled as for [pay],
     * except that a refund does not need the Checkout API.
     *
     * @throws IllegalArgumentException If [RefundParams.originalTimestamp] is not an XML date-time.
     */
    suspend fun refund(
        params: RefundParams,
        serviceId: String,
        expected: PaymentContext? = null,
        onContext: suspend (PaymentContext) -> Unit = {},
        onSending: suspend (poiId: String) -> Unit = {},
    ): Attempt<TransactionOutcome> = send(serviceId, paying = false, onSending, onContext, expected) { it.refund(params, serviceId) }

    /**
     * Asks the terminal once for the result of the [kind] of transaction sent with [serviceId], see
     * [TerminalClient.status]. Without complete setup the terminal cannot be asked, so the outcome stays
     * [TransactionOutcome.Unknown].
     */
    suspend fun status(
        serviceId: String,
        kind: TransactionKind,
        expected: PaymentContext? = null,
    ): TransactionOutcome =
        when (val connection = connection()) {
            is Connection.Open -> {
                if (expected == null || expected.matchesTerminal(connection.context())) {
                    connection.client.status(serviceId, kind)
                } else {
                    TransactionOutcome.Unknown(serviceId, SetupProblem.PAYMENT_CONTEXT.name)
                }
            }

            is Connection.NotSetUp -> {
                TransactionOutcome.Unknown(serviceId, connection.problem.name)
            }

            is Connection.Unreachable -> {
                TransactionOutcome.Unknown(serviceId, connection.message)
            }
        }

    /**
     * Asks the terminal to stop the [kind] of transaction sent with [serviceId], see [TerminalClient.abort]. Returns
     * whether the request was sent, which it is not without complete setup, nor to a destination that takes no abort
     * ([DestinationRules.aborts]).
     */
    suspend fun abort(
        serviceId: String,
        kind: TransactionKind = TransactionKind.PAYMENT,
    ): Boolean {
        val connection = connection() as? Connection.Open ?: return false
        if (!connection.destination.aborts) return false
        connection.client.abort(serviceId, kind)
        return true
    }

    /**
     * Prints [jobs], see [TerminalClient.print]. Without complete setup nothing is sent ([Attempt.NotSetUp]); a refusal
     * for want of a printer is remembered in [printers].
     */
    suspend fun print(jobs: List<PrintJob>): Attempt<PrintOutcome> =
        when (val connection = connection()) {
            is Connection.Open -> {
                val outcome = connection.client.print(jobs)
                if (outcome is PrintOutcome.Failed && outcome.noPrinter) learnPrinter(connection.client, hasPrinter = false)
                Attempt.Made(outcome)
            }

            is Connection.NotSetUp -> {
                Attempt.NotSetUp(connection.problem)
            }

            is Connection.Unreachable -> {
                Attempt.Made(PrintOutcome.Failed(connection.message, noPrinter = false))
            }
        }

    /**
     * Checks the connection with a diagnosis request, see [TerminalClient.diagnose], and remembers whether the terminal
     * has a printer. A destination that takes no diagnosis ([DestinationRules.diagnoses]) only has its setup checked, without
     * being opened. Never [TerminalConnection.Unknown] or [TerminalConnection.Checking].
     */
    suspend fun diagnose(): TerminalConnection =
        when (val connection = connection()) {
            is Connection.NotSetUp -> {
                TerminalConnection.NotSetUp(connection.problem)
            }

            is Connection.Unreachable -> {
                TerminalConnection.Failed(connection.message)
            }

            is Connection.Open if !connection.destination.diagnoses -> {
                TerminalConnection.Connected(DiagnosisResult(reachable = true, message = null))
            }

            is Connection.Open -> {
                val diagnosis = connection.client.diagnose()
                if (diagnosis.reachable) {
                    learnPrinter(connection.client, diagnosis.hasPrinter)
                    TerminalConnection.Connected(diagnosis)
                } else {
                    TerminalConnection.Failed(diagnosis.message)
                }
            }
        }

    /** The terminals connected to the merchant account in the cloud, for choosing one, see [CloudTerminal.connectedTerminals]. */
    suspend fun connectedTerminals(): ConnectedTerminals = cloudTerminal.connectedTerminals(setups.unlocked())

    /** Reads and saves this device's certificate environment before setup discovery or a connection check. */
    suspend fun readEnvironment() = setups.readLocalEnvironment(localEnvironment)

    /** Called by [TerminalTls] with the environment of each verified terminal certificate. */
    internal fun environmentDetected(environment: TerminalEnvironment) {
        _detectedEnvironment.value = DetectedEnvironment(environment)
    }

    private fun cloudDetected(endpoint: CloudEndpoint) {
        _detectedEnvironment.value = DetectedEnvironment(endpoint.environment, endpoint.region)
    }

    private fun learnPrinter(
        client: TerminalClient,
        hasPrinter: Boolean,
    ) = _printers.update { it + (client.identity.poiId to hasPrinter) }

    /**
     * The client for where payments go now with the stored settings, or why there is none: for a payment ([paying]) the
     * whole [TerminalSetup.problem], Checkout API included, else the [TerminalSetup.connectionProblem].
     */
    private suspend fun connection(paying: Boolean = false): Connection {
        readEnvironment()
        val unlocked = setups.unlocked()
        val setup = unlocked.setup
        return (if (paying) setup.problem else setup.connectionProblem)?.let(Connection::NotSetUp)
            ?: destinations.single { it.rules == setup.destination }.connect(unlocked, application)
    }

    /**
     * Sends a payment ([paying]) or refund with [call] once [onSending] was told the POIID, or says why nothing can be
     * sent.
     */
    private suspend fun send(
        serviceId: String,
        paying: Boolean,
        onSending: suspend (poiId: String) -> Unit,
        onContext: suspend (PaymentContext) -> Unit,
        expected: PaymentContext? = null,
        call: suspend (TerminalClient) -> TransactionOutcome,
    ): Attempt<TransactionOutcome> =
        when (val connection = connection(paying)) {
            is Connection.Open -> {
                if (expected != null && !expected.matchesTerminal(connection.context())) {
                    Attempt.NotSetUp(SetupProblem.PAYMENT_CONTEXT)
                } else {
                    onContext(connection.context())
                    onSending(connection.client.identity.poiId)
                    val outcome = call(connection.client)
                    onContext(connection.context())
                    Attempt.Made(outcome)
                }
            }

            is Connection.NotSetUp -> {
                Attempt.NotSetUp(connection.problem)
            }

            is Connection.Unreachable -> {
                Attempt.Made(TransactionOutcome.NotProcessed(serviceId, connection.message))
            }
        }

    /** Fixed identities. */
    companion object {
        /** The nexo SaleID used when none is configured. */
        const val DEFAULT_SALE_ID = "MiniMPOS"
    }
}
