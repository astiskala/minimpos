package app.minimpos.app.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

/**
 * Runs one screen action whose [ActionState] [slot] reads from [state] and [put] writes into it: nothing while it is
 * already running (such as a second tap), else it shows as running and [action] runs with [launchWrite], so it finishes
 * if the screen closes; the state it returns is shown only while the view model is in use. Call on the main thread.
 */
fun <S> ViewModel.runAction(
    state: MutableStateFlow<S>,
    slot: (S) -> ActionState,
    put: S.(ActionState) -> S,
    action: suspend () -> ActionState,
) {
    if (slot(state.value).running) return
    state.update { it.put(ActionState(running = true)) }
    launchWrite(action) { result -> state.update { it.put(result) } }
}
