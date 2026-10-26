package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.settings.CashbackCard
import app.minimpos.app.data.settings.CashbackSettings
import app.minimpos.app.feature.sale.CashoutScreen
import app.minimpos.app.feature.sale.CashoutViewModel
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class CashoutUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test fun `cashout fits AMS1 with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        cashout()
    }

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese cashout fits AMS1`() = cashout()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese cashout fits AMS1`() = cashout()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese cashout fits AMS1`() = cashout()

    @Test
    fun `cashout initiation rechecks setup after the screen opens`() = cashout(gateBlocked = true)

    private fun cashout(gateBlocked: Boolean = false) {
        val container = env.container
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        await {
            container.walletDiscovery.refresh()
            container.pinManager.setPin("1357")
            container.managerPin.setPin("2468")
        }
        val facts = container.walletDiscovery.state.value
        env.updateSettings {
            it.copy(
                payment =
                    it.payment.copy(
                        cashback =
                            CashbackSettings(
                                standalone = true,
                                card = CashbackCard.EFTPOS,
                                limitMinor = 5000,
                                context = facts.context,
                                currency = "AUD",
                            ),
                    ),
            )
        }
        val vm =
            CashoutViewModel(
                container.settingsState,
                container.walletDiscovery.state,
                container.managerPin.pinConfigured,
                container::currency,
                container.payments,
            )
        try {
            compose.setContent {
                MiniMposTheme {
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        CashoutScreen(Navigator(NavBackStack<NavKey>(Route.Home, Route.Cashout)), vm = vm)
                    }
                }
            }
            compose.awaitCondition("cashout configured") { vm.state.value.available && vm.state.value.managerConfigured }
            listOf(5, 2).forEach { compose.onNodeWithTag("key_$it").performScrollTo().performClick() }
            compose.onNodeWithTag("key_<").performScrollTo().performClick()
            compose.onNodeWithTag("key_00").performScrollTo().performClick()
            compose.onNodeWithTag("cashoutCard").performScrollTo().performClick()
            compose.awaitCondition("cashout ready") { vm.state.value.ready }
            compose.onNodeWithTag("startCashout").assertIsDisplayed().assertIsEnabled()
            if (gateBlocked) {
                compose.runOnIdle { container.setupGate.follow(true) {} }
                compose.onNodeWithTag("startCashout").performClick()
                compose.runOnIdle { assertThat(container.setupGate.explaining).isTrue() }
            }
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
