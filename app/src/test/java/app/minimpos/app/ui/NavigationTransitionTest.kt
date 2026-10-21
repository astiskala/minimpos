package app.minimpos.app.ui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.createRecordingComposeRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class NavigationTransitionTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `cancelled predictive Back keeps the screen and committed Back pops once to the root`() {
        lateinit var back: OnBackPressedDispatcher
        compose.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MiniMposApp(env.container)
        }
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("section_tax"), 15_000)
        compose.onNodeWithTag("section_tax").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("chargeTax"), 15_000)
        compose.runOnUiThread { back.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT)) }
        compose.runOnUiThread { back.dispatchOnBackProgressed(BackEventCompat(100f, 100f, 0.5f, BackEventCompat.EDGE_LEFT)) }
        compose.runOnUiThread { back.dispatchOnBackCancelled() }
        compose.waitForIdle()
        compose.onNodeWithTag("chargeTax").assertExists()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasTestTag("section_tax"), 15_000)
        compose.onNodeWithTag("chargeTax").assertDoesNotExist()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitUntilAtLeastOneExists(hasTestTag("newSale"), 15_000)
        compose.runOnUiThread { assertThat(back.hasEnabledCallbacks()).isFalse() }
    }
}
