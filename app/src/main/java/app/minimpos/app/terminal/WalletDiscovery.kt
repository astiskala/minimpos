package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.payment.ScanWallet
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListingApi
import app.minimpos.terminal.transport.WalletMethodListing
import app.minimpos.terminal.transport.WalletMethodsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memory-only wallet discovery, with no credentials, codes or raw responses.
 * @property context Destination/account/environment that was checked; null before discovery.
 * @property identity Fingerprint of the checked connection and encrypted payment secrets; never a credential.
 * @param offers Eligible wallet configurations, filtered for the terminal's store and country.
 * @property nativeScanner Whether the checked terminal is a confirmed scanner model.
 * @property mexicanStore Whether Management verified an assigned Mexican store for this context.
 * @property mexicanDestination Whether the destination supports Mexican Terminal API card routing.
 * @property simulated Whether the resolved destination supplies offline demos, not a reading of stored payment meaning.
 * @property checking Whether a refresh is running.
 * @property checked Whether a complete discovery answer has been obtained.
 * @property supported Whether the destination supports scanned wallets, independently of configuration.
 * @property problem Setup problem blocking discovery; null when complete.
 * @property failure Discovery-only failure, presented only in Settings; null when none.
 */
data class WalletAvailability(
    val context: PaymentContext? = null,
    val identity: String? = null,
    private val offers: List<WalletOffer> = emptyList(),
    val nativeScanner: Boolean = false,
    val mexicanStore: Boolean = false,
    val mexicanDestination: Boolean = false,
    val simulated: Boolean = false,
    val checking: Boolean = false,
    val checked: Boolean = false,
    val supported: Boolean = false,
    val problem: SetupProblem? = null,
    val failure: Failure? = null,
) {
    /** Current verified Mexican assignment or an explicitly configured offline demo; never inferred from currency or locale. */
    val mexicanRouting: Boolean get() =
        mexicanDestination && checked && problem == null && (mexicanStore || simulated) &&
            context != null && identity != null

    /** Why a refresh cannot run now: the setup [problem] it would only report again; null when it can. */
    val refreshBlocked: Failure? get() = problem?.let(Failure::NotSetUp)

    /** Explicitly configured wallets for [currency]; the simulator offers offline demos of every requested wallet. */
    fun offered(currency: String): List<ScanWallet> =
        when {
            !supported || !checked || problem != null -> emptyList()
            simulated -> ScanWallet.entries.sortedBy { it.displayName }
            else -> WalletOffers.forCurrency(offers, currency)
        }
}

/**
 * Optional, read-only wallet discovery; only this owner publishes cached wallet availability.
 * Startup/setup changes and explicit manual refresh are the only triggers. No timer or checkout-triggered requests.
 * @param setups Resolves setup and decrypts credentials once per lookup.
 * @param scope Runs startup observation and manual requests beyond screen navigation.
 * @param terminals Reads the current terminal assignment, hardware model and country.
 * @param connect Supplies Management payment-method access in the exact environment.
 */
