package io.minimpos.app.terminal

import android.content.Context
import android.os.Build
import android.provider.Settings
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.SettingsRepository
import io.minimpos.app.data.settings.SimulatorSettings
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.terminal.client.DiagnosisResult
import io.minimpos.terminal.client.PaymentParams
import io.minimpos.terminal.client.PosApplication
import io.minimpos.terminal.client.PrintJob
import io.minimpos.terminal.client.PrintOutcome
import io.minimpos.terminal.client.RefundParams
import io.minimpos.terminal.client.TerminalClient
import io.minimpos.terminal.client.TerminalIdentity
import io.minimpos.terminal.client.TransactionKind
import io.minimpos.terminal.client.TransactionOutcome
import io.minimpos.terminal.simulator.SimulatorConfig
import io.minimpos.terminal.simulator.TerminalSimulator
import io.minimpos.terminal.transport.AdyenLocalTransport
import io.minimpos.terminal.transport.TerminalEnvironment
import io.minimpos.terminal.transport.TerminalKey
import io.minimpos.terminal.transport.TerminalTls
import io.minimpos.terminal.transport.TerminalTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.seconds

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
}

/**
 * [DeviceInfo] of the real device. Adyen terminals publish their POIID as the device name
 * (`Settings.Global.DEVICE_NAME`); on other devices that name does not look like a POIID, so none is detected.
 */
