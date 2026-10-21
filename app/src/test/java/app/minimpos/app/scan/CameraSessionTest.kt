package app.minimpos.app.scan

import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

class CameraSessionTest {
    private val queued = ArrayDeque<Runnable>()
    private val main = Executor { queued.addLast(it) }

    private fun drain() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    @Test
    fun `closing before initialization never waits or binds when the future completes`() {
        val future = PendingFuture<String>()
        var binds = 0
        val session = CameraSession(main) { error("unexpected failure") }
        session.bind(future) {
            binds++;
            {}
        }
        session.close()
        future.complete("camera")
        drain()
        assertThat(binds).isEqualTo(0)
        assertThat(future.reads).isEqualTo(0)
    }

    @Test
    fun `closing invalidates queued decodes and unbinds only once`() {
        val future = PendingFuture<String>()
        var unbinds = 0
        var results = 0
        val session = CameraSession(main) { error("unexpected failure") }
        session.bind(future) { { unbinds++ } }
        future.complete("camera")
        drain()
        session.dispatch { results++ }
        session.close()
        session.close()
        drain()
        assertThat(unbinds).isEqualTo(1)
        assertThat(results).isEqualTo(0)
    }

    @Test
    fun `active sessions deliver results and report failed binding`() {
        val future = PendingFuture<String>()
        var failures = 0
        var results = 0
        val session = CameraSession(main) { failures++ }
        session.bind(future) { error("camera unavailable") }
        future.complete("camera")
        session.dispatch { results++ }
        drain()
        assertThat(failures).isEqualTo(1)
        assertThat(results).isEqualTo(1)
        session.close()
    }

    private class PendingFuture<T> :
        FutureTask<T>(Callable { error("complete explicitly") }),
        ListenableFuture<T> {
        private val listeners = mutableListOf<Pair<Runnable, Executor>>()
        var reads = 0

        override fun addListener(
            listener: Runnable,
            executor: Executor,
        ) {
            if (isDone) executor.execute(listener) else listeners += listener to executor
        }

        override fun done() = listeners.forEach { (listener, executor) -> executor.execute(listener) }

        override fun get(): T {
            check(isDone) { "Camera initialization must never block" }
            reads++
            return super.get()
        }

        fun complete(value: T) = set(value)
    }
}