class WalletDiscovery(
    private val setups: TerminalSetupSource,
    private val scope: CoroutineScope,
    private val terminals: (String) -> TerminalListingApi,
    private val connect: (String, TerminalEnvironment) -> WalletMethodsApi,
) {
    private val refreshes = Mutex()
    private val started = AtomicBoolean()

    /** Current non-secret availability and Settings-only diagnostics. */
    val state: StateFlow<WalletAvailability>
        field = MutableStateFlow(WalletAvailability())

    /** Starts once per process; changed setup invalidates availability before starting a new lookup. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            setups.discoveryChanges.collectLatest {
                state.value = WalletAvailability()
                refresh()
            }
        }
    }

    /** Requests a manual refresh; duplicate taps while checking do not queue additional requests. */
    fun refreshInBackground() {
        if (!state.value.checking) scope.launch { refresh() }
    }

    /** Performs one serialized read, retaining cached offers only after a transient failure for unchanged setup. */
    suspend fun refresh() =
        refreshes.withLock {
            val origin = setups.unlocked()
            val setup = origin.setup
            val context = setup.paymentContext()
            val identity = setups.SetupFence(origin, SetupFreshness.SECRETS).identity
            val old = state.value.takeIf { it.identity == identity && it.context == context } ?: WalletAvailability()
            val initial =
                old.copy(
                    context = context,
                    identity = identity,
                    supported = setup.capabilities.scannedWallets,
                    mexicanDestination = setup.capabilities.mexicanInstallments,
                    simulated = setup.apiSetup == ApiSetup.Simulated,
                    checking = true,
                )
            state.value = initial
            when {
                !setup.capabilities.scannedWallets -> {
                    publish(origin, initial.copy(checking = false))
                }

                setup.problem != null -> {
                    publish(origin, initial.copy(checking = false, checked = false, problem = setup.problem))
                }

                setup.apiSetup == ApiSetup.Simulated -> {
                    publish(
                        origin,
                        initial.copy(checking = false, checked = true, problem = null, failure = null),
                    )
                }

                else -> {
                    discover(origin, initial)
                }
            }
        }

    /** Revalidates a scan's original setup without networking, so stale discovery can never rebind a payment. */
    internal suspend fun current(origin: WalletAvailability): Boolean {
        val actual = setups.unlocked()
        return actual.setup.problem == null && setups.SetupFence(actual, SetupFreshness.SECRETS).identity == origin.identity &&
            actual.setup.paymentContext() == origin.context
    }

    private suspend fun discover(
        origin: UnlockedSetup,
        initial: WalletAvailability,
    ) {
        val setup = origin.setup
        val environment = checkNotNull(setup.environment)
        val key = checkNotNull(origin.apiKey)
        when (
            val assignment =
                TerminalAssignments.assigned(
                    terminals(key),
                    environment,
                    checkNotNull(setup.poiId),
                    setup.settings.terminal.merchantAccount,
                )
        ) {
            is AssignedTerminal.Failed -> {
                publishFailure(origin, initial, assignment.fault)
            }

            is AssignedTerminal.Blocked -> {
                publish(origin, initial.copy(checking = false, checked = false, problem = assignment.problem))
            }

            is AssignedTerminal.Found -> {
                val terminal = assignment.terminal
                when (val methods = connect(key, environment).methods(terminal.merchantAccount, terminal.storeId)) {
                    is WalletMethodListing.Failed -> {
                        publishFailure(origin, initial, methods.fault)
                    }

                    is WalletMethodListing.Listed -> {
                        publish(
                            origin,
                            initial.copy(
                                offers = WalletOffers.configured(methods.methods, terminal.storeId, terminal.countryCode),
                                mexicanStore = terminal.storeId.isNotBlank() && terminal.countryCode.equals("MX", ignoreCase = true),
                                nativeScanner = WalletOffers.nativeScanner(terminal.model, terminal.id),
                                checking = false,
                                checked = true,
                                problem = null,
                                failure = null,
                            ),
                        )
                    }
                }
            }
        }
    }

    private suspend fun publishFailure(
        origin: UnlockedSetup,
        initial: WalletAvailability,
        reason: Fault,
    ) {
        // A rejected credential or an unreadable answer discards cached offers; an outage keeps a verified list.
        val discard =
            reason is Fault.Credential || reason is Fault.Permission || reason is Fault.UnreadableReply ||
                reason is Fault.Malformed || reason == Fault.ListTooLarge
        val kept = if (discard) initial.copy(offers = emptyList(), checked = false) else initial
        publish(origin, kept.copy(checking = false, failure = Failure.Remote(reason)))
    }

    private suspend fun publish(
        origin: UnlockedSetup,
        result: WalletAvailability,
    ) {
        if (setups.SetupFence(origin, SetupFreshness.SECRETS).stillCurrent()) state.value = result
    }
}
