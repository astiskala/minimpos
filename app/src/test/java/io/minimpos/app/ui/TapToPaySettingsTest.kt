package io.minimpos.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.minimpos.app.FakeDevice
import io.minimpos.app.MiniMposApp
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.awaitCondition
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.terminal.transport.TerminalEnvironment
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Settings › Terminal for Tap to Pay on a phone: getting the Payments app, and removing what was set up. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU")
class TapToPaySettingsTest {
    private val device = FakeDevice()

    @get:Rule(order = 0)
    val env = TestEnvironment(device)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP)) }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun openTerminalSettings() {
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
    }

    @Test
    fun `without the Payments app, Google Play opens on it, and the app shows once installed`() {
        openTerminalSettings()
        waitForTag("getPaymentsAppLive")
        compose.onNodeWithTag("getPaymentsAppTest").assertTextContains("Get Adyen Payments Test")
        compose.onNodeWithTag("getPaymentsAppLive").performScrollTo().performClick()
        val opened = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertThat(opened.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(opened.dataString).isEqualTo("https://play.google.com/store/apps/details?id=com.adyen.ipp.mobile.companion.live")
        compose.onNodeWithTag("getPaymentsAppTest").performScrollTo().performClick()
        assertThat(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity.dataString)
            .isEqualTo("https://play.google.com/store/apps/details?id=com.adyen.ipp.mobile.companion.test")

        // Back in the app, the device is read again.
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        container.terminalStatus.readDevice()
        compose.waitUntilAtLeastOneExists(hasText("Adyen Payments Test (TEST)"), 15_000)
        compose.onNodeWithTag("getPaymentsAppLive").assertDoesNotExist()
    }

    @Test
    fun `removing a saved key asks first`() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        await { container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "pa-key") }
        openTerminalSettings()
        waitForTag("forgetPaymentsAppKey")
        compose.onNodeWithTag("forgetPaymentsAppKey").performScrollTo().performClick()
        compose.onNodeWithText("Remove the saved Payments app API key?").assertExists()
        // Cancelling keeps it.
        compose.onNodeWithText("Cancel").performClick()
        assertThat(await { container.secrets.get(Secret.PAYMENTS_APP_API_KEY) }).isEqualTo("pa-key")
        compose.onNodeWithTag("forgetPaymentsAppKey").performScrollTo().performClick()
        compose.onNodeWithTag("confirm").performClick()
        compose.awaitCondition("Removing the key") { await { container.secrets.get(Secret.PAYMENTS_APP_API_KEY) } == null }
    }
}
