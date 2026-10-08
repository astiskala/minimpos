package io.github.astiskala.minimpos.app.terminal

import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.core.money.PaymentContext
import io.github.astiskala.minimpos.core.payment.ScanWallet
import io.github.astiskala.minimpos.terminal.transport.ManagementFailure
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import io.github.astiskala.minimpos.terminal.transport.WalletMethodListing
import io.github.astiskala.minimpos.terminal.transport.WalletMethodsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** Discovery failure presented only in Settings, independently of ordinary payment setup. */
enum class WalletDiscoveryFailure {
    /** The current environment rejected the credential; discard cached availability. */
    AUTHENTICATION,

    /** The credential lacks payment-method or terminal read access; discard cached availability. */
    PERMISSION,

    /** Temporary network/server failure; retain a verified list only for unchanged setup. */
    UNAVAILABLE,

    /** Malformed or incomplete configuration; never interpret it as an empty list. */
    UNREADABLE,
}

/**
 * Memory-only wallet discovery, with no credentials, codes or raw responses.
 * @property context Destination/account/environment that was checked; null before discovery.
 * @property identity Fingerprint of the checked connection and encrypted payment secrets; never a credential.
 * @param offers Eligible wallet configurations, filtered for the terminal's store and country.
 * @property nativeScanner Whether the checked terminal is a confirmed scanner model.
 * @property simulated Whether the resolved destination supplies offline demos, not a reading of stored payment meaning.
 * @property checking Whether a refresh is running.
 * @property checked Whether a complete discovery answer has been obtained.
 * @property supported Whether the destination supports scanned wallets, independently of configuration.
 * @property problem Setup problem blocking discovery; null when complete.
 * @property failure Discovery-only failure; null when none.
 */
data class WalletAvailability(
    val context: PaymentContext? = null,
    val identity: String? = null,
    private val offers: List<WalletOffer> = emptyList(),
    val nativeScanner: Boolean = false,
    val simulated: Boolean = false,
    val checking: Boolean = false,
    val checked: Boolean = false,
    val supported: Boolean = false,
    val problem: SetupProblem? = null,
    val failure: WalletDiscoveryFailure? = null,
) {
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
    private val terminals: (String) -> TerminalDetailsApi,
    private val connect: (String, TerminalEnvironment) -> WalletMethodsApi,
) {
    private val mutable = MutableStateFlow(WalletAvailability())
    private val refreshes = Mutex()
    private val started = AtomicBoolean()

    /** Current non-secret availability and Settings-only diagnostics. */
    val state: StateFlow<WalletAvailability> = mutable.asStateFlow()

    /** Starts once per process; changed setup invalidates availability before starting a new lookup. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            setups.changes
                .map { setup ->
                    Triple(setup.settings.terminal to setup.settings.verifiedSetup, setup.paymentContext(), setup.problem)
                }.distinctUntilChanged()
                .collectLatest {
                    mutable.value = WalletAvailability()
                    refresh()
                }
        }
    }

    /** Requests a manual refresh; duplicate taps while checking do not queue additional requests. */
    fun refreshInBackground() {
        if (!mutable.value.checking) scope.launch { refresh() }
    }

    /** Performs one serialized read, retaining cached offers only after a transient failure for unchanged setup. */
    suspend fun refresh() =
        refreshes.withLock {
            val origin = setups.unlocked()
            val setup = origin.setup
            val context = setup.paymentContext()
            val old = mutable.value.takeIf { it.identity == origin.validationIdentity && it.context == context } ?: WalletAvailability()
            val initial =
                old.copy(
                    context = context,
                    identity = origin.validationIdentity,
                    supported = setup.scannedWallets,
                    simulated = setup.apiSetup == ApiSetup.Simulated,
                    checking = true,
                )
            mutable.value = initial
            when {
                !setup.scannedWallets -> {
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
        return actual.setup.problem == null && actual.validationIdentity == origin.identity &&
            actual.setup.paymentContext() == origin.context
    }

    private suspend fun discover(
        origin: UnlockedSetup,
        initial: WalletAvailability,
    ) {
        val setup = origin.setup
        val environment = checkNotNull(setup.environment)
        val key = checkNotNull(origin.apiKey)
        when (val listing = terminals(key).terminals(environment, setup.poiId)) {
            is TerminalListing.Failed -> {
                publishFailure(origin, initial, listing.reason)
            }

            is TerminalListing.Listed -> {
                if (listing.environment != environment) {
                    publishFailure(origin, initial, ManagementFailure.UNREADABLE)
                    return
                }
                when (
                    val assignment =
                        TerminalAssignments.receipt(
                            listing.terminals,
                            checkNotNull(setup.poiId),
                            setup.settings.terminal.merchantAccount,
                        )
                ) {
                    is TerminalAssignment.Blocked -> {
                        publish(origin, initial.copy(checking = false, checked = false, problem = assignment.problem))
                    }

                    is TerminalAssignment.Assigned -> {
                        val terminal = assignment.terminal
                        when (val methods = connect(key, environment).methods(terminal.merchantAccount, terminal.storeId)) {
                            is WalletMethodListing.Failed -> {
                                publishFailure(origin, initial, methods.reason)
                            }

                            is WalletMethodListing.Listed -> {
                                publish(
                                    origin,
                                    initial.copy(
                                        offers = WalletOffers.configured(methods.methods, terminal.storeId, terminal.countryCode),
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
        }
    }

    private suspend fun publishFailure(
        origin: UnlockedSetup,
        initial: WalletAvailability,
        reason: ManagementFailure,
    ) {
        val failure =
            when (reason) {
                ManagementFailure.AUTHENTICATION -> WalletDiscoveryFailure.AUTHENTICATION

                ManagementFailure.PERMISSION -> WalletDiscoveryFailure.PERMISSION

                ManagementFailure.UNAVAILABLE -> WalletDiscoveryFailure.UNAVAILABLE

                ManagementFailure.UNREADABLE, ManagementFailure.SETTINGS_UNREADABLE,
                ManagementFailure.KEY_INCOMPLETE, ManagementFailure.KEY_INVALID,
                -> WalletDiscoveryFailure.UNREADABLE
            }
        val kept = if (failure == WalletDiscoveryFailure.UNAVAILABLE) initial else initial.copy(offers = emptyList(), checked = false)
        publish(origin, kept.copy(checking = false, failure = failure))
    }

    private suspend fun publish(
        origin: UnlockedSetup,
        result: WalletAvailability,
    ) {
        val actual = setups.unlocked()
        if (actual.validationIdentity == origin.validationIdentity && actual.setup.settings.terminal == origin.setup.settings.terminal) {
            mutable.value = result
        }
    }
}
