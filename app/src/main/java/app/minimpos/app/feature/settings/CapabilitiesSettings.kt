package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.R
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.data.settings.TerminalSettings
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.OutcomeMessage
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.CapabilityDiscovery
import app.minimpos.app.terminal.CapabilityPatchResult
import app.minimpos.app.terminal.CapabilityStanding
import app.minimpos.app.terminal.GratuityCapability
import app.minimpos.app.terminal.MotoCapability
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.theme.LocalStatusColors
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Action outcomes for Settings › Capabilities.
 * @property gratuity Latest terminal gratuity `PATCH` outcome.
 * @property dcc Latest DCC `PATCH` outcome.
 * @property moto Latest MOTO `PATCH` outcome.
 * @property offline Latest offline limits `PATCH` outcome.
 * @property revision Increments on remote read/update so local field drafts reload verified values.
 */
data class CapabilitiesActions(
    val gratuity: ActionState = ActionState(),
    val dcc: ActionState = ActionState(),
    val moto: ActionState = ActionState(),
    val offline: ActionState = ActionState(),
    val revision: Int = 0,
)

/**
 * ViewModel for Settings › Capabilities, observing only the narrow [TerminalSettings] slice.
 * @param discovery Reads and updates Management terminal capabilities.
 * @param observedTerminal Narrow terminal settings slice that triggers capability refresh on change.
 */
class CapabilitiesViewModel(
    private val discovery: CapabilityDiscovery,
    observedTerminal: Flow<TerminalSettings>,
) : ViewModel() {
    /** Current non-secret capability state. */
    val state: StateFlow<AdyenCapabilitiesState> = discovery.state

    private val _actions = MutableStateFlow(CapabilitiesActions())

    /** Outcomes of the latest capability updates. */
    val actions: StateFlow<CapabilitiesActions> = _actions.asStateFlow()

    init {
        viewModelScope.launch {
            observedTerminal.distinctUntilChanged().collectLatest {
                refresh()
            }
        }
    }

    /** Refreshes Management capability inspection. */
    fun refresh() {
        if (state.value.checking) return
        launchWrite({ discovery.refresh() }) { _ ->
            _actions.update { it.copy(revision = it.revision + 1) }
        }
    }

    /** Updates the terminal's gratuity configuration for [currency]. */
    fun saveGratuity(
        currency: String,
        gratuity: GratuityCapability?,
    ) {
        if (_actions.value.gratuity.running) return
        _actions.update { it.copy(gratuity = ActionState(running = true)) }
        launchWrite({ discovery.updateGratuity(currency, gratuity) }) { result ->
            _actions.update {
                it.copy(
                    gratuity = result.toActionState(),
                    revision = if (result == CapabilityPatchResult.Updated) it.revision + 1 else it.revision,
                )
            }
        }
    }

    /** Updates the terminal's Dynamic Currency Conversion setting. */
    fun saveDcc(enabled: Boolean) {
        if (_actions.value.dcc.running) return
        _actions.update { it.copy(dcc = ActionState(running = true)) }
        launchWrite({ discovery.updateDcc(enabled) }) { result ->
            _actions.update {
                it.copy(
                    dcc = result.toActionState(),
                    revision = if (result == CapabilityPatchResult.Updated) it.revision + 1 else it.revision,
                )
            }
        }
    }

    /** Updates the terminal's MOTO configuration. */
    fun saveMoto(moto: MotoCapability) {
        if (_actions.value.moto.running) return
        _actions.update { it.copy(moto = ActionState(running = true)) }
        launchWrite({ discovery.updateMoto(moto) }) { result ->
            _actions.update {
                it.copy(
                    moto = result.toActionState(),
                    revision = if (result == CapabilityPatchResult.Updated) it.revision + 1 else it.revision,
                )
            }
        }
    }

    /** Updates the terminal's offline EMV and store-and-forward limits for [currency]. */
    fun saveOffline(
        currency: String,
        chipFloorLimit: Int?,
        maxStoreAndForwardMinor: Int?,
        maxPayments: Int?,
    ) {
        if (_actions.value.offline.running) return
        _actions.update { it.copy(offline = ActionState(running = true)) }
        launchWrite({
            discovery.updateOffline(
                currency = currency,
                chipFloorLimit = chipFloorLimit,
                maxStoreAndForwardMinor = maxStoreAndForwardMinor,
                maxPayments = maxPayments,
            )
        }) { result ->
            _actions.update {
                it.copy(
                    offline = result.toActionState(),
                    revision = if (result == CapabilityPatchResult.Updated) it.revision + 1 else it.revision,
                )
            }
        }
    }

    private fun CapabilityPatchResult.toActionState(): ActionState =
        when (this) {
            CapabilityPatchResult.Updated -> ActionState(done = true)
            is CapabilityPatchResult.NotDone -> ActionState(outcome = ActionOutcome.Failed(failure), isError = true)
            is CapabilityPatchResult.Unconfirmed -> ActionState(outcome = ActionOutcome.Unconfirmed(failure), isError = true)
        }
}

