package app.minimpos.app.scan

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/**
 * Owns one scanner's delayed camera binding and main-thread result delivery. Call all methods on the main thread;
 * [main] must dispatch there too. Closing never waits for camera initialization and invalidates queued callbacks.
 *
 * @param main Dispatches initialization and decoded results to the main thread.
 * @param onFailure Reports a failed camera bind without keeping exception text.
 */
internal class CameraSession(
    private val main: Executor,
    private val onFailure: () -> Unit,
) {
    private var active = true
    private var unbind: (() -> Unit)? = null

    /** Binds once [future] completes; [connect] returns cleanup for only the use cases this scanner owns. */
    fun <T> bind(
        future: ListenableFuture<T>,
        connect: (T) -> (() -> Unit),
    ) {
        future.addListener({
            if (active) {
                runCatching { connect(future.get()) }
                    .onSuccess { unbind = it }
                    .onFailure { onFailure() }
            }
        }, main)
    }

    /** Dispatches a decoded [result] only while the scanner is still open, including when delivery was queued. */
    fun dispatch(result: () -> Unit) {
        main.execute { if (active) result() }
    }

    /** Invalidates queued work and releases already-bound use cases without waiting for a pending future. */
    fun close() {
        active = false
        unbind?.invoke()
        unbind = null
    }
}
