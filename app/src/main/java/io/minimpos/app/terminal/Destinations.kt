package io.minimpos.app.terminal

import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.security.SecretStore
import io.minimpos.app.data.settings.TerminalSettings
import io.minimpos.terminal.checkout.PaymentModifications
import io.minimpos.terminal.client.PosApplication
import io.minimpos.terminal.client.RecoveryPolicy
import io.minimpos.terminal.client.TerminalClient
import io.minimpos.terminal.client.TerminalIdentity
import io.minimpos.terminal.simulator.SimulatorConfig
import io.minimpos.terminal.simulator.TerminalSimulator
import io.minimpos.terminal.transport.AdyenCloudDevices
import io.minimpos.terminal.transport.CloudCredentials
import io.minimpos.terminal.transport.CloudDetection
import io.minimpos.terminal.transport.CloudDevices
import io.minimpos.terminal.transport.CloudEndpoint
import io.minimpos.terminal.transport.TerminalEnvironment
import io.minimpos.terminal.transport.TerminalKey
import io.minimpos.terminal.transport.TerminalTls
import io.minimpos.terminal.transport.TerminalTransport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where payments go now, ready to send ([Open]) or why nothing can be sent ([Blocked]), see [Destination.connect]. */
sealed interface Connection {
    /**
     * Requests can be sent.
     *
     * @property client Sends them, with the setup's SaleID and POIID and the destination's timeout and recovery.
     * @property destination What it can do besides payments and refunds ([Destination.aborts], [Destination.diagnoses]).
     */
    class Open(
        val client: TerminalClient,
        val destination: Destination,
    ) : Connection

    /** Nothing can be sent; also what a [Destination.open] that found no transport returns. */
    sealed interface Blocked :
        Connection,
        Opening

    /**
     * Something must be entered first, or something saved cannot be used.
     *
     * @property problem What to enter (again).
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : Blocked

    /**
     * The destination could not be found, such as a cloud API key no endpoint accepts.
     *
     * @property message Why, as Adyen or the client worded it.
     */
    data class Unreachable(
        val message: String,
    ) : Blocked
}

/** What [Destination.open] found: its transport, or why there is none ([Connection.Blocked]). */
sealed interface Opening {
    /**
     * The destination can be reached.
     *
     * @property transport Sends the requests.
     */
    class Transport(
        val transport: TerminalTransport,
    ) : Opening
}

/**
 * One place payments can go ("Payments go to", [TerminalSetup.mode]) as the [TerminalGateway] reaches it: how to open
 * its transport with a complete setup, and what it takes. It is the one place that knows what a destination can and
 * cannot do, and [connect] makes the client every request is sent with. The adapters are [SimulatedTerminal],
 * [LocalTerminal] (this terminal or one on the network), [CloudTerminal] and [PaymentsAppDestination]; each reuses its
 * transport while what it was opened from stays the same ([Reused]).
 */
sealed interface Destination {
    /** Whether it takes an AbortRequest; the Payments app does not (it has its own Cancel). */
    val aborts: Boolean get() = true

    /** Whether it takes a DiagnosisRequest; without one, a connection check only checks the setup. */
    val diagnoses: Boolean get() = true

    /** How a transaction whose answer is missing is recovered with status checks. */
    val recovery: RecoveryPolicy get() = RecoveryPolicy()

    /** How long to wait for a payment or refund, with [configured] as Settings say. */
    fun transactionTimeout(configured: Duration): Duration = configured

    /** The transport for [setup], which has no [TerminalSetup.problem] and goes here, or why there is none. */
    suspend fun open(setup: TerminalSetup): Opening

