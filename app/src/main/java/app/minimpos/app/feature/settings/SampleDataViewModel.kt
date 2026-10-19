package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import app.minimpos.app.data.repo.SampleData
import app.minimpos.app.feature.SampleDataState
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.feature.sampleDataWrite
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings' offline sample-data actions, independent of connection tests and secret configuration.
 * @param samples Creates/removes tracked demo rows and applies fictional receipt business details on creation requests.
 */
class SampleDataViewModel(
    private val samples: SampleData,
) : ViewModel() {
    /** Progress and typed result of the latest sample-data action. */
    val state: StateFlow<SampleDataState>
        field = MutableStateFlow(SampleDataState())

    /** Adds catalog/history samples and replaces receipt business details without switching destination. */
    fun populate() =
        write {
            samples.populate()
            true
        }

    /** Removes only tracked sample catalog/history rows. */
    fun purge() =
        write {
            samples.purge()
            false
        }

    /** Consumes the completed result; never clears an in-progress operation. */
    fun dismissResult() {
        if (!state.value.running) state.value = SampleDataState()
    }

    private fun write(operation: suspend () -> Boolean) {
        if (state.value.running) return
        state.value = SampleDataState(running = true)
        launchWrite({
            sampleDataWrite(operation)
        }) { result ->
            state.value = SampleDataState(added = result.getOrNull(), failed = result.isFailure)
        }
    }
}
