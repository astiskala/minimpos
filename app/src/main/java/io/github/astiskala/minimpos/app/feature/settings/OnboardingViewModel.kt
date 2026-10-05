package io.github.astiskala.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import io.github.astiskala.minimpos.app.data.repo.SampleData
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.feature.launchWrite
import io.github.astiskala.minimpos.app.payment.PricingChanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The first-run path; selecting import or terminal setup creates no samples and changes no connection settings. */
enum class OnboardingChoice {
    /** Use the offline simulator with sample catalog/history. */
    SIMULATOR,

    /** Open QR setup import. */
    IMPORT,

    /** Open terminal configuration. */
    TERMINAL,
}

/**
 * Persists a first-run choice before navigation; failed writes leave onboarding available for retry.
 * @param pricing Owns ordinary settings writes and pricing recovery.
 * @param samples Atomically adds tracked examples, without any network requests.
 * @param automaticMode The destination this device's automatic choice resolves to.
 */
class OnboardingViewModel(
    private val pricing: PricingChanges,
    private val samples: SampleData,
    private val automaticMode: TerminalMode,
) : ViewModel() {
    private val _state = MutableStateFlow(SampleDataState())

    /** Persistence progress; completion is delivered through [choose]'s callback. */
    val state: StateFlow<SampleDataState> = _state.asStateFlow()

    /** Completes setup then calls [onDone] with [choice]; ignores duplicate taps while saving. */
    fun choose(
        choice: OnboardingChoice,
        onDone: (OnboardingChoice) -> Unit,
    ) {
        if (_state.value.running) return
        _state.value = SampleDataState(running = true)
        launchWrite({
            sampleDataWrite {
                pricing.recover()
                if (choice == OnboardingChoice.SIMULATOR) pricing.withStableSettings { samples.populate(it) }
                pricing.update {
                    it.copy(
                        onboardingCompleted = true,
                        terminal =
                            if (choice == OnboardingChoice.SIMULATOR) {
                                it.terminal.selectDestination(TerminalMode.SIMULATOR, automaticMode)
                            } else {
                                it.terminal
                            },
                    )
                }
            }
        }) { result ->
            _state.value = SampleDataState(failed = result.isFailure)
            if (result.isSuccess) onDone(choice)
        }
    }
}
