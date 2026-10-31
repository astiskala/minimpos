package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.GatedButton
import app.minimpos.app.feature.text
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.ui.components.BlockedReason
import app.minimpos.app.ui.components.InfoDialog
import app.minimpos.app.ui.components.Notice
import app.minimpos.app.ui.components.NoticeKind
import app.minimpos.app.ui.components.ResultDialog
import app.minimpos.app.ui.components.SectionHeader
import app.minimpos.app.ui.components.TertiaryButton
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer

private val CapabilityEditSaver =
    Saver<CapabilityEdit, String>(
        save = { Json.encodeToString(serializer<CapabilityEdit>(), it) },
        restore = { Json.decodeFromString(serializer<CapabilityEdit>(), it) },
    )

/** One independent remote draft, based on a successful read; refresh never keys or recreates its form. */
@Composable
internal fun ColumnScope.CapabilityEditor(
    feature: CapabilityFeature,
    reading: AdyenCapabilitiesState,
    actions: CapabilitiesActions,
    currency: String,
    edit: CapabilityEdit,
    onEdit: (CapabilityEdit) -> Unit,
    onRefresh: () -> Unit,
    onDismissResult: (CapabilityResult) -> Unit,
    onSave: (CapabilityDraft) -> Unit,
) {
    val result = checkNotNull(feature.editorResult)
    val action = actions.action(result)
    val current = CapabilityDraft.from(feature, reading, currency)
    var refreshedFrom by remember { mutableStateOf<Int?>(null) }
    val updateEdit by rememberUpdatedState(onEdit)
    LaunchedEffect(actions.readRevision) {
        if (refreshedFrom?.let { actions.readRevision > it } == true) {
            updateEdit(edit.refreshed(current))
            refreshedFrom = null
        }
    }
    CapabilitySaveResult(action.done, action.outcome, action.isError) { onDismissResult(result) }
    CapabilityRefresh(reading, actions.running) {
        refreshedFrom = actions.readRevision
        onRefresh()
    }
    SectionHeader(stringResource(R.string.capability_at_adyen))
    SettingNote(stringResource(R.string.capability_remote_draft))
    SettingNote(stringResource(R.string.capability_apply_note))
    CapabilityEditorFields(feature, edit.draft, currency, edit.draft.motoCurrency, !actions.running) { onEdit(edit.copy(draft = it)) }
    CapabilityEditorActions(feature, reading, actions, currency, edit, onEdit, onSave)
}

@Composable
internal fun rememberCapabilityEdit(
    feature: CapabilityFeature,
    reading: AdyenCapabilitiesState,
    actions: CapabilitiesActions,
    currency: String,
): MutableState<CapabilityEdit> {
    val current = CapabilityDraft.from(feature, reading, currency)
    val result = checkNotNull(feature.editorResult)
    val revision = actions.savedRevisions[result] ?: 0
    val state =
        rememberSaveable(reading.identity, currency, feature, stateSaver = CapabilityEditSaver) {
            mutableStateOf(CapabilityEdit(current, savedRevision = revision))
        }
    LaunchedEffect(current, revision) { state.value = state.value.read(current, revision) }
    val outcome = actions.action(result).outcome
    LaunchedEffect(outcome) {
        if (outcome is ActionOutcome.Unconfirmed) state.value = state.value.copy(unconfirmed = true)
    }
    return state
}

@Composable
private fun ColumnScope.CapabilityEditorActions(
    feature: CapabilityFeature,
    reading: AdyenCapabilitiesState,
    actions: CapabilitiesActions,
    currency: String,
    edit: CapabilityEdit,
    onEdit: (CapabilityEdit) -> Unit,
    onSave: (CapabilityDraft) -> Unit,
) {
    val result = checkNotNull(feature.editorResult)
    val current = CapabilityDraft.from(feature, reading, currency)
    val changedAtAdyen = edit.dirty && current != edit.baseline
    val valid = edit.draft.valid(feature, currency)
    CapabilityDraftNotices(edit.unconfirmed, changedAtAdyen, valid, reading.editable, edit.dirty)
    if (changedAtAdyen && !edit.unconfirmed) {
        SettingActions {
            TertiaryButton(
                stringResource(R.string.capability_keep_draft),
                { onEdit(edit.copy(baseline = current, draft = edit.draft.copy(motoCurrency = current.motoCurrency))) },
                enabled = !actions.running && reading.editable,
                modifier = Modifier.testTag("keepCapabilityDraft"),
            )
        }
    }
    SettingActions {
        GatedButton(
            stringResource(R.string.capability_save),
            { onSave(edit.draft) },
            blocked = null,
            enabled = edit.dirty && valid && reading.editable && !edit.unconfirmed && !changedAtAdyen && !actions.running,
            loading = actions.action(result).running,
            icon = Icons.Default.CloudUpload,
            requiresSetup = true,
            modifier = Modifier.testTag(result.saveTag),
        )
        if (edit.dirty || edit.unconfirmed) {
            TertiaryButton(
                stringResource(R.string.capability_reload),
                {
                    onEdit(CapabilityEdit(current, savedRevision = edit.savedRevision))
                },
                enabled = !actions.running && reading.editable && !edit.unconfirmed,
                modifier = Modifier.testTag("reloadCapabilityDraft"),
            )
        }
    }
}

@Composable
private fun ColumnScope.CapabilityDraftNotices(
    unconfirmed: Boolean,
    changedAtAdyen: Boolean,
    valid: Boolean,
    editable: Boolean,
    dirty: Boolean,
) {
    when {
        unconfirmed -> Notice(stringResource(R.string.capability_unknown_save), kind = NoticeKind.WARNING, inset = true)
        !editable -> Notice(stringResource(R.string.capability_need_refresh), kind = NoticeKind.WARNING, inset = true)
        changedAtAdyen -> Notice(stringResource(R.string.capability_changed_remote), kind = NoticeKind.WARNING, inset = true)
        !valid -> BlockedReason(stringResource(R.string.capability_invalid_draft))
        !dirty -> SettingNote(stringResource(R.string.capability_no_changes))
    }
}

@Composable
private fun CapabilitySaveResult(
    done: Boolean,
    outcome: ActionOutcome?,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    if (outcome is ActionOutcome.Unconfirmed) {
        InfoDialog(
            stringResource(R.string.capability_save),
            outcome.text() + "\n\n" + stringResource(R.string.capability_unknown_save),
            "capabilityResult",
            onDismiss,
        )
    } else if (done || outcome != null) {
        ResultDialog(
            title = stringResource(R.string.capability_save),
            message = outcome?.text() ?: stringResource(R.string.capability_saved),
            isError = isError,
            tag = "capabilityResult",
            onDismiss = onDismiss,
        )
    }
}
