package app.minimpos.app.terminal

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.RemoteTerminalCapabilities
import app.minimpos.terminal.transport.TerminalCapabilitiesApi
import app.minimpos.terminal.transport.TerminalCapabilitiesLookup
import app.minimpos.terminal.transport.TerminalCapabilitiesPatch
import app.minimpos.terminal.transport.TerminalCapabilitiesUpdate
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalGratuity
import app.minimpos.terminal.transport.TerminalMotoSettings
import app.minimpos.terminal.transport.TerminalOfflineLimits
import app.minimpos.terminal.transport.WalletMethod
import app.minimpos.terminal.transport.WalletMethodListing
import app.minimpos.terminal.transport.WalletMethodsApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Progressive readiness of one Adyen capability on the current destination. */
enum class CapabilityStanding {
    /** Ready to use on this destination and account/terminal configuration. */
    AVAILABLE,

    /** Supported by the destination, but requires Management API or Customer Area configuration. */
    NEEDS_CONFIGURATION,

    /** Not supported on the selected payment destination. */
    UNSUPPORTED,
}

/**
 * Terminal-level gratuity settings for one [currency].
 * @property currency ISO 4217 currency code.
 * @property usePredefinedEntries True when the terminal displays predefined buttons; false for a direct tip prompt.
 * @property predefinedEntries Up to four percentage (`"10%"`) or minor-unit (`"100"`) options.
 * @property allowCustomAmount Whether one button lets the shopper enter a custom tip amount.
 */
data class GratuityCapability(
    val currency: String,
    val usePredefinedEntries: Boolean = false,
    val predefinedEntries: List<String> = emptyList(),
    val allowCustomAmount: Boolean = false,
)

/**
 * Terminal-level MOTO configuration.
 * @property enabled Whether MOTO is enabled on the terminal.
 * @property maxAmount Maximum MOTO transaction amount in major units, or null when unset.
 */
data class MotoCapability(
    val enabled: Boolean = false,
    val maxAmount: Int? = null,
)

/**
 * Terminal-level offline EMV and store-and-forward limits.
 * @property chipFloorLimit Maximum chip offline EMV amount in major units, or null when unset.
 * @property maxStoreAndForwardMinor Maximum store-and-forward transaction amount in minor units by currency code.
 * @property maxPayments Maximum number of stored offline payments before forwarding, or null when unset.
 */
data class OfflineCapability(
    val chipFloorLimit: Int? = null,
    val maxStoreAndForwardMinor: Map<String, Long> = emptyMap(),
    val maxPayments: Int? = null,
) {
    /** Whether any offline EMV or store-and-forward limit is active for [currency]. */
    fun configuredFor(currency: String): Boolean =
        (chipFloorLimit != null && chipFloorLimit > 0) ||
            ((maxStoreAndForwardMinor[currency] ?: 0L) > 0L && (maxPayments == null || maxPayments > 0))
}

/**
 * Snapshot of destination and Management API capabilities for Settings › Capabilities.
 * @property context Destination/account/environment that was checked; null before discovery.
 * @property identity Fingerprint of the checked connection and encrypted payment secrets; never a credential.
 * @property capabilities What the destination supports and asks of its setup, carried whole (as `TerminalState`
 *   carries it too); Settings › Capabilities reads its flags from there.
 * @property simulated Whether the resolved destination is the built-in simulator.
 * @property checking Whether a capability refresh is running.
 * @property checked Whether a complete capability answer has been obtained.
 * @property gratuities Configured gratuities by currency.
 * @property dccEnabled Whether Dynamic Currency Conversion is enabled on the terminal.
 * @property surchargeConfigured Whether card surcharge rules are configured on the terminal.
 * @property moto MOTO configuration on the terminal.
 * @property offline Offline EMV and store-and-forward limits on the terminal.
 * @param methods Configured POS payment methods for gift-card inspection.
 * @property storeId Assigned terminal store ID, when known.
 * @property countryCode Assigned terminal country code, when known.
 * @property problem Setup problem blocking Management inspection; null when complete.
 * @property failure Discovery failure presented in Settings; null when none.
 */
