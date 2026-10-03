package io.github.astiskala.minimpos.app.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.settings.PaymentSettings
import io.github.astiskala.minimpos.app.ui.components.ActionMessage
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton
import io.github.astiskala.minimpos.app.ui.components.SectionHeader
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens
import io.github.astiskala.minimpos.core.cart.AppliedTax
import io.github.astiskala.minimpos.core.receipt.label
import io.github.astiskala.minimpos.core.tax.TaxMode
import io.github.astiskala.minimpos.core.tax.TaxRates

/** Settings › Tax: whether prices include tax, and the named tax rates products and custom items use. */
@Composable
internal fun ColumnScope.TaxSection(
    state: SettingsUiState,
    actions: SettingsActions,
    events: SettingsEvents,
) {
    var editing by remember { mutableStateOf<TaxRateEntity?>(null) }
    val defaultId = state.defaultTaxRate?.id

    fun update(transform: (PaymentSettings) -> PaymentSettings) = events.onUpdate { it.copy(payment = transform(it.payment)) }
    val chargeTax = state.settings.payment.chargeTax
    SettingSwitch(
        title = stringResource(R.string.settings_charge_tax),
        checked = chargeTax,
        onChange = { on -> update { it.copy(chargeTax = on) } },
        subtitle = stringResource(if (chargeTax) R.string.settings_charge_tax_on else R.string.settings_charge_tax_off),
        tag = "chargeTax",
    )
    if (!chargeTax) return
    SettingChoice(
        title = stringResource(R.string.settings_tax_mode),
        options =
            listOf(
                TaxMode.INCLUSIVE to stringResource(R.string.settings_tax_inclusive),
                TaxMode.EXCLUSIVE to stringResource(R.string.settings_tax_exclusive),
            ),
        selected = state.settings.payment.taxMode,
        onSelect = { mode -> update { it.copy(taxMode = mode) } },
        tag = "taxMode",
    )
    SectionHeader(stringResource(R.string.settings_tax_rates))
    state.taxRates.forEach { rate ->
        TaxRateRow(rate, products = state.taxRateUsage[rate.id] ?: 0, isDefault = rate.id == defaultId) { editing = rate }
        HorizontalDivider()
    }
    actions.taxRateInUse?.let { count ->
        ActionMessage(
            pluralStringResource(R.plurals.settings_tax_in_use, count, count),
            isError = true,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    SettingActions {
        SecondaryButton(stringResource(R.string.settings_tax_add), {
            editing = TaxRateEntity(name = "", rateMilliPercent = 0, sortOrder = state.taxRates.size)
        }, icon = Icons.Default.Add, modifier = Modifier.testTag("addTaxRate"))
    }
    SettingNote(stringResource(R.string.settings_tax_note))

    editing?.let { rate ->
        TaxRateDialog(
            taxRate = rate,
            isDefault = rate.id != 0L && rate.id == defaultId,
            canDelete = rate.id != 0L && state.taxRates.size > 1,
            onSave = { updated, makeDefault ->
                events.onTaxRateSave(updated, makeDefault)
                editing = null
            },
            onDelete = {
                events.onTaxRateDelete(rate)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** A tax rate with how many products use it and whether it is the default; tapping it opens the editor. */
@Composable
private fun TaxRateRow(
    rate: TaxRateEntity,
    products: Int,
    isDefault: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = LocalDimens.current.rowPadding)
            .testTag("taxRow_${rate.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(rate.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    pluralStringResource(R.plurals.settings_tax_products, products, products),
                    stringResource(R.string.settings_tax_is_default).takeIf { isDefault },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${TaxRates.format(rate.rateMilliPercent)}%", style = MaterialTheme.typography.titleMedium)
    }
}

/** The editable fields of a tax rate. The rate accepts up to 3 decimals (e.g. 5, 8.1, 25.5 or US rates like 8.875). */
@Stable
internal class TaxRateFormState(
    private val taxRate: TaxRateEntity,
    isDefault: Boolean,
) {
    var name by mutableStateOf(taxRate.name)
    var rate by mutableStateOf(if (taxRate.id == 0L) "" else TaxRates.format(taxRate.rateMilliPercent))
    var makeDefault by mutableStateOf(isDefault)

    val parsedRate: Int? get() = TaxRates.parse(rate)
    val invalidRate: Boolean get() = rate.isNotBlank() && parsedRate == null
    val valid: Boolean get() = name.isNotBlank() && parsedRate != null

    fun toEntity(): TaxRateEntity = taxRate.copy(name = name.trim(), rateMilliPercent = parsedRate ?: 0)
}

/** Adds or edits a tax rate: a name (with quick picks such as GST or VAT) and a percentage. */
@Composable
internal fun TaxRateDialog(
    taxRate: TaxRateEntity,
    isDefault: Boolean,
    canDelete: Boolean,
    onSave: (TaxRateEntity, Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val form = remember(taxRate) { TaxRateFormState(taxRate, isDefault) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (taxRate.id == 0L) R.string.settings_tax_add else R.string.settings_tax_edit)) },
        text = { TaxRateForm(form) },
        confirmButton = {
            TextButton(
                onClick = { onSave(form.toEntity(), form.makeDefault) },
                enabled = form.valid,
                modifier = Modifier.testTag("saveTax"),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag("deleteTax")) {
                        Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
internal fun TaxRateForm(form: TaxRateFormState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = form.name,
            onValueChange = { form.name = it.take(MAX_NAME_LENGTH) },
            label = { Text(stringResource(R.string.settings_tax_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().testTag("taxName"),
        )
        TaxNameSuggestions(form.name) { form.name = it }
        OutlinedTextField(
            value = form.rate,
            onValueChange = { form.rate = it.take(MAX_RATE_LENGTH) },
            label = { Text(stringResource(R.string.settings_tax_rate)) },
            suffix = { Text("%") },
            isError = form.invalidRate,
            supportingText = {
                Text(stringResource(if (form.invalidRate) R.string.settings_tax_rate_invalid else R.string.settings_tax_rate_hint))
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth().testTag("taxRate"),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = form.makeDefault, onCheckedChange = { form.makeDefault = it }, modifier = Modifier.testTag("taxDefault"))
            Text(stringResource(R.string.settings_tax_default))
        }
        form.parsedRate?.takeIf { form.name.isNotBlank() }?.let {
            Text(
                stringResource(R.string.settings_tax_preview, AppliedTax(form.name.trim(), it).label()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Common tax names as chips; the one matching [name] is shown selected. */
@Composable
private fun TaxNameSuggestions(
    name: String,
    onPick: (String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        stringArrayResource(R.array.tax_name_suggestions).forEach { suggestion ->
            val selected = name.trim() == suggestion
            FilterChip(
                selected = selected,
                onClick = { onPick(suggestion) },
                label = { Text(suggestion) },
                leadingIcon = if (selected) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null,
                // The default selected colour matches the dialog background.
                colors =
                    FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.primary,
                    ),
                border =
                    FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = selected,
                        selectedBorderColor = MaterialTheme.colorScheme.primary,
                        selectedBorderWidth = 1.dp,
                    ),
                modifier = Modifier.testTag("taxSuggestion_$suggestion"),
            )
        }
    }
}

private const val MAX_NAME_LENGTH = 30
private const val MAX_RATE_LENGTH = 8
