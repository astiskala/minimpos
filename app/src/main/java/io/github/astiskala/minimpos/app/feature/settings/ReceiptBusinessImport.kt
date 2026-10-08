package io.github.astiskala.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.feature.OutcomeMessage
import io.github.astiskala.minimpos.app.terminal.ReceiptBusiness
import io.github.astiskala.minimpos.app.ui.components.LabeledValue
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton

/** Explicit Management lookup followed by review; only confirmation fills blank receipt fields. */
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
    state.proposal?.let { business ->
        AlertDialog(
            onDismissRequest = { events.onChoose(null) },
            title = { Text(stringResource(R.string.settings_business_review)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { BusinessReview(business) } },
            confirmButton = {
                TextButton(
                    onClick = { events.onChoose(business) },
                    enabled = business.available,
                    modifier = Modifier.testTag("confirmReceiptBusiness"),
                ) { Text(stringResource(R.string.transfer_import_action)) }
            },
            dismissButton = {
                TextButton(onClick = { events.onChoose(null) }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
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
