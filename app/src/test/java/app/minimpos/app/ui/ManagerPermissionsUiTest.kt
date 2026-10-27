package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.core.access.ManagerAction
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class ManagerPermissionsUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private val container get() = env.container

    private fun prepare(
        admin: Boolean,
        manager: Boolean,
        protected: Boolean,
    ) {
        env.useSimulator()
        await {
            container.settings.update {
                it.copy(
                    security =
                        it.security.copy(
                            managerRequirements = mapOf(ManagerAction.SALE.name to protected),
                        ),
                )
            }
            if (admin) container.pinManager.setPin("1357")
            if (manager) container.managerPin.setPin("2468")
        }
        compose.setContent { MiniMposApp(container) }
        compose.waitUntilAtLeastOneExists(hasTestTag("newSale"), 15_000)
    }

    @Test
    fun `without admin PIN a protected sale opens even with a Manager PIN configured`() {
        prepare(admin = false, manager = true, protected = true)
        compose.onNodeWithTag("newSale").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("charge"), 15_000)
        compose.onNodeWithTag("pin_1").assertDoesNotExist()
    }

    @Test
    fun `protected sale with admin but no Manager PIN explains the block before cart entry`() {
        prepare(admin = true, manager = false, protected = true)
        compose.onNodeWithTag("newSale").performClick()
        val message = "This action requires a Manager PIN. Set one in Settings › Security, or have an admin change its permission."
        compose.waitUntilAtLeastOneExists(hasText(message), 15_000)
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithTag("charge").assertDoesNotExist()
    }

    @Test
    fun `public sale opens with both PINs configured`() {
        prepare(admin = true, manager = true, protected = false)
        compose.onNodeWithTag("newSale").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("charge"), 15_000)
        compose.onNodeWithTag("pin_1").assertDoesNotExist()
    }

    @Test
    fun `admin changes a Manager permission on AMS1 without a Manager PIN`() {
        prepare(admin = true, manager = false, protected = false)
        container.sessionLock.unlock()
        compose.onNodeWithTag("settings").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("section_security"), 15_000)
        compose.onNodeWithTag("section_security").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("managerPermissionSALE"), 15_000)
        compose.onNodeWithTag("managerPermissionSALE").performScrollTo().performClick()
        compose.awaitCondition("Manager permission saved") {
            container.settingsState.value.security
                .requiresManager(ManagerAction.SALE)
        }
        assertThat(
            container.settingsState.value.security
                .requiresManager(ManagerAction.PRE_AUTHORISATION),
        ).isFalse()
    }
}
