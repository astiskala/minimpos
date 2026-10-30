package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.minimpos.app.R
import app.minimpos.app.feature.OutcomeMessage
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.ReceiptBusiness
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ResultDialog
import app.minimpos.app.ui.components.ScreenColumn
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.currentSetupGate
import app.minimpos.app.ui.theme.LocalDimens
import app.minimpos.app.ui.theme.Spacing

/**
 * Explicit Management lookup through the setup gate, followed by review. Confirmation fills missing fields or
 * explicitly replaces supplied fields.
 */
@Composable
internal fun ReceiptBusinessImport(
    state: ReceiptBusinessImportState,
    events: ReceiptBusinessEvents,
    hasExistingDetails: Boolean,
) {
    val gate = currentSetupGate()
    SettingIntro(stringResource(R.string.settings_business_import_hint))
    SettingActions {
        SecondaryButton(
            stringResource(R.string.settings_business_import),
            { gate.run(events::onFind) },
            loading = state.lookup.running,
            icon = Icons.Default.FileDownload,
            modifier = Modifier.testTag("findReceiptBusinesses"),
        )
        if (!state.resultPending) OutcomeMessage(state.lookup)
    }
    if (state.resultPending) {
        ResultDialog(
            title = stringResource(R.string.settings_business_import),
            message = state.lookup.outcome?.text() ?: stringResource(R.string.settings_changes_saved),
            isError = state.lookup.isError,
            tag = "businessImportResult",
            onDismiss = events::onResultDismiss,
        )
    }
    state.proposal?.let { business ->
        Dialog(
            onDismissRequest = { events.onChoose(null) },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            BusinessReviewScreen(business, hasExistingDetails, events::onChoose)
        }
    }
}

/** Reviews imported fields without saving until the pinned Import action is pressed. */
@Composable
internal fun BusinessReviewScreen(
    business: ReceiptBusiness,
    hasExistingDetails: Boolean,
    onChoose: (ReceiptBusiness?, Boolean) -> Unit,
) {
    var replace by rememberSaveable(business, hasExistingDetails) { mutableStateOf(false) }
    val dismiss = { onChoose(null, false) }
    MiniScaffold(
        title = stringResource(R.string.settings_business_review),
        onBack = dismiss,
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            BottomActions {
                Row(horizontalArrangement = Arrangement.spacedBy(LocalDimens.current.spacing)) {
                    SecondaryButton(stringResource(R.string.action_cancel), dismiss, Modifier.weight(1f))
                    PrimaryButton(
                        stringResource(R.string.transfer_import_action),
                        { onChoose(business, replace && hasExistingDetails) },
                        enabled = business.available,
                        modifier = Modifier.weight(1f).testTag("confirmReceiptBusiness"),
                    )
                }
            }
        },
    ) { padding ->
        ScreenColumn(padding) {
            BusinessReview(business)
            if (hasExistingDetails) {
                Column(Modifier.selectableGroup()) {
                    BusinessImportChoice(false, replace) { replace = false }
                    BusinessImportChoice(true, replace) { replace = true }
                }
            }
        }
    }
}

@Composable
private fun BusinessImportChoice(
    replace: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = replace == selected, onClick = onSelect, role = Role.RadioButton)
            .testTag(if (replace) "replaceReceiptBusiness" else "supplementReceiptBusiness"),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = replace == selected, onClick = null)
        Text(stringResource(if (replace) R.string.settings_business_replace else R.string.settings_business_supplement))
    }
}

@Composable
private fun ColumnScope.BusinessReview(business: ReceiptBusiness) {
    BusinessField(stringResource(R.string.settings_business_name), business.name)
    BusinessField(stringResource(R.string.settings_address), business.address)
    BusinessField(stringResource(R.string.settings_phone), business.phone)
    SettingNote(stringResource(R.string.settings_business_review_hint))
    if (!business.available) Notice(stringResource(R.string.settings_business_unavailable), kind = NoticeKind.WARNING, inset = true)
}

@Composable
private fun BusinessField(
    label: String,
    value: String?,
) {
    if (value.isNullOrBlank()) return
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
