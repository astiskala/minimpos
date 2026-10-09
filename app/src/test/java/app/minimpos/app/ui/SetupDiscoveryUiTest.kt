package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.minimpos.app.FakeDevice
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.terminal.TerminalConnection
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.simulator.SimulatedModifications
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SetupDiscoveryUiTest {
    private val phone = FakeDevice()
    private var apiFailure: Fault? = null
    private var apiRelease: CompletableDeferred<Unit>? = null
    private var apiEntered = CompletableDeferred<Unit>()
    private val modifications =
        object : PaymentModifications by SimulatedModifications() {
            override suspend fun verify(): Fault? {
                apiEntered.complete(Unit)
                apiRelease?.await()
                return apiFailure
            }
        }

    @get:Rule(order = 0)
    val env = TestEnvironment(phone, modifications = modifications)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun chooseMode(
        mode: TerminalMode,
        stored: TerminalMode = mode,
    ) {
        compose.onNodeWithTag("terminalMode").performScrollTo().performClick()
        compose.onNodeWithTag("terminalMode_$mode").performClick()
        compose.awaitCondition("the destination is saved") { env.container.settingsState.value.terminal.mode == stored }
    }

    private fun assertSteps(vararg titles: String) =
        titles.forEachIndexed { index, title -> compose.onNodeWithTag("step_${index + 1}").assertTextContains(title) }

    @Test
    @Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
    fun `terminal setup steps follow the selected destination`() {
        val container = env.container
        env.useSimulator()
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("terminalMode")
        compose.onNodeWithTag("terminalMode").assertTextContains("Simulator", substring = true)
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithTag("advanced").assertDoesNotExist()

        assertNetworkSteps()
        assertCloudSteps()
        assertTapToPaySteps()
        // Choosing the device's own default stores Automatic, so it keeps following the device.
        chooseMode(TerminalMode.SIMULATOR, TerminalMode.AUTO)
    }

    private fun assertNetworkSteps() {
        chooseMode(TerminalMode.TERMINAL)
        waitForTag("environment_TEST")
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("environment_TEST").performScrollTo().performClick()
        compose.awaitCondition("the TEST environment is saved") {
            env.container.settingsState.value.terminal.environment ==
                TerminalEnvironment.TEST
        }
        waitForTag("apiKey")
        assertSteps("Environment", "Adyen API")
        compose.onNodeWithTag("step_3").assertDoesNotExist()
        compose.onNodeWithTag("merchantAccount").performScrollTo().performTextInput("Merchant")
        compose.awaitCondition("the network account is saved") {
            env.container.settingsState.value.terminal.merchantAccount == "Merchant"
        }
        compose.onNodeWithTag("apiKey").performScrollTo().performTextInput("key")
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        waitForTag("host")
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithTag("host").performScrollTo().performTextInput("192.168.1.42")
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithTag("poiId").performScrollTo().performTextInput("S1F2-000158213605014")
        waitForTag("keyIdentifier")
        assertSteps("Environment", "Adyen API", "Terminal", "Shared key")
        compose.onNodeWithTag("step_5").assertDoesNotExist()
    }

    private fun assertCloudSteps() {
        chooseMode(TerminalMode.CLOUD)
        waitForTag("environment_LIVE")
        compose.onNodeWithTag("apiKey").assertExists()
        compose.onNodeWithTag("environment_LIVE").performScrollTo().performClick()
        compose.awaitCondition("the LIVE environment is saved") {
            env.container.settingsState.value.terminal.environment ==
                TerminalEnvironment.LIVE
        }
        waitForTag("apiKey")
        compose.onNodeWithTag("apiKey").assertExists()
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithTag("step_3").assertExists()
        compose.onNodeWithTag("testApi").assertExists()
        compose.onNodeWithTag("livePrefix").performScrollTo().performTextInput("prefix")
        compose.awaitCondition("the cloud prefix is saved") {
            env.container.settingsState.value.terminal.liveUrlPrefix == "prefix"
        }
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        waitForTag("findTerminals")
        compose.onNodeWithTag("testConnection").assertExists()
        assertSteps("Environment", "Adyen API", "Terminal")
        compose.onNodeWithTag("step_4").assertDoesNotExist()
        compose.onNodeWithTag("livePrefix").assertExists()
    }

    private fun assertTapToPaySteps() {
        chooseMode(TerminalMode.PAYMENTS_APP)
        waitForTag("getPaymentsAppTest")
        compose.onNodeWithText("Not installed").assertExists()
        compose.onNodeWithTag("getPaymentsAppTest").assertExists()
        compose.onNodeWithTag("getPaymentsAppLive").assertExists()
        assertSteps("Adyen Payments app", "Adyen API")
        compose.onNodeWithTag("step_2").assertExists()
        phone.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.container.terminalStatus.readDevice()
        compose.awaitCondition("the TEST Payments app is detected") {
            env.container.terminalStatus.state.value.environment == TerminalEnvironment.TEST
        }
        compose.waitUntilAtLeastOneExists(hasText("Adyen Payments Test (TEST)"), 15_000)
        waitForTag("apiKey")
        assertSteps("Adyen Payments app", "Adyen API")
        compose.onNodeWithTag("step_3").assertDoesNotExist()
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        waitForTag("setUpTapToPay")
        assertSteps("Adyen Payments app", "Adyen API", "Tap to Pay")
        compose.onNodeWithTag("environment").assertDoesNotExist()
        compose.onNodeWithTag("discoverSetup").assertDoesNotExist()
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithTag("paymentsAppKey").assertExists()
        compose.onNodeWithTag("host").assertDoesNotExist()
    }

    @Test
    fun `network TEST setup starts with an explicit environment on the smallest screen`() =
        chooseEnvironment(TerminalMode.TERMINAL, TerminalEnvironment.TEST)

    @Test
    fun `cloud LIVE setup shows the account and live prefix after choosing the environment`() =
        chooseEnvironment(TerminalMode.CLOUD, TerminalEnvironment.LIVE)

    @Test
    @Config(qualifiers = "en-rAU-w800dp-h1200dp-xhdpi")
    fun `a tablet selects its environment before network setup`() = chooseEnvironment(TerminalMode.TERMINAL, TerminalEnvironment.LIVE)

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese cloud setup selects TEST before credentials`() = chooseEnvironment(TerminalMode.CLOUD, TerminalEnvironment.TEST)

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `Japanese network setup selects LIVE before credentials`() = chooseEnvironment(TerminalMode.TERMINAL, TerminalEnvironment.LIVE)

    private fun chooseEnvironment(
        mode: TerminalMode,
        environment: TerminalEnvironment,
    ) {
        val container = env.container
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = mode)) }
        container.start()
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        compose.waitUntilAtLeastOneExists(
            hasTestTag("section_terminal") and hasText(env.context.getString(R.string.settings_status_not_set_up), substring = true),
            15_000,
        )
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("step_1")
        compose.onNodeWithTag("step_1").assertTextContains(env.context.getString(R.string.settings_environment))
        assertEnvironmentHelpOrder(environment)
        compose.onNodeWithTag("apiKey").assertDoesNotExist()
        compose.onNodeWithTag("connectionStatus").assertDoesNotExist()
        compose.onNodeWithTag("environment_$environment").assertIsDisplayed().performClick()
        waitForTag("apiKey")
        compose.onNodeWithTag("step_2").assertTextContains(env.context.getString(R.string.settings_api))
        compose.onNodeWithTag("merchantAccount").assertExists()
        compose.onNodeWithTag("step_3").assertDoesNotExist()
        compose.awaitCondition("the selected environment is saved") {
            container.settingsState.value.terminal.environment == environment
        }
        assertRoleHelp(mode)
        assertHintBeforeField("merchantAccountHint", "merchantAccount")
        if (environment == TerminalEnvironment.LIVE) {
            assertHintBeforeField("livePrefixHint", "livePrefix")
            compose.onNodeWithTag("livePrefix").performScrollTo().assertIsDisplayed()
        } else {
            compose.onNodeWithTag("livePrefix").assertDoesNotExist()
        }
        assertThat(env.context.getString(R.string.settings_api_hint)).doesNotContain("API credentials")
    }

    private fun assertHintBeforeField(
        hintTag: String,
        fieldTag: String,
    ) {
        compose.onNodeWithTag(hintTag).performScrollTo()
        val hint = compose.onNodeWithTag(hintTag).getUnclippedBoundsInRoot()
        val field = compose.onNodeWithTag(fieldTag).getUnclippedBoundsInRoot()
        assertThat(hint.bottom).isAtMost(field.top)
    }

    private fun assertEnvironmentHelpOrder(environment: TerminalEnvironment) {
        val heading = compose.onNodeWithTag("step_1").fetchSemanticsNode().boundsInRoot
        val hint = compose.onNodeWithTag("environmentHint").fetchSemanticsNode().boundsInRoot
        val choice = compose.onNodeWithTag("environment_$environment").fetchSemanticsNode().boundsInRoot
        assertThat(hint.top).isAtLeast(heading.bottom)
        assertThat(hint.bottom).isAtMost(choice.top)
    }

    private fun assertRoleHelp(mode: TerminalMode) {
        compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_terminals), substring = true).assertExists()
        compose.onNodeWithText("Checkout webservice role", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Required for real payments.", substring = true).assertDoesNotExist()
        if (mode == TerminalMode.CLOUD) {
            compose.onNodeWithTag("roleCloud").assertExists()
            compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_shared_key), substring = true).assertExists()
        } else {
            compose.onNodeWithTag("roleCloud").assertDoesNotExist()
            compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_settings), substring = true).assertExists()
            compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_shared_key), substring = true).assertExists()
        }
    }

    @Test
    fun `Tap to Pay keeps boarding hidden until its API test succeeds without terminal discovery`() {
        phone.paymentsApps = setOf(TerminalEnvironment.TEST)
        val container = env.container
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP)) }
        container.start()
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("step_1")
        compose.onNodeWithTag("step_1").assertIsDisplayed().assertTextContains("Adyen Payments app")
        compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_settings), substring = true).assertExists()
        compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_shared_key), substring = true).assertExists()
        compose.onNodeWithText(env.context.getString(R.string.settings_adyen_role_terminals), substring = true).assertExists()
        compose.onNodeWithTag("environment").assertDoesNotExist()
        compose.onNodeWithTag("discoverSetup").assertDoesNotExist()
        compose.onNodeWithTag("merchantAccount").performScrollTo().performTextInput("Merchant")
        compose.awaitCondition("the account is saved") { container.settingsState.value.terminal.merchantAccount == "Merchant" }
        apiFailure = Fault.Credential(ApiKey.ADYEN)
        compose.onNodeWithTag("apiKey").performScrollTo().performTextInput(" demo-key ")
        compose
            .onNodeWithTag("testApi")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.awaitCondition("the API key is saved without discovery") {
            await { container.secrets.get(Secret.ADYEN_API_KEY) } == "demo-key"
        }
        compose.waitUntilAtLeastOneExists(
            hasTestTag("apiResult") and hasText(env.context.getString(R.string.fault_credential_adyen), substring = true),
            15_000,
        )
        compose.onNodeWithTag("step_3").assertDoesNotExist()
        apiFailure = null
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        waitForTag("setUpTapToPay")
        compose.awaitCondition("the unregistered phone needs setup") {
            container.terminalStatus.state.value.connection is TerminalConnection.NotSetUp
        }
        compose.onNodeWithTag("connectionStatus").assertDoesNotExist()
        compose.onNodeWithText(env.context.getString(R.string.settings_not_boarded)).assertExists()
        compose.onNodeWithTag("terminalsResult").assertDoesNotExist()
        compose.onNodeWithTag("step_3").assertExists()
        compose.onNodeWithTag("step_4").assertDoesNotExist()
        // Once unlocked, retesting cannot discard a secret draft in a later step.
        compose.onNodeWithTag("paymentsAppKey").performScrollTo().performTextInput("boarding-draft")
        apiFailure = Fault.AdyenUnavailable(503)
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(
            hasTestTag("apiResult") and hasText(env.context.getString(R.string.fault_adyen_unavailable), substring = true),
            15_000,
        )
        compose.onNodeWithTag("paymentsAppKey").performScrollTo().assertTextContains("boarding-draft", substring = true)
    }

    @Test
    fun `discovery stays hidden until API testing succeeds then refreshes manual fields`() {
        val container = env.container
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST)) }
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("apiKey")
        compose.onNodeWithTag("discoverSetup").assertDoesNotExist()
        compose.onNodeWithTag("merchantAccount").performScrollTo().performTextInput("Merchant")
        compose.awaitCondition("the account is saved") { container.settingsState.value.terminal.merchantAccount == "Merchant" }
        compose.onNodeWithTag("apiKey").performScrollTo().performTextInput("discovery-key")
        apiFailure = Fault.Credential(ApiKey.ADYEN)
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(
            hasTestTag("apiResult") and hasText(env.context.getString(R.string.fault_credential_adyen), substring = true),
            15_000,
        )
        compose.onNodeWithTag("discoverSetup").assertDoesNotExist()
        apiFailure = null
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        waitForTag("discoverSetup")
        compose.onNodeWithTag("discoverSetup").performScrollTo().performClick()
        waitForTag("terminal_S1F2-000158213605014")
        compose.onNodeWithTag("terminal_S1F2-000158213605014").performClick()
        compose.awaitCondition("discovered connection details are saved") {
            val terminal = container.settingsState.value.terminal
            terminal.host == "192.168.1.42" && terminal.poiIdOverride == "S1F2-000158213605014"
        }
        compose.waitUntilAtLeastOneExists(hasTestTag("host") and hasText("192.168.1.42", substring = true), 15_000)
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("apiResult") and hasText("API key works", substring = true), 15_000)
        compose.onNodeWithTag("host").performScrollTo().assertTextContains("192.168.1.42", substring = true)
        compose.onNodeWithTag("poiId").assertTextContains("S1F2-000158213605014", substring = true)
        compose.onNodeWithTag("merchantAccount").performScrollTo().assertTextContains("Merchant", substring = true)
    }

    @Test
    fun `an API test still running or completed for a changed account cannot unlock terminal setup`() =
        verifyChangedAccount(holdRetest = false)

    @Test
    fun `a running retest cannot reuse success for the previous account`() = verifyChangedAccount(holdRetest = true)

    private fun verifyChangedAccount(holdRetest: Boolean) {
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST))
        }
        env.useCheckoutApi()
        apiRelease = CompletableDeferred()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("testApi")
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        compose.awaitCondition("API check starts") { apiEntered.isCompleted }
        compose.onNodeWithTag("step_3").assertDoesNotExist()
        compose.onNodeWithTag("merchantAccount").performScrollTo().performTextReplacement("ChangedMerchant")
        compose.awaitCondition("changed account reaches settings") {
            env.container.settingsState.value.terminal.merchantAccount == "ChangedMerchant"
        }
        apiRelease?.complete(Unit)
        compose.waitUntilAtLeastOneExists(hasTestTag("apiResult") and hasText(env.context.getString(R.string.settings_api_ok)), 15_000)
        compose.onNodeWithTag("step_3").assertDoesNotExist()
        compose.onNodeWithTag("discoverSetup").assertDoesNotExist()
        if (holdRetest) {
            apiEntered = CompletableDeferred()
            apiRelease = CompletableDeferred()
        }
        compose.onNodeWithTag("testApi").performScrollTo().performClick()
        if (holdRetest) {
            compose.awaitCondition("the new account test starts") { apiEntered.isCompleted }
            compose.onNodeWithTag("step_3").assertDoesNotExist()
            apiRelease?.complete(Unit)
        }
        waitForTag("host")
    }

    @Test
    fun `installing both Payments apps leaves only the first step visible`() {
        phone.paymentsApps = TerminalEnvironment.entries.toSet()
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP)) }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        waitForTag("step_1")
        compose.onNodeWithTag("step_2").assertDoesNotExist()
        compose.onNodeWithTag("apiKey").assertDoesNotExist()
    }
}
