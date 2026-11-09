package app.minimpos.app.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.terminal.TerminalState

/** Why a software-dependent switch cannot be enabled; null only when this destination establishes support. */
@Composable
internal fun terminalSoftwareBlocked(
    terminal: TerminalState,
    minimum: String?,
): String? =
    when {
        !terminal.capabilities.supportsTerminalFeatures -> stringResource(R.string.settings_unavailable_tap_to_pay)
        terminal.mode == TerminalMode.SIMULATOR -> null
        minimum == null -> stringResource(R.string.settings_unavailable_destination)
        terminal.supportsSoftware(minimum) -> null
        terminal.softwareVersion == null -> stringResource(R.string.settings_software_check_required)
        else -> stringResource(R.string.settings_software_required, minimum, terminal.softwareVersion)
    }
