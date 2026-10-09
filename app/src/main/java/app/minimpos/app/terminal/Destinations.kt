package app.minimpos.app.terminal

import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.core.money.PaymentContext
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.paymentsapp.PaymentsAppLinks
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.AdyenCloudDevices
import app.minimpos.terminal.transport.CloudCredentials
import app.minimpos.terminal.transport.CloudDetection
import app.minimpos.terminal.transport.CloudDevices
import app.minimpos.terminal.transport.CloudEndpoint
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import app.minimpos.terminal.transport.TerminalTls
import app.minimpos.terminal.transport.TerminalTransport
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/** Where payments go now, ready to send ([Open]) or why nothing can be sent ([Blocked]), see [Destination.connect]. */
sealed interface Connection {
    /**
     * Requests can be sent.
     *
     * @property client Sends them, with the app's SaleID and POIID and the destination's timeout and recovery.
     * @property destination What it can do besides payments and refunds ([DestinationRules.aborts],
     *   [DestinationRules.diagnoses]).
     * @property context Reads only the setup and certificate belonging to this connection.
     */
    class Open(
        val client: TerminalClient,
        val destination: DestinationRules,
        /** Reads the identity and certificate environment of this connection, never a later setup. */
        val context: () -> PaymentContext,
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
     * @property fault Why.
     */
    data class Unreachable(
        val fault: Fault,
    ) : Blocked
}

/** What [Destination.open] found: its transport, or why there is none ([Connection.Blocked]). */
sealed interface Opening {
    /**
     * The destination can be reached.
     *
     * @property transport Sends the requests.
     * @property environment Reads this transport's verified certificate or endpoint environment.
     */
    class Transport(
        val transport: TerminalTransport,
        /** Certificate or endpoint environment learned by this transport; null until known. */
        val environment: () -> TerminalEnvironment? = { null },
    ) : Opening
}

/**
 * One place payments can go ("Payments go to", [TerminalSetup.mode]) as the [TerminalGateway] reaches it: how to open
 * its transport with a complete, unlocked setup. What it needs and can do are its [rules], on the adapter's companion,
 * and [connect] makes the client every request is sent with. The adapters are [SimulatedTerminal], [LocalTerminal]
 * (this terminal or one on the network), [CloudTerminal] and [PaymentsAppDestination]; each reuses its transport while
 * what it was opened from stays the same ([Reused]). None reads a secret itself: [TerminalSetupSource.unlocked] hands
 * them over.
 */
sealed interface Destination {
    /** What it needs and can do, the same object [TerminalSetup.destination] holds when payments go here. */
    val rules: DestinationRules

    /** The transport for [unlocked], which has no [TerminalSetup.problem] and goes here, or why there is none. */
    suspend fun open(unlocked: UnlockedSetup): Opening

