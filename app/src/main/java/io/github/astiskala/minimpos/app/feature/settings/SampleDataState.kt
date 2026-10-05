package io.github.astiskala.minimpos.app.feature.settings

import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Progress of a sample-data write.
 * @property running Whether a write is in progress; disables duplicate actions.
 * @property added True after population, false after removal, null before success.
 * @property failed Whether persistence failed, allowing a retry without losing merchant data.
 */
data class SampleDataState(
    val running: Boolean = false,
    val added: Boolean? = null,
    val failed: Boolean = false,
)

/** Captures storage failures for retryable presentation, while cancellation still propagates to the owning scope. */
internal suspend fun <T> sampleDataWrite(operation: suspend () -> T): Result<T> =
    try {
        Result.success(operation())
    } catch (e: CancellationException) {
        throw e
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: SQLiteException) {
        Result.failure(e)
    } catch (e: IllegalStateException) {
        Result.failure(e)
    }
