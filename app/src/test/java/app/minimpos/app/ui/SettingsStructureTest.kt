package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
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
        open("payments")
        val headingTop =
            compose
                .onNodeWithText(env.context.getString(R.string.settings_pricing).uppercase())
                .fetchSemanticsNode()
                .boundsInRoot.top
        compose.onNodeWithTag("referenceSource").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receiptTipping").assertDoesNotExist()
        compose.onNodeWithTag("showVirtualReceipt").assertDoesNotExist()
        compose.onNodeWithTag("emailCapture").assertDoesNotExist()
        back()
        open("tipping")
        compose.onNodeWithTag("terminalTipping").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("receiptTipping").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("askGivingSetting").performScrollTo().assertIsDisplayed()
        back()
        open("shopper_display")
        // An opening explanation without a heading starts where a heading's text would.
        val introTop =
            compose
                .onNodeWithText(env.context.getString(R.string.settings_shopper_display_hint))
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
        open("shopper_display")
        compose.onNodeWithTag("showVirtualReceipt").performScrollTo().assertIsOff()
        compose.onNodeWithTag("askShopperRating").performScrollTo().assertIsDisplayed()
        back()
        open("email")
        compose.onNodeWithTag("emailCapture").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("fromAddress").performScrollTo().assertIsDisplayed()
    }

    private fun open(section: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag("section_$section"), 15_000)
        compose.onNodeWithTag("section_$section").performScrollTo().performClick()
    }

    private fun back() {
        compose.onNodeWithTag("back").performClick()
    }
}
