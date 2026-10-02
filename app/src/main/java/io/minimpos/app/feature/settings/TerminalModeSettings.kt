package io.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.minimpos.app.R
import io.minimpos.app.feature.ActionState
import io.minimpos.app.feature.OutcomeMessage
import io.minimpos.app.ui.components.LabeledValue
import io.minimpos.app.ui.components.PrimaryButton
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.app.ui.components.SectionHeader
import io.minimpos.app.ui.components.TertiaryButton
import io.minimpos.app.ui.theme.LocalDimens
import io.minimpos.terminal.transport.TerminalEnvironment

/**
 * The terminal in the cloud: its POIID, typed or chosen from the terminals connected to the merchant account
 * ([TerminalSetupActions.connectedTerminals], found with [onFindTerminals] and offered in a list until
 * [onChooseTerminal]), and the connection test ([testing] while it runs).
 */
@Composable
internal fun ColumnScope.CloudTerminalSettings(
    poiId: String,
    setup: TerminalSetupActions,
    testing: Boolean,
    onPoiId: (String) -> Unit,
    onFindTerminals: () -> Unit,
    onChooseTerminal: (poiId: String?) -> Unit,
    onTestConnection: () -> Unit,
) {
    SectionHeader(stringResource(R.string.settings_cloud_terminal))
    SettingTextField(
        label = stringResource(R.string.settings_poiid),
        value = poiId,
        onCommit = { onPoiId(it.trim()) },
        placeholder = stringResource(R.string.settings_poiid_hint),
        supporting = stringResource(R.string.settings_cloud_poiid_help),
        autoCorrect = false,
        imeAction = ImeAction.Done,
        tag = "poiId",
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = LocalDimens.current.spacing)) {
        SecondaryButton(
            stringResource(R.string.settings_find_terminals),
            onFindTerminals,
            loading = setup.terminals.running,
            modifier = Modifier.testTag("findTerminals"),
        )
        SecondaryButton(
            stringResource(R.string.settings_test_connection),
            onTestConnection,
            loading = testing,
            modifier = Modifier.testTag("testConnection"),
        )
        OutcomeMessage(setup.terminals, Modifier.testTag("terminalsResult"))
    }
    setup.connectedTerminals?.let { TerminalChoiceDialog(it, onChooseTerminal) }
}

/** The terminals connected in the cloud, to choose one; [onChoose] gets null when the dialog is dismissed. */
@Composable
private fun TerminalChoiceDialog(
    poiIds: List<String>,
    onChoose: (poiId: String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text(stringResource(R.string.settings_choose_terminal)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (poiIds.isEmpty()) Text(stringResource(R.string.settings_no_terminals))
                poiIds.forEach { poiId ->
                    TextButton({ onChoose(poiId) }, Modifier.fillMaxWidth().testTag("terminal_$poiId")) { Text(poiId) }
                }
            }
        },
        confirmButton = { TextButton({ onChoose(null) }) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Tap to Pay with the Adyen Payments app on this phone: which Payments app is installed ([paymentsApps]), the boarded
 * [installationId] (blank until boarded), the optional [storeId], the Payments app API key (only kept in memory while
 * typed, and cleared once stored), and the buttons that set it up ([onSetUp] gets the key typed, or an empty one, and
 * whether it is boarded already) or remove this phone.
 */
@Composable
internal fun ColumnScope.TapToPaySettings(
    paymentsApps: Set<TerminalEnvironment>,
    installationId: String,
    storeId: String,
    apiKeySaved: Boolean,
    setup: TerminalSetupActions,
    onStoreId: (String) -> Unit,
    onSetUp: (apiKey: String, again: Boolean) -> Unit,
    onRemove: () -> Unit,
    onForgetApiKey: () -> Unit,
) {
    var apiKey by remember { mutableStateOf("") }
    LaunchedEffect(setup.paymentsAppKeyStored) { if (setup.paymentsAppKeyStored) apiKey = "" }
    val boarded = installationId.isNotBlank()
    SectionHeader(stringResource(R.string.settings_tap_to_pay))
    SettingNote(stringResource(R.string.settings_tap_to_pay_hint))
    Column(Modifier.padding(horizontal = 16.dp)) {
        LabeledValue(stringResource(R.string.settings_payments_app), paymentsAppLabel(paymentsApps))
        LabeledValue(
            stringResource(R.string.settings_installation_id),
            installationId.ifBlank { stringResource(R.string.settings_not_boarded) },
        )
    }
    SettingTextField(
        label = stringResource(R.string.settings_store_id),
        value = storeId,
        onCommit = { onStoreId(it.trim()) },
        supporting = stringResource(R.string.settings_store_id_hint),
        autoCorrect = false,
        imeAction = ImeAction.Next,
        tag = "storeId",
    )
    SecretField(
        label = stringResource(R.string.settings_payments_app_key),
        isSet = apiKeySaved,
        value = apiKey,
        onValueChange = { apiKey = it },
        onSubmit = { onSetUp(apiKey, boarded) },
        tag = "paymentsAppKey",
    )
    SettingNote(stringResource(R.string.settings_payments_app_key_hint))
    TapToPayButtons(boarded, apiKeySaved, setup.tapToPay, { again -> onSetUp(apiKey, again) }, onRemove, onForgetApiKey)
}

/** Set up (or again, once [boarded]), remove this phone and forget the saved API key, with the last [result]. */
@Composable
private fun TapToPayButtons(
    boarded: Boolean,
    apiKeySaved: Boolean,
    result: ActionState,
    onSetUp: (again: Boolean) -> Unit,
    onRemove: () -> Unit,
    onForgetApiKey: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = LocalDimens.current.spacing)) {
        val tag = Modifier.testTag("setUpTapToPay")
        if (boarded) {
            SecondaryButton(stringResource(R.string.settings_set_up_again), { onSetUp(true) }, tag, loading = result.running)
            TertiaryButton(stringResource(R.string.settings_remove_phone), onRemove, Modifier.testTag("removePhone"), destructive = true)
        } else {
            PrimaryButton(stringResource(R.string.settings_set_up_tap_to_pay), { onSetUp(false) }, tag, loading = result.running)
        }
        if (apiKeySaved) {
            TertiaryButton(
                stringResource(R.string.settings_forget_payments_app_key),
                onForgetApiKey,
                Modifier.testTag("forgetPaymentsAppKey"),
                destructive = true,
            )
        }
        OutcomeMessage(result, Modifier.testTag("tapToPayResult"))
    }
}

/** Which Adyen Payments app is installed, for display. */
@Composable
private fun paymentsAppLabel(paymentsApps: Set<TerminalEnvironment>): String =
    when (paymentsApps.singleOrNull()) {
        TerminalEnvironment.TEST -> stringResource(R.string.settings_env_test)
        TerminalEnvironment.LIVE -> stringResource(R.string.settings_env_live)
        null -> stringResource(if (paymentsApps.isEmpty()) R.string.settings_payments_app_none else R.string.settings_payments_app_both)
    }
