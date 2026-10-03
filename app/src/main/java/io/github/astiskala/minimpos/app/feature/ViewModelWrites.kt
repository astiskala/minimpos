package io.github.astiskala.minimpos.app.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [write] to the end even if the calling coroutine is cancelled meanwhile, such as a view model's scope when its
 * screen closes: leaving a screen right after Save must not lose the change. Returns [write]'s result.
 *
 * @throws kotlinx.coroutines.CancellationException after [write] has finished, when the caller was cancelled, so
 *     nothing that follows (updating or leaving a screen that is gone) runs.
 */
suspend fun <T> persisting(write: suspend () -> T): T =
    withContext(NonCancellable) { write() }.also { currentCoroutineContext().ensureActive() }

/**
 * Launches [write] in [viewModelScope] with [persisting], so it finishes even if the screen closes; [then] gets its
 * result on the main thread, but only while the view model is still in use (to update the screen or navigate).
 */
fun <T> ViewModel.launchWrite(
    write: suspend () -> T,
    then: (T) -> Unit = {},
): Job = viewModelScope.launch { then(persisting(write)) }
