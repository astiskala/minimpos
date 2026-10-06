package io.github.astiskala.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.feature.OutcomeMessage
import io.github.astiskala.minimpos.app.terminal.ReceiptBusiness
import io.github.astiskala.minimpos.app.ui.components.LabeledValue
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton

/** Explicit Management lookup followed by store selection and review; only confirmation replaces supplied receipt fields. */
@Composable
internal fun ReceiptBusinessImport(
    state: ReceiptBusinessImportState,
    events: ReceiptBusinessEvents,
) {
    SettingNote(stringResource(R.string.settings_business_import_hint))
    SettingActions {
        SecondaryButton(
            stringResource(R.string.settings_business_import),
            events::onFind,
            loading = state.lookup.running,
            icon = Icons.Default.FileDownload,
            modifier = Modifier.testTag("findReceiptBusinesses"),
        )
        OutcomeMessage(state.lookup)
    }
    if (state.stores?.isEmpty() == true) SettingNote(stringResource(R.string.settings_business_none))
    state.stores?.takeIf { it.isNotEmpty() }?.let { stores ->
        var selected by remember(stores) { mutableStateOf(stores.singleOrNull()) }
        val title = if (selected == null) R.string.settings_business_choose else R.string.settings_business_review
        AlertDialog(
            onDismissRequest = { events.onChoose(null) },
            title = { Text(stringResource(title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val business = selected
                    if (business == null) {
                        StoreChoices(stores) { selected = it }
                    } else {
                        BusinessReview(business)
                    }
                }
            },
            confirmButton = {
                selected?.let { business ->
                    TextButton(
                        onClick = { events.onChoose(business) },
                        enabled = business.available,
                        modifier = Modifier.testTag("confirmReceiptBusiness"),
                    ) { Text(stringResource(R.string.transfer_import_action)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { events.onChoose(null) }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun ColumnScope.StoreChoices(
    stores: List<ReceiptBusiness>,
    choose: (ReceiptBusiness) -> Unit,
) {
    if (stores.isEmpty()) Text(stringResource(R.string.settings_business_none))
    stores.forEach { store ->
        TextButton(onClick = { choose(store) }, modifier = Modifier.fillMaxWidth().testTag("receiptBusiness_${store.id}")) {
            Text(listOf(store.name, store.reference, store.id).filter(String::isNotBlank).joinToString("\n"))
        }
    }
}

@Composable
private fun ColumnScope.BusinessReview(business: ReceiptBusiness) {
    LabeledValue(stringResource(R.string.settings_business_name), business.name)
    LabeledValue(stringResource(R.string.settings_address), business.address)
    LabeledValue(stringResource(R.string.settings_phone), business.phone)
    SettingNote(stringResource(R.string.settings_business_review_hint), Modifier.padding(top = 8.dp))
    if (!business.available) Text(stringResource(R.string.settings_business_unavailable))
}
