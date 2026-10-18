package app.minimpos.app.ui

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NavigatorTest {
    @Test
    fun `setup Back retains onboarding while finishing at Home removes it`() {
        val stack = NavBackStack<NavKey>(Route.Home, Route.Onboarding)
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
