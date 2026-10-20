package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.minimpos.app.BuildConfig
import app.minimpos.app.R
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.text
import app.minimpos.app.share.TroubleshootingShareEffect
import app.minimpos.app.terminal.ApiCheck
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.TertiaryButton
import app.minimpos.app.ui.theme.LocalDimens
import java.time.Instant

/** Help's collapsed support details. Each expansion refreshes checks; sharing uses exactly the displayed non-secret facts. */
@Composable
internal fun ColumnScope.IntegrationTroubleshooting(
    terminal: TerminalState,
    setup: TerminalSetupActions,
    onRefresh: () -> Unit,
    showDetails: Boolean = false,
) {
    var expanded by rememberSaveable { mutableStateOf(showDetails) }
    var report by remember { mutableStateOf<String?>(null) }
    val anchor = remember { BringIntoViewRequester() }
    var placed by remember { mutableStateOf(false) }
    val refresh by rememberUpdatedState(onRefresh)
    LaunchedEffect(placed) { if (showDetails && placed) anchor.bringIntoView() }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(anchor).onGloballyPositioned { placed = true }) {
        SettingActions {
            TertiaryButton(
                stringResource(R.string.diagnostics_details),
                { expanded = !expanded },
                Modifier.testTag("tapTroubleshootingDetails"),
            )
        }
        if (expanded) {
            LaunchedEffect(Unit) { refresh() }
            if (setup.checkingDiagnostics) CircularProgressIndicator(Modifier.testTag("diagnosticsLoading"))
            val rows = integrationRows(terminal, setup)
            val inset = LocalDimens.current.rowHorizontalPadding
            rows.forEach { (name, value) ->
                HorizontalDivider(Modifier.padding(horizontal = inset))
                Row(Modifier.fillMaxWidth().padding(horizontal = inset, vertical = 8.dp)) {
                    Text(name, Modifier.weight(0.45f).padding(end = 12.dp), style = MaterialTheme.typography.labelMedium)
                    Text(
                        value,
                        Modifier.weight(0.55f).testTag(if (name == "clientKeyCheck") "tapClientKeyStatus" else "diagnostic_$name"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            SettingNote(stringResource(R.string.diagnostics_report_notice))
            SettingActions {
                SecondaryButton(
                    stringResource(R.string.diagnostics_share_report),
                    { report = rows.joinToString("\n") { (name, value) -> "$name=$value" } },
                    Modifier.testTag("shareTapReport"),
                    enabled = !setup.checkingDiagnostics,
                )
            }
        }
    }
    TroubleshootingShareEffect(report) { report = null }
}

@Composable
@ReadOnlyComposable
private fun integrationRows(
    terminal: TerminalState,
    setup: TerminalSetupActions,
): List<Pair<String, String>> {
    val reading = setup.integrationDiagnostics
    val notChecked = stringResource(R.string.diagnostics_not_checked)
    return buildList {
        add("appVersion" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        add("apiAccess" to (reading?.api?.diagnosticText() ?: notChecked))
        add("connection" to (reading?.connection?.diagnosticText() ?: notChecked))
        val checkedAt = reading?.checkedAt?.let { Instant.ofEpochMilli(it).toString() } ?: notChecked
        add("checkedAt (UTC)" to checkedAt)
        if (reading == null) {
            add("integration" to terminal.mode.name)
            add("environment" to (terminal.environment?.name ?: notChecked))
        }
        reading?.facts?.forEach { (name, value) -> add(name to value.ifEmpty { notChecked }) }
        if (terminal.mode == TerminalMode.PAYMENTS_APP) addAll(phoneDetails(setup))
    }
}

@Composable
@ReadOnlyComposable
private fun ApiCheck.diagnosticText(): String =
    when (this) {
        ApiCheck.Works -> "OK"
        is ApiCheck.NotSetUp -> problem.name
        is ApiCheck.Failed -> failure.text(redactExternal = true)
    }

@Composable
@ReadOnlyComposable
private fun TerminalConnection.diagnosticText(): String =
    when (this) {
        TerminalConnection.Unknown -> stringResource(R.string.diagnostics_not_checked)
        TerminalConnection.Checking -> stringResource(R.string.settings_status_checking)
        is TerminalConnection.Connected -> "CONNECTED"
        is TerminalConnection.NotSetUp -> problem.name
        is TerminalConnection.Failed -> failure.text(redactExternal = true)
    }

@Composable
@ReadOnlyComposable
private fun phoneDetails(setup: TerminalSetupActions): List<Pair<String, String>> {
    val check = setup.diagnostics
    return buildList {
        add("clientKeyCheck" to check.clientKey.name)
        add("boardingStatus" to check.boarding.name)
        check.credential?.let {
            add("credential.id" to it.id)
            add("credential.username" to it.username)
            add("credential.active" to it.active.toString())
        }
        check.boardingIdentity?.let {
            it.credentialId?.let { id -> add("boardingCredential.id" to id) }
            it.username?.let { name -> add("boardingCredential.username" to name) }
            add("boardingInstallationId" to it.installationId)
            add("boardingMerchantAccount" to it.merchant)
            it.store?.let { store -> add("boardingStoreId" to store) }
            add("boardingCredentialAssociated" to check.associated.toString())
        }
        check.installationId?.let { add("reportedInstallationId" to it) }
        check.checkedAt?.let { add("credentialCheck (UTC)" to Instant.ofEpochMilli(it).toString()) }
        check.boardingAt?.let { add("boardingCheck (UTC)" to Instant.ofEpochMilli(it).toString()) }
        check.failure?.let { add("credentialError" to it.text(redactExternal = true)) }
    }
}
