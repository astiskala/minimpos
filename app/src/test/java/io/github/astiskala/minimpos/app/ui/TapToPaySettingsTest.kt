package io.github.astiskala.minimpos.app.ui

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
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
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

/** Settings › Terminal for Tap to Pay on a phone: getting the Payments app, and removing what was set up. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU")
class TapToPaySettingsTest {
    private val device = FakeDevice()
    private val paymentsApp = FakePaymentsApp()
    private var foundKey: DiscoveredKey? = null
    private var reads = 0
    private val details =
        object : TerminalDetailsApi {
            override suspend fun terminals(environment: TerminalEnvironment): TerminalListing = error("No terminal listing")

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): DiscoveredKey? = error("No terminal key lookup")

            override suspend fun accountSharedKey(
                merchantAccount: String,
                storeId: String?,
                environment: TerminalEnvironment,
            ): DiscoveredKey? = foundKey.also { reads++ }
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

    private fun boardPhone() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        await {
            container.secrets.set(Secret.ADYEN_API_KEY, "checkout-key")
            container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "boarding-key")
        }
        openTerminalSettings()
        waitForTag("setUpTapToPay")
        compose.onNodeWithTag("setUpTapToPay").performScrollTo().performClick()
        compose.awaitCondition("phone registration completes") {
            container.settingsState.value.terminal.paymentsAppInstallationId == FakePaymentsApp.INSTALLATION_ID
        }
        waitForTag("findSharedKey")
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `boarding fills shared-key fields and permits a read-only retry on the smallest screen`() {
        foundKey = DiscoveredKey("discovered-key", 2, "discovered secret")
        boardPhone()
        compose.awaitCondition("lookup fields reach the screen") {
            container.settingsState.value.terminal.keyIdentifier == "discovered-key"
        }
        compose.onNodeWithTag("keyIdentifier").performScrollTo().assertTextContains("discovered-key", substring = true)
        compose.onNodeWithTag("sharedKeyManual").assertDoesNotExist()
        val opened = paymentsApp.opened.size
        compose.onNodeWithTag("findSharedKey").performScrollTo().performClick()
        compose.awaitCondition("lookup retry finishes") { reads == 2 }
        assertThat(paymentsApp.opened).hasSize(opened)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("discovered secret")
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `missing permissions show manual entry while registration and a retry remain available`() {
        boardPhone()
        waitForTag("sharedKeyManual")
        compose.onNodeWithTag("keyIdentifier").performScrollTo().performTextInput("manual-key")
        compose.onNodeWithTag("passphrase").performScrollTo().assertExists()
        compose.onNodeWithTag("keyVersion").assertExists()
        foundKey = DiscoveredKey("retried-key", 3, "retried secret")
        val opened = paymentsApp.opened.size
        compose.onNodeWithTag("findSharedKey").performScrollTo().performClick()
        compose.awaitCondition("retry fills the key") { container.settingsState.value.terminal.keyIdentifier == "retried-key" }
        compose.onNodeWithTag("keyIdentifier").performScrollTo().assertTextContains("retried-key", substring = true)
        compose.onNodeWithTag("sharedKeyManual").assertDoesNotExist()
        assertThat(paymentsApp.opened).hasSize(opened)
    }
}