data class AdyenCapabilitiesState(
    val context: PaymentContext? = null,
    val identity: String? = null,
    val capabilities: DestinationCapabilities = DestinationCapabilities(),
    val simulated: Boolean = false,
    val checking: Boolean = false,
    val checked: Boolean = false,
    val gratuities: List<GratuityCapability> = emptyList(),
    val dccEnabled: Boolean = false,
    val surchargeConfigured: Boolean = false,
    val moto: MotoCapability = MotoCapability(),
    val offline: OfflineCapability = OfflineCapability(),
    private val methods: List<WalletMethod> = emptyList(),
    val storeId: String = "",
    val countryCode: String = "",
    val problem: SetupProblem? = null,
    val failure: Failure? = null,
) {
    /** Why a refresh cannot run now: the setup [problem] it would only report again; null when it can. */
    val refreshBlocked: Failure? get() = problem?.let(Failure::NotSetUp)

    /** Gratuity configuration for [currency], if any. */
    fun gratuityFor(currency: String): GratuityCapability? = gratuities.firstOrNull { it.currency.equals(currency, ignoreCase = true) }

    /** Configured POS gift card types for [currency]. */
    fun giftCardsFor(currency: String): List<String> =
        if (!capabilities.supportsTerminalFeatures) {
            emptyList()
        } else if (simulated) {
            listOf("svs")
        } else {
            WalletOffers.giftCards(methods, storeId, countryCode, currency)
        }

    /** Standing of native terminal tipping (`AskGratuity`) for [currency], given whether local tipping is [enabledLocally]. */
    fun tippingStanding(
        currency: String,
        enabledLocally: Boolean,
    ): CapabilityStanding =
        when {
            !capabilities.supportsTerminalFeatures -> CapabilityStanding.UNSUPPORTED
            simulated || gratuityFor(currency) != null || enabledLocally -> CapabilityStanding.AVAILABLE
            else -> CapabilityStanding.NEEDS_CONFIGURATION
        }

    /** Standing of Dynamic Currency Conversion (`dcc`). */
    val dccStanding: CapabilityStanding
        get() =
            when {
                !capabilities.supportsTerminalFeatures -> CapabilityStanding.UNSUPPORTED
                dccEnabled -> CapabilityStanding.AVAILABLE
                else -> CapabilityStanding.NEEDS_CONFIGURATION
            }

    /** Standing of Adyen Giving (`AskGiving`), given whether it is [enabledLocally]. */
    fun givingStanding(enabledLocally: Boolean): CapabilityStanding =
        when {
            !capabilities.supportsTerminalFeatures -> CapabilityStanding.UNSUPPORTED
            enabledLocally || simulated -> CapabilityStanding.AVAILABLE
            else -> CapabilityStanding.NEEDS_CONFIGURATION
        }

    /** Standing of terminal card surcharging (`surcharge`). */
    val surchargeStanding: CapabilityStanding
        get() =
            when {
                !capabilities.supportsTerminalFeatures -> CapabilityStanding.UNSUPPORTED
                surchargeConfigured -> CapabilityStanding.AVAILABLE
                else -> CapabilityStanding.NEEDS_CONFIGURATION
            }

    /** Standing of partial authorization (`AllowPartialAuthorisation`); supported on all destinations. */
    val partialAuthStanding: CapabilityStanding get() = CapabilityStanding.AVAILABLE

    /** Standing of POS gift cards for [currency]. */
    fun giftCardStanding(currency: String): CapabilityStanding =
        when {
            !capabilities.supportsTerminalFeatures -> CapabilityStanding.UNSUPPORTED
            giftCardsFor(currency).isNotEmpty() -> CapabilityStanding.AVAILABLE
            else -> CapabilityStanding.NEEDS_CONFIGURATION
        }

    /** Standing of MOTO / Manual Key Entry, given whether either is [enabledLocally]. */
    fun motoStanding(enabledLocally: Boolean): CapabilityStanding =
        when {
            !capabilities.supportsTerminalFeatures -> CapabilityStanding.UNSUPPORTED
            moto.enabled || enabledLocally -> CapabilityStanding.AVAILABLE
            else -> CapabilityStanding.NEEDS_CONFIGURATION
        }

    /** Standing of local offline payments for [currency]. */
    fun offlineStanding(currency: String): CapabilityStanding =
        when {
            !capabilities.supportsOfflinePayments -> CapabilityStanding.UNSUPPORTED
            simulated || offline.configuredFor(currency) -> CapabilityStanding.AVAILABLE
            else -> CapabilityStanding.NEEDS_CONFIGURATION
        }

    /** Standing of terminal reconciliation (`GetTotals` / `SaleReconciliation`). */
    val reconciliationStanding: CapabilityStanding
        get() = if (capabilities.supportsTerminalFeatures) CapabilityStanding.AVAILABLE else CapabilityStanding.UNSUPPORTED

    /** Standing of shopper-facing display and input requests. */
    val shopperDisplayStanding: CapabilityStanding
        get() = if (capabilities.supportsShopperDisplay) CapabilityStanding.AVAILABLE else CapabilityStanding.UNSUPPORTED
}

