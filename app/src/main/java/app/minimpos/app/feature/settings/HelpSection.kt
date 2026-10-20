package app.minimpos.app.feature.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.ui.components.SecondaryButton

/** Guides and issue reporting, with expandable read-only checks for every integration. */
@Composable
internal fun ColumnScope.HelpSection(
    terminal: TerminalState,
    setup: TerminalSetupActions,
    events: TerminalSetupEvents,
    showDetails: Boolean = false,
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
    IntegrationTroubleshooting(terminal, setup, events::onDiagnosticsRefresh, showDetails)
}
