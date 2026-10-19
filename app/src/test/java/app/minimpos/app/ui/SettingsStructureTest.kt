package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.settings.TerminalMode
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SettingsStructureTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `checkout workflows have dedicated sections and email collection lives with delivery`() = sectionsFit()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese settings sections fit AMS1`() = sectionsFit()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese settings sections fit AMS1`() = sectionsFit()

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese settings sections fit AMS1`() = sectionsFit()

    @Test
    fun `network Help refreshes collapsed diagnostics on every expansion without result dialogs`() = diagnostics(TerminalMode.TERMINAL)

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `Japanese cloud Help has expandable support details on AMS1`() = diagnostics(TerminalMode.CLOUD)

    @Test
    fun `simulator Help has expandable support details`() = diagnostics(TerminalMode.SIMULATOR)

    private fun diagnostics(mode: TerminalMode) {
        env.useCheckoutApi()
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = mode)) }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        open("help", "tapTroubleshootingDetails")
        compose.onNodeWithTag("shareTapReport").assertDoesNotExist()
        repeat(2) {
            compose.onNodeWithTag("tapTroubleshootingDetails").performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasText("integration=${mode.name}"), 15_000)
            compose.waitUntilAtLeastOneExists(hasText("merchantAccount=HarbourCoffeeCOM"), 15_000)
            compose.waitUntilDoesNotExist(hasTestTag("diagnosticsLoading"), 15_000)
            compose.onNodeWithTag("shareTapReport").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("testConnection").assertDoesNotExist()
            compose.onNodeWithTag("connectionResult").assertDoesNotExist()
            compose.onNodeWithTag("tapClientKeyStatus").assertDoesNotExist()
            compose.onNodeWithTag("tapTroubleshootingDetails").performScrollTo().performClick()
            compose.onNodeWithTag("shareTapReport").assertDoesNotExist()
        }
    }

    private fun sectionsFit() {
        env.useSimulator()
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performClick()
        listOf(
            "terminal",
            "capabilities",
            "payments",
            "tax",
            "tipping",
            "shopper_display",
            "receipts",
            "email",
            "security",
            "data",
            "about",
        ).forEach { section ->
            compose.onNodeWithTag("section_$section").performScrollTo().assertHeightIsEqualTo(72.dp)
        }
        compose.onNodeWithTag("appLanguage").performScrollTo().assertHeightIsEqualTo(72.dp)
        open("payments", "pricingHeader")
        val headingTop =
            compose
                .onNodeWithTag("pricingHeader")
                .fetchSemanticsNode()
                .boundsInRoot.top
        compose.onNodeWithTag("referenceSource").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receiptTipping").assertDoesNotExist()
        compose.onNodeWithTag("showVirtualReceipt").assertDoesNotExist()
        compose.onNodeWithTag("emailCapture").assertDoesNotExist()
        back()
        open("tipping", "terminalTipping")
        compose.onNodeWithTag("terminalTipping").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receiptTipping").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("askGivingSetting").performScrollTo().assertIsDisplayed()
        back()
        open("shopper_display", "settingIntro")
        // An opening explanation without a heading starts where a heading's text would.
        val introTop =
            compose
                .onNodeWithTag("settingIntro")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertThat(introTop).isWithin(1f).of(headingTop)
        // The virtual receipt starts on; switching it off is kept.
        compose
            .onNodeWithTag("showVirtualReceipt")
            .performScrollTo()
            .assertIsOn()
            .performClick()
        compose.awaitCondition("shopper display saved") { !env.container.settingsState.value.payment.showVirtualReceipt }
        back()
        open("shopper_display", "settingIntro")
        compose.onNodeWithTag("showVirtualReceipt").performScrollTo().assertIsOff()
        compose.onNodeWithTag("askShopperRating").performScrollTo().assertIsDisplayed()
        back()
        open("email", "emailCapture")
        compose.onNodeWithTag("emailCapture").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fromAddress").performScrollTo().assertIsDisplayed()
    }

    private fun open(
        section: String,
        content: String,
    ) {
        compose.waitUntilAtLeastOneExists(hasTestTag("section_$section"), 15_000)
        compose.onNodeWithTag("section_$section").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(content), 15_000)
    }

    private fun back() {
        compose.onNodeWithTag("back").performClick()
    }
}