/** Callbacks for Settings › Capabilities, keeping composables free of view models. */
internal interface CapabilitiesEvents {
    /** Refreshes Management capability inspection. */
    fun onRefresh()

    /** Saves terminal gratuity settings for [currency]. */
    fun onSaveGratuity(
        currency: String,
        gratuity: GratuityCapability?,
    )

    /** Saves terminal DCC setting. */
    fun onSaveDcc(enabled: Boolean)

    /** Saves terminal MOTO configuration. */
    fun onSaveMoto(moto: MotoCapability)

    /** Saves terminal offline limits for [currency]. */
    fun onSaveOffline(
        currency: String,
        chipFloorLimit: Int?,
        maxStoreAndForwardMinor: Int?,
        maxPayments: Int?,
    )
}

internal fun capabilitiesEvents(vm: CapabilitiesViewModel): CapabilitiesEvents =
    object : CapabilitiesEvents {
        override fun onRefresh() = vm.refresh()

        override fun onSaveGratuity(
            currency: String,
            gratuity: GratuityCapability?,
        ) = vm.saveGratuity(currency, gratuity)

        override fun onSaveDcc(enabled: Boolean) = vm.saveDcc(enabled)

        override fun onSaveMoto(moto: MotoCapability) = vm.saveMoto(moto)

        override fun onSaveOffline(
            currency: String,
            chipFloorLimit: Int?,
            maxStoreAndForwardMinor: Int?,
            maxPayments: Int?,
        ) = vm.saveOffline(currency, chipFloorLimit, maxStoreAndForwardMinor, maxPayments)
    }

/** Settings › Capabilities: progressive readiness matrix and scoped Management `terminalSettings` editors. */
@Composable
internal fun ColumnScope.CapabilitiesSection(
    capabilities: AdyenCapabilitiesState,
    actions: CapabilitiesActions,
    payment: PaymentSettings,
    currency: String,
    events: CapabilitiesEvents,
) {
    SettingNote(stringResource(R.string.capabilities_intro))
    capabilities.problem?.let { SettingNote(it.text()) }
    capabilities.failure?.let { SettingNote(it.text()) }
    if (capabilities.discoversTerminals || capabilities.simulated) {
        SettingActions {
            SecondaryButton(
                stringResource(R.string.capabilities_refresh),
                events::onRefresh,
                loading = capabilities.checking,
                modifier = Modifier.testTag("refreshCapabilities"),
            )
        }
    }

    SectionHeader(stringResource(R.string.capabilities_matrix_header))
    CapabilitiesMatrix(capabilities, payment, currency)

    if ((capabilities.discoversTerminals || capabilities.simulated) && capabilities.problem == null) {
        key(actions.revision, currency) {
            RemoteGratuityEditor(capabilities, actions.gratuity, currency, events::onSaveGratuity)
            RemoteDccEditor(capabilities, actions.dcc, events::onSaveDcc)
            RemoteMotoEditor(capabilities, actions.moto, events::onSaveMoto)
            if (capabilities.supportsOfflinePayments) {
                RemoteOfflineEditor(capabilities, actions.offline, currency, events::onSaveOffline)
            }
        }
    }
}

