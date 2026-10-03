package io.github.astiskala.minimpos.app.ui

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import org.junit.Test

class NavigatorTest {
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
