package app.minimpos.app.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.closeViewModels
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.feature.sale.SavedCardReviewScreen
import app.minimpos.app.feature.sale.SavedCardViewModel
import app.minimpos.app.payment.OperationAmount
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.ChosenCard
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class SavedCardBackTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `Back cannot leave a saved-card charge while its answer is pending`() {
        val card = ChosenCard("token", "visa", "1111")
        val route = Route.SavedCardReview(card)
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, route))
        val progress = MutableStateFlow<TransactionState>(TransactionState.Processing("sale", OperationAmount(100, "AUD"), false))
        val container = env.container
        val vm =
            SavedCardViewModel(
                container.savedCardSession,
                progress,
                container.savedCardLookup,
                container.settingsState,
                container::currency,
            )
        try {
            lateinit var back: OnBackPressedDispatcher
            compose.setContent {
                back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                MiniMposTheme {
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        SavedCardReviewScreen(card, navigator, vm = vm)
                    }
                }
            }
            compose.awaitCondition("charge shown") { vm.state.value.charge is TransactionState.Processing }
            compose.waitForIdle()
            compose.runOnUiThread { back.onBackPressed() }
            compose.waitUntilAtLeastOneExists(hasText(env.context.getString(R.string.processing_wait)), 15_000)
            assertThat(navigator.current).isEqualTo(route)
            progress.value = TransactionState.Finished("sale")
            compose.awaitCondition("answer opens result") { navigator.current == Route.SaleResult("sale") }
        } finally {
            closeViewModels(listOf(vm))
        }
    }
}