@Composable
private fun ColumnScope.CapabilitiesMatrix(
    capabilities: AdyenCapabilitiesState,
    payment: PaymentSettings,
    currency: String,
) {
    CapabilityStandingRow(
        title = stringResource(R.string.capability_terminal_tipping),
        standing =
            capabilities.tippingStanding(
                currency,
                enabledLocally = payment.terminalTipping != ReceiptTipping.DISABLED,
            ),
        tag = "cap_tipping",
    )
    CapabilityStandingRow(
        title = stringResource(R.string.capability_dcc),
        standing = capabilities.dccStanding,
        tag = "cap_dcc",
    )
    CapabilityStandingRow(
        title = stringResource(R.string.capability_giving),
        standing = capabilities.givingStanding(enabledLocally = payment.askGiving != ReceiptTipping.DISABLED),
        tag = "cap_giving",
    )
    CapabilityStandingRow(
        title = stringResource(R.string.capability_surcharge),
        standing = capabilities.surchargeStanding,
        tag = "cap_surcharge",
    )
    CapabilityStandingRow(
        title = stringResource(R.string.capability_partial_auth),
        standing = capabilities.partialAuthStanding,
        tag = "cap_partial_auth",
    )
    val giftCards = capabilities.giftCardsFor(currency)
    CapabilityStandingRow(
        title = stringResource(R.string.capability_gift_cards),
        standing = capabilities.giftCardStanding(currency),
        detail = giftCards.takeIf { it.isNotEmpty() }?.joinToString(),
        tag = "cap_gift_cards",
    )
    CapabilityStandingRow(
        title = stringResource(R.string.capability_moto_mke),
        standing = capabilities.motoStanding(enabledLocally = false),
        tag = "cap_moto",
    )
    CapabilityStandingRow(
        title = stringResource(R.string.capability_offline),
        standing = capabilities.offlineStanding(currency),
        tag = "cap_offline",
    )
    CapabilityStandingRow(
        title = stringResource(R.string.capability_reconciliation),
        standing = capabilities.reconciliationStanding,
        tag = "cap_reconciliation",
    )
    CapabilityStandingRow(
        title = stringResource(R.string.capability_shopper_display),
        standing = capabilities.shopperDisplayStanding,
        tag = "cap_shopper_display",
    )
}

