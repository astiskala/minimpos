package app.minimpos.app.ui.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey

/** Navigation operations shared by all screens. Call from the main thread; the back stack is Compose state. */
class Navigator(
    private val backStack: NavBackStack<NavKey>,
) {
    private var leaving: Leaving? = null

    /** The screen on top of the back stack. */
    val current: Route? get() = backStack.lastOrNull() as? Route

    /** Opens [route] on top of the current screen. */
    fun push(route: Route) =
        navigate {
            if (current != route) backStack.add(route)
        }

    /** Closes the current screen; the root (Home or first-run onboarding) is never removed. */
    fun back() =
        navigate {
            if (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
        }

    /** Replaces the top of the stack (e.g. scan -> refund, payment -> result). */
    fun replace(route: Route) =
        navigate {
            if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex)
            backStack.add(route)
        }

    /** Pops back to [route] if it is on the stack (pushing it otherwise), dropping everything above it. */
    fun popTo(route: Route) =
        navigate {
            val index = backStack.indexOfLast { it == route }
            if (index < 0) {
                backStack.clear()
                backStack.add(Route.Home)
                if (route != Route.Home) backStack.add(route)
            } else {
                while (backStack.lastIndex > index) backStack.removeAt(backStack.lastIndex)
            }
        }

    /** Whether an active first-run choice remains below setup; false after explicitly returning Home. */
    val isOnboarding: Boolean get() = Route.Onboarding in backStack

    /** Returns to the Home screen, closing everything else, including first-run onboarding. */
    fun home() = popTo(Route.Home)

    /**
     * Opens [route] from the list [list] (History, Settings): whatever was opened from that list before is closed
     * first, so in an expanded window, where the list stays beside its detail, a new choice replaces the old one rather
     * than stacking up. In a narrower window the list is the current screen whenever it can be tapped, so this pushes.
     */
    fun openFrom(
        list: Route,
        route: Route,
    ) = navigate {
        val index = backStack.indexOfLast { it == list }
        if (index >= 0) while (backStack.lastIndex > index) backStack.removeAt(backStack.lastIndex)
        push(route)
    }

    /**
     * Closes [route] and everything opened above it, as a list's Back does while its detail is beside it; Home is never
     * removed. Without [route] on the stack, this is [back].
     */
    fun leave(route: Route) =
        navigate {
            val index = backStack.indexOfLast { it == route }
            if (index < 0) return@navigate back()
            while (backStack.size > maxOf(index, 1)) backStack.removeAt(backStack.lastIndex)
        }

    /**
     * Lets [route] review any attempted navigation, including a different selection in an expanded list. The callback
     * receives the exact pending navigation to continue after confirmation. Returns a disposer that removes only this
     * registration, so an outgoing composition cannot clear a newer guard.
     */
    fun reviewLeaving(
        route: Route,
        review: (() -> Unit) -> Unit,
    ): () -> Unit {
        val registered = Leaving(route, review)
        leaving = registered
        return { if (leaving === registered) leaving = null }
    }

    private fun navigate(action: () -> Unit) {
        val guard = leaving?.takeIf { it.route == current }
        if (guard == null) {
            action()
        } else {
            var continued = false
            guard.review {
                if (!continued && current == guard.route && leaving === guard) {
                    continued = true
                    leaving = null
                    action()
                }
            }
        }
    }

    private class Leaving(
        val route: Route,
        val review: (() -> Unit) -> Unit,
    )
}
