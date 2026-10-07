package io.github.astiskala.minimpos.app.ui

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakePaymentsApp
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.terminal.transport.SharedKeyLookup
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

/** Settings › Terminal for Tap to Pay on a phone: getting the Payments app, and removing what was set up. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU")
class TapToPaySettingsTest {
    private val device = FakeDevice()
    private val paymentsApp = FakePaymentsApp()
    private val details =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) =
                io.github.astiskala.minimpos.terminal.transport.CredentialLookup.Allowed

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing = error("No terminal listing")

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup = error("No terminal key lookup")
        }

    @get:Rule(order = 0)
    val env = TestEnvironment(device, paymentsApp = paymentsApp, terminalDetails = details)

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
        env.useCheckoutApi()
        await { container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "pa-key") }
        openTerminalSettings()
        testApi()
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

    private fun boardPhone() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        await {
            container.secrets.set(Secret.ADYEN_API_KEY, "checkout-key")
            container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "boarding-key")
        }
        openTerminalSettings()
        testApi()
        waitForTag("setUpTapToPay")
        compose.onNodeWithTag("setUpTapToPay").performScrollTo().performClick()
        compose.awaitCondition("phone registration completes") {
            container.settingsState.value.terminal.paymentsAppInstallationId == FakePaymentsApp.INSTALLATION_ID
        }
        waitForTag("keyIdentifier")
    }

    private fun testApi() {
        waitForTag("testApi")
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        waitForTag("setUpTapToPay")
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `boarding preserves the manual shared key without any settings lookup on the smallest screen`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "manual-key", keyVersion = 2)) }
        await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "manual secret") }
        boardPhone()
        compose.onNodeWithTag("keyIdentifier").performScrollTo().assertTextContains("manual-key", substring = true)
        compose.onNodeWithTag("findSharedKey").assertDoesNotExist()
        assertThat(container.settingsState.value.terminal.keyVersion).isEqualTo(2)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("manual secret")
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `boarding without a shared key leaves manual entry available and never queries settings`() {
        boardPhone()
        compose.onNodeWithTag("keyIdentifier").performScrollTo().performTextInput("manual-key")
        compose.onNodeWithTag("passphrase").performScrollTo().assertExists()
        compose.onNodeWithTag("keyVersion").assertExists()
        compose.onNodeWithTag("findSharedKey").assertDoesNotExist()
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
    }
}
