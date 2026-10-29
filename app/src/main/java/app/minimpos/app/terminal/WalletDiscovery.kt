package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.payment.ScanWallet
import app.minimpos.core.voucher.MealVoucherScheme
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListingApi
import app.minimpos.terminal.transport.WalletMethodListing
import app.minimpos.terminal.transport.WalletMethodsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Memory-only wallet discovery, with no credentials, codes or raw responses.
 * @property context Destination/account/environment that was checked; null before discovery.
 * @property identity Fingerprint of the checked connection and encrypted payment secrets; never a credential.
 * @param offers Eligible wallet configurations, filtered for the terminal's store and country.
 * @property nativeScanner Whether the checked terminal is a confirmed scanner model.
 * @property mexicanStore Whether Management verified an assigned Mexican store for this context.
 * @property mexicanDestination Whether the destination supports Mexican Terminal API card routing.
 * @property storeCountry Country of the store Management verified as assigned for this context; null when unassigned.
 * @property storeId Verified assigned Management store ID; null when unassigned or simulated.
 * @property mealVoucherDestination Whether the destination supports meal-voucher tenders.
 * @property cashbackDestination Whether this destination carries Terminal API cashback.
 * @property unreferencedRefundDestination Whether this destination carries unreferenced card refunds.
 * @property simulated Whether the resolved destination supplies offline demos, not a reading of stored payment meaning.
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
    val storeCountry: String? = null,
    val storeId: String? = null,
    val mealVoucherDestination: Boolean = false,
    val cashbackDestination: Boolean = false,
    val unreferencedRefundDestination: Boolean = false,
    val simulated: Boolean = false,
    val checked: Boolean = false,
    val supported: Boolean = false,
    val problem: SetupProblem? = null,
    val failure: Failure? = null,
) {
    /** Current verified Mexican assignment or an explicitly configured offline demo; never inferred from currency or locale. */
    val mexicanRouting: Boolean get() =
        mexicanDestination && checked && problem == null && (mexicanStore || simulated) &&
            context != null && identity != null

    /**
     * Meal-voucher markets a voucher tender may use now: the verified store country's market on a supporting
     * destination, or, for an explicitly configured offline demo, those in [currency] for the operator to pick. Never
     * inferred from currency, language or locale for a real setup.
     */
    fun mealVoucherSchemes(currency: String): List<MealVoucherScheme> =
        when {
            !mealVoucherDestination || !checked || problem != null || context == null || identity == null -> emptyList()
            simulated -> MealVoucherScheme.demonstrable(currency)
            else -> listOfNotNull(MealVoucherScheme.forCountry(storeCountry)?.takeIf { it.currency == currency })
        }

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
    private val started = AtomicBoolean()
    private val reading =
        SetupReading(setups, WalletAvailability(), { it.identity }, { it.context }) { it }

    /** Current non-secret availability and Settings-only diagnostics. */
    val state: StateFlow<WalletAvailability> get() = reading.state

    /** Starts once per process; changed setup invalidates availability before starting a new lookup. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            setups.discoveryChanges.collectLatest {
                reading.reset()
                refresh()
            }
        }
    }

    /** Performs one serialized read, retaining cached offers only after a transient failure for unchanged setup. */
    suspend fun refresh() {
        reading.refresh(
            begin = { old ->
                old.copy(
                    context = context,
                    identity = identity,
                    supported = setup.capabilities.scannedWallets,
                    mexicanDestination = setup.capabilities.mexicanInstallments,
                    mealVoucherDestination = setup.capabilities.mealVouchers,
                    cashbackDestination = setup.destination.cashback,
                    unreferencedRefundDestination = setup.destination.unreferencedRefunds,
                    simulated = setup.apiSetup == ApiSetup.Simulated,
                )
            },
        ) { initial ->
            when {
                !setup.capabilities.scannedWallets -> {
                    publish(initial)
                }

                setup.problem != null -> {
                    publish(initial.copy(checked = false, problem = setup.problem))
                }

                setup.apiSetup == ApiSetup.Simulated -> {
                    publish(initial.copy(checked = true, problem = null, failure = null))
                }

                else -> {
                    discover(origin, initial)
                }
            }
        }
    }

    /** Revalidates a scan's original setup without networking, so stale discovery can never rebind a payment. */
    internal suspend fun current(origin: WalletAvailability): Boolean = reading.madeForCurrent(origin)

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
                publish(origin, initial.copy(checked = false, problem = assignment.problem))
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
                                storeId = terminal.storeId.takeIf { it.isNotBlank() },
                                mexicanStore = terminal.storeId.isNotBlank() && terminal.countryCode.equals("MX", ignoreCase = true),
                                storeCountry = terminal.countryCode.uppercase().takeIf { terminal.storeId.isNotBlank() && it.isNotBlank() },
                                nativeScanner = WalletOffers.nativeScanner(terminal.model, terminal.id),
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
        publish(origin, kept.copy(failure = Failure.Remote(reason)))
    }

    private suspend fun publish(
        origin: UnlockedSetup,
        result: WalletAvailability,
    ) = reading.publish(origin, result)
}
