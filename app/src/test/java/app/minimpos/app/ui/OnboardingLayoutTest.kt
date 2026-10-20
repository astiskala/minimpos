package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import app.minimpos.app.TestEnvironment
import app.minimpos.app.feature.SampleDataState
import app.minimpos.app.feature.onboarding.OnboardingChoice
import app.minimpos.app.feature.onboarding.OnboardingContent
import app.minimpos.app.feature.transfer.SetupCodesActions
import app.minimpos.app.feature.transfer.SetupCodesIntro
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.MiniScaffold
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class OnboardingLayoutTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun `terminal choices and QR guidance fit AMS1`() = fitsAms1()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Simplified Chinese choices and QR guidance fit AMS1`() = fitsAms1()

    @Test
    @Config(qualifiers = "b+zh+Hant+HK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese choices and QR guidance fit AMS1`() = fitsAms1()

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese choices and QR guidance fit AMS1`() = fitsAms1()

    /** Checks both first-run screens in one composition, as each Robolectric test pays for its own environment. */
    private fun fitsAms1() {
        var chosen: OnboardingChoice? = null
        var codes by mutableStateOf(false)
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    if (codes) {
                        MiniScaffold(title = "", onBack = null, bottomBar = { SetupCodesActions({}, {}) }) {
                            SetupCodesIntro(it)
                        }
                    } else {
                        OnboardingContent(SampleDataState(), onTerminal = true) { chosen = it }
                    }
                }
            }
        }
        listOf("onboardingSimulator", "onboardingImport", "onboardingTerminal", "onboardingContinue").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("onboardingTerminal").assertIsSelected()
        assertThat(chosen).isNull()
        compose.onNodeWithTag("onboardingContinue").performClick()
        assertThat(chosen).isEqualTo(OnboardingChoice.TERMINAL)

        codes = true
        compose.waitForIdle()
        compose.onNodeWithTag("startSetupScan").assertIsDisplayed()
        compose.onNodeWithTag("manualSetup").assertIsDisplayed()
        val hint =
            compose
                .onNodeWithTag("setupTransferCodeHint")
                .assertIsDisplayed()
                .getUnclippedBoundsInRoot()
        val scan = compose.onNodeWithTag("startSetupScan").getUnclippedBoundsInRoot()
        assertThat(hint.bottom.value).isAtMost(scan.top.value)
    }

    @Test
    fun `saving disables choices and Continue`() {
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    OnboardingContent(SampleDataState(running = true), onTerminal = false) { error("Cannot select while saving") }
                }
            }
        }
        listOf("onboardingSimulator", "onboardingImport", "onboardingTerminal", "onboardingContinue").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }
}
