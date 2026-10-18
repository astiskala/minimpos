package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.minimpos.app.R
import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.OutcomeMessage
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.CapabilityStanding
import app.minimpos.app.terminal.GratuityCapability
import app.minimpos.app.terminal.MotoCapability
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.SetupRequired
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.LocalStatusColors

/** Settings › Capabilities: progressive readiness matrix and scoped Management `terminalSettings` editors. */
@Composable
internal fun ColumnScope.CapabilitiesSection(
    capabilities: AdyenCapabilitiesState,
    actions: CapabilitiesActions,
    payment: PaymentSettings,
    currency: String,
    events: CapabilitiesEvents,
) {
    SettingIntro(stringResource(R.string.capabilities_intro))
    // Until payments are set up there is nothing at Adyen to inspect or configure yet.
    if (currentSetupGate().pending) {
        SetupRequired()
        return
    }
    capabilities.failure?.let { SettingNote(it.text()) }
    if (capabilities.capabilities.discoversTerminals || capabilities.simulated) {
        SettingActions {
            GatedButton(
                stringResource(R.string.capabilities_refresh),
                events::onRefresh,
                blocked = capabilities.refreshBlocked,
                loading = capabilities.checking,
                modifier = Modifier.testTag("refreshCapabilities"),
            )
        }
    } else {
        capabilities.problem?.let { SettingNote(it.text()) }
    }

    SectionHeader(stringResource(R.string.capabilities_matrix_header))
    CapabilitiesMatrix(capabilities, payment, currency)

    if ((capabilities.capabilities.discoversTerminals || capabilities.simulated) && capabilities.problem == null) {
        key(actions.revision, currency) {
            RemoteGratuityEditor(capabilities, actions.gratuity, currency, events::onSaveGratuity)
            RemoteDccEditor(capabilities, actions.dcc, events::onSaveDcc)
            RemoteMotoEditor(capabilities, actions.moto, events::onSaveMoto)
            if (capabilities.capabilities.supportsOfflinePayments) {
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
        standing = capabilities.motoStanding(enabledLocally = payment.allowMoto || payment.allowMke),
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
                .padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = 10.dp)
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
        HorizontalDivider(Modifier.padding(start = LocalDimens.current.rowHorizontalPadding))
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
    var enabled by rememberSaveable { mutableStateOf(existing != null) }
    var predefined by rememberSaveable { mutableStateOf(existing?.usePredefinedEntries != false) }
    var entriesText by rememberSaveable {
        mutableStateOf(
            existing
                ?.predefinedEntries
                ?.takeIf { it.isNotEmpty() }
                ?.joinToString(",")
                ?: "10%,15%,20%",
        )
    }
    var allowCustom by rememberSaveable { mutableStateOf(existing?.allowCustomAmount != false) }

    SectionHeader(stringResource(R.string.capability_gratuity_section, currency))
    SettingSwitch(
        stringResource(R.string.capability_gratuity_enable),
        enabled,
        { enabled = it },
        subtitle = stringResource(R.string.capability_gratuity_enable_hint, currency),
        tag = "capGratuityEnable",
    )
    if (enabled) {
        SettingSwitch(
            stringResource(R.string.capability_gratuity_predefined),
            predefined,
            { predefined = it },
            subtitle = stringResource(R.string.capability_gratuity_predefined_hint),
            tag = "capGratuityPredefined",
        )
        if (predefined) {
            SettingTextField(
                label = stringResource(R.string.capability_gratuity_entries),
                value = entriesText,
                onCommit = { entriesText = it },
                supporting = stringResource(R.string.capability_gratuity_entries_hint),
                autoCorrect = false,
                tag = "capGratuityEntries",
            )
            SettingSwitch(
                stringResource(R.string.capability_gratuity_custom),
                allowCustom,
                { allowCustom = it },
                subtitle = stringResource(R.string.capability_gratuity_custom_hint),
                tag = "capGratuityCustom",
            )
        }
    }
    SaveToAdyen(
        action,
        { onSave(currency, buildGratuityTarget(currency, enabled, predefined, entriesText, allowCustom)) },
        "saveCapGratuity",
    )
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
    var enabled by rememberSaveable { mutableStateOf(capabilities.dccEnabled) }
    SectionHeader(stringResource(R.string.capability_dcc))
    SettingSwitch(
        title = stringResource(R.string.capability_dcc_enable),
        checked = enabled,
        onChange = { enabled = it },
        subtitle = stringResource(R.string.capability_dcc_enable_hint),
        tag = "capDccEnable",
    )
    SaveToAdyen(action, { onSave(enabled) }, "saveCapDcc")
}

@Composable
private fun ColumnScope.RemoteMotoEditor(
    capabilities: AdyenCapabilitiesState,
    action: ActionState,
    onSave: (MotoCapability) -> Unit,
) {
    var enabled by rememberSaveable { mutableStateOf(capabilities.moto.enabled) }
    var maxAmountText by rememberSaveable {
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
        subtitle = stringResource(R.string.capability_moto_enable_hint),
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
    SaveToAdyen(action, {
        onSave(MotoCapability(enabled = enabled, maxAmount = maxAmountText.trim().toIntOrNull()?.takeIf { it > 0 }))
    }, "saveCapMoto")
}

@Composable
private fun ColumnScope.RemoteOfflineEditor(
    capabilities: AdyenCapabilitiesState,
    action: ActionState,
    currency: String,
    onSave: (String, Int?, Int?, Int?) -> Unit,
) {
    val offline = capabilities.offline
    var chipFloorText by rememberSaveable { mutableStateOf(offline.chipFloorLimit?.toString().orEmpty()) }
    var safMaxMinorText by rememberSaveable { mutableStateOf(offline.maxStoreAndForwardMinor[currency]?.toString().orEmpty()) }
    var maxPaymentsText by rememberSaveable { mutableStateOf(offline.maxPayments?.toString().orEmpty()) }

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
    SaveToAdyen(action, {
        onSave(
            currency,
            chipFloorText.trim().toIntOrNull()?.takeIf { it >= 0 },
            safMaxMinorText.trim().toIntOrNull()?.takeIf { it >= 0 },
            maxPaymentsText.trim().toIntOrNull()?.takeIf { it >= 0 },
        )
    }, "saveCapOffline")
}

/**
 * "Save to Adyen" under an Adyen-side section: unlike local settings, which save as they change, its edits are a draft
 * until [onSave] sends them, with the [action]'s outcome under the button, which is tagged [tag].
 */
@Composable
private fun SaveToAdyen(
    action: ActionState,
    onSave: () -> Unit,
    tag: String,
) {
    SettingActions {
        SecondaryButton(
            text = stringResource(R.string.capability_save),
            onClick = onSave,
            loading = action.running,
            icon = Icons.Default.CloudUpload,
            modifier = Modifier.testTag(tag),
        )
        OutcomeMessage(action)
    }
}
