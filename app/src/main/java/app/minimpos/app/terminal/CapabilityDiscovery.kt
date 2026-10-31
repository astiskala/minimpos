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
import kotlinx.coroutines.flow.StateFlow

/** Progressive readiness of one Adyen capability on the current destination. */
enum class CapabilityStanding {
    /** Ready to use on this destination and account/terminal configuration. */
    AVAILABLE,

    /** Supported, but local/Adyen configuration or hardware verification is still needed. */
    NEEDS_CONFIGURATION,

    /** Not supported by the selected destination or its known hardware. */
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
 * @property chipFloorLimit Maximum chip offline EMV amount in minor units of the processing currency, or null when unset.
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
 * @property checked Whether Management (or the simulator) supplied a successful capability reading for this identity.
 * @property gratuities Configured gratuities by currency.
 * @property dccEnabled Whether Dynamic Currency Conversion is enabled on the terminal.
 * @property surchargeConfigured Whether card surcharge rules are configured on the terminal.
 * @property moto MOTO configuration on the terminal.
 * @property offline Offline EMV and store-and-forward limits on the terminal.
 * @property motoCurrency Currency of the MOTO limit, or empty when Management did not supply it.
 * @param methods Configured POS payment methods for gift-card and scanned-wallet inspection.
 * @property methodsChecked Whether the POS payment-method listing succeeded, independently of the settings read.
 * @property storeId Assigned terminal store ID, when known.
 * @property countryCode Assigned terminal country code, when known.
 * @property problem Setup problem blocking Management inspection; null when complete.
 * @property failure Discovery failure presented in Settings; null when none.
 * @property cameraAvailable Whether Android reports a camera on the device running Mini mPOS, without opening it.
 * @property nativeScanner Whether the assigned terminal is a confirmed scanner model; null before hardware discovery.
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
    val motoCurrency: String = "",
    private val methods: List<WalletMethod> = emptyList(),
    val methodsChecked: Boolean = false,
    val storeId: String = "",
    val countryCode: String = "",
    val problem: SetupProblem? = null,
    val failure: Failure? = null,
    val cameraAvailable: Boolean = false,
    val nativeScanner: Boolean? = null,
) {
    /** Whether an editor may offer a save based on a successful, current reading, not default values. */
    val editable: Boolean get() = checked && !checking && problem == null && failure == null

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

    /** Whether a connection check can send a terminal diagnosis request, rather than only validate setup. */
    val diagnosisStanding: CapabilityStanding
        get() = if (capabilities.diagnoses) CapabilityStanding.AVAILABLE else CapabilityStanding.UNSUPPORTED

    /** Native terminal scanner support; an undiscovered model is not proof that a scanner is absent. */
    val terminalScannerStanding: CapabilityStanding
        get() =
            when {
                !capabilities.scannedWallets || nativeScanner == false -> CapabilityStanding.UNSUPPORTED
                nativeScanner == true -> CapabilityStanding.AVAILABLE
                else -> CapabilityStanding.NEEDS_CONFIGURATION
            }
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
    private val reading =
        SetupReading(setups, AdyenCapabilitiesState(), { it.identity }, { it.context }) { it.copy(checking = false) }
    private var simulatedCapabilities =
        RemoteTerminalCapabilities(
            gratuities = listOf(TerminalGratuity("EUR", true, listOf("10%", "15%", "20%"), true)),
            dccEnabled = false,
            surchargeConfigured = false,
            moto = TerminalMotoSettings(enabled = false, maxAmount = null),
            offline = TerminalOfflineLimits(chipFloorLimit = 5_000),
            motoCurrency = "EUR",
        )

    /** Current non-secret capability state. */
    val state: StateFlow<AdyenCapabilitiesState> get() = reading.state

    /** Distinct setup-identity changes that require a capability refresh. */
    val changes: Flow<*> get() = setups.discoveryChanges

    /** Refreshes capability state for the current setup. */
    suspend fun refresh(): AdyenCapabilitiesState =
        reading.refresh(
            begin = { old ->
                old.copy(
                    context = context,
                    identity = identity,
                    capabilities = setup.capabilities,
                    simulated = setup.apiSetup == ApiSetup.Simulated,
                    cameraAvailable = setups.device.hasCamera,
                    nativeScanner = if (setup.apiSetup == ApiSetup.Simulated) false else old.nativeScanner,
                    checking = true,
                )
            },
        ) { initial ->
            when {
                setup.apiSetup == ApiSetup.Simulated -> {
                    publish(
                        initial
                            .withRemote(
                                simulatedCapabilities,
                            ).copy(checking = false, methodsChecked = true, problem = null, failure = null),
                    )
                }

                !setup.capabilities.discoversTerminals -> {
                    publish(initial.copy(checking = false, problem = setup.problem, failure = null))
                }

                setup.problem != null -> {
                    publish(initial.copy(checking = false, problem = setup.problem, failure = null))
                }

                else -> {
                    discoverRemote(origin, initial)
                }
            }
        }

    /** Updates the terminal's gratuity configuration for [currency]. */
    suspend fun updateGratuity(
        currency: String,
        gratuity: GratuityCapability?,
        expectedIdentity: String? = state.value.identity,
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
            expectedIdentity,
        )

    /** Updates the terminal's Dynamic Currency Conversion setting. */
    suspend fun updateDcc(
        enabled: Boolean,
        expectedIdentity: String? = state.value.identity,
    ): CapabilityPatchResult = patch(TerminalCapabilitiesPatch.Dcc(enabled), expectedIdentity)

    /** Updates the terminal's MOTO configuration. */
    suspend fun updateMoto(
        moto: MotoCapability,
        expectedIdentity: String? = state.value.identity,
    ): CapabilityPatchResult =
        patch(TerminalCapabilitiesPatch.Moto(TerminalMotoSettings(enabled = moto.enabled, maxAmount = moto.maxAmount)), expectedIdentity)

    /** Updates the terminal's offline EMV and store-and-forward limits for [currency]. */
    suspend fun updateOffline(
        currency: String,
        chipFloorLimit: Int?,
        maxStoreAndForwardMinor: Int?,
        maxPayments: Int?,
        expectedIdentity: String? = state.value.identity,
    ): CapabilityPatchResult =
        patch(
            TerminalCapabilitiesPatch.Offline(
                currency = currency,
                chipFloorLimit = chipFloorLimit,
                maxStoreAndForwardMinor = maxStoreAndForwardMinor,
                maxPayments = maxPayments,
            ),
            expectedIdentity,
        )

    private suspend fun patch(
        patch: TerminalCapabilitiesPatch,
        expectedIdentity: String?,
    ): CapabilityPatchResult =
        reading.locked {
            val origin = setups.unlocked()
            val setup = origin.setup
            if (expectedIdentity != reading.identity(origin)) {
                return@locked CapabilityPatchResult.NotDone(Failure.NotSetUp(SetupProblem.SETUP_CHANGED))
            }
            if (setup.apiSetup == ApiSetup.Simulated) {
                simulatedCapabilities = applySimulated(simulatedCapabilities, patch)
                val next =
                    state.value
                        .copy(
                            context = setup.paymentContext(),
                            identity = reading.identity(origin),
                            capabilities = setup.capabilities,
                            simulated = true,
                            methodsChecked = true,
                            checking = false,
                            problem = null,
                            failure = null,
                        ).withRemote(simulatedCapabilities)
                reading.publish(origin, next)
                return@locked CapabilityPatchResult.Updated
            }
            if (!setup.capabilities.discoversTerminals) {
                return@locked CapabilityPatchResult.NotDone(Failure.Remote(Fault.Unsupported))
            }
            val problem = setup.problem
            if (problem != null) {
                return@locked CapabilityPatchResult.NotDone(Failure.NotSetUp(problem))
            }
            val key = checkNotNull(origin.apiKey)
            val environment = checkNotNull(setup.environment)
            val poiId = checkNotNull(setup.poiId)
            val api = terminals(key)
            val unchangedFailure = TerminalAssignments.verifyUnchanged(api, environment, poiId, setup.settings.terminal)
            if (unchangedFailure != null) {
                return@locked CapabilityPatchResult.NotDone(unchangedFailure)
            }
            reading.changed(origin)?.let {
                return@locked CapabilityPatchResult.NotDone(Failure.NotSetUp(it))
            }
            when (val result = api.updateCapabilities(poiId, environment, patch)) {
                is TerminalCapabilitiesUpdate.Ready -> {
                    publish(
                        origin,
                        state.value
                            .withRemote(result.capabilities)
                            .copy(checking = false, problem = null, failure = null),
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
                publish(origin, initial.copy(checking = false, problem = assigned.problem))
            }

            is AssignedTerminal.Found -> {
                val terminal = assigned.terminal
                val hardware = initial.copy(nativeScanner = WalletOffers.nativeScanner(terminal.model, terminal.id))
                when (val caps = api.capabilities(poiId, environment)) {
                    is TerminalCapabilitiesLookup.Failed -> {
                        publish(origin, hardware.copy(checking = false, failure = Failure.Remote(caps.fault)))
                    }

                    is TerminalCapabilitiesLookup.Found -> {
                        val methods =
                            when (val listed = walletMethods(key, environment).methods(terminal.merchantAccount, terminal.storeId)) {
                                is WalletMethodListing.Listed -> listed.methods
                                is WalletMethodListing.Failed -> null
                            }
                        publish(
                            origin,
                            hardware
                                .withRemote(caps.capabilities)
                                .copy(
                                    methods = methods.orEmpty(),
                                    methodsChecked = methods != null,
                                    storeId = terminal.storeId,
                                    countryCode = terminal.countryCode,
                                    checking = false,
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
            checked = true,
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
            motoCurrency = remote.motoCurrency,
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
    ) = reading.publish(origin, result)
}
