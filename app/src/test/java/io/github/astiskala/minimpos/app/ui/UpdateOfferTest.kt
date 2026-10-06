package io.github.astiskala.minimpos.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeUpdateCheck
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.R
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.update.UpdateCheck
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

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
