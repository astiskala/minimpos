package app.minimpos.app.feature.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.minimpos.app.R
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.SampleDataState
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.ui.components.ActionMessage
import app.minimpos.app.ui.components.BottomActions
import app.minimpos.app.ui.components.Description
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.components.PrimaryButton
import app.minimpos.app.ui.components.ScreenColumn
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.LocalDimens

/** First-run setup choices, before Home; no credentials or merchant content are replaced. */
@Composable
fun OnboardingScreen(
    navigator: Navigator,
    vm: OnboardingViewModel = onboardingViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    OnboardingContent(state, LocalAppContainer.current.device.isAdyenTerminal) { choice ->
        vm.choose(choice) {
            when (it) {
                OnboardingChoice.SIMULATOR -> navigator.home()
                OnboardingChoice.IMPORT -> navigator.push(Route.TransferImport)
                OnboardingChoice.TERMINAL -> navigator.push(Route.SettingsSection(SettingsSections.TERMINAL))
            }
        }
    }
}

@Composable
private fun onboardingViewModel(): OnboardingViewModel {
    val container = LocalAppContainer.current
    return viewModel {
        OnboardingViewModel(
            container.pricingChanges,
            container.sampleData,
            if (container.device.isAdyenTerminal) TerminalMode.TERMINAL else TerminalMode.SIMULATOR,
        )
    }
}

/** Compact intent choices with one pinned action; terminals initially select Adyen, other devices the offline demo. */
@Composable
internal fun OnboardingContent(
    state: SampleDataState,
    onTerminal: Boolean,
    onChoose: (OnboardingChoice) -> Unit,
) {
    val choices =
        if (onTerminal) {
            listOf(OnboardingChoice.TERMINAL, OnboardingChoice.SIMULATOR, OnboardingChoice.IMPORT)
        } else {
            listOf(OnboardingChoice.SIMULATOR, OnboardingChoice.TERMINAL, OnboardingChoice.IMPORT)
        }
    var selected by rememberSaveable { mutableStateOf(choices.first()) }
    MiniScaffold(
        title = stringResource(R.string.onboarding_title),
        onBack = null,
        bottomBar = {
            BottomActions {
                PrimaryButton(
                    stringResource(R.string.onboarding_continue),
                    { onChoose(selected) },
                    modifier = Modifier.testTag("onboardingContinue"),
                    loading = state.running,
                )
            }
        },
    ) { padding ->
        ScreenColumn(padding, Modifier.selectableGroup()) {
            Description(stringResource(R.string.onboarding_hint))
            choices.forEach { choice ->
                OnboardingOption(choice, selected == choice, !state.running) { selected = choice }
            }
            if (state.failed) ActionMessage(stringResource(R.string.sample_write_failed), isError = true)
        }
    }
}

/** One accessible choice, keeping its explanation beside its selection control instead of separate from its action. */
@Composable
private fun OnboardingOption(
    choice: OnboardingChoice,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    val (title, hint, tag) =
        when (choice) {
            OnboardingChoice.SIMULATOR -> Triple(R.string.onboarding_simulator, R.string.onboarding_demo_hint, "onboardingSimulator")
            OnboardingChoice.TERMINAL -> Triple(R.string.onboarding_terminal, R.string.onboarding_connect_hint, "onboardingTerminal")
            OnboardingChoice.IMPORT -> Triple(R.string.onboarding_scan, R.string.onboarding_scan_hint, "onboardingImport")
        }
    val dimens = LocalDimens.current
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .testTag(tag)
                .padding(dimens.spacing),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f).padding(start = dimens.spacing)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