/** Outcome of a scoped Management `terminalSettings` capability update. */
sealed interface CapabilityPatchResult {
    /** Update succeeded and read-back verified. */
    data object Updated : CapabilityPatchResult

    /** Update did not happen.
     * @property failure Why.
     */
    data class NotDone(
        val failure: Failure,
    ) : CapabilityPatchResult

    /** Update was sent, but read-back verification did not confirm the outcome.
     * @property failure Why.
     */
    data class Unconfirmed(
        val failure: Failure,
    ) : CapabilityPatchResult
}

/**
 * Owns Management capability inspection and scoped `terminalSettings` updates (`gratuities`, `dcc`, `moto`, `offline`).
 * Verifies unchanged terminal assignment before every remote write.
 * @param setups Resolves setup and decrypts credentials once per operation.
 * @param terminals Reads terminal assignments and capability settings.
 * @param walletMethods Reads POS payment-method settings for gift-card inspection.
 */
class CapabilityDiscovery(
    private val setups: TerminalSetupSource,
    private val terminals: (String) -> TerminalCapabilitiesApi,
    private val walletMethods: (String, TerminalEnvironment) -> WalletMethodsApi,
) {
    private val mutex = Mutex()
    private var simulatedCapabilities =
        RemoteTerminalCapabilities(
            gratuities = listOf(TerminalGratuity("EUR", true, listOf("10%", "15%", "20%"), true)),
            dccEnabled = false,
            surchargeConfigured = false,
            moto = TerminalMotoSettings(enabled = false, maxAmount = null),
            offline = TerminalOfflineLimits(chipFloorLimit = 50),
        )

    /** Current non-secret capability state. */
    val state: StateFlow<AdyenCapabilitiesState>
        field = MutableStateFlow(AdyenCapabilitiesState())

    /** Distinct setup-identity changes that require a capability refresh. */
    val changes: Flow<*> get() = setups.discoveryChanges

    /** Refreshes capability state for the current setup. */
    suspend fun refresh(): AdyenCapabilitiesState =
        mutex.withLock {
            val origin = setups.unlocked()
            val setup = origin.setup
            val context = setup.paymentContext()
            val identity = setups.SetupFence(origin, SetupFreshness.SECRETS).identity
            val old = state.value.takeIf { it.identity == identity && it.context == context } ?: AdyenCapabilitiesState()
            val initial =
                old.copy(
                    context = context,
                    identity = identity,
                    capabilities = setup.capabilities,
                    simulated = setup.apiSetup == ApiSetup.Simulated,
                    checking = true,
                )
            state.value = initial
            when {
                setup.apiSetup == ApiSetup.Simulated -> {
                    publish(
                        origin,
                        initial
                            .withRemote(simulatedCapabilities)
                            .copy(checking = false, checked = true, problem = null, failure = null),
                    )
                }

                !setup.capabilities.discoversTerminals -> {
                    publish(
                        origin,
                        initial.copy(checking = false, checked = true, problem = setup.problem, failure = null),
                    )
                }

                setup.problem != null -> {
                    publish(origin, initial.copy(checking = false, checked = false, problem = setup.problem, failure = null))
                }

                else -> {
                    discoverRemote(origin, initial)
                }
            }
            state.value
        }

    /** Updates the terminal's gratuity configuration for [currency]. */
    suspend fun updateGratuity(
        currency: String,
        gratuity: GratuityCapability?,
    ): CapabilityPatchResult =
        patch(
            TerminalCapabilitiesPatch.Gratuity(
                currency = currency,
                gratuity =
                    gratuity?.let {
                        TerminalGratuity(
                            currency = it.currency,
                            usePredefinedEntries = it.usePredefinedEntries,
                            predefinedEntries = it.predefinedEntries,
                            allowCustomAmount = it.allowCustomAmount,
                        )
                    },
            ),
        )

    /** Updates the terminal's Dynamic Currency Conversion setting. */
    suspend fun updateDcc(enabled: Boolean): CapabilityPatchResult = patch(TerminalCapabilitiesPatch.Dcc(enabled))

    /** Updates the terminal's MOTO configuration. */
    suspend fun updateMoto(moto: MotoCapability): CapabilityPatchResult =
        patch(TerminalCapabilitiesPatch.Moto(TerminalMotoSettings(enabled = moto.enabled, maxAmount = moto.maxAmount)))

    /** Updates the terminal's offline EMV and store-and-forward limits for [currency]. */
    suspend fun updateOffline(
        currency: String,
        chipFloorLimit: Int?,
        maxStoreAndForwardMinor: Int?,
        maxPayments: Int?,
    ): CapabilityPatchResult =
        patch(
            TerminalCapabilitiesPatch.Offline(
                currency = currency,
                chipFloorLimit = chipFloorLimit,
                maxStoreAndForwardMinor = maxStoreAndForwardMinor,
                maxPayments = maxPayments,
            ),
        )

    private suspend fun patch(patch: TerminalCapabilitiesPatch): CapabilityPatchResult =
        mutex.withLock {
            val origin = setups.unlocked()
            val setup = origin.setup
            if (setup.apiSetup == ApiSetup.Simulated) {
                simulatedCapabilities = applySimulated(simulatedCapabilities, patch)
                val next =
                    state.value
                        .copy(
                            context = setup.paymentContext(),
                            identity = setups.SetupFence(origin, SetupFreshness.SECRETS).identity,
                            capabilities = setup.capabilities,
                            simulated = true,
                            checking = false,
                            checked = true,
                            problem = null,
                            failure = null,
                        ).withRemote(simulatedCapabilities)
                state.value = next
                return@withLock CapabilityPatchResult.Updated
            }
            if (!setup.capabilities.discoversTerminals) {
                return@withLock CapabilityPatchResult.NotDone(Failure.Remote(Fault.Unsupported))
            }
            val problem = setup.problem
            if (problem != null) {
                return@withLock CapabilityPatchResult.NotDone(Failure.NotSetUp(problem))
            }
            val key = checkNotNull(origin.apiKey)
            val environment = checkNotNull(setup.environment)
            val poiId = checkNotNull(setup.poiId)
            val api = terminals(key)
            val unchangedFailure = TerminalAssignments.verifyUnchanged(api, environment, poiId, setup.settings.terminal)
            if (unchangedFailure != null) {
                return@withLock CapabilityPatchResult.NotDone(unchangedFailure)
            }
            setups.SetupFence(origin, SetupFreshness.SECRETS).changed()?.let {
                return@withLock CapabilityPatchResult.NotDone(Failure.NotSetUp(it))
            }
            when (val result = api.updateCapabilities(poiId, environment, patch)) {
                is TerminalCapabilitiesUpdate.Ready -> {
                    publish(
                        origin,
                        state.value
                            .withRemote(result.capabilities)
                            .copy(checking = false, checked = true, problem = null, failure = null),
                    )
                    CapabilityPatchResult.Updated
                }

                is TerminalCapabilitiesUpdate.Failed -> {
                    val failure = Failure.Remote(result.fault)
                    if (result.uncertain) CapabilityPatchResult.Unconfirmed(failure) else CapabilityPatchResult.NotDone(failure)
                }
            }
        }

    private suspend fun discoverRemote(
        origin: UnlockedSetup,
        initial: AdyenCapabilitiesState,
    ) {
        val setup = origin.setup
        val environment = checkNotNull(setup.environment)
        val key = checkNotNull(origin.apiKey)
        val poiId = checkNotNull(setup.poiId)
        val api = terminals(key)
        when (val assigned = TerminalAssignments.assigned(api, environment, poiId, setup.settings.terminal.merchantAccount)) {
            is AssignedTerminal.Failed -> {
                publish(origin, initial.copy(checking = false, failure = Failure.Remote(assigned.fault)))
            }

            is AssignedTerminal.Blocked -> {
                publish(origin, initial.copy(checking = false, checked = false, problem = assigned.problem))
            }

            is AssignedTerminal.Found -> {
                val terminal = assigned.terminal
                when (val caps = api.capabilities(poiId, environment)) {
                    is TerminalCapabilitiesLookup.Failed -> {
                        publish(origin, initial.copy(checking = false, failure = Failure.Remote(caps.fault)))
                    }

                    is TerminalCapabilitiesLookup.Found -> {
                        val methods =
                            when (val listed = walletMethods(key, environment).methods(terminal.merchantAccount, terminal.storeId)) {
                                is WalletMethodListing.Listed -> listed.methods
                                is WalletMethodListing.Failed -> emptyList()
                            }
                        publish(
                            origin,
                            initial
                                .withRemote(caps.capabilities)
                                .copy(
                                    methods = methods,
                                    storeId = terminal.storeId,
                                    countryCode = terminal.countryCode,
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

    private fun AdyenCapabilitiesState.withRemote(remote: RemoteTerminalCapabilities): AdyenCapabilitiesState =
        copy(
            gratuities =
                remote.gratuities.map {
                    GratuityCapability(
                        currency = it.currency,
                        usePredefinedEntries = it.usePredefinedEntries,
                        predefinedEntries = it.predefinedEntries,
                        allowCustomAmount = it.allowCustomAmount,
                    )
                },
            dccEnabled = remote.dccEnabled,
            surchargeConfigured = remote.surchargeConfigured,
            moto = MotoCapability(enabled = remote.moto.enabled, maxAmount = remote.moto.maxAmount),
            offline =
                OfflineCapability(
                    chipFloorLimit = remote.offline.chipFloorLimit,
                    maxStoreAndForwardMinor = remote.offline.maxStoreAndForwardMinor,
                    maxPayments = remote.offline.maxPayments,
                ),
        )

    private fun applySimulated(
        current: RemoteTerminalCapabilities,
        patch: TerminalCapabilitiesPatch,
    ): RemoteTerminalCapabilities =
        when (patch) {
            is TerminalCapabilitiesPatch.Gratuity -> {
                val gratuity = patch.gratuity
                val retained = current.gratuities.filterNot { it.currency.equals(patch.currency, ignoreCase = true) }
                current.copy(gratuities = if (gratuity == null) retained else retained + gratuity)
            }

            is TerminalCapabilitiesPatch.Dcc -> {
                current.copy(dccEnabled = patch.enabled)
            }

            is TerminalCapabilitiesPatch.Moto -> {
                current.copy(moto = patch.settings)
            }

            is TerminalCapabilitiesPatch.Offline -> {
                val safMax = patch.maxStoreAndForwardMinor
                val map = current.offline.maxStoreAndForwardMinor.toMutableMap()
                if (safMax == null) {
                    map.remove(patch.currency.uppercase())
                } else {
                    map[patch.currency.uppercase()] = safMax.toLong()
                }
                current.copy(
                    offline =
                        current.offline.copy(
                            chipFloorLimit = patch.chipFloorLimit,
                            maxStoreAndForwardMinor = map,
                            maxPayments = patch.maxPayments,
                        ),
                )
            }
        }

    private suspend fun publish(
        origin: UnlockedSetup,
        result: AdyenCapabilitiesState,
    ) {
        if (setups.SetupFence(origin, SetupFreshness.SECRETS).stillCurrent()) state.value = result
    }
}
