package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import app.minimpos.app.R
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.Subheading
import app.minimpos.app.ui.components.TertiaryButton
import app.minimpos.app.ui.components.fieldErrorIcon
import app.minimpos.app.ui.theme.LocalDimens

/** Controlled draft fields report even invalid text; unlike autosaved settings, they never suppress an edit. */
@Composable
internal fun ColumnScope.CapabilityEditorFields(
    feature: CapabilityFeature,
    draft: CapabilityDraft,
    currency: String,
    enabled: Boolean,
    onChange: (CapabilityDraft) -> Unit,
) {
    when (feature.editorResult) {
        CapabilityResult.GRATUITY -> {
            GratuityFields(draft, currency, enabled, onChange)
        }

        CapabilityResult.DCC -> {
            SettingSwitch(
                stringResource(R.string.capability_dcc_enable),
                draft.enabled,
                { onChange(draft.copy(enabled = it)) },
                subtitle = stringResource(R.string.capability_dcc_enable_hint),
                enabled = enabled,
                tag = "capDccEnable",
            )
        }

        CapabilityResult.MOTO -> {
            MotoFields(draft, enabled, onChange)
        }

        CapabilityResult.OFFLINE -> {
            OfflineFields(draft, currency, enabled, onChange)
        }

        null -> {}
    }
}

@Composable
private fun ColumnScope.MotoFields(
    draft: CapabilityDraft,
    enabled: Boolean,
    onChange: (CapabilityDraft) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.capability_moto_enable),
        draft.enabled,
        { onChange(draft.copy(enabled = it)) },
        subtitle = stringResource(R.string.capability_moto_enable_hint),
        enabled = enabled,
        tag = "capMotoEnable",
    )
    SettingNote(
        stringResource(
            R.string.capability_moto_units,
            draft.motoCurrency.ifBlank { stringResource(R.string.capability_terminal_currency) },
        ),
    )
    LimitField(
        stringResource(R.string.capability_moto_max_amount),
        draft.motoLimit,
        enabled,
        draft.motoLimit.moto() != null,
        "capMotoMaxAmount",
    ) { onChange(draft.copy(motoLimit = it)) }
}

@Composable
private fun ColumnScope.OfflineFields(
    draft: CapabilityDraft,
    currency: String,
    enabled: Boolean,
    onChange: (CapabilityDraft) -> Unit,
) {
    SettingNote(stringResource(R.string.capability_inherited_note))
    LimitField(
        stringResource(R.string.capability_offline_chip_floor, currency),
        draft.chip,
        enabled,
        draft.chip.amount(currency) != null,
        "capOfflineChipFloor",
    ) { onChange(draft.copy(chip = it)) }
    LimitField(
        stringResource(R.string.capability_offline_saf_max, currency),
        draft.storeAndForward,
        enabled,
        draft.storeAndForward.amount(currency) != null,
        "capOfflineSafMax",
    ) { onChange(draft.copy(storeAndForward = it)) }
    LimitField(
        stringResource(R.string.capability_offline_max_payments),
        draft.count,
        enabled,
        draft.count.integer() != null,
        "capOfflineMaxPayments",
        KeyboardType.Number,
    ) { onChange(draft.copy(count = it)) }
}

@Composable
private fun ColumnScope.GratuityFields(
    draft: CapabilityDraft,
    currency: String,
    enabled: Boolean,
    onChange: (CapabilityDraft) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.capability_gratuity_enable),
        draft.enabled,
        { onChange(draft.copy(enabled = it)) },
        subtitle = stringResource(R.string.capability_gratuity_enable_hint, currency),
        enabled = enabled,
        tag = "capGratuityEnable",
    )
    if (!draft.enabled) return
    SettingSwitch(
        stringResource(R.string.capability_gratuity_predefined),
        draft.predefined,
        { onChange(draft.copy(predefined = it)) },
        subtitle = stringResource(R.string.capability_gratuity_predefined_hint),
        enabled = enabled,
        tag = "capGratuityPredefined",
    )
    if (!draft.predefined) return
    SettingSwitch(
        stringResource(R.string.capability_gratuity_custom),
        draft.custom,
        { onChange(draft.copy(custom = it)) },
        subtitle = stringResource(R.string.capability_gratuity_custom_hint),
        enabled = enabled,
        tag = "capGratuityCustom",
    )
    SettingNote(stringResource(R.string.capability_tip_count, currency))
    draft.tips.forEachIndexed { index, tip ->
        TipOption(index, tip, currency, enabled, onChange = { value ->
            onChange(draft.copy(tips = draft.tips.toMutableList().also { it[index] = value }))
        }, onRemove = { onChange(draft.copy(tips = draft.tips.filterIndexed { row, _ -> row != index })) })
    }
    SettingActions {
        SecondaryButton(
            stringResource(R.string.capability_add_tip),
            { onChange(draft.copy(tips = draft.tips + CapabilityTip())) },
            enabled = enabled && draft.tips.size < draft.maxTips,
            icon = Icons.Default.Add,
            modifier = Modifier.testTag("addCapTip"),
        )
    }
}

@Composable
private fun ColumnScope.TipOption(
    index: Int,
    tip: CapabilityTip,
    currency: String,
    enabled: Boolean,
    onChange: (CapabilityTip) -> Unit,
    onRemove: () -> Unit,
) {
    Subheading(
        stringResource(R.string.capability_tip_option, index + 1),
        Modifier.padding(horizontal = LocalDimens.current.rowHorizontalPadding),
    )
    SettingChoice(
        stringResource(R.string.capability_tip_type),
        listOf(true to stringResource(R.string.capability_percent), false to currency),
        tip.percentage,
        { onChange(tip.copy(percentage = it)) },
        tag = "capTipType_$index",
        enabled = enabled,
    )
    DraftField(
        stringResource(if (tip.percentage) R.string.capability_percent else R.string.capability_amount),
        tip.text,
        { onChange(tip.copy(text = it)) },
        enabled,
        tip.entry(currency) == null,
        "capTipValue_$index",
    )
    SettingActions {
        TertiaryButton(
            stringResource(R.string.capability_remove_tip, index + 1),
            onRemove,
            enabled = enabled,
            destructive = true,
            icon = Icons.Default.Delete,
            modifier = Modifier.testTag("removeCapTip_$index"),
        )
    }
}

@Composable
private fun LimitField(
    title: String,
    limit: CapabilityLimit,
    enabled: Boolean,
    valid: Boolean,
    tag: String,
    keyboard: KeyboardType = KeyboardType.Decimal,
    onChange: (CapabilityLimit) -> Unit,
) {
    SettingSwitch(
        stringResource(R.string.capability_use_inherited),
        limit.inherited,
        { onChange(limit.copy(inherited = it)) },
        subtitle = title,
        enabled = enabled,
        tag = "${tag}Inherited",
    )
    if (!limit.inherited) {
        DraftField(title, limit.text, { onChange(limit.copy(text = it)) }, enabled, !valid, tag, keyboard)
    }
}

@Composable
private fun DraftField(
    title: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    error: Boolean,
    tag: String,
    keyboard: KeyboardType = KeyboardType.Decimal,
) {
    val container = LocalAppContainer.current
    Column(
        Modifier.fillMaxWidth().padding(horizontal = LocalDimens.current.rowHorizontalPadding, vertical = LocalDimens.current.rowPadding),
    ) {
        SettingFieldHeading(title)
        OutlinedTextField(
            value,
            {
                container.userActivity()
                onChange(it)
            },
            enabled = enabled,
            singleLine = true,
            isError = error,
            trailingIcon = fieldErrorIcon(error),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = title }.testTag(tag),
        )
    }
}
