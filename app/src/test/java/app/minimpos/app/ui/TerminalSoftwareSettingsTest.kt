package app.minimpos.app.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.FakeTerminal
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.settings.ReceiptTipping
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class TerminalSoftwareSettingsTest {
    private val terminal = FakeTerminal(passphrase = "passphrase", softwareVersion = "1.95.1")

    @get:Rule(order = 0)
    val env = TestEnvironment(terminal = terminal)

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private fun open(section: String) {
        env.useLinks()
        env.updateSettings {
            it.copy(
                payment =
                    it.payment.copy(
                        terminalTipping = ReceiptTipping.DEFAULT_ON,
                        dynamicTipOverride = true,
                        tipOnPreTax = true,
                        audioGuidance = true,
                        mealVouchers = true,
                        mealVoucherAccountSelection = true,
                    ),
            )
        }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("section_$section").performScrollTo().performClick()
    }

    private fun checkSoftware(version: String?) {
        terminal.softwareVersion = version
        await { env.container.terminalStatus.check() }
        await {
            env.container.terminalStatus.state
                .first { it.softwareVersion == version }
        }
    }

    @Test
    fun `tip override settings require verified 101 software and preserve stored choices`() {
        open("tipping")
        checkSoftware("1.95.1")
        listOf("dynamicTipOverride", "tipOnPreTax").forEach {
            compose
                .onNodeWithTag(it)
                .performScrollTo()
                .assertIsNotEnabled()
                .assertIsOff()
        }
        compose.onNodeWithTag("terminalTipping").performScrollTo().assertIsEnabled()
        compose.onAllNodesWithText("Requires terminal software 1.101 or later. Installed: 1.95.1.").assertCountEquals(2)
        assertThat(env.container.settingsState.value.payment.dynamicTipOverride).isTrue()
        assertThat(env.container.settingsState.value.payment.tipOnPreTax).isTrue()
        checkSoftware("1.97.0")
        compose.onNodeWithTag("dynamicTipOverride").performScrollTo().assertIsNotEnabled()
        checkSoftware("1.101.0")
        listOf("dynamicTipOverride", "tipOnPreTax").forEach {
            compose
                .onNodeWithTag(it)
                .performScrollTo()
                .assertIsEnabled()
                .assertIsOn()
        }
    }

    @Test
    fun `unknown software cannot enable options and checking the connection unlocks supported audio guidance`() {
        open("payments")
        checkSoftware(null)
        compose
            .onNodeWithTag("audioGuidanceSetting")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertIsOff()
        checkSoftware("1.116.9")
        compose.onNodeWithTag("audioGuidanceSetting").performScrollTo().assertIsNotEnabled()
        checkSoftware("1.117.0")
        compose
            .onNodeWithTag("audioGuidanceSetting")
            .performScrollTo()
            .assertIsEnabled()
            .assertIsOn()
        assertThat(env.container.settingsState.value.payment.audioGuidance).isTrue()
    }

    @Test
    fun `Brazilian account selection needs 129 without disabling ordinary meal vouchers`() {
        open("payments")
        checkSoftware("1.128.9")
        compose
            .onNodeWithTag("mealVouchers")
            .performScrollTo()
            .assertIsEnabled()
            .assertIsOn()
        compose
            .onNodeWithTag("mealVoucherAccountSelection")
            .performScrollTo()
            .assertIsNotEnabled()
            .assertIsOff()
        checkSoftware("1.129.0")
        compose
            .onNodeWithTag("mealVoucherAccountSelection")
            .performScrollTo()
            .assertIsEnabled()
            .assertIsOn()
    }
}
