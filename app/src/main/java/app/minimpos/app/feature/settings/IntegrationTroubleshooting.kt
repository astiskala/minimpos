package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.BuildConfig
import app.minimpos.app.R
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.labels
import app.minimpos.app.feature.text
import app.minimpos.app.share.TroubleshootingShareEffect
import app.minimpos.app.terminal.ApiCheck
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.components.SecondaryButton
import java.time.Instant

/** Help's collapsed support details. Each expansion refreshes checks; sharing uses exactly the displayed non-secret facts. */
@Composable
internal fun ColumnScope.IntegrationTroubleshooting(
    terminal: TerminalState,
    setup: TerminalSetupActions,
    onRefresh: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    val refresh by rememberUpdatedState(onRefresh)
    TextButton({ expanded = !expanded }, Modifier.testTag("tapTroubleshootingDetails")) {
        Text(stringResource(R.string.diagnostics_details))
    }
    if (expanded) {
        LaunchedEffect(Unit) { refresh() }
        if (setup.checkingDiagnostics) CircularProgressIndicator(Modifier.testTag("diagnosticsLoading"))
        val phone = terminal.mode == TerminalMode.PAYMENTS_APP
        val clientLabel = if (phone) setup.diagnostics.labels().clientKey else null
        val lines = integrationLines(terminal, setup)
        lines.forEach { SettingNote(it, if (it == clientLabel) Modifier.testTag("tapClientKeyStatus") else Modifier) }
        SettingActions {
            SecondaryButton(
                stringResource(R.string.diagnostics_share_report),
                { report = lines.joinToString("\n") },
                Modifier.testTag("shareTapReport"),
                enabled = !setup.checkingDiagnostics,
            )
        }
    }
    TroubleshootingShareEffect(report) { report = null }
}

@Composable
@ReadOnlyComposable
private fun integrationLines(
    terminal: TerminalState,
    setup: TerminalSetupActions,
): List<String> {
    val reading = setup.integrationDiagnostics
    val notChecked = stringResource(R.string.diagnostics_not_checked)
    return buildList {
        add("appVersion=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        add(stringResource(R.string.diagnostics_api, reading?.api?.diagnosticText() ?: notChecked))
        add(stringResource(R.string.diagnostics_connection, reading?.connection?.diagnosticText() ?: notChecked))
        val checkedAt = reading?.checkedAt?.let { Instant.ofEpochMilli(it).toString() } ?: notChecked
        add(stringResource(R.string.diagnostics_checked_at, checkedAt))
        if (reading == null) {
            add("integration=${terminal.mode.name}")
            add("environment=${terminal.environment?.name ?: notChecked}")
        }
        reading?.facts?.forEach { (name, value) -> add("$name=${value.ifEmpty { notChecked }}") }
        terminal.setupProblem?.let { add(Failure.NotSetUp(it).text()) }
        if (terminal.mode == TerminalMode.PAYMENTS_APP) addAll(phoneDetails(setup))
        add(stringResource(R.string.diagnostics_limits))
        if (!terminal.capabilities.diagnoses) add(stringResource(R.string.diagnostics_no_diagnosis))
        if (!terminal.capabilities.supportsRemoteStatus) add(stringResource(R.string.diagnostics_no_status))
        add(stringResource(R.string.diagnostics_report_notice))
    }
}

@Composable
@ReadOnlyComposable
private fun ApiCheck.diagnosticText(): String =
    when (this) {
        ApiCheck.Works -> ActionOutcome.ApiWorks.text()
        is ApiCheck.NotSetUp -> Failure.NotSetUp(problem).text()
        is ApiCheck.Failed -> failure.text(redactExternal = true)
    }

@Composable
@ReadOnlyComposable
private fun TerminalConnection.diagnosticText(): String =
    when (this) {
        TerminalConnection.Unknown -> stringResource(R.string.diagnostics_not_checked)
        TerminalConnection.Checking -> stringResource(R.string.settings_status_checking)
        is TerminalConnection.Connected -> ActionOutcome.Connected(diagnosis.globalStatus, diagnosis.hasPrinter, setupOnly).text()
        is TerminalConnection.NotSetUp -> Failure.NotSetUp(problem).text()
        is TerminalConnection.Failed -> "${stringResource(R.string.settings_connection_failed)}: ${failure.text(redactExternal = true)}"
    }

@Composable
@ReadOnlyComposable
private fun phoneDetails(setup: TerminalSetupActions): List<String> {
    val check = setup.diagnostics
    val labels = check.labels()
    return buildList {
        add(labels.clientKey)
        add(labels.boarding)
        add("clientKeyCheck=${check.clientKey.name}")
        add("boardingStatus=${check.boarding.name}")
        check.credential?.let {
            add("credential.id=${it.id}")
            add("credential.username=${it.username}")
            add(stringResource(R.string.tap_credential_verified, it.username))
            add("credential.active=${it.active}")
            if (!it.active) add(stringResource(R.string.tap_credential_inactive))
        }
        check.boardingIdentity?.let {
            add("boardingCredential.id=${it.credentialId.orEmpty()}")
            add("boardingCredential.username=${it.username.orEmpty()}")
            add("boardingInstallationId=${it.installationId}")
            add("boardingMerchantAccount=${it.merchant}")
            add("boardingStoreId=${it.store.orEmpty()}")
            add("boardingCredentialAssociated=${check.associated}")
            add(stringResource(if (check.associated) R.string.tap_boarding_known else R.string.tap_boarding_unmatched))
        } ?: add(stringResource(R.string.tap_boarding_unknown))
        check.installationId?.let { add("reportedInstallationId=$it") }
        check.checkedAt?.let { add(stringResource(R.string.tap_check_time, Instant.ofEpochMilli(it).toString())) }
        check.boardingAt?.let { add(stringResource(R.string.tap_boarding_time, Instant.ofEpochMilli(it).toString())) }
        check.failure?.let { add(it.text(redactExternal = true)) }
    }
}
