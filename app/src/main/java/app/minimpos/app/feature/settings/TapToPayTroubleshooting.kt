package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.feature.labels
import app.minimpos.app.share.TroubleshootingShareEffect
import app.minimpos.app.terminal.TapToPayCheck
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.components.SecondaryButton
import java.time.Instant

/**
 * Expandable advisory setup checks, shared by Settings and unsuccessful Payments app results. No payment or
 * registration side effects; callbacks explicitly request checks or a redacted share report.
 */
@Composable
internal fun ColumnScope.TapToPayTroubleshooting(
    check: TapToPayCheck,
    onReport: () -> Unit,
    report: String?,
    onReportConsume: () -> Unit,
    onTestConnection: (() -> Unit)? = null,
) {
    onTestConnection?.let { test ->
        SettingActions {
            SecondaryButton(
                stringResource(R.string.settings_test_connection),
                test,
                Modifier.testTag("testConnection"),
                loading = check.checking,
            )
        }
    }
    val labels = check.labels()
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton({ expanded = !expanded }, Modifier.testTag("tapTroubleshootingDetails")) {
        Text(stringResource(R.string.diagnostics_details))
    }
    if (expanded) {
        SettingNote(labels.clientKey, Modifier.testTag("tapClientKeyStatus"))
        check.credential?.let { credential ->
            SettingNote(stringResource(R.string.tap_credential_verified, credential.username))
            if (!credential.active) SettingNote(stringResource(R.string.tap_credential_inactive))
        }
        val originalUsername = check.boardingIdentity?.username
        originalUsername?.let { SettingNote(stringResource(R.string.tap_boarding_credential, it)) }
        val association =
            when {
                check.associated -> R.string.tap_boarding_known
                originalUsername != null -> R.string.tap_boarding_unmatched
                else -> R.string.tap_boarding_unknown
            }
        SettingNote(stringResource(association))
        SettingNote(labels.boarding)
        check.checkedAt?.let { SettingNote(stringResource(R.string.tap_check_time, Instant.ofEpochMilli(it).toString())) }
        check.boardingAt?.let { SettingNote(stringResource(R.string.tap_boarding_time, Instant.ofEpochMilli(it).toString())) }
        SettingNote(stringResource(R.string.diagnostics_report_notice))
        SettingActions {
            SecondaryButton(stringResource(R.string.diagnostics_share_report), onReport, Modifier.testTag("shareTapReport"))
        }
    }
    TroubleshootingShareEffect(report, onReportConsume)
}

/** Guides and issue reporting, with expandable read-only checks for every integration. */
@Composable
internal fun ColumnScope.HelpSection(
    terminal: TerminalState,
    setup: TerminalSetupActions,
    events: TerminalSetupEvents,
) {
    val uri = LocalUriHandler.current
    SettingActions {
        SecondaryButton(
            stringResource(R.string.help_user_guide),
            { uri.openUri("https://minimpos.app/using.html") },
            Modifier.testTag("helpUserGuide"),
            icon = Icons.AutoMirrored.Filled.MenuBook,
        )
        SecondaryButton(
            stringResource(R.string.help_troubleshooting),
            { uri.openUri("https://minimpos.app/troubleshooting.html") },
            Modifier.testTag("helpTroubleshooting"),
            icon = Icons.AutoMirrored.Filled.HelpOutline,
        )
        SecondaryButton(
            stringResource(R.string.help_report_issue),
            { uri.openUri("https://github.com/astiskala/minimpos/issues") },
            Modifier.testTag("helpReportIssue"),
            icon = Icons.Default.BugReport,
        )
    }
    IntegrationTroubleshooting(terminal, setup, events::onDiagnosticsRefresh)
}
