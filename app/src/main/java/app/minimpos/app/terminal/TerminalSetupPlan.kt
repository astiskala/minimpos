package app.minimpos.app.terminal

import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.terminal.transport.TerminalEnvironment

/** One step of Settings › Terminal, see [TerminalSetupPlan]. */
enum class SetupStepKind {
    /** TEST or LIVE, for network/cloud terminals and Tap to Pay. */
    ENVIRONMENT,

    /** The Adyen Payments app installed on this phone, for Tap to Pay. */
    PAYMENTS_APP,

    /** The Checkout API: merchant account, API key and live URL prefix. */
    ADYEN_API,

    /** The address and POIID of a terminal on the network. */
    NETWORK_TERMINAL,

    /** The POIID of a terminal in the cloud, tested with the API key. */
    CLOUD_TERMINAL,

    /** Registering this phone's Payments app (boarding). */
    TAP_TO_PAY,

    /** The shared key a local terminal or the Payments app encrypts messages with. */
    SHARED_KEY,

    /** Final verification of API access and the supported connection checks. */
    CONNECTION,
}

/**
 * A step as Settings › Terminal shows it.
 *
 * @property kind Which step.
 * @property number Its number, counted from 1 in the order the steps are done.
 * @property done Whether its number turns into a tick.
 */
data class SetupStep(
    val kind: SetupStepKind,
    val number: Int,
    val done: Boolean,
)

/**
 * What this visit to Settings › Terminal has unlocked, which reveals later steps until the visit ends.
 *
 * @property apiTested Whether the Checkout API test passed for the account fields shown.
 * @property terminalEntered Whether a network terminal's address and POIID were both entered.
 * @property boarded Whether this phone's Payments app was registered.
 */
data class SetupVisit(
    val apiTested: Boolean = false,
    val terminalEntered: Boolean = false,
    val boarded: Boolean = false,
)

/**
 * What Settings › Terminal knows when it lays out its steps.
 *
 * @property onTerminal Whether the app runs on the payment terminal itself.
 * @property environmentChosen Whether TEST or LIVE is chosen.
 * @property paymentsApps Which environments' Adyen Payments apps are installed on this phone.
 * @property terminal The saved terminal settings.
 * @property secrets The secrets saved.
 * @property verified Whether a connection check verified the setup.
 * @property visit What this visit unlocked.
 */
data class SetupProgress(
    val onTerminal: Boolean,
    val environmentChosen: Boolean,
    val paymentsApps: Set<TerminalEnvironment>,
    val terminal: TerminalSettings,
    val secrets: Set<Secret>,
    val verified: Boolean,
    val visit: SetupVisit = SetupVisit(),
)

/**
 * The one reading of Settings › Terminal's steps: in what order and numbering the destination's steps
 * ([DestinationRules.setupSteps]) are shown, which are revealed and which are done. Pure; the Settings steps only
 * render it, and it never branches on the destination itself. A later step stays hidden until the earlier one is done
 * in this visit or its details are already saved; every step is done once the setup is verified.
 */
object TerminalSetupPlan {
    /** The steps [rules] ask for, as far as [progress] reveals them. */
    fun steps(
        rules: DestinationRules,
        progress: SetupProgress,
    ): List<SetupStep> {
        val supplied = rules.suppliedDetails(progress.terminal, progress.secrets)
        val shown = mutableListOf<SetupStep>()
        for (kind in rules.setupSteps(progress.onTerminal)) {
            shown += SetupStep(kind, shown.size + 1, progress.verified || progress.done(kind))
            if (!progress.revealsAfter(kind, supplied)) break
        }
        return shown
    }

    /** Whether step [kind] is done in this visit or with what is saved, before the setup is verified. */
    private fun SetupProgress.done(kind: SetupStepKind): Boolean =
        when (kind) {
            SetupStepKind.ENVIRONMENT -> environmentChosen
            SetupStepKind.PAYMENTS_APP -> terminal.environment in paymentsApps
            SetupStepKind.ADYEN_API -> visit.apiTested
            SetupStepKind.NETWORK_TERMINAL -> terminal.host.isNotBlank() && terminal.poiIdOverride.isNotBlank()
            SetupStepKind.TAP_TO_PAY -> terminal.paymentsAppInstallationId.isNotBlank()
            SetupStepKind.CLOUD_TERMINAL, SetupStepKind.SHARED_KEY, SetupStepKind.CONNECTION -> false
        }

    /** Whether the steps after [kind] are revealed: it is done, or the destination's details are [supplied] already. */
    private fun SetupProgress.revealsAfter(
        kind: SetupStepKind,
        supplied: Boolean,
    ): Boolean {
        val keyStarted = terminal.keyIdentifier.isNotBlank() || Secret.TERMINAL_PASSPHRASE in secrets
        return when (kind) {
            SetupStepKind.ENVIRONMENT -> environmentChosen || savedConnectionDetails(terminal, secrets)
            SetupStepKind.PAYMENTS_APP -> terminal.environment in paymentsApps || supplied || Secret.ADYEN_API_KEY in secrets
            SetupStepKind.ADYEN_API -> visit.apiTested || supplied
            SetupStepKind.NETWORK_TERMINAL -> visit.terminalEntered || keyStarted
            SetupStepKind.TAP_TO_PAY -> visit.boarded || keyStarted
            SetupStepKind.CLOUD_TERMINAL, SetupStepKind.SHARED_KEY, SetupStepKind.CONNECTION -> true
        }
    }

    /**
     * Whether any connection detail of any destination is saved: a field of [terminal] or one of the [secrets] they are
     * reached with. Without one, Settings › Terminal offers quick setup and waits for the environment first.
     */
    fun savedConnectionDetails(
        terminal: TerminalSettings,
        secrets: Set<Secret>,
    ): Boolean =
        listOf(
            terminal.host,
            terminal.poiIdOverride,
            terminal.merchantAccount,
            terminal.keyIdentifier,
            terminal.paymentsAppInstallationId,
            terminal.storeId,
            terminal.liveUrlPrefix,
        ).any(String::isNotBlank) ||
            secrets.any { it in setOf(Secret.ADYEN_API_KEY, Secret.TERMINAL_PASSPHRASE) }
}
