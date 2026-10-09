package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import app.minimpos.app.data.repo.SampleData
import app.minimpos.app.feature.launchWrite
import app.minimpos.app.payment.PricingChanges
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings' offline sample-data actions, independent of connection tests and secret configuration.
 * @param pricing Serializes sample pricing against currency/tax-style changes.
 * @param samples Creates and removes tracked demo data without changing merchant records.
 */
class SampleDataViewModel(
    private val pricing: PricingChanges,
    private val samples: SampleData,
) : ViewModel() {
    private val _state = MutableStateFlow(SampleDataState())

    /** Progress and typed result of the latest sample-data action. */
    val state: StateFlow<SampleDataState> = _state.asStateFlow()

    /** Adds catalog/history samples without switching destination or replacing merchant data. */
    fun populate() =
        write {
            pricing.withStableSettings { samples.populate(it) }
            true
        }

    /** Removes only tracked sample catalog/history rows. */
    fun purge() =
        write {
            samples.purge()
            false
        }

    private fun write(operation: suspend () -> Boolean) {
        if (_state.value.running) return
        _state.value = SampleDataState(running = true)
        launchWrite({
            sampleDataWrite(operation)
        }) { result ->
            _state.value = SampleDataState(added = result.getOrNull(), failed = result.isFailure)
        }
    }
}
