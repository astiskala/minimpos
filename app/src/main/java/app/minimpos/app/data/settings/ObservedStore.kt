package app.minimpos.app.data.settings

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update

/**
 * A [DataStore] whose [data] always catches up with a completed [update]. Every write to the store must go through
 * [update].
 *
 * DataStore 1.2.1 can leave a [DataStore.data] collector that starts while a write is underway on the earlier value
 * until the next write: its first read takes the version the write has already raised, then skips the write's own
 * value as not newer. [data] therefore collects the store again after each completed [update]; a fresh read after the
 * write sees it.
 *
 * @param T The stored value's type; equal values are not emitted again.
 * @param store The store, used by nothing else.
 */
internal class ObservedStore<T>(
    private val store: DataStore<T>,
) {
    private val writes = MutableStateFlow(0L)

    /** The stored value now and after every change, without repeating an unchanged value. */
    val data: Flow<T> =
        channelFlow { writes.collectLatest { store.data.collect { value -> send(value) } } }.distinctUntilChanged()

    /** Atomically replaces the stored value with [transform] applied to it, and returns the new value. */
    suspend fun update(transform: suspend (T) -> T): T = store.updateData(transform).also { writes.update { count -> count + 1 } }
}