    /**
     * The client for [unlocked], which has no [TerminalSetup.problem] and goes here, identifying the POS as
     * [application]: the transport ([open]) with the app's SaleID ([TerminalGateway.DEFAULT_SALE_ID]) and POIID, and
     * the [rules]' transaction timeout and recovery. Or why there is none.
     */
    suspend fun connect(
        unlocked: UnlockedSetup,
        application: PosApplication,
    ): Connection =
        when (val opening = open(unlocked)) {
            is Connection.Blocked -> {
                opening
            }

            is Opening.Transport -> {
                val setup = unlocked.setup
                val client =
                    TerminalClient(
                        transport = opening.transport,
                        identity = TerminalIdentity(TerminalGateway.DEFAULT_SALE_ID, checkNotNull(setup.poiId)),
                        application = application,
                        transactionTimeout = rules.transactionTimeout,
                        recovery = rules.recovery,
                    )
                Connection.Open(client, rules) { setup.paymentContext(opening.environment() ?: setup.environment) }
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
 * The built-in simulator ([TerminalMode.SIMULATOR]): it behaves as the Simulator settings say at each request, prints to
 * [virtualPrinter] and simulates the Checkout API for its own payments ([modifications]), from one shared ledger.
 *
 * @param virtualPrinter Receives what it prints.
 */
class SimulatedTerminal(
    virtualPrinter: VirtualPrinter,
) : Destination {
    @Volatile private var config = SimulatorConfig()
    private val simulator = TerminalSimulator(config = { config }, onPrint = virtualPrinter::print)

    override val rules: DestinationRules get() = Companion

    /** Adyen's Checkout API for the simulator's payments, answering from what happened to them; [AdyenApi] uses it. */
    val modifications: PaymentModifications get() = simulator.modifications

    override suspend fun open(unlocked: UnlockedSetup): Opening {
        val sim = unlocked.setup.settings.simulator
        config = SimulatorConfig(sim.outcome, sim.delayMillis, sim.hasPrinter, sim.signatureRequired)
        return Opening.Transport(simulator)
    }

    /**
     * The simulator's rules: nothing to enter, the POIID of the terminal it runs on (or the configured one, or
     * [TerminalSetup.SIMULATOR_POI_ID]), no environment, a printer as the Simulator settings say, the Checkout API
     * simulated too, and no background connection checks.
     */
    companion object : DestinationRules {
        override val mode: TerminalMode get() = TerminalMode.SIMULATOR
        override val scannedWallets: Boolean get() = true
        override val secrets: Set<Secret> get() = emptySet()
        override val checksConnection: Boolean get() = false
        override val simulatesApi: Boolean get() = true

        override fun poiId(
            terminal: TerminalSettings,
            device: DeviceInfo,
        ): String = device.detectedPoiId ?: configuredPoiId(terminal) ?: TerminalSetup.SIMULATOR_POI_ID

        override fun environment(
            terminal: TerminalSettings,
            device: DeviceInfo,
        ): TerminalEnvironment? = null

        override fun problem(
            terminal: TerminalSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
            poiId: String?,
            host: String?,
        ): SetupProblem? = null

        override fun printer(
            settings: AppSettings,
            poiId: String?,
            printers: Map<String, Boolean>,
        ): Boolean = settings.simulator.hasPrinter
    }
}

/**
 * This terminal, or one on the network: the local Terminal API at [TerminalSetup.host] with the shared key, trusted by
 * its Adyen certificate, which also tells the environment.
 *
 * @param dial Opens the transport; tests replace it.
 * @param onEnvironment Told the originating setup and environment of each verified terminal certificate.
 */
internal class LocalTerminal(
    private val dial: (host: String, key: TerminalKey, tls: TerminalTls) -> TerminalTransport,
    private val onEnvironment: (TerminalSetup, TerminalEnvironment) -> Unit,
) : Destination {
    private val transports = Reused<Triple<String, TerminalKey, TerminalEnvironment?>, Opening.Transport>()

    override val rules: DestinationRules get() = Companion

    override suspend fun open(unlocked: UnlockedSetup): Opening {
        val key = checkNotNull(unlocked.terminalKey)
        val expected = unlocked.setup.environment.takeUnless { unlocked.setup.onTerminal }
        return transports.get(Triple(checkNotNull(unlocked.setup.host), key, expected)) {
            val detected = AtomicReference<TerminalEnvironment?>()
            val tls =
                TerminalTls(expectedEnvironment = expected, onEnvironment = { environment ->
                    detected.set(environment)
                    onEnvironment(unlocked.setup, environment)
                })
            Opening.Transport(dial(it.first, key, tls), detected::get)
        }
    }

    /**
     * The rules of a terminal reached by the local Terminal API ([TerminalMode.TERMINAL]): on a terminal its own POIID
     * at [TerminalSetup.LOCALHOST], else the configured POIID and address; the shared key in either case.
     */
    companion object : DestinationRules {
        override val mode: TerminalMode get() = TerminalMode.TERMINAL
        override val scannedWallets: Boolean get() = true
        override val secrets: Set<Secret> get() = setOf(Secret.TERMINAL_PASSPHRASE)
        override val discoversTerminals: Boolean get() = true

        override fun selectsEnvironment(onTerminal: Boolean): Boolean = !onTerminal

        override fun learnedEnvironment(
            setup: TerminalSetup,
            detected: DetectedEnvironment,
        ): TerminalSettings =
            if (setup.onTerminal) setup.settings.terminal.copy(environment = detected.environment) else setup.settings.terminal

        override fun poiId(
            terminal: TerminalSettings,
            device: DeviceInfo,
        ): String? = device.detectedPoiId ?: configuredPoiId(terminal)

        override fun host(
            terminal: TerminalSettings,
            device: DeviceInfo,
        ): String? = if (device.isAdyenTerminal) TerminalSetup.LOCALHOST else terminal.host.trim().ifEmpty { null }

        override fun problem(
            terminal: TerminalSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
            poiId: String?,
            host: String?,
        ): SetupProblem? =
            when {
                selectsEnvironment(device.isAdyenTerminal) && terminal.environment == null -> SetupProblem.ENVIRONMENT
                poiId == null -> SetupProblem.POI_ID
                host == null -> SetupProblem.HOST
                else -> sharedKeyProblem(terminal, saved)
            }
    }
}

/**
 * A terminal in the cloud, reached through the Cloud device API with the API key: its endpoint is found once per API key
 * and terminal ([CloudDevices.detect]), within the selected environment.
 *
 * @param cloud Reaches terminals in the cloud with an API key; tests replace it.
 * @param device Its country picks the first live data centre to try.
 * @param onDetected Told the originating setup and endpoint the API key was found at.
 */
internal class CloudTerminal(
    private val cloud: (CloudCredentials) -> CloudDevices,
    private val device: DeviceInfo,
    private val onDetected: (TerminalSetup, CloudEndpoint) -> Unit,
) : Destination {
    private val transports = Reused<Triple<CloudCredentials, String, TerminalEnvironment>, Pair<CloudEndpoint, Opening.Transport>>()

    override val rules: DestinationRules get() = Companion

    override suspend fun open(unlocked: UnlockedSetup): Opening {
        val poiId = checkNotNull(unlocked.setup.poiId)
        val id =
            Triple(
                CloudCredentials(
                    checkNotNull(unlocked.apiKey),
                    unlocked.setup.settings.terminal.merchantAccount
                        .trim(),
                ),
                poiId,
                checkNotNull(unlocked.setup.environment),
            )
        transports.of(id)?.let { (endpoint, transport) ->
            onDetected(unlocked.setup, endpoint)
            return transport
        }
        val devices = cloud(id.first)
        return when (val detection = devices.detect(id.third, poiId, device.country)) {
            is CloudDetection.Found -> {
                onDetected(unlocked.setup, detection.endpoint)
                val transport = Opening.Transport(devices.transport(detection.endpoint)) { detection.endpoint.environment }
                transports.keep(id, detection.endpoint to transport).second
            }

            is CloudDetection.Failed -> {
                Connection.Unreachable(detection.fault)
            }
        }
    }

    /**
     * The terminals connected to the merchant account of [unlocked], for choosing one: it needs only the merchant account
     * and API key (no POIID yet), in the selected environment.
     */
    suspend fun connectedTerminals(unlocked: UnlockedSetup): ConnectedTerminals {
        val merchantAccount = unlocked.setup.settings.terminal.merchantAccount
        val apiKey = unlocked.apiKey
        val environment = unlocked.setup.environment ?: return ConnectedTerminals.NotSetUp(SetupProblem.ENVIRONMENT)
        if (merchantAccount.isBlank() || apiKey == null) return ConnectedTerminals.NotSetUp(SetupProblem.API_REQUIRED)
        return when (val detection = cloud(CloudCredentials(apiKey, merchantAccount.trim())).detect(environment, null, device.country)) {
            is CloudDetection.Found -> {
                onDetected(unlocked.setup, detection.endpoint)
                ConnectedTerminals.Listed(detection.devices.sorted())
            }

            is CloudDetection.Failed -> {
                ConnectedTerminals.Failed(detection.fault)
            }
        }
    }

    /**
     * The rules of a terminal in the cloud ([TerminalMode.CLOUD]): the merchant account, the API key and its configured
     * POIID, no shared key or address; payments wait [AdyenCloudDevices.MIN_TRANSACTION_TIMEOUT], as Adyen requires.
     */
    companion object : DestinationRules {
        override val mode: TerminalMode get() = TerminalMode.CLOUD
        override val scannedWallets: Boolean get() = true
        override val secrets: Set<Secret> get() = setOf(Secret.ADYEN_API_KEY)
        override val discoversTerminals: Boolean get() = true

        override fun selectsEnvironment(onTerminal: Boolean): Boolean = true

        override fun learnedEnvironment(
            setup: TerminalSetup,
            detected: DetectedEnvironment,
        ): TerminalSettings =
            if (setup.environment == detected.environment) {
                setup.settings.terminal.copy(cloudRegion = detected.cloudRegion)
            } else {
                setup.settings.terminal
            }

        override val transactionTimeout: Duration get() = AdyenCloudDevices.MIN_TRANSACTION_TIMEOUT

        override fun poiId(
            terminal: TerminalSettings,
            device: DeviceInfo,
        ): String? = configuredPoiId(terminal)

        override fun problem(
            terminal: TerminalSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
            poiId: String?,
            host: String?,
        ): SetupProblem? =
            when {
                terminal.environment == null -> SetupProblem.ENVIRONMENT
                terminal.merchantAccount.isBlank() -> SetupProblem.MERCHANT_ACCOUNT
                Secret.ADYEN_API_KEY !in saved -> SetupProblem.CLOUD_API_KEY
                poiId == null -> SetupProblem.POI_ID
                else -> null
            }
    }
}

/**
 * The Adyen Payments app on this phone (Tap to Pay), with the shared key, in the environment of the installed app.
 *
 * @param reach Reaches the Payments app of an environment with a shared key; the container plugs in its App Links.
 */
internal class PaymentsAppDestination(
    private val reach: (key: TerminalKey, environment: TerminalEnvironment) -> TerminalTransport,
) : Destination {
    private val transports = Reused<Pair<TerminalKey, TerminalEnvironment>, TerminalTransport>()

    override val rules: DestinationRules get() = Companion

    override suspend fun open(unlocked: UnlockedSetup): Opening {
        val key = checkNotNull(unlocked.terminalKey)
        return Opening.Transport(transports.get(key to checkNotNull(unlocked.setup.environment)) { reach(key, it.second) })
    }

    /**
     * The rules of the Payments app ([TerminalMode.PAYMENTS_APP]): a phone with exactly one Payments app installed, whose
     * environment it is, boarding (for the merchant account), whose installation ID is the POIID, then the shared key, in
     * the order Settings › Terminal asks for them. It takes only payments and refunds: no abort (it has its own Cancel),
     * no diagnosis and no printer, and it cannot be asked for a transaction's status, only answer from a late reply, so a
     * payment whose answer is missing is checked once, shortly after, before it is reported unknown.
     */
    companion object : DestinationRules {
        private val RECOVERY = RecoveryPolicy(attempts = 1, intervalMillis = 2_000, maxInProgressChecks = 1)

        override val mode: TerminalMode get() = TerminalMode.PAYMENTS_APP
        override val secrets: Set<Secret> get() = setOf(Secret.TERMINAL_PASSPHRASE)
        override val boardsPhone: Boolean get() = true
        override val aborts: Boolean get() = false
        override val diagnoses: Boolean get() = false
        override val recovery: RecoveryPolicy get() = RECOVERY

        override fun poiId(
            terminal: TerminalSettings,
            device: DeviceInfo,
        ): String? = terminal.paymentsAppInstallationId.trim().ifEmpty { null }

        override fun environment(
            terminal: TerminalSettings,
            device: DeviceInfo,
        ): TerminalEnvironment? = device.paymentsApps.singleOrNull()

        override fun problem(
            terminal: TerminalSettings,
            saved: Set<Secret>,
            device: DeviceInfo,
            poiId: String?,
            host: String?,
        ): SetupProblem? =
            paymentsAppProblem(device.isAdyenTerminal, device.paymentsApps)
                ?: boardingProblem(terminal, poiId)
                ?: sharedKeyProblem(terminal, saved)

        /** Until boarded ([poiId] is null): the merchant account boarding needs, else boarding itself. */
        private fun boardingProblem(
            terminal: TerminalSettings,
            poiId: String?,
        ): SetupProblem? =
            when {
                poiId != null -> null
                terminal.merchantAccount.isBlank() -> SetupProblem.MERCHANT_ACCOUNT
                else -> SetupProblem.PAYMENTS_APP_NOT_BOARDED
            }

        private const val PLAY_STORE = "https://play.google.com/store/apps/details?id="

        /** The Google Play page of the Adyen Payments app for [environment], where Settings offers to install it. */
        fun storeUrl(environment: TerminalEnvironment): String = PLAY_STORE + PaymentsAppLinks.packageName(environment)

        override fun printer(
            settings: AppSettings,
            poiId: String?,
            printers: Map<String, Boolean>,
        ): Boolean = false
    }
}
