package app.minimpos.app.data.repo

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordObservationsTest {
    @Test
    fun `equivalent query results do not repeat downstream mapping`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val source = MutableSharedFlow<List<Int>>(replay = 1)
            source.tryEmit(listOf(1))
            val reads = RecordObservations(dispatcher) { _: String -> source }
            val emitted = mutableListOf<List<Int>>()
            val job = backgroundScope.launch(dispatcher) { reads.observe("catalog").collect { emitted += it } }
            source.emit(listOf(1))
            source.emit(listOf(1, 2))
            source.emit(listOf(1, 2))
            assertThat(emitted).containsExactly(listOf(1), listOf(1, 2)).inOrder()
            job.cancel()
        }

    @Test
    fun `concurrent observers share one source and the last cancellation releases it`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            var queries = 0
            var open = 0
            val updates = MutableStateFlow<Int?>(null)
            val records =
                RecordObservations<Int?>(dispatcher) {
                    flow {
                        queries++
                        open++
                        try {
                            emitAll(updates)
                        } finally {
                            open--
                        }
                    }
                }
            val first = mutableListOf<Int?>()
            val second = mutableListOf<Int?>()
            val one = backgroundScope.launch(dispatcher) { records.observe("id").collect { first += it } }
            val two = backgroundScope.launch(dispatcher) { records.observe("id").collect { second += it } }
            assertThat(queries).isEqualTo(1)
            updates.value = 42
            assertThat(first).containsExactly(null, 42).inOrder()
            assertThat(second).containsExactly(null, 42).inOrder()
            one.cancel()
            assertThat(open).isEqualTo(1)
            two.cancel()
            assertThat(open).isEqualTo(0)
            val three = backgroundScope.launch(dispatcher) { records.observe("id").collect() }
            assertThat(queries).isEqualTo(2)
            three.cancel()
            assertThat(open).isEqualTo(0)
        }

    @Test
    fun `source failures reach every observer and a later subscription starts fresh`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val updates = MutableStateFlow(Result.success(1))
            var queries = 0
            val records =
                RecordObservations(dispatcher) { _: String ->
                    flow {
                        queries++
                        emitAll(updates.map { it.getOrThrow() })
                    }
                }
            val failures = mutableListOf<Throwable>()
            repeat(2) { backgroundScope.launch(dispatcher) { records.observe("id").catch { failures += it }.collect() } }
            val error = IllegalStateException("storage unavailable")
            updates.value = Result.failure(error)
            assertThat(failures).containsExactly(error, error)
            updates.value = Result.success(2)
            val values = mutableListOf<Int>()
            val again = backgroundScope.launch(dispatcher) { records.observe("id").collect { values += it } }
            assertThat(queries).isEqualTo(2)
            assertThat(values).containsExactly(2)
            again.cancel()
        }
}