    /**
     * The client for [setup], which has no [TerminalSetup.problem] and goes here, identifying the POS as
     * [application]: the transport ([open]) with the setup's SaleID ([TerminalGateway.DEFAULT_SALE_ID] when blank) and
     * POIID, and this destination's [transactionTimeout] and [recovery]. Or why there is none.
     */
    suspend fun connect(
        setup: TerminalSetup,
        application: PosApplication,
    ): Connection =
        when (val opening = open(setup)) {
            is Connection.Blocked -> {
                opening
            }

            is Opening.Transport -> {
                val terminal = setup.settings.terminal
                val client =
                    TerminalClient(
                        transport = opening.transport,
                        identity =
                            TerminalIdentity(
                                terminal.saleId.trim().ifEmpty { TerminalGateway.DEFAULT_SALE_ID },
                                checkNotNull(setup.poiId),
                            ),
                        application = application,
                        // Stored settings are normalized, so the timeout is within its range.
                        transactionTimeout = transactionTimeout(terminal.timeoutSeconds.seconds),
                        recovery = recovery,
                    )
                Connection.Open(client, this)
            }
        }
}

/**
 * What a destination (or the Checkout API) opened last, [V], kept with what it was opened from, [K], so it is reused
 * while that stays the same and opened again once it changes (another host, key or credentials). Thread-safe: callers
 * that race may both open, and the last one is kept.
 */
internal class Reused<K, V : Any> {
    @Volatile private var kept: Pair<K, V>? = null

    /** What was opened from [key], if it is the last thing opened; else null. */
    fun of(key: K): V? = kept?.takeIf { it.first == key }?.second

    /** Keeps [value], opened from [key], in place of what was kept, and returns it. */
    fun keep(
        key: K,
        value: V,
    ): V = value.also { kept = key to it }

    /** What was opened from [key], opening it with [open] unless it is the last thing opened. */
    inline fun get(
        key: K,
        open: (K) -> V,
    ): V = of(key) ?: keep(key, open(key))
}

/**
 * The built-in simulator ([TerminalMode][io.minimpos.app.data.settings.TerminalMode] `SIMULATOR`): it behaves as the
 * Simulator settings say at each request, prints to [virtualPrinter] and simulates the Checkout API for its own
 * payments ([modifications]), from one shared ledger.
 *
 * @param virtualPrinter Receives what it prints.
 */
class SimulatedTerminal(
    virtualPrinter: VirtualPrinter,
) : Destination {
    @Volatile private var config = SimulatorConfig()
    private val simulator = TerminalSimulator(config = { config }, onPrint = virtualPrinter::print)

    /** Adyen's Checkout API for the simulator's payments, answering from what happened to them; [AdyenApi] uses it. */
    val modifications: PaymentModifications get() = simulator.modifications

    override suspend fun open(setup: TerminalSetup): Opening {
        val sim = setup.settings.simulator
        config = SimulatorConfig(sim.outcome, sim.delayMillis, sim.hasPrinter, sim.signatureRequired)
        return Opening.Transport(simulator)
    }
}

/**
 * This terminal, or one on the network: the local Terminal API at [TerminalSetup.host] with the shared key, trusted by
 * its Adyen certificate, which also tells the environment.
 *
 * @param secrets Holds the passphrase.
 * @param dial Opens the transport; tests replace it.
 * @param onEnvironment Told the environment of each verified terminal certificate.
 */
internal class LocalTerminal(
    private val secrets: SecretStore,
    private val dial: (host: String, key: TerminalKey, tls: TerminalTls) -> TerminalTransport,
    onEnvironment: (TerminalEnvironment) -> Unit,
) : Destination {
    private val tls by lazy { TerminalTls(onEnvironment = onEnvironment) }

    private val transports = Reused<Pair<String, TerminalKey>, TerminalTransport>()

    override suspend fun open(setup: TerminalSetup): Opening {
        val key = sharedKey(secrets, setup.settings.terminal) ?: return Connection.NotSetUp(SetupProblem.UNREADABLE_PASSPHRASE)
        return Opening.Transport(transports.get(checkNotNull(setup.host) to key) { dial(it.first, key, tls) })
    }
}

/**
 * A terminal in the cloud, reached through the Cloud device API with the API key: its endpoint is found once per API key
 * and terminal ([CloudDevices.detect]), which also tells the environment, and payments wait at least
 * [AdyenCloudDevices.MIN_TRANSACTION_TIMEOUT], as Adyen requires.
 *
 * @param secrets Holds the API key.
 * @param cloud Reaches terminals in the cloud with an API key; tests replace it.
 * @param device Its country picks the first live data centre to try.
 * @param onDetected Told the endpoint the API key was found at.
 */