class AndroidDeviceInfo(
    context: Context,
) : DeviceInfo {
    override val detectedPoiId: String? =
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
            ?.trim()
            ?.takeIf { POI_ID.matches(it) }
    override val model: String = Build.MODEL.orEmpty()
    override val osVersion: String = Build.VERSION.RELEASE.orEmpty()

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
     * @property message What to enter, as [TerminalGateway.setupProblem] words it.
     */
    data class NotSetUp(
        val message: String,
    ) : TerminalConnection

    /**
     * The terminal could not be reached or did not answer properly.
     *
     * @property message Why, for display.
     */
    data class Failed(
        val message: String,
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
 * The payment terminal as the rest of the app sees it: where payments go (this terminal, one on the network, or the
 * built-in simulator), and the Terminal API operations, run with the stored settings at the time of each call.
 *
 * Missing setup (no POIID, IP address or shared key) never throws: every operation reports it through its outcome,
 * which then says what to enter. The transport to a real terminal is reused while its host and shared key stay the
 * same. What terminals report about themselves (their certificate's environment and whether they have a printer) is
 * remembered in [detectedEnvironment] and [printers].
 */
class TerminalGateway(
    private val settings: SettingsRepository,
    private val secrets: SecretStore,
    /** The device the app runs on, which decides where [TerminalMode.AUTO] sends payments. */
    val device: DeviceInfo,
    virtualPrinter: VirtualPrinter,
    /** Identifies Mini mPOS to Adyen on every payment and refund. */
    private val application: PosApplication,
    /** Connects to a real terminal; tests replace it. */
    private val connect: (host: String, key: TerminalKey, tls: TerminalTls) -> TerminalTransport = ::AdyenLocalTransport,
) {
    @Volatile private var simulatorConfig = SimulatorConfig()
    private val simulator = TerminalSimulator(config = { simulatorConfig }, onPrint = virtualPrinter::print)
    private var cachedLocal: Pair<LocalKey, TerminalTransport>? = null

    private val _detectedEnvironment = MutableStateFlow<TerminalEnvironment?>(null)

    /** The environment of the terminal's certificate, known once a connection has been made. */
    val detectedEnvironment: StateFlow<TerminalEnvironment?> = _detectedEnvironment.asStateFlow()
    private val tls by lazy { TerminalTls(onEnvironment = ::environmentDetected) }

    private val _printers = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /**
     * Whether a terminal has a printer, by POIID, as far as it has told: a diagnosis reports a printer status only when
     * one is fitted, and a print request can be refused because there is none. Terminals not asked yet are absent.
     */
    val printers: StateFlow<Map<String, Boolean>> = _printers.asStateFlow()

    /** Where payments go in [TerminalMode.AUTO]: this terminal when running on one, the simulator elsewhere. */
    val automaticMode: TerminalMode get() = if (device.isAdyenTerminal) TerminalMode.TERMINAL else TerminalMode.SIMULATOR

    /** Where payments go with [terminal]: its mode, with [TerminalMode.AUTO] resolved to [automaticMode]. */
    fun effectiveMode(terminal: TerminalSettings): TerminalMode = if (terminal.mode == TerminalMode.AUTO) automaticMode else terminal.mode

    /** On a terminal, its own POIID; elsewhere the configured one (or the simulator's). */
    fun poiId(terminal: TerminalSettings): String? =
        device.detectedPoiId
            ?: terminal.poiIdOverride.trim().ifEmpty { null }
            ?: SIMULATOR_POI_ID.takeIf { effectiveMode(terminal) == TerminalMode.SIMULATOR }

    /**
     * What must still be entered before requests can be sent with [terminal], given whether a shared key passphrase
     * is saved ([passphraseSaved]); null when nothing is missing. Every operation applies the same rule, and also fails
     * when a saved passphrase can no longer be decrypted. The simulator needs nothing.
     */
    fun setupProblem(
        terminal: TerminalSettings,
        passphraseSaved: Boolean,
    ): String? =
        when {
            poiId(terminal) == null -> "Enter the terminal ID (POIID) in Terminal settings"
            effectiveMode(terminal) == TerminalMode.SIMULATOR -> null
            !device.isAdyenTerminal && terminal.host.isBlank() -> "Enter the terminal's IP address in Terminal settings"
            terminal.keyIdentifier.isBlank() -> "Enter the shared key identifier in Terminal settings"
            !passphraseSaved -> "Enter the shared key passphrase in Terminal settings"
            terminal.keyVersion < 1 -> "Enter the shared key version in Terminal settings"
            else -> null
        }

    /**
     * Takes a card payment, see [TerminalClient.pay]. [onSending] is called with the terminal's POIID right before the
     * request is sent; without complete setup nothing is sent and the outcome is [TransactionOutcome.NotProcessed].
     */
    suspend fun pay(
        params: PaymentParams,
        serviceId: String,
        onSending: suspend (poiId: String) -> Unit = {},
    ): TransactionOutcome =
        when (val target = target()) {
            is Target.NotSetUp -> {
                TransactionOutcome.NotProcessed(serviceId, target.message)
            }

            is Target.Ready -> {
                onSending(target.client.identity.poiId)
                target.client.pay(params, serviceId)
            }
        }

    /**
     * Refunds an earlier payment, see [TerminalClient.refund]; [onSending] and missing setup are handled as for [pay].
     *
     * @throws IllegalArgumentException If [RefundParams.originalTimestamp] is not an XML date-time.
     */
    suspend fun refund(
        params: RefundParams,
        serviceId: String,
        onSending: suspend (poiId: String) -> Unit = {},
    ): TransactionOutcome =
        when (val target = target()) {
            is Target.NotSetUp -> {
                TransactionOutcome.NotProcessed(serviceId, target.message)
            }

            is Target.Ready -> {
                onSending(target.client.identity.poiId)
                target.client.refund(params, serviceId)
            }
        }

    /**
     * Asks the terminal once for the result of the [kind] of transaction sent with [serviceId], see
     * [TerminalClient.status]. Without complete setup the terminal cannot be asked, so the outcome stays
     * [TransactionOutcome.Unknown].
     */
    suspend fun status(
        serviceId: String,
        kind: TransactionKind,
    ): TransactionOutcome =
        when (val target = target()) {
            is Target.NotSetUp -> TransactionOutcome.Unknown(serviceId, target.message)
            is Target.Ready -> target.client.status(serviceId, kind)
        }

    /**
     * Asks the terminal to stop the [kind] of transaction sent with [serviceId], see [TerminalClient.abort]. Returns
     * whether the request was sent, which it is not without complete setup.
     */
    suspend fun abort(
        serviceId: String,
        kind: TransactionKind = TransactionKind.PAYMENT,
    ): Boolean =
        when (val target = target()) {
            is Target.NotSetUp -> {
                false
            }

            is Target.Ready -> {
                target.client.abort(serviceId, kind)
                true
            }
        }

    /**
     * Prints [jobs], see [TerminalClient.print]. Missing setup is a [PrintOutcome.Failed] that says what to enter; a
     * refusal for want of a printer is remembered in [printers].
     */
    suspend fun print(jobs: List<PrintJob>): PrintOutcome =
        when (val target = target()) {
            is Target.NotSetUp -> {
                PrintOutcome.Failed(target.message, noPrinter = false)
            }

            is Target.Ready -> {
                target.client.print(jobs).also { outcome ->
                    if (outcome is PrintOutcome.Failed && outcome.noPrinter) learnPrinter(target.client, hasPrinter = false)
                }
            }
        }

    /**
     * Checks the connection with a diagnosis request, see [TerminalClient.diagnose], and remembers whether the terminal
     * has a printer. Never [TerminalConnection.Unknown] or [TerminalConnection.Checking].
     */
    suspend fun diagnose(): TerminalConnection =
        when (val target = target()) {
            is Target.NotSetUp -> {
                TerminalConnection.NotSetUp(target.message)
            }

            is Target.Ready -> {
                val diagnosis = target.client.diagnose()
                if (diagnosis.reachable) {
                    learnPrinter(target.client, diagnosis.hasPrinter)
                    TerminalConnection.Connected(diagnosis)
                } else {
                    TerminalConnection.Failed(diagnosis.message ?: "The terminal did not answer")
                }
            }
        }

    /** Called by [TerminalTls] with the environment of each verified terminal certificate. */
    internal fun environmentDetected(environment: TerminalEnvironment) {
        _detectedEnvironment.value = environment
    }

    private fun learnPrinter(
        client: TerminalClient,
        hasPrinter: Boolean,
    ) = _printers.update { it + (client.identity.poiId to hasPrinter) }

    /** A client for where payments go now with the stored settings, or what must be entered first. */
    private suspend fun target(): Target {
        val appSettings = settings.current()
        val terminal = appSettings.terminal
        val problem = setupProblem(terminal, passphraseSaved = Secret.TERMINAL_PASSPHRASE in secrets.configured.first())
        val transport =
            when {
                problem != null -> null
                effectiveMode(terminal) == TerminalMode.SIMULATOR -> simulator(appSettings.simulator)
                else -> localTransport(terminal)
            }
        return when {
            problem != null -> {
                Target.NotSetUp(problem)
            }

            transport == null -> {
                Target.NotSetUp("The saved shared key passphrase could not be read on this device; enter it again in Terminal settings")
            }

            else -> {
                Target.Ready(
                    TerminalClient(
                        transport = transport,
                        identity = TerminalIdentity(terminal.saleId.trim().ifEmpty { DEFAULT_SALE_ID }, checkNotNull(poiId(terminal))),
                        application = application,
                        transactionTimeout =
                            terminal.timeoutSeconds
                                .coerceIn(TerminalSettings.MIN_TIMEOUT_SECONDS, TerminalSettings.MAX_TIMEOUT_SECONDS)
                                .seconds,
                    ),
                )
            }
        }
    }

    /** The simulator, set up to behave as [sim] says for the next request. */
    private fun simulator(sim: SimulatorSettings): TerminalTransport {
        simulatorConfig = SimulatorConfig(sim.outcome, sim.delayMillis, sim.hasPrinter, sim.signatureRequired)
        return simulator
    }

    /** The transport to the configured terminal; null when the saved passphrase cannot be decrypted. */
    private suspend fun localTransport(terminal: TerminalSettings): TerminalTransport? {
        val passphrase = secrets.get(Secret.TERMINAL_PASSPHRASE) ?: return null
        val host = if (device.isAdyenTerminal) LOCALHOST else terminal.host.trim()
        val key = LocalKey(host, TerminalKey(terminal.keyIdentifier.trim(), passphrase, terminal.keyVersion))
        return cachedLocal?.takeIf { it.first == key }?.second ?: connect(key.host, key.key, tls).also { cachedLocal = key to it }
    }

    private sealed interface Target {
        data class Ready(
            val client: TerminalClient,
        ) : Target

        data class NotSetUp(
            val message: String,
        ) : Target
    }

    private data class LocalKey(
        val host: String,
        val key: TerminalKey,
    )

    /** Fixed identities and addresses. */
    companion object {
        /** The POIID the simulator reports, in the same `<model>-<serial>` form as a real one. */
        const val SIMULATOR_POI_ID = "SIMULATOR-000000001"

        /** The nexo SaleID used when none is configured. */
        const val DEFAULT_SALE_ID = "MiniMPOS"

        /** Where the Terminal API listens when the app runs on the terminal itself. */
        const val LOCALHOST = "localhost"
    }
}
