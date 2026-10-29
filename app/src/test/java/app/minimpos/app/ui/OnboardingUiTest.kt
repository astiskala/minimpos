package app.minimpos.app.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.sales
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

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
    fun `welcome is a root and restoration keeps the selected first-run choice`() {
        lateinit var back: OnBackPressedDispatcher
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MiniMposApp(env.container)
        }
        waitFor("onboardingContinue")
        compose.runOnUiThread { assertThat(back.hasEnabledCallbacks()).isFalse() }
        compose.onNodeWithTag("onboardingImport").performClick()
        restoration.emulateSavedInstanceStateRestore()
        waitFor("onboardingContinue")
        compose.onNodeWithTag("onboardingImport").assertIsSelected()
        assertThat(env.container.settingsState.value.onboardingCompleted).isFalse()
    }

    @Test
    fun `setup Back returns to the selected choice even after restoration and Home finishes onboarding`() {
        lateinit var back: OnBackPressedDispatcher
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            MiniMposApp(env.container)
        }
        waitFor("onboardingTerminal")
        compose.onNodeWithTag("onboardingTerminal").performClick()
        compose.onNodeWithTag("onboardingContinue").performClick()
        waitFor("scanSetup")
        compose.awaitCondition("choice persisted") { env.container.settingsState.value.onboardingCompleted }
        restoration.emulateSavedInstanceStateRestore()
        waitFor("scanSetup")
        compose.runOnUiThread { back.onBackPressed() }
        waitFor("onboardingContinue")
        compose.onNodeWithTag("onboardingTerminal").assertIsSelected()
        // Completion records the confirmed intent; it must not discard an active wizard's parent on rotation.
        restoration.emulateSavedInstanceStateRestore()
        waitFor("onboardingContinue")
        compose.onNodeWithTag("onboardingContinue").performClick()
        waitFor("finishOnboarding")
        compose.onNodeWithTag("finishOnboarding").performClick()
        waitFor("newSale")
        restoration.emulateSavedInstanceStateRestore()
        waitFor("newSale")
        compose.onNodeWithTag("onboardingContinue").assertDoesNotExist()
        compose.runOnUiThread { assertThat(back.hasEnabledCallbacks()).isFalse() }
    }

    @Test
    fun `manual setup from first-run import retains the selected import choice on Back`() {
        compose.setContent { MiniMposApp(env.container) }
        waitFor("onboardingImport")
        compose.onNodeWithTag("onboardingImport").performClick()
        compose.onNodeWithTag("onboardingContinue").performClick()
        waitFor("manualSetup")
        compose.onNodeWithTag("manualSetup").performClick()
        waitFor("scanSetup")
        compose.onNodeWithTag("back").performClick()
        waitFor("onboardingContinue")
        compose.onNodeWithTag("onboardingImport").assertIsSelected()
    }

    @Test
    fun `leaving demo reviews data and opens setup without changing merchant or sample data`() {
        compose.setContent { MiniMposApp(env.container) }
        waitFor("onboardingContinue")
        compose.onNodeWithTag("onboardingContinue").performClick()
        waitFor("settings")
        await {
            val tax =
                env.container.catalog.taxRates
                    .first()
                    .first()
            env.container.catalog.saveProduct(ProductEntity(name = "Merchant product", priceMinor = 100, taxRateId = tax.id))
        }
        val products =
            await {
                env.container.catalog.products
                    .first()
            }
        val sales =
            await {
                env.container.database
                    .saleDao()
                    .sales()
                    .first()
            }
        val settings = env.container.settingsState.value
        compose.onNodeWithTag("settings").performClick()
        waitFor("section_simulator")
        compose.onNodeWithTag("section_simulator").performScrollTo().performClick()
        waitFor("exitDemo")
        compose.onNodeWithTag("exitDemo").performClick()
        compose.onNodeWithText(env.context.getString(R.string.demo_exit_history_hint)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reviewDemoSamples").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("reviewDemoReceipts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("continueDemoSetup").performScrollTo().performClick()
        waitFor("terminalMode")
        assertThat(env.container.settingsState.value).isEqualTo(settings)
        assertThat(env.container.terminalStatus.state.value.mode).isEqualTo(TerminalMode.SIMULATOR)
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).isEqualTo(products)
        assertThat(
            await {
                env.container.database
                    .saleDao()
                    .sales()
                    .first()
            },
        ).isEqualTo(sales)
    }

    @Test
    fun `all first-run choices fit AMS1 and simulator samples can be removed from Data`() {
        compose.setContent { MiniMposApp(env.container) }
        waitFor("onboardingSimulator")
        listOf("onboardingSimulator", "onboardingImport", "onboardingTerminal").forEach {
            compose.onNodeWithTag(it).assertIsDisplayed()
        }
        compose.onNodeWithTag("onboardingSimulator").assertIsSelected()
        compose.onNodeWithTag("onboardingSimulator").performClick()
        compose.onNodeWithTag("onboardingContinue").assertIsDisplayed().performClick()
        waitFor("settings")
        compose.awaitCondition("onboarding is persisted") { env.container.settingsState.value.onboardingCompleted }
        assertThat(env.container.terminalStatus.state.value.mode).isEqualTo(TerminalMode.SIMULATOR)
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).hasSize(11)

        compose.onNodeWithTag("settings").performClick()
        waitFor("section_data")
        compose.onNodeWithTag("section_data").performScrollTo().performClick()
        waitFor("addSamples")
        compose.onNodeWithTag("addSamples").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(
            hasText("Sample data is ready. Existing samples were kept; receipt business details were replaced."),
            15_000,
        )
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).hasSize(11)
        compose.onNodeWithTag("sampleResult").assertExists()
        compose.onNodeWithTag("sampleResultOk").performClick()
        compose.onNodeWithTag("sampleResult").assertDoesNotExist()
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
        waitFor("sampleResult")
        compose.onNodeWithTag("sampleResultOk").performClick()
        compose.onNodeWithTag("sampleResult").assertDoesNotExist()
        assertThat(env.container.settingsState.value.onboardingCompleted).isTrue()
    }

    @Test
    fun `first-run import explains codes before scanning without adding samples`() {
        compose.setContent { MiniMposApp(env.container) }
        waitFor("onboardingImport")
        compose.onNodeWithTag("onboardingImport").performClick()
        assertThat(env.container.settingsState.value.onboardingCompleted).isFalse()
        compose.onNodeWithTag("onboardingContinue").performClick()
        compose.waitUntilAtLeastOneExists(
            hasText("Set up from another device"),
            15_000,
        )
        compose.awaitCondition("import choice is persisted") { env.container.settingsState.value.onboardingCompleted }
        compose.onNodeWithTag("setupCodesIntro").assertExists()
        compose.onNodeWithTag("startSetupScan").assertIsDisplayed()
        compose.onNodeWithTag("manualSetup").assertIsDisplayed()
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).isEmpty()
        compose.onNodeWithTag("back").performClick()
        waitFor("onboardingContinue")
        compose.onNodeWithTag("onboardingImport").assertIsSelected()
        assertThat(
            await {
                env.container.catalog.products
                    .first()
            },
        ).isEmpty()
        compose.onNodeWithTag("onboardingSimulator").performClick()
        compose.onNodeWithTag("onboardingContinue").performClick()
        waitFor("newSale")
    }

    @Test
    fun `first-run terminal setup opens connection settings without adding samples`() {
        compose.setContent { MiniMposApp(env.container) }
        waitFor("onboardingTerminal")
        compose.onNodeWithTag("onboardingTerminal").performClick()
        compose.onNodeWithTag("onboardingContinue").performClick()
        waitFor("scanSetup")
        compose.awaitCondition("terminal choice is persisted") { env.container.settingsState.value.onboardingCompleted }
        // Payments would still go to the simulator, so where they go is asked straight away.
        waitFor("terminalMode_CLOUD")
        compose.onNodeWithText(env.context.getString(R.string.action_cancel)).performClick()
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
        waitFor("onboardingContinue")
        compose.onNodeWithTag("onboardingTerminal").assertIsSelected()
    }
}
