package app.minimpos.app.feature.transfer

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.Card
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ScreenColumn
import app.minimpos.app.ui.components.SecondaryButton

/** Explains both code sources and the separate transfer code before the camera requests permission. */
@Composable
internal fun SetupCodesIntro(padding: PaddingValues) {
    ScreenColumn(padding, Modifier.testTag("setupCodesIntro")) {
        Card {
            Text(stringResource(R.string.setup_codes_computer), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.setup_codes_computer_hint), style = MaterialTheme.typography.bodySmall)
        }
        Card {
            Text(stringResource(R.string.setup_codes_device), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.setup_codes_device_hint), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.setup_codes_transfer_hint),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("setupTransferCodeHint"),
        )
    }
}

/** Camera opt-in and manual fallback, both reachable without scrolling the source instructions. */
@Composable
internal fun SetupCodesActions(
    onScan: () -> Unit,
    onManual: () -> Unit,
) {
    BottomActions {
        PrimaryButton(
            stringResource(R.string.setup_codes_ready),
            onScan,
            modifier = Modifier.testTag("startSetupScan"),
        )
        SecondaryButton(
            stringResource(R.string.setup_enter_manually),
            onManual,
            modifier = Modifier.testTag("manualSetup"),
        )
    }
}
