package app.minimpos.app.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.minimpos.app.data.settings.ReceiptLogoStore
import app.minimpos.app.feature.persisting
import app.minimpos.core.logo.ArgbImage
import app.minimpos.core.logo.LogoConverter
import app.minimpos.core.logo.LogoOptions
import app.minimpos.core.logo.LogoResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The selected options and the latest converted preview.
 *
 * @property options The controls as selected now.
 * @property result The latest finished conversion; null before the first one finishes. While [pending], it was made
 *   from earlier options and stays shown so the editor's layout does not jump.
 * @property pending Whether a conversion of [options] is still scheduled or running.
 */
internal data class LogoEditorState(
    val options: LogoOptions = LogoOptions(),
    val result: LogoResult? = null,
    val pending: Boolean = false,
) {
    /** True only when the displayed bitmap was converted from [options] and can be saved. */
    val canSave: Boolean get() = !pending && result is LogoResult.Converted
}

/**
 * Converts a chosen [source] on [cpu], debouncing changes for 150 ms, and saves the displayed bitmap to [store].
 * The source stays in memory for this editor only. Updates must be called on the main thread.
 */
internal class ReceiptLogoEditorViewModel(
    private val source: ArgbImage,
    private val store: ReceiptLogoStore,
    private val cpu: CoroutineDispatcher,
) : ViewModel() {
    // Conversion is synchronous: cancellation discards a result but cannot interrupt its image buffers mid-pass.
    // Serialize work so rapid slider changes never allocate several full-size conversions at once.
    private val conversionMutex = Mutex()
    private var conversion: Job? = null

    /** Current controls and preview; conversion never publishes a stale result as current. */
    val state: StateFlow<LogoEditorState>
        field = MutableStateFlow(LogoEditorState(pending = true))

    init {
        convert(LogoOptions(), false)
    }

    /**
     * Replaces the controls and schedules conversion. The previous preview stays shown, and save stays disabled, until
     * the new preview is ready.
     */
    fun update(options: LogoOptions) = convert(options, true)

    private fun convert(
        options: LogoOptions,
        debounce: Boolean,
    ) {
        conversion?.cancel()
        state.value = state.value.copy(options = options, pending = true)
        conversion =
            viewModelScope.launch {
                if (debounce) delay(CONVERSION_DEBOUNCE_MS)
                val result =
                    withContext(cpu) {
                        conversionMutex.withLock {
                            currentCoroutineContext().ensureActive()
                            LogoConverter.convert(source, options)
                        }
                    }
                val resolved =
                    if (result is LogoResult.Converted) {
                        options.copy(threshold = options.threshold ?: result.threshold, invert = options.invert ?: result.inverted)
                    } else {
                        options
                    }
                state.value = LogoEditorState(resolved, result)
            }
    }

    /** Saves exactly the current preview, finishing the write if the editor closes. Blank/pending previews do nothing. */
    suspend fun save() {
        val current = state.value
        val bitmap = (current.result as? LogoResult.Converted)?.bitmap
        if (current.pending || bitmap == null) return
        persisting { store.replace(bitmap) }
    }
}

private const val CONVERSION_DEBOUNCE_MS = 150L
