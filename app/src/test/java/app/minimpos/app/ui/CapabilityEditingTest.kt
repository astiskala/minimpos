package app.minimpos.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.Failure
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.app.feature.settings.CapabilitiesActions
import app.minimpos.app.feature.settings.CapabilityEditor
import app.minimpos.app.feature.settings.CapabilityFeature
import app.minimpos.app.feature.settings.rememberCapabilityEdit
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.DestinationCapabilities
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class CapabilityEditingTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `admin relocking retains a non-secret capability draft`() {
        env.useSimulator()
        await { env.container.pinManager.setPin("1357") }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        unlock()
        compose.onNodeWithTag("section_capabilities").performScrollTo().performClick()
        compose.awaitCondition("capabilities checked") { env.container.capabilityDiscovery.state.value.editable }
        compose.onNodeWithTag("cap_dcc").performScrollTo().performClick()
        compose.onNodeWithTag("capDccEnable").performScrollTo().performClick()
        compose.runOnUiThread { env.container.sessionLock.lock() }
        compose.onNodeWithTag("capDccEnable").assertDoesNotExist()
        unlock()
        compose.onNodeWithTag("capDccEnable").performScrollTo().assertIsOn()
        compose.onNodeWithTag("saveCapDcc").performScrollTo().assertIsEnabled()
    }

    private fun unlock() {
        compose.awaitCondition("admin PIN displayed") { compose.onAllNodesWithTag("pin_1").fetchSemanticsNodes().isNotEmpty() }
        listOf(1, 3, 5, 7).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        compose.awaitCondition("admin PIN accepted") { compose.onAllNodesWithTag("pin_1").fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun `explicit refresh preserves a dirty DCC draft without sending it`() {
        env.useSimulator()
        val reading =
            mutableStateOf(
                AdyenCapabilitiesState(
                    checked = true,
                    identity = "test-refresh",
                    simulated = true,
                    capabilities = DestinationCapabilities(supportsTerminalFeatures = true),
                ),
            )
        val actions = mutableStateOf(CapabilitiesActions())
        var refreshes = 0
        var sends = 0
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    val edit = rememberCapabilityEdit(CapabilityFeature.DCC, reading.value, actions.value, "AUD")
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        CapabilityEditor(
                            CapabilityFeature.DCC,
                            reading.value,
                            actions.value,
                            "AUD",
                            edit.value,
                            { edit.value = it },
                            onRefresh = {
                                refreshes++
                                reading.value = reading.value.copy(checking = true)
                            },
                            onDismissResult = {},
                            onSave = { sends++ },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("saveCapDcc").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("capDccEnable").performScrollTo().performClick()
        compose.onNodeWithTag("refreshCapabilities").performScrollTo().performClick()
        compose.onNodeWithTag("capDccEnable").performScrollTo().assertIsOn()
        compose.onNodeWithTag("saveCapDcc").performScrollTo().assertIsNotEnabled()
        compose.runOnUiThread {
            reading.value = reading.value.copy(checking = false)
            actions.value = actions.value.copy(readRevision = actions.value.readRevision + 1)
        }
        compose.onNodeWithTag("saveCapDcc").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("capDccEnable").performScrollTo().assertIsOn()
        assertThat(reading.value.dccEnabled).isFalse()
        assertThat(refreshes).isEqualTo(1)
        assertThat(sends).isEqualTo(0)
    }

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `keep editing retains invalid MOTO text while discard opens the exact original local control in Japanese`() {
        open("cap_moto")
        compose.onNodeWithTag("capMotoMaxAmountInherited").performScrollTo().performClick()
        compose.onNodeWithTag("capMotoMaxAmount").performScrollTo().performTextInput("2147483648")
        compose.onNodeWithTag("openSetting_allowMoto").performScrollTo().performClick()
        compose.onNodeWithText(env.context.getString(R.string.capability_keep_editing)).performClick()
        compose.onNodeWithTag("capMotoMaxAmount").performScrollTo().assertTextContains("2147483648")
        compose.onNodeWithTag("saveCapMoto").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("openSetting_allowMoto").performScrollTo().performClick()
        compose.onNodeWithText(env.context.getString(R.string.capability_discard)).performClick()
        compose.onNodeWithTag("allowMoto").assertIsDisplayed()
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("capMotoMaxAmountInherited").performScrollTo().assertIsOn()
        compose.onNodeWithTag("saveCapMoto").performScrollTo().assertIsNotEnabled()
    }

    @Test
    @Config(qualifiers = "b+zh+Hant-w320dp-h460dp-hdpi")
    fun `shopper display link reveals its exact autosaved control in Traditional Chinese`() {
        open("cap_shopper_display")
        compose.onNodeWithTag("openSetting_askShopperRating").performScrollTo().performClick()
        compose.onNodeWithTag("askShopperRating").assertIsDisplayed().performClick()
        compose.awaitCondition("local shopper preference saved") { env.container.settingsState.value.payment.askShopperRating }
    }

    @Test
    fun `unknown save is never retried and only a successful explicit refresh resolves it`() {
        env.useSimulator()
        val reading =
            mutableStateOf(
                AdyenCapabilitiesState(
                    checked = true,
                    identity = "test-editor",
                    simulated = true,
                    capabilities = DestinationCapabilities(supportsTerminalFeatures = true),
                ),
            )
        val actions = mutableStateOf(CapabilitiesActions())
        var sends = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    val edit = rememberCapabilityEdit(CapabilityFeature.DCC, reading.value, actions.value, "AUD")
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        CapabilityEditor(
                            CapabilityFeature.DCC,
                            reading.value,
                            actions.value,
                            "AUD",
                            edit.value,
                            { edit.value = it },
                            onRefresh = {
                                reading.value = reading.value.copy(dccEnabled = true)
                                actions.value = actions.value.copy(readRevision = actions.value.readRevision + 1)
                            },
                            onDismissResult = { actions.value = actions.value.copy(dcc = ActionState()) },
                            onSave = {
                                sends++
                                actions.value =
                                    actions.value.copy(
                                        dcc = ActionState(outcome = ActionOutcome.Unconfirmed(Failure.Remote(Fault.TimedOut))),
                                    )
                            },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("capDccEnable").performScrollTo().performClick()
        compose.onNodeWithTag("saveCapDcc").performScrollTo().performClick()
        compose.onNodeWithTag("capabilityResult").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("saveCapDcc").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("refreshCapabilities").performScrollTo().performClick()
        compose.onNodeWithTag("capDccEnable").performScrollTo().assertIsOn()
        compose.onNodeWithTag("saveCapDcc").performScrollTo().assertIsNotEnabled()
        assertThat(sends).isEqualTo(1)
    }

    private fun open(feature: String) {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("section_capabilities").performScrollTo().performClick()
        compose.awaitCondition("capabilities checked") { env.container.capabilityDiscovery.state.value.editable }
        compose.onNodeWithTag(feature).performScrollTo().performClick()
    }
}
