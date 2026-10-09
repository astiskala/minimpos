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
}