internal class CloudTerminal(
    private val secrets: SecretStore,
    private val cloud: (CloudCredentials) -> CloudDevices,
    private val device: DeviceInfo,
    private val onDetected: (CloudEndpoint) -> Unit,
) : Destination {
    private val transports = Reused<Pair<CloudCredentials, String>, TerminalTransport>()

    override fun transactionTimeout(configured: Duration): Duration = maxOf(configured, AdyenCloudDevices.MIN_TRANSACTION_TIMEOUT)

    override suspend fun open(setup: TerminalSetup): Opening {
        val poiId = checkNotNull(setup.poiId)
        val apiKey = secrets.get(Secret.CHECKOUT_API_KEY) ?: return Connection.NotSetUp(SetupProblem.UNREADABLE_API_KEY)
        val id =
            CloudCredentials(
                apiKey,
                setup.settings.terminal.merchantAccount
                    .trim(),
            ) to poiId
        transports.of(id)?.let { return Opening.Transport(it) }
        val devices = cloud(id.first)
        return when (val detection = devices.detect(poiId, device.country)) {
            is CloudDetection.Found -> {
                onDetected(detection.endpoint)
                Opening.Transport(transports.keep(id, devices.transport(detection.endpoint)))
            }

            is CloudDetection.Failed -> {
                Connection.Unreachable(detection.message)
            }
        }
    }

    /**
     * The terminals connected to [terminal]'s merchant account, for choosing one: it needs only the merchant account and
     * API key (no POIID yet), and also finds the API key's environment.
     */
    suspend fun connectedTerminals(terminal: TerminalSettings): ConnectedTerminals {
        val apiKey = secrets.get(Secret.CHECKOUT_API_KEY)
        if (terminal.merchantAccount.isBlank() || apiKey == null) return ConnectedTerminals.NotSetUp(SetupProblem.API_REQUIRED)
        return when (val detection = cloud(CloudCredentials(apiKey, terminal.merchantAccount.trim())).detect(null, device.country)) {
            is CloudDetection.Found -> {
                onDetected(detection.endpoint)
                ConnectedTerminals.Listed(detection.devices.sorted())
            }

            is CloudDetection.Failed -> {
                ConnectedTerminals.Failed(detection.message)
            }
        }
    }
}

/**
 * The Adyen Payments app on this phone (Tap to Pay), with the shared key, in the environment of the installed app. It
 * takes only payments and refunds: no abort (it has its own Cancel) and no diagnosis, and it cannot be asked for a
 * transaction's status, only answer from a late reply, so a payment whose answer is missing is checked once, shortly
 * after, before it is reported unknown.
 *
 * @param secrets Holds the passphrase.
 * @param reach Reaches the Payments app of an environment with a shared key; the container plugs in its App Links.
 */
internal class PaymentsAppDestination(
    private val secrets: SecretStore,
    private val reach: (key: TerminalKey, environment: TerminalEnvironment) -> TerminalTransport,
) : Destination {
    private val transports = Reused<Pair<TerminalKey, TerminalEnvironment>, TerminalTransport>()

    override val aborts: Boolean get() = false

    override val diagnoses: Boolean get() = false

    override val recovery: RecoveryPolicy get() = RECOVERY

    override suspend fun open(setup: TerminalSetup): Opening {
        val key = sharedKey(secrets, setup.settings.terminal) ?: return Connection.NotSetUp(SetupProblem.UNREADABLE_PASSPHRASE)
        return Opening.Transport(transports.get(key to checkNotNull(setup.environment)) { reach(key, it.second) })
    }

    private companion object {
        val RECOVERY = RecoveryPolicy(attempts = 1, intervalMillis = 2_000, maxInProgressChecks = 1)
    }
}

/** [terminal]'s shared key with the saved passphrase; null when that cannot be decrypted. */
internal suspend fun sharedKey(
    secrets: SecretStore,
    terminal: TerminalSettings,
): TerminalKey? = secrets.get(Secret.TERMINAL_PASSPHRASE)?.let { TerminalKey(terminal.keyIdentifier.trim(), it, terminal.keyVersion) }
