package app.minimpos.app.ui

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
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeManagement
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.FakeStoreDetails
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.paymentsapp.BoardingCredentialCheck
import app.minimpos.terminal.paymentsapp.ManagementResult
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRoles
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.StoreChoice
import app.minimpos.terminal.transport.StoreListing
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

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
                CredentialLookup.Allowed(CredentialRoles(AdyenRole.required(), emptyList()))

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing = error("No terminal listing")

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup = error("No terminal key lookup")
        }

    private val management = FakeManagement()
    private val stores = FakeStoreDetails()

    @get:Rule(order = 0)
    val env = TestEnvironment(device, paymentsApp = paymentsApp, management = management, terminalDetails = details, stores = stores)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP, environment = TerminalEnvironment.TEST))
        }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun openTerminalSettings() {
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
    }

    private fun openHelp() {
        compose.onNodeWithTag("back").performClick()
        waitForTag("section_help")
        compose.onNodeWithTag("section_help").performScrollTo().performClick()
        waitForTag("helpUserGuide")
    }

    private fun returnToTerminal() {
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("section_terminal").performScrollTo().performClick()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `help links open the guides and issue tracker on AMS1`() {
        openTerminalSettings()
        openHelp()
        val application = ApplicationProvider.getApplicationContext<Application>()
        listOf(
            "helpUserGuide" to "https://minimpos.app/using.html",
            "helpTroubleshooting" to "https://minimpos.app/troubleshooting.html",
            "helpReportIssue" to "https://github.com/astiskala/minimpos/issues",
        ).forEach { (tag, url) ->
            compose.onNodeWithTag(tag).performScrollTo().performClick()
            val opened = shadowOf(application).nextStartedActivity
            assertThat(opened.action).isEqualTo(Intent.ACTION_VIEW)
            assertThat(opened.dataString).isEqualTo(url)
        }
    }

    @Test
    fun `without the Payments app, Google Play opens on it, and the app shows once installed`() {
        openTerminalSettings()
        compose.onNodeWithTag("getPaymentsAppTest").assertExists()
        compose.onNodeWithTag("getPaymentsAppLive").assertDoesNotExist()
        compose.onNodeWithTag("environment_LIVE").performScrollTo().performClick()
        waitForTag("getPaymentsAppLive")
        compose.onNodeWithTag("getPaymentsAppLive").performScrollTo().performClick()
        val opened = shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity
        assertThat(opened.action).isEqualTo(Intent.ACTION_VIEW)
        assertThat(opened.dataString).isEqualTo("https://play.google.com/store/apps/details?id=com.adyen.ipp.mobile.companion.live")
        compose.onNodeWithTag("environment_TEST").performScrollTo().performClick()
        waitForTag("getPaymentsAppTest")
        compose
            .onNodeWithTag("getPaymentsAppTest")
            .assertTextContains("Get Adyen Payments Test")
            .performScrollTo()
            .performClick()
        assertThat(shadowOf(ApplicationProvider.getApplicationContext<Application>()).nextStartedActivity.dataString)
            .isEqualTo("https://play.google.com/store/apps/details?id=com.adyen.ipp.mobile.companion.test")

        // Back in the app, the device is read again.
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        container.terminalStatus.readDevice()
        compose.waitUntilAtLeastOneExists(hasText("Adyen Payments Test (TEST)"), 15_000)
        compose.onNodeWithTag("getPaymentsAppLive").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `new Tap to Pay setup requires environment choice even with both apps installed`() {
        device.paymentsApps = TerminalEnvironment.entries.toSet()
        env.updateSettings { it.copy(terminal = it.terminal.selectEnvironment(null)) }
        openTerminalSettings()
        waitForTag("environment")
        compose.onNodeWithTag("step_2").assertDoesNotExist()
        compose.onNodeWithTag("apiKey").assertDoesNotExist()
        compose.onNodeWithTag("environment_TEST").performScrollTo().performClick()
        waitForTag("apiKey")
        compose.onNodeWithTag("step_2").assertTextContains("Adyen Payments app")
        compose.onNodeWithTag("getPaymentsAppTest").assertDoesNotExist()
        compose.onNodeWithTag("getPaymentsAppLive").assertDoesNotExist()
        assertThat(container.settingsState.value.terminal.environment).isEqualTo(TerminalEnvironment.TEST)
    }

    @Test
    fun `registration credentials have no manual entry or removal controls`() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.useCheckoutApi()
        openTerminalSettings()
        testApi()
        compose.onNodeWithTag("paymentsAppKey").assertDoesNotExist()
        compose.onNodeWithTag("forgetPaymentsAppKey").assertDoesNotExist()
    }

    private fun boardPhone() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        await { container.secrets.set(Secret.ADYEN_API_KEY, "checkout-key") }
        openTerminalSettings()
        testApi()
        waitForTag("setUpTapToPay")
        compose.onNodeWithTag("setUpTapToPay").performScrollTo().performClick()
        compose.awaitCondition("phone registration completes") {
            container.settingsState.value.terminal.paymentsAppInstallationId == FakePaymentsApp.INSTALLATION_ID
        }
        compose.waitUntilAtLeastOneExists(
            hasTestTag("tapToPayResult") and hasText(FakePaymentsApp.INSTALLATION_ID, substring = true),
            15_000,
        )
        compose.onNodeWithTag("tapToPayResultOk").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("tapToPayResult"), 15_000)
        waitForTag("keyIdentifier")
    }

    private fun testApi() {
        waitForTag("testApi")
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        waitForTag("apiResult")
        compose.onNodeWithTag("apiResultOk").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("apiResult"), 15_000)
        waitForTag("setUpTapToPay")
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `store selector fills boarding store without opening Payments app on AMS1`() {
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Merchant")) }
        await { container.secrets.set(Secret.ADYEN_API_KEY, "checkout-key") }
        stores.listing = StoreListing.Found(listOf(StoreChoice("ST1", "counter", "Main counter")))
        openTerminalSettings()
        testApi()
        compose.onNodeWithTag("findStores").performScrollTo().performClick()
        waitForTag("store_ST1")
        compose.onNodeWithTag("store_ST1").assertTextContains("Main counter", substring = true).performClick()
        compose.awaitCondition("store selection saved") { container.settingsState.value.terminal.storeId == "ST1" }
        compose.onNodeWithTag("storeId").performScrollTo().assertTextContains("ST1", substring = true)
        assertThat(paymentsApp.opened).isEmpty()
        assertThat(stores.calls).containsExactly("stores:Merchant")
    }

    private fun testConnection(expected: String) {
        compose.onNodeWithTag("testConnection").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("connectionResult") and hasText(expected, substring = true), 15_000)
        compose.onNodeWithTag("connectionResult").assertTextContains(expected, substring = true).assertTextContains("•")
        compose.onNodeWithTag("connectionResultOk").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("connectionResult"), 15_000)
        compose.onNodeWithTag("checkTapToPay").assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
    fun `confirmed missing key offers scoped guidance refresh and an explicitly redacted share on AMS1`() {
        management.credentialResult = BoardingCredentialCheck.Verified("CRED-1", "ws@Company.Test", true, false, true)
        boardPhone()
        compose.onNodeWithTag("tapClientKeyStatus").assertDoesNotExist()
        compose.onNodeWithTag("tapTroubleshootingDetails").assertDoesNotExist()
        compose.onNodeWithText("Credential saved.", substring = true).assertDoesNotExist()
        openHelp()
        compose.onNodeWithTag("tapClientKeyStatus").assertDoesNotExist()
        compose.onNodeWithTag("tapTroubleshootingDetails").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("tapClientKeyStatus") and hasText("MISSING"), 15_000)
        compose.waitUntilDoesNotExist(hasTestTag("diagnosticsLoading"), 15_000)
        compose.onNodeWithTag("diagnostic_credential.username").performScrollTo().assertTextContains("ws@Company.Test")
        compose.onNodeWithTag("diagnostic_boardingStatus").performScrollTo().assertTextContains("BOARDED")
        compose.onNodeWithText("Payments app reports boarded.", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("tapCustomerArea").assertDoesNotExist()
        val application = ApplicationProvider.getApplicationContext<Application>()
        compose.onNodeWithTag("shareTapReport").performScrollTo().performClick()
        compose.awaitCondition("report chooser opens") { shadowOf(application).peekNextStartedActivity() != null }
        val chooser = shadowOf(application).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        val send = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        val report = send.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        assertThat(report).contains("clientKeyCheck=MISSING")
        assertThat(report).contains("credential.username=ws@Company.Test")
        assertThat(report).contains("apiHasRequiredRoles=true")
        assertThat(report).contains("apiRolesPresent=[Management API - Terminal actions read")
        assertThat(report).contains("apiRolesMissing=[]")
        assertThat(report).contains("paymentsAppApiHasRequiredRole=true")
        assertThat(report).doesNotContain("boarding-key")
        assertThat(report).doesNotContain("checkout-key")
        management.credentialResult = BoardingCredentialCheck.Verified("CRED-1", "ws@Company.Test", true, true, true)
        returnToTerminal()
        testConnection("Client key present")
        openHelp()
        compose.onNodeWithTag("tapTroubleshootingDetails").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("tapClientKeyStatus") and hasText("PRESENT"), 15_000)
        assertThat(management.requests).hasSize(1)
        assertThat(management.revoked).isEmpty()
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `permission unavailable offers a troubleshooting guide without declaring a missing key in Japanese`() {
        boardPhone()
        management.credentialResult = BoardingCredentialCheck.Failed(Fault.Permission(ApiKey.PAYMENTS_APP))
        val permission = env.context.getString(R.string.tap_key_permission)
        testConnection(permission)
        openHelp()
        compose.onNodeWithTag("tapTroubleshootingDetails").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("tapClientKeyStatus") and hasText("PERMISSION_UNAVAILABLE"), 15_000)
        compose.onNodeWithTag("helpTroubleshooting").performScrollTo().assertExists()
        compose.onNodeWithText("現在確認している Tap to Pay 設定用の認証情報にクライアントキーがありません。", substring = true).assertDoesNotExist()
        assertThat(management.requests).hasSize(1)
        assertThat(management.revoked).isEmpty()
    }

    @Test
    fun `a failed reboarding says why in a dialog and asks for Tap to Pay again`() {
        boardPhone()
        management.result = ManagementResult.Failed(Fault.AdyenUnavailable(503))
        compose
            .onNodeWithTag("setUpTapToPay")
            .performScrollTo()
            .assertTextContains("Set up again")
            .performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("tapToPayResult") and hasText("HTTP 503", substring = true), 15_000)
        compose.onNodeWithTag("tapToPayResultOk").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("tapToPayResult"), 15_000)
        assertThat(container.settingsState.value.terminal.paymentsAppInstallationId).isEmpty()
        compose.onNodeWithText("Not set up yet").performScrollTo().assertExists()
        compose.onNodeWithTag("setUpTapToPay").assertTextContains("Set up Tap to Pay")
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
        compose.awaitCondition("manual key identifier is saved") {
            container.settingsState.value.terminal.keyIdentifier == "manual-key"
        }
        compose.onNodeWithTag("passphrase").performScrollTo().assertExists()
        compose.onNodeWithTag("keyVersion").assertExists()
        compose.onNodeWithTag("findSharedKey").assertDoesNotExist()
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
    }
}
