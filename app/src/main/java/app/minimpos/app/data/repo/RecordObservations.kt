package app.minimpos.app.data.repo

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn

/** Shares one Room query per actively observed ID; the last observer releases both its scope and cached record. */
internal class RecordObservations<T>(
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val source: (String) -> Flow<T>,
) {
    private val active = mutableMapOf<String, Entry<T>>()

    /** Observes [id] on its own dispatcher; failures reach every current collector and release their references. */
    fun observe(id: String): Flow<T> =
        flow {
            val entry =
                synchronized(active) {
                    active
                        .getOrPut(id) {
                            val scope = CoroutineScope(cpu + SupervisorJob())
                            Entry(
                                scope,
                                source(id)
                                    .distinctUntilChanged()
                                    .map { Result.success(it) }
                                    .catch { emit(Result.failure(it)) }
                                    .shareIn(scope, SharingStarted.Eagerly, replay = 1),
                            )
                        }.also { it.observers++ }
                }
            try {
                emitAll(entry.values.map { it.getOrThrow() })
            } finally {
                synchronized(active) {
                    entry.observers--
                    if (entry.observers == 0) {
                        active.remove(id)
                        entry.scope.cancel()
                    }
                }
            }
        }

    private class Entry<T>(
        val scope: CoroutineScope,
        val values: Flow<Result<T>>,
        var observers: Int = 0,
    )
}
