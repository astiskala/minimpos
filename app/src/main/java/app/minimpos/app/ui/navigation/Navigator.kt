package app.minimpos.app.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/** Navigation operations shared by all screens. Call from the main thread; the back stack is Compose state. */
class Navigator(
    private val backStack: NavBackStack<NavKey>,
) {
    /** The screen on top of the back stack. */
    val current: Route? get() = backStack.lastOrNull() as? Route

    /** Opens [route] on top of the current screen. */
    fun push(route: Route) {
        if (current != route) backStack.add(route)
    }

    /** Closes the current screen; the last one (Home) is never removed. */
    fun back() {
        if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    }

    /** Replaces the top of the stack (e.g. scan -> refund, payment -> result). */
    fun replace(route: Route) {
        if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
        backStack.add(route)
    }

    /** Pops back to [route] if it is on the stack (pushing it otherwise), dropping everything above it. */
    fun popTo(route: Route) {
        val index = backStack.indexOfLast { it == route }
        if (index < 0) {
            while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
            if (route != Route.Home) backStack.add(route)
        } else {
            while (backStack.lastIndex > index) backStack.removeAt(backStack.lastIndex)
        }
    }

    /** Returns to the Home screen, closing everything else. */
    fun home() = popTo(Route.Home)
}
