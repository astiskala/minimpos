package app.minimpos.app.payment

import android.database.SQLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The single-flight skeleton every financial-operation runner shares: [TransactionLifecycle] on the terminal,
 * [CheckoutOperations] through the Checkout API, and [PaymentLinks] creations. At most one operation runs at a time,
 * on [scope] so it survives screen changes, and it always ends [TransactionState.Finished], even when something
 * unexpected fails, after its runner has stored it as unknown. Requests started in this process are kept until their
 * outcome is known, so a runner can report a success once with the original request.
 *
 * @param R What the screen collected for one operation.
 * @param scope Where operations run.
 * @param onTerminal Whether the shopper follows its operations on a terminal, for [TransactionState.Processing].
 * @param amountOf What an operation charges or refunds, for [TransactionState.Processing].
 */
internal class OperationRunner<R>(
    private val scope: CoroutineScope,
    private val onTerminal: Boolean,
    private val amountOf: (R) -> OperationAmount,
) {
    /** The current operation; a runner may refine a [TransactionState.Processing] one (for example once it is sent). */
    val state = MutableStateFlow<TransactionState>(TransactionState.Idle)
    private val unresolved = mutableMapOf<String, R>()

    /**
     * Starts [request] in the background as the record [id] names (asked only when none is running) and returns that
     * ID straight away; null, with nothing run, while another operation is [TransactionState.Processing].
     *
     * @param request What the screen collected, kept until the operation is resolved (see [resolved]).
     * @param id Makes the record's ID, asked only once the operation starts.
     * @param interrupted Stores the record as unknown after [run] failed unexpectedly; a database that is closed or
     *   failing leaves it PENDING, which the next start turns into UNKNOWN.
     * @param release Erases transient request material after every attempt, including failed ones.
     * @param run Stores and sends the operation, and stores its outcome.
     */
    @Synchronized
    fun start(
        request: R,
        id: () -> String,
        interrupted: suspend (id: String, cause: Exception) -> Unit,
        release: () -> Unit = {},
        run: suspend (id: String) -> Unit,
    ): String? {
        if (state.value is TransactionState.Processing) return null
        val started = id()
        state.value = TransactionState.Processing(started, amountOf(request), onTerminal)
        synchronized(unresolved) { unresolved[started] = request }
        scope.launch {
            try {
                run(started)
                state.value = TransactionState.Finished(started)
            } catch (e: CancellationException) {
                throw e
            } catch (
                // Whatever went wrong, the record must not be left looking as if it were still in progress.
                @Suppress("TooGenericExceptionCaught") unexpected: Exception,
            ) {
                try {
                    interrupted(started, unexpected)
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: SQLException) {
                    // It stays PENDING, which the next start turns into UNKNOWN.
                } catch (ignored: IllegalStateException) {
                    // The database is closed; it stays PENDING, which the next start turns into UNKNOWN.
                }
                state.value = TransactionState.Finished(started)
            } finally {
                release()
            }
        }
        return started
    }

    /** The request of record [id] when it was started in this process and is now resolved; null otherwise or once taken. */
    fun resolved(id: String): R? = synchronized(unresolved) { unresolved.remove(id) }

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next operation can start. */
    fun acknowledge() {
        if (state.value is TransactionState.Finished) state.value = TransactionState.Idle
    }
}
