package app.minimpos.app.feature.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.SectionHeader

/** Sample controls grouped separately from ordinary settings and connection-test callbacks. */
internal interface SampleDataEvents {
    /** Adds tracked catalog/history samples. */
    fun onAdd()

    /** Removes only tracked sample rows. */
    fun onPurge()
}

/** Screen-level factory for the sample-data settings actions. */
@Composable
internal fun sampleDataViewModel(): SampleDataViewModel {
    val container = LocalAppContainer.current
    return viewModel { SampleDataViewModel(container.pricingChanges, container.sampleData) }
}

/** Sample creation and confirmed scoped removal; never changes the payment destination. */
@Composable
internal fun SampleDataSection(
    state: SampleDataState,
    events: SampleDataEvents,
) {
    SectionHeader(stringResource(R.string.sample_data))
    SettingActions {
        Text(stringResource(R.string.sample_data_hint), style = MaterialTheme.typography.bodySmall)
        SecondaryButton(
            stringResource(R.string.sample_add),
            events::onAdd,
            modifier = Modifier.testTag("addSamples"),
            loading = state.running,
            icon = Icons.Default.Science,
        )
        ConfirmedRemoval(
            text = stringResource(R.string.sample_remove),
            confirmTitle = stringResource(R.string.sample_remove),
            confirmMessage = stringResource(R.string.sample_remove_message),
            onConfirm = events::onPurge,
            modifier = Modifier.testTag("purgeSamples"),
            enabled = !state.running,
        )
        if (state.failed) {
            ActionMessage(stringResource(R.string.sample_write_failed), isError = true)
        } else {
            state.added?.let {
                ActionMessage(stringResource(if (it) R.string.sample_added else R.string.sample_removed), isError = false)
            }
        }
    }
}
