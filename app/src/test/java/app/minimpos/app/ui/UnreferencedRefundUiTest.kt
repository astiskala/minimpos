package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
import app.minimpos.app.data.settings.UnreferencedRefundSettings
import app.minimpos.app.feature.refund.RefundStartScreen
import app.minimpos.app.feature.refund.UnreferencedRefundScreen
import app.minimpos.app.feature.refund.UnreferencedRefundViewModel
import app.minimpos.app.payment.UnreferencedRefundBlock
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

/** The Refund entry and the refund without receipt at AMS1 size, with large text and in Japanese and both Chinese scripts. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class UnreferencedRefundUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test fun `refund entry and refund without receipt fit AMS1 with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        refundWithoutReceipt()
    }

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese refund without receipt fits AMS1`() = refundWithoutReceipt()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese refund without receipt fits AMS1`() = refundWithoutReceipt()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese refund without receipt fits AMS1`() = refundWithoutReceipt()

    @Test
    fun `the refund entry offers all three ways and the new flow starts disabled`() {
        val container = env.container
        env.useSimulator()
        await { container.walletDiscovery.refresh() }
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.RefundStart))
        val vm = viewModel()
        try {
            show(navigator, vm)
            listOf("refundScan", "refundHistory", "refundUnreferenced").forEach {
                compose.onNodeWithTag(it).performScrollTo().assertIsDisplayed()
            }
            compose.onNodeWithTag("refundHistory").performScrollTo().performClick()
            compose.awaitCondition("history opened") { navigator.current == Route.History }
            navigator.back()
            compose.onNodeWithTag("refundUnreferenced").performScrollTo().performClick()
            compose.awaitCondition("refund without receipt opened") { navigator.current == Route.UnreferencedRefund }
            compose.awaitCondition("disabled explained") { vm.state.value.block == UnreferencedRefundBlock.NOT_ENABLED }
            compose.onNodeWithTag("blockedReason").assertIsDisplayed()
            compose.onNodeWithTag("startUnreferencedRefund").assertIsNotEnabled()
        } finally {
            vm.viewModelScope.cancel()
        }
    }

    /** Shows whichever of the two refund screens [navigator] is on, with [vm] for the refund without receipt. */
    private fun show(
        navigator: Navigator,
        vm: UnreferencedRefundViewModel,
    ) = compose.setContent {
        MiniMposTheme {
            CompositionLocalProvider(LocalAppContainer provides env.container) {
                val current = navigator.current
                if (current == Route.RefundStart) RefundStartScreen(navigator)
                if (current == Route.UnreferencedRefund) UnreferencedRefundScreen(navigator, vm = vm)
            }
        }
    }

    private fun viewModel() =
        env.container.let {
            UnreferencedRefundViewModel(
                it.settingsState,
                it.walletDiscovery.state,
                it.managerPin.pinConfigured,
                it::currency,
                it.storedPaymentActions,
            )
        }

    private fun refundWithoutReceipt() {
        val container = env.container
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "EUR")) }
        await {
            container.walletDiscovery.refresh()
            container.managerPin.setPin("2468")
        }
        val facts = container.walletDiscovery.state.value
        env.updateSettings {
            it.copy(
                payment =
                    it.payment.copy(
                        unreferencedRefunds =
                            UnreferencedRefundSettings(enabled = true, limitMinor = 5000, context = facts.context, currency = "EUR"),
                    ),
            )
        }
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.RefundStart))
        val vm = viewModel()
        try {
            show(navigator, vm)
            compose.onNodeWithTag("refundScan").performScrollTo().assertIsDisplayed()
            compose
                .onNodeWithTag("refundUnreferenced")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
            compose.awaitCondition("refund without receipt opened") { navigator.current == Route.UnreferencedRefund }
            compose.awaitCondition("refund configured") { vm.state.value.block == null && vm.state.value.managerConfigured }
            listOf(6, 0, 0, 0).forEach { compose.onNodeWithTag("key_$it").performScrollTo().performClick() }
            compose.awaitCondition("over the limit") { vm.state.value.overLimit }
            compose.onNodeWithTag("blockedReason").assertIsDisplayed()
            compose.onNodeWithTag("key_<").performScrollTo().performClick()
            compose.onNodeWithTag("reason_PRICE_ADJUSTMENT").performScrollTo().performClick()
            compose.awaitCondition("refund ready") { vm.state.value.ready }
            assertThat(vm.state.value.amount.minor).isEqualTo(600)
            compose.onNodeWithTag("startUnreferencedRefund").assertIsDisplayed().assertIsEnabled()
        } finally {
            vm.viewModelScope.cancel()
        }
    }
}
