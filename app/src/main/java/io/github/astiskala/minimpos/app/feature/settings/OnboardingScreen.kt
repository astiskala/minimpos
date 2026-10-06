package io.github.astiskala.minimpos.app.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.PhonelinkSetup
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.ui.components.ActionMessage
import io.github.astiskala.minimpos.app.ui.components.BottomActions
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.components.MiniScaffold
import io.github.astiskala.minimpos.app.ui.components.PrimaryButton
import io.github.astiskala.minimpos.app.ui.components.SecondaryButton
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.LocalDimens

/** First-run setup choices, before Home; no credentials or merchant content are replaced. */
@Composable
fun OnboardingScreen(
    navigator: Navigator,
    vm: OnboardingViewModel = onboardingViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    OnboardingContent(state) { choice ->
        vm.choose(choice) {
            when (it) {
                OnboardingChoice.SIMULATOR -> navigator.home()
                OnboardingChoice.IMPORT -> navigator.replace(Route.TransferImport)
                OnboardingChoice.TERMINAL -> navigator.replace(Route.SettingsSection(SettingsSections.TERMINAL))
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

/** Stateless first-run content; all three choices fit the smallest terminal's action area. */
@Composable
internal fun OnboardingContent(
    state: SampleDataState,
    onChoose: (OnboardingChoice) -> Unit,
) {
    val dimens = LocalDimens.current
    MiniScaffold(
        title = stringResource(R.string.onboarding_title),
        onBack = null,
        bottomBar = {
            BottomActions {
                PrimaryButton(
                    stringResource(R.string.onboarding_simulator),
                    { onChoose(OnboardingChoice.SIMULATOR) },
                    modifier = Modifier.testTag("onboardingSimulator"),
                    loading = state.running,
                    icon = Icons.Default.Science,
                )
                SecondaryButton(
                    stringResource(R.string.transfer_import),
                    { onChoose(OnboardingChoice.IMPORT) },
                    modifier = Modifier.testTag("onboardingImport"),
                    enabled = !state.running,
                    icon = Icons.Default.FileDownload,
                )
                SecondaryButton(
                    stringResource(R.string.onboarding_terminal),
                    { onChoose(OnboardingChoice.TERMINAL) },
                    modifier = Modifier.testTag("onboardingTerminal"),
                    enabled = !state.running,
                    icon = Icons.Default.PhonelinkSetup,
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(dimens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(dimens.spacing),
        ) {
            Text(stringResource(R.string.onboarding_hint), style = MaterialTheme.typography.bodyLarge)
            if (state.failed) ActionMessage(stringResource(R.string.sample_write_failed), isError = true)
        }
    }
}
