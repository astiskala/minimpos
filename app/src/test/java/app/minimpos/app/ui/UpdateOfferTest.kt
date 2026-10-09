package app.minimpos.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.FakeUpdateCheck
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.update.UpdateCheck
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** Home offers an available release at the smallest screen, where the payment tiles remain beside it. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class UpdateOfferTest {
    private val apkUrl = "https://github.com/astiskala/minimpos/releases/download/v0.6.5/minimpos-0.6.5.apk"
    private val updates = FakeUpdateCheck(UpdateCheck.Available("0.6.5", apkUrl))

    @get:Rule(order = 0)
    val env = TestEnvironment(dispatcher = UnconfinedTestDispatcher(), updates = updates)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Before
    fun setUp() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
    }

    @Test
    fun `an available release is offered with its version, opens its APK in the browser, and dismisses for this session`() {
        env.container.start()
        compose.awaitCondition("the update offer is shown") {
            compose.onAllNodes(hasTestTag("update")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(env.context.getString(R.string.update_available, "0.6.5")).assertExists()
        compose.onNodeWithTag("newSale").assertExists()

        compose.onNodeWithTag("updateDownload").performClick()
        val opened = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertThat(opened.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(opened.dataString).isEqualTo(apkUrl)
        // A browser app, not whatever claims github.com links (such as an in-app browser), downloads the APK.
        assertThat(opened.selector?.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(opened.selector?.categories).containsExactly(Intent.CATEGORY_APP_BROWSER)

        compose.onNodeWithTag("updateDismiss").performClick()
        compose.awaitCondition("the update offer is gone") {
            compose.onAllNodes(hasTestTag("update")).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `nothing is offered while no update is available`() {
        updates.result = UpdateCheck.Current
        env.container.start()
        compose.awaitCondition("Home is shown") {
            compose.onAllNodes(hasTestTag("newSale")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("update").assertDoesNotExist()
    }
}
