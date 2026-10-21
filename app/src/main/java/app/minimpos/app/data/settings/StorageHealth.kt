package app.minimpos.app.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import java.io.IOException

/** Whether both configuration files were read successfully; an unreadable file stays blocked until app restart. */
enum class StorageState {
    /** Both files have not yet been read. No protected UI or startup work may proceed. */
    LOADING,

    /** Settings and encrypted secrets were read successfully. */
    READY,

    /** A file could not be read or written. Its contents are preserved for recovery. */
    UNAVAILABLE,
}

/**
 * Fails closed on configuration-file errors without replacing credentials, PINs or recovery journals. Readers wait
 * rather than emitting defaults; failed writes cancel their caller so no success continuation runs. No operation is
 * automatically replayed after an uncertain write. A fresh process can retry the original files.
 */
class StorageHealth {
    /** Process-local storage readiness; failure is latched, even if a different file subsequently loads. */
    val state: StateFlow<StorageState>
        field = MutableStateFlow(StorageState.LOADING)

    /**
     * Wraps a single-owner [store] without altering its file or serializing additional data. All its reads and writes
     * must use the returned store. Cancellation and non-storage exceptions propagate unchanged.
     */
    internal fun <T> protect(store: DataStore<T>): DataStore<T> =
        object : DataStore<T> {
            override val data: Flow<T> =
                store.data
                    .onEach {
                        if (state.value == StorageState.UNAVAILABLE) awaitCancellation()
                    }.catch { failure ->
                        if (failure !is IOException) throw failure
                        state.value = StorageState.UNAVAILABLE
                        awaitCancellation()
                    }

            override suspend fun updateData(transform: suspend (t: T) -> T): T {
                if (state.value == StorageState.UNAVAILABLE) throw CancellationException("Local storage unavailable")
                return try {
                    store.updateData(transform)
                } catch (failure: IOException) {
                    state.value = StorageState.UNAVAILABLE
                    throw CancellationException("Local storage unavailable", failure)
                }
            }
        }

    /** Reads both protected stores before publishing readiness; a failure never becomes an empty installation. */
    internal suspend fun check(
        settings: DataStore<AppSettings>,
        secrets: DataStore<*>,
    ) {
        settings.data.first()
        secrets.data.first()
        state.compareAndSet(StorageState.LOADING, StorageState.READY)
    }

    /** Waits until both files are readable; unavailable storage never permits startup recovery or terminal checks. */
    suspend fun awaitReady() {
        state.first { it == StorageState.READY }
    }
}
