package app.minimpos.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@RunWith(RobolectricTestRunner::class)
class StorageUnavailableTest {
    @get:Rule(order = 0)
    val env = TestEnvironment(storedFiles = mapOf("secrets.json" to "{"))

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `unreadable secrets show recovery guidance instead of an unprotected home screen`() {
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("storageUnavailable").assertIsDisplayed()
        compose.onNodeWithTag("storageTroubleshooting").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("storageReportIssue").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings").assertDoesNotExist()
        compose.onNodeWithTag("sale").assertDoesNotExist()
    }
}