@Composable
private fun CapabilityStandingRow(
    title: String,
    standing: CapabilityStanding,
    detail: String? = null,
    tag: String? = null,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .let { if (tag != null) it.testTag(tag) else it },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(12.dp))
            val colors = LocalStatusColors.current
            Text(
                text =
                    stringResource(
                        when (standing) {
                            CapabilityStanding.AVAILABLE -> R.string.capability_status_available
                            CapabilityStanding.NEEDS_CONFIGURATION -> R.string.capability_status_needs_config
                            CapabilityStanding.UNSUPPORTED -> R.string.capability_status_unsupported
                        },
                    ),
                style = MaterialTheme.typography.labelLarge,
                color =
                    when (standing) {
                        CapabilityStanding.AVAILABLE -> colors.success
                        CapabilityStanding.NEEDS_CONFIGURATION -> colors.warning
                        CapabilityStanding.UNSUPPORTED -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        HorizontalDivider(Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun ColumnScope.RemoteGratuityEditor(
    capabilities: AdyenCapabilitiesState,
    action: ActionState,
    currency: String,
    onSave: (String, GratuityCapability?) -> Unit,
) {
    val existing = capabilities.gratuityFor(currency)
    var enabled by remember { mutableStateOf(existing != null) }
    var predefined by remember { mutableStateOf(existing?.usePredefinedEntries != false) }
    var entriesText by remember {
        mutableStateOf(
            existing
                ?.predefinedEntries
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(",")
                ?: "10%,15%,20%",
        )
    }
    var allowCustom by remember { mutableStateOf(existing?.allowCustomAmount != false) }

    SectionHeader(stringResource(R.string.capability_gratuity_section, currency))
    SettingSwitch(stringResource(R.string.capability_gratuity_enable, currency), enabled, { enabled = it }, tag = "capGratuityEnable")
    if (enabled) {
        SettingSwitch(stringResource(R.string.capability_gratuity_predefined), predefined, {
            predefined = it
        }, tag = "capGratuityPredefined")
        if (predefined) {
            SettingTextField(
                label = stringResource(R.string.capability_gratuity_entries),
                value = entriesText,
                onCommit = { entriesText = it },
                supporting = stringResource(R.string.capability_gratuity_entries_hint),
                autoCorrect = false,
                tag = "capGratuityEntries",
            )
            SettingSwitch(stringResource(R.string.capability_gratuity_custom), allowCustom, { allowCustom = it }, tag = "capGratuityCustom")
        }
    }
    SettingActions {
        SecondaryButton(
            text = stringResource(R.string.action_save),
            onClick = { onSave(currency, buildGratuityTarget(currency, enabled, predefined, entriesText, allowCustom)) },
            loading = action.running,
            modifier = Modifier.testTag("saveCapGratuity"),
        )
        OutcomeMessage(action)
    }
}

private fun buildGratuityTarget(
    currency: String,
    enabled: Boolean,
    predefined: Boolean,
    entriesText: String,
    allowCustom: Boolean,
): GratuityCapability? {
    if (!enabled) return null
    val parsed =
        entriesText
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(if (allowCustom) 3 else 4)
    return GratuityCapability(
        currency = currency,
        usePredefinedEntries = predefined,
        predefinedEntries = if (predefined) parsed else emptyList(),
        allowCustomAmount = predefined && allowCustom,
    )
}

@Composable
private fun ColumnScope.RemoteDccEditor(
    capabilities: AdyenCapabilitiesState,
    action: ActionState,
    onSave: (Boolean) -> Unit,
) {
    var enabled by remember { mutableStateOf(capabilities.dccEnabled) }
    SectionHeader(stringResource(R.string.capability_dcc))
    SettingSwitch(
        title = stringResource(R.string.capability_dcc_enable),
        checked = enabled,
        onChange = {
            enabled = it
            onSave(it)
        },
        enabled = !action.running,
        tag = "capDccEnable",
    )
    SettingActions {
        OutcomeMessage(action)
    }
}

@Composable
private fun ColumnScope.RemoteMotoEditor(
    capabilities: AdyenCapabilitiesState,
    action: ActionState,
    onSave: (MotoCapability) -> Unit,
) {
    var enabled by remember { mutableStateOf(capabilities.moto.enabled) }
    var maxAmountText by remember {
        mutableStateOf(
            capabilities.moto.maxAmount
                ?.toString()
                .orEmpty(),
        )
    }
    SectionHeader(stringResource(R.string.capability_moto_section))
    SettingSwitch(
        title = stringResource(R.string.capability_moto_enable),
        checked = enabled,
        onChange = { enabled = it },
        tag = "capMotoEnable",
    )
    if (enabled) {
        SettingTextField(
            label = stringResource(R.string.capability_moto_max_amount),
            value = maxAmountText,
            onCommit = { maxAmountText = it },
            keyboardType = KeyboardType.Number,
            autoCorrect = false,
            tag = "capMotoMaxAmount",
        )
    }
    SettingActions {
        SecondaryButton(
            text = stringResource(R.string.action_save),
            onClick = {
                onSave(
                    MotoCapability(
                        enabled = enabled,
                        maxAmount = maxAmountText.trim().toIntOrNull()?.takeIf { it > 0 },
                    ),
                )
            },
            loading = action.running,
            modifier = Modifier.testTag("saveCapMoto"),
        )
        OutcomeMessage(action)
    }
}

@Composable
private fun ColumnScope.RemoteOfflineEditor(
    capabilities: AdyenCapabilitiesState,
    action: ActionState,
    currency: String,
    onSave: (String, Int?, Int?, Int?) -> Unit,
) {
    val offline = capabilities.offline
    var chipFloorText by remember { mutableStateOf(offline.chipFloorLimit?.toString().orEmpty()) }
    var safMaxMinorText by remember { mutableStateOf(offline.maxStoreAndForwardMinor[currency]?.toString().orEmpty()) }
    var maxPaymentsText by remember { mutableStateOf(offline.maxPayments?.toString().orEmpty()) }

    SectionHeader(stringResource(R.string.capability_offline_section, currency))
    SettingTextField(
        stringResource(R.string.capability_offline_chip_floor),
        chipFloorText,
        { chipFloorText = it },
        keyboardType = KeyboardType.Number,
        autoCorrect = false,
        tag = "capOfflineChipFloor",
    )
    SettingTextField(
        stringResource(R.string.capability_offline_saf_max, currency),
        safMaxMinorText,
        { safMaxMinorText = it },
        keyboardType = KeyboardType.Number,
        autoCorrect = false,
        tag = "capOfflineSafMax",
    )
    SettingTextField(
        stringResource(R.string.capability_offline_max_payments),
        maxPaymentsText,
        { maxPaymentsText = it },
        keyboardType = KeyboardType.Number,
        autoCorrect = false,
        tag = "capOfflineMaxPayments",
    )
    SettingActions {
        SecondaryButton(
            text = stringResource(R.string.action_save),
            onClick = {
                onSave(
                    currency,
                    chipFloorText.trim().toIntOrNull()?.takeIf { it >= 0 },
                    safMaxMinorText.trim().toIntOrNull()?.takeIf { it >= 0 },
                    maxPaymentsText.trim().toIntOrNull()?.takeIf { it >= 0 },
                )
            },
            loading = action.running,
            modifier = Modifier.testTag("saveCapOffline"),
        )
        OutcomeMessage(action)
    }
}
