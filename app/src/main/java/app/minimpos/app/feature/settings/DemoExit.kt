package app.minimpos.app.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader

/** Explicit demo-to-Adyen handoff; navigation never changes destinations, deletes samples or replaces receipt text. */
@Composable
internal fun DemoExit(onOpen: (String) -> Unit) {
    var review by rememberSaveable { mutableStateOf(false) }
    SettingActions {
        SecondaryButton(
            stringResource(R.string.demo_exit_title),
            { review = !review },
            icon = Icons.Default.PhonelinkSetup,
            modifier = Modifier.testTag("exitDemo"),
        )
    }
    if (review) {
        SectionHeader(stringResource(R.string.demo_exit_history))
        SettingNote(stringResource(R.string.demo_exit_history_hint))
        SettingNavRow(Icons.Default.Storage, stringResource(R.string.sample_data), stringResource(R.string.demo_exit_samples_hint), {
            onOpen(SettingsSections.DATA)
        }, tag = "reviewDemoSamples")
        SettingNavRow(Icons.Default.Receipt, stringResource(R.string.settings_receipts), stringResource(R.string.demo_exit_receipts_hint), {
            onOpen(SettingsSections.RECEIPTS)
        }, tag = "reviewDemoReceipts")
        SettingActions {
            SecondaryButton(
                stringResource(R.string.demo_exit_continue),
                { onOpen(SettingsSections.TERMINAL) },
                modifier = Modifier.testTag("continueDemoSetup"),
            )
            Text(stringResource(R.string.demo_exit_unchanged))
        }
    }
}
