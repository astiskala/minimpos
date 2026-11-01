package app.minimpos.app.ui

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavigatorTest {
    @Test
    fun `a confirmed navigation continuation cannot be replayed after reopening the same editor`() {
        val editor = Route.SettingsSection("capabilities", feature = "DCC")
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, editor))
        lateinit var pending: () -> Unit
        val dispose = navigator.reviewLeaving(editor) { pending = it }
        navigator.back()
        pending()
        navigator.push(editor)
        pending()
        assertThat(navigator.current).isEqualTo(editor)
        dispose()
    }

    @Test
    fun `dirty editor reviews Back and expanded pane choices without first mutating the stack`() {
        val editor = Route.SettingsSection("capabilities", feature = "MOTO")
        val stack = NavBackStack<NavKey>(Route.Home, Route.Settings, Route.SettingsSection("capabilities"), editor)
        val navigator = Navigator(stack)
        var pending: (() -> Unit)? = null
        val dispose = navigator.reviewLeaving(editor) { pending = it }
        navigator.back()
        assertThat(navigator.current).isEqualTo(editor)
        assertThat(pending).isNotNull()
        pending = null // Keep editing.
        navigator.openFrom(Route.Settings, Route.SettingsSection("payments"))
        assertThat(navigator.current).isEqualTo(editor)
        checkNotNull(pending).invoke()
        assertThat(stack).containsExactly(Route.Home, Route.Settings, Route.SettingsSection("payments")).inOrder()
        dispose()
    }

    @Test
    fun `outgoing editor disposal cannot remove a newer navigation review`() {
        val editor = Route.SettingsSection("capabilities", feature = "DCC")
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, editor))
        var reviewed = false
        val dispose = navigator.reviewLeaving(editor) { error("Old review") }
        val disposeCurrent = navigator.reviewLeaving(editor) { reviewed = true }
        dispose()
        navigator.home()
        assertThat(reviewed).isTrue()
        assertThat(navigator.current).isEqualTo(editor)
        disposeCurrent()
        navigator.home()
        assertThat(navigator.current).isEqualTo(Route.Home)
    }

    @Test
    fun `setup Back retains onboarding while finishing at Home removes it`() {
        val stack = NavBackStack<NavKey>(Route.Onboarding)
        val navigator = Navigator(stack)
        navigator.push(Route.TransferImport)
        navigator.back()
        assertThat(navigator.current).isEqualTo(Route.Onboarding)
        navigator.push(Route.SettingsSection("terminal"))
        navigator.back()
        assertThat(navigator.current).isEqualTo(Route.Onboarding)
        navigator.home()
        assertThat(stack).containsExactly(Route.Home)
    }

    @Test
    fun `Back never removes onboarding root and explicit Home replaces it`() {
        val stack = NavBackStack<NavKey>(Route.Onboarding)
        val navigator = Navigator(stack)
        navigator.back()
        assertThat(stack).containsExactly(Route.Onboarding)
        assertThat(navigator.isOnboarding).isTrue()
        navigator.home()
        assertThat(stack).containsExactly(Route.Home)
        assertThat(navigator.isOnboarding).isFalse()
    }

    @Test
    fun `repeated taps open a screen once and Back returns to its parent`() {
        val stack = NavBackStack<NavKey>(Route.Home)
        val navigator = Navigator(stack)
        navigator.push(Route.Sale)
        navigator.push(Route.Sale)
        assertThat(stack).containsExactly(Route.Home, Route.Sale).inOrder()
        navigator.back()
        assertThat(navigator.current).isEqualTo(Route.Home)
    }

    @Test
    fun `different screens still form a back stack`() {
        val stack = NavBackStack<NavKey>(Route.Home)
        val navigator = Navigator(stack)
        navigator.push(Route.History)
        navigator.push(Route.SaleDetail("sale"))
        navigator.back()
        assertThat(navigator.current).isEqualTo(Route.History)
        navigator.back()
        navigator.back()
        assertThat(stack).containsExactly(Route.Home)
    }

    @Test
    fun `a list's next choice replaces what it opened, keeping that detail's own back stack until then`() {
        val stack = NavBackStack<NavKey>(Route.Home, Route.History)
        val navigator = Navigator(stack)
        navigator.openFrom(Route.History, Route.SaleDetail("first"))
        // A refund opened from the sale detail stacks on it, as on a phone.
        navigator.push(Route.RefundDetail("refund"))
        assertThat(stack).containsExactly(Route.Home, Route.History, Route.SaleDetail("first"), Route.RefundDetail("refund")).inOrder()
        navigator.openFrom(Route.History, Route.SaleDetail("second"))
        assertThat(stack).containsExactly(Route.Home, Route.History, Route.SaleDetail("second")).inOrder()
    }

    @Test
    fun `leaving a list closes its detail too but never Home`() {
        val stack = NavBackStack<NavKey>(Route.Home, Route.Settings, Route.SettingsSection("tax"))
        val navigator = Navigator(stack)
        navigator.leave(Route.Settings)
        assertThat(stack).containsExactly(Route.Home)
        navigator.leave(Route.Home)
        assertThat(stack).containsExactly(Route.Home)
        navigator.push(Route.History)
        // Without the list on the stack (opened another way), leaving is plain Back.
        navigator.push(Route.SettingsSection("terminal"))
        navigator.leave(Route.Settings)
        assertThat(stack).containsExactly(Route.Home, Route.History).inOrder()
    }
}
