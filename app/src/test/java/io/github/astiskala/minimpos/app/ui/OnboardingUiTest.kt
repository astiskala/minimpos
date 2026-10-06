package io.github.astiskala.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class OnboardingUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment(onboardingCompleted = false)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun waitFor(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    @Test
    fun `all first-run choices fit AMS1 and simulator samples can be removed from Data`() {
        compose.setContent { MiniMposApp(env.container) }
        waitFor("onboardingSimulator")
        listOf("onboardingSimulator", "onboardingImport", "onboardingTerminal").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("onboardingSimulator").performClick()
        waitFor("settings")
        compose.awaitCondition("onboarding is persisted") { env.container.settingsState.value.onboardingCompleted }
        assertThat(env.container.terminalStatus.state.value.mode).isEqualTo(TerminalMode.SIMULATOR)
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).hasSize(4)

        compose.onNodeWithTag("settings").performClick()
        waitFor("section_data")
        compose.onNodeWithTag("section_data").performScrollTo().performClick()
        waitFor("addSamples")
        compose.onNodeWithTag("addSamples").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(
            hasText("Sample data is ready. Existing samples were kept."),
            15_000,
        )
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).hasSize(4)
        compose.onNodeWithTag("purgeSamples").performScrollTo().performClick()
        compose.onNodeWithTag("confirm").performClick()
        compose.awaitCondition("samples are purged") {
            await {
                env.container.catalog.products
                    .first()
            }.isEmpty()
        }
        assertThat(
            await {
                env.container.database
                    .saleDao()
                    .sales()
                    .first()
            },
        ).isEmpty()
        assertThat(env.container.settingsState.value.onboardingCompleted).isTrue()
    }

    @Test
    fun `first-run import opens the scanner without adding samples`() {
        compose.setContent { MiniMposApp(env.container) }
        waitFor("onboardingImport")
        compose.onNodeWithTag("onboardingImport").performClick()
        compose.waitUntilAtLeastOneExists(
            hasText("Set up from another device"),
            15_000,
        )
        compose.awaitCondition("import choice is persisted") { env.container.settingsState.value.onboardingCompleted }
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).isEmpty()
        compose.onNodeWithTag("back").performClick()
        waitFor("settings")
        compose.onNodeWithTag("onboardingImport").assertDoesNotExist()
        compose.onNodeWithTag("onboardingSimulator").assertDoesNotExist()
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).isEmpty()
    }

    @Test
    fun `first-run terminal setup opens connection settings without adding samples`() {
        compose.setContent { MiniMposApp(env.container) }
        waitFor("onboardingTerminal")
        compose.onNodeWithTag("onboardingTerminal").performClick()
        waitFor("scanSetup")
        compose.awaitCondition("terminal choice is persisted") { env.container.settingsState.value.onboardingCompleted }
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).isEmpty()
        assertThat(
            await {
                env.container.database
                    .saleDao()
                    .sales()
                    .first()
            },
        ).isEmpty()
        compose.onNodeWithTag("back").performClick()
        waitFor("settings")
        compose.onNodeWithTag("onboardingTerminal").assertDoesNotExist()
        compose.onNodeWithTag("onboardingImport").assertDoesNotExist()
        compose.onNodeWithTag("onboardingSimulator").assertDoesNotExist()
    }
}
