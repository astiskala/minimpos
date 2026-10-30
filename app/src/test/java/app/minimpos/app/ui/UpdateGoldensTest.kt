package app.minimpos.app.ui

import androidx.compose.ui.test.hasTestTag
import app.minimpos.app.FakeUpdateCheck
import app.minimpos.app.MiniMposApp
import app.minimpos.app.ScreenshotRule
import app.minimpos.app.ScreenshotTolerance
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.update.UpdateCheck
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The compact secondary update action above Home's payment tiles, using a fake startup update check. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class UpdateGoldensTest {
    @get:Rule(order = 0)
    val env =
        TestEnvironment(
            dispatcher = UnconfinedTestDispatcher(),
            updates = FakeUpdateCheck(UpdateCheck.Available("3.0.0", "https://example.com/minimpos.apk")),
        )

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @get:Rule(order = 2)
    val screenshot = ScreenshotRule(compose, ScreenshotTolerance.SCREEN)

    @Test
    fun `Home with an update offer`() {
        env.useSimulator { it.copy(receipt = it.receipt.copy(businessName = "Corner Cafe")) }
        compose.setContent { MiniMposApp(env.container) }
        env.container.start()
        compose.awaitCondition("update offered") { compose.onAllNodes(hasTestTag("update")).fetchSemanticsNodes().isNotEmpty() }
        screenshot.capture()
    }
}
