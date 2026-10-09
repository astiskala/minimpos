package app.minimpos.app.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the startup update check found. Only [Available] is offered, and only on a device that is not an Adyen
 * terminal; terminals update through the Customer Area and never check, so they stay [Unchecked].
 */
sealed interface UpdateCheck {
    /** No answer yet: the check has not run (it never does on an Adyen terminal) or has not returned. */
    data object Unchecked : UpdateCheck

    /**
     * A newer release: the [versionName] to show, and the [apkUrl] of its APK on GitHub. Update opens that address in
     * the browser, which downloads it; Android then asks for its installation, checking it against the installed app.
     */
    data class Available(
        val versionName: String,
        val apkUrl: String,
    ) : UpdateCheck

    /** The latest release's version code is not above the installed one. */
    data object Current : UpdateCheck

    /** The check failed: GitHub or the release's update metadata was unreachable or invalid. Never offered. */
    data object Unavailable : UpdateCheck

    /** An offered update the merchant dismissed; nothing more is shown for the rest of this session. */
    data object Dismissed : UpdateCheck
}

/**
 * The one update check of this process, held for Home: [state] starts [UpdateCheck.Unchecked] and takes [check]'s
 * answer once [start] has run it; an offer can then be dismissed until the app starts again. The container starts it on
 * app startup, except on an Adyen terminal.
 *
 * @param check Reads the latest release for the installed version; it reports failure as
 *   [UpdateCheck.Unavailable] instead of throwing. Called once, off the main thread.
 * @param scope The application scope the check runs in.
 */
class AppUpdate(
    private val check: suspend () -> UpdateCheck,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UpdateCheck>(UpdateCheck.Unchecked)

    /** The check's answer for Home: only [UpdateCheck.Available] shows anything. */
    val state: StateFlow<UpdateCheck> = _state.asStateFlow()

    /** Runs the check once, in [scope]; later calls do nothing, so an answered or dismissed check stays. */
    fun start() {
        if (_state.value != UpdateCheck.Unchecked) return
        scope.launch { _state.value = check() }
    }

    /** Hides an offered update for the rest of this session; nothing when there was nothing to offer. */
    fun dismiss() {
        _state.update { if (it is UpdateCheck.Available) UpdateCheck.Dismissed else it }
    }
}
