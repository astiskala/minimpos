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

/** The selected options and the exact converted preview; null means conversion is still running. */
internal data class LogoEditorState(
    val options: LogoOptions = LogoOptions(),
    val result: LogoResult? = null,
) {
    /** True only when the displayed bitmap can be saved. */
    val canSave: Boolean get() = result is LogoResult.Converted
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

    /** Current controls and preview; conversion never publishes a stale result. */
    val state: StateFlow<LogoEditorState>
        field = MutableStateFlow(LogoEditorState())

    init {
        convert(LogoOptions(), false)
    }

    /** Replaces the controls and schedules conversion; save stays disabled until the new preview is ready. */
    fun update(options: LogoOptions) = convert(options, true)

    private fun convert(
        options: LogoOptions,
        debounce: Boolean,
    ) {
        conversion?.cancel()
        state.value = LogoEditorState(options)
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
        val bitmap = (state.value.result as? LogoResult.Converted)?.bitmap ?: return
        persisting { store.replace(bitmap) }
    }
}

private const val CONVERSION_DEBOUNCE_MS = 150L
