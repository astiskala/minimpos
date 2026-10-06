package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.data.settings.TerminalSettings
import io.github.astiskala.minimpos.terminal.client.RecoveryPolicy
import io.github.astiskala.minimpos.terminal.client.TerminalClient
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import kotlin.time.Duration

/**
 * What one destination needs before requests can be sent, and what it can do, read without reaching it: the pure half
 * of a [Destination], on its adapter's companion ([SimulatedTerminal], [LocalTerminal], [CloudTerminal],
 * [PaymentsAppDestination]), so all that is known about a destination is in one place. [TerminalSetup.resolve] asks
 * the one the settings choose ([of]); the adapter itself only opens its transport. Plain JUnit tests cover them through
 * [TerminalSetup.resolve].
 */
sealed interface DestinationRules {
    /** The "Payments go to" setting it is chosen with; never [TerminalMode.AUTO]. */
    val mode: TerminalMode

    /** The secrets it is reached with, which [TerminalSetupSource.unlocked] decrypts for it. */
    val secrets: Set<Secret>

    /** Whether the connection is checked in the background (at startup and when the setup changes). */
    val checksConnection: Boolean get() = true

    /** Whether the Checkout API is simulated along with it ([ApiSetup.Simulated]). */
    val simulatesApi: Boolean get() = false

    /** Whether Management terminal discovery can propose connection fields for this destination. */
    val discoversTerminals: Boolean get() = false

    /** Whether this destination needs explicit phone registration before connection verification. */
    val boardsPhone: Boolean get() = false

    /** Whether the merchant must choose TEST or LIVE instead of reading it from this device or the Payments app. */
    fun selectsEnvironment(onTerminal: Boolean): Boolean = false

    /** Whether it takes an AbortRequest. */
    val aborts: Boolean get() = true

    /** Whether it takes a DiagnosisRequest; without one, a connection check only checks the setup. */
    val diagnoses: Boolean get() = true

    /** How a transaction whose answer is missing is recovered with status checks. */
    val recovery: RecoveryPolicy get() = RecoveryPolicy()

    /** How long to wait for a payment or refund response before checking its status, as Adyen advises. */
    val transactionTimeout: Duration get() = TerminalClient.DEFAULT_TRANSACTION_TIMEOUT

    /** The POIID requests go to with [terminal] on [device], as [TerminalSetup.poiId] describes it. */
    fun poiId(
        terminal: TerminalSettings,
        device: DeviceInfo,
    ): String?

    /** Where the Terminal API listens, as [TerminalSetup.host] describes it; null when it is not reached by address. */
    fun host(
        terminal: TerminalSettings,
        device: DeviceInfo,
    ): String? = null

    /** Whether payments go to TEST or LIVE, as [TerminalSetup.environment] describes it; by default the stored one. */
    fun environment(
        terminal: TerminalSettings,
        device: DeviceInfo,
    ): TerminalEnvironment? = terminal.environment

    /** The terminal settings after accepting [detected] for [setup]; by default this destination learns nothing. */
    fun learnedEnvironment(
        setup: TerminalSetup,
        detected: DetectedEnvironment,
    ): TerminalSettings = setup.settings.terminal

    /**
     * What must still be entered (or installed or fixed) with [terminal] and the [saved] secrets on [device], given the
     * [poiId] and [host] it resolved to; null when nothing is missing.
     */
    fun problem(
        terminal: TerminalSettings,
        saved: Set<Secret>,
        device: DeviceInfo,
        poiId: String?,
        host: String?,
    ): SetupProblem?

    /**
     * Whether printing is offered when Settings › Receipts › Printer is set to detect it, with [settings], the [poiId]
     * and what terminals reported about their printers ([printers], by POIID). By default a terminal reported as having
     * one, or not asked yet and an S1F2 or S1F4 (models such as the S1E4Pro and S1F4Pro share the same `Build.MODEL`).
     */
    fun printer(
        settings: AppSettings,
        poiId: String?,
        printers: Map<String, Boolean>,
    ): Boolean = poiId != null && (printers[poiId] ?: PRINTER_MODEL_PREFIXES.any { poiId.startsWith(it, ignoreCase = true) })

    /** Choosing the destination. */
    companion object {
        private val PRINTER_MODEL_PREFIXES = listOf("S1F2", "S1F4")

        /** The destination [mode] chooses on [device]: [TerminalMode.AUTO] is this terminal on one, else the simulator. */
        fun of(
            mode: TerminalMode,
            device: DeviceInfo,
        ): DestinationRules =
            when (mode) {
                TerminalMode.AUTO -> if (device.isAdyenTerminal) LocalTerminal else SimulatedTerminal
                TerminalMode.TERMINAL -> LocalTerminal
                TerminalMode.CLOUD -> CloudTerminal
                TerminalMode.PAYMENTS_APP -> PaymentsAppDestination
                TerminalMode.SIMULATOR -> SimulatedTerminal
            }
    }
}

/** The POIID entered in [terminal]; null while none is. */
internal fun configuredPoiId(terminal: TerminalSettings): String? = terminal.poiIdOverride.trim().ifEmpty { null }

/** What is missing of [terminal]'s shared key, given which secrets are [saved]; null when nothing is. */
internal fun sharedKeyProblem(
    terminal: TerminalSettings,
    saved: Set<Secret>,
): SetupProblem? =
    when {
        terminal.keyIdentifier.isBlank() -> SetupProblem.KEY_IDENTIFIER
        Secret.TERMINAL_PASSPHRASE !in saved -> SetupProblem.PASSPHRASE
        terminal.keyVersion !in TerminalSettings.KEY_VERSIONS -> SetupProblem.KEY_VERSION
        else -> null
    }

/** What is wrong with the device for the Payments app: it must be a phone with exactly one Payments app installed. */
internal fun paymentsAppProblem(
    onTerminal: Boolean,
    paymentsApps: Set<TerminalEnvironment>,
): SetupProblem? =
    when {
        onTerminal -> SetupProblem.PAYMENTS_APP_ON_TERMINAL
        paymentsApps.isEmpty() -> SetupProblem.PAYMENTS_APP_MISSING
        paymentsApps.size > 1 -> SetupProblem.PAYMENTS_APP_AMBIGUOUS
        else -> null
    }
