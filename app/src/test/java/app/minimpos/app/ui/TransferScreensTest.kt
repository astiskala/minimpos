package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.GST_RATES
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.TransferSeal
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.transfer.TransferredSettings
import app.minimpos.app.feature.settings.SettingsSections
import app.minimpos.app.feature.transfer.ImportUiState
import app.minimpos.app.feature.transfer.TransferImportScreen
import app.minimpos.app.feature.transfer.TransferImportViewModel
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.codec.QrChunks
import app.minimpos.core.codec.SealedSecrets
import app.minimpos.core.codec.Transfer
import app.minimpos.core.codec.TransferCodec
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** Sharing a terminal's setup on the AMS1's small screen, and importing it on another. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class TransferScreensTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container
    private val fixtureCode = "K7PQ-8Z3D-2RXM"

    private fun protect(
        transfer: Transfer,
        values: String = "{}",
        code: String = fixtureCode,
    ): Transfer =
        transfer.copy(
            sealedSecrets =
                SealedSecrets(
                    TransferSeal(iterations = 1_000).seal(values.toByteArray(), code, TransferCodec.authenticationData(transfer)),
                ),
        )

    @Before
    fun setUp() {
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(currencyCode = "AUD"),
                receipt = it.receipt.copy(businessName = "Corner Cafe"),
            )
        }
        await {
            container.catalog.seedDefaults(GST_RATES)
            val rate =
                container.catalog.taxRates
                    .first()
                    .first()
            container.catalog.saveProduct(ProductEntity(name = "Latte", priceMinor = 450, taxRateId = rate.id))
        }
    }

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    @Test
    fun `settings share the catalogue, settings and secrets, with a transfer code`() {
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        waitForTag("section_about")
        compose.onNodeWithText("Data").performScrollTo().performClick()
        waitForTag("shareToTerminal")
        compose.onNodeWithTag("shareToTerminal").performScrollTo().performClick()
        waitForTag("showCodes")
        compose.onNodeWithTag("shareCatalogue").assertIsOn()
        compose.onNodeWithTag("shareSettings").assertIsOn()
        // Nothing secret is set yet.
        compose
            .onNodeWithTag("shareSecrets")
            .performScrollTo()
            .assertIsOff()
            .assertIsNotEnabled()
        compose.onNodeWithText("None set on this device").assertExists()
        compose.onNodeWithTag("shareCatalogue").performClick()
        compose.onNodeWithTag("shareSettings").performClick()
        compose.onNodeWithTag("showCodes").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("back").performClick()
        waitForTag("shareToTerminal")

        await { container.secrets.set(Secret.SMTP_PASSWORD, "pw") }
        compose.onNodeWithTag("shareToTerminal").performScrollTo().performClick()
        waitForTag("showCodes")
        compose.waitUntilAtLeastOneExists(hasTestTag("shareSecrets") and isOn(), 15_000)
        compose.onNodeWithTag("showCodes").performClick()
        waitForTag("exportQr")
        compose.onNodeWithTag("transferCode").assertTextContains("-", substring = true)
        compose.onNodeWithTag("exportQr").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("SMTP password").performScrollTo().assertIsDisplayed()
        // Back returns to the choice, and the next codes get a new transfer code.
        compose.onNodeWithTag("back").performClick()
        waitForTag("showCodes")
        compose.onNodeWithTag("back").performClick()
        waitForTag("shareToTerminal")
    }

    @Test
    fun `the import shows what was scanned, asks for the transfer code and imports`() {
        // Export/import round trips belong to SetupTransferTest; this UI fixture needs no second database or DataStore.
        val settings = AppSettings().let { it.copy(receipt = it.receipt.copy(businessName = "Harbour Coffee Co.", footer = "Ta!")) }
        val seal = TransferSeal(iterations = 1_000)
        val code = seal.newCode()
        val payload =
            TransferCodec.encode(
                protect(
                    Transfer(settings = TransferredSettings(settings.shared(), null).encode()),
                    """{"TERMINAL_PASSPHRASE":"correct horse"}""",
                    code,
                ),
            )
        val vm = TransferImportViewModel(container.setupTransfer, "AUD", container.setupImport)
        compose.awaitCondition(
            "Opening scanner",
        ) { vm.state.value is ImportUiState.Scanning }
        QrChunks.split(payload, "TEST").forEach { vm.onCode(it.encode()) }
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.SettingsSection(SettingsSections.DATA), Route.TransferImport))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    TransferImportScreen(navigator, vm = vm)
                }
            }
        }
        waitForTag("import")
        compose.onNodeWithText("Ready to import").assertIsDisplayed()
        compose.onNodeWithTag("merge").assertDoesNotExist()
        compose.onNodeWithTag("import").assertIsDisplayed()
        compose.onNodeWithTag("transferCodeInput").performScrollTo().performTextInput("2222 2222 2222")
        compose.onNodeWithTag("import").performClick()
        compose.waitUntilAtLeastOneExists(hasText("That transfer code is wrong", substring = true), 15_000)
        compose.onNodeWithText("Scan again").assertDoesNotExist()
        compose.onNodeWithTag("transferCodeInput").performTextReplacement(code.lowercase())
        compose.onNodeWithTag("import").performClick()
        waitForTag("importDone")
        compose.onNodeWithText("Shared key passphrase").assertIsDisplayed()
        compose.onNodeWithTag("importFinished").assertIsDisplayed()
        compose.awaitCondition("Applying the settings") { container.settingsState.value.receipt.businessName == "Corner Cafe" }
        assertThat(container.settingsState.value.receipt.footer).isEqualTo("Ta!")
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("correct horse")
        compose.onNodeWithTag("importFinished").performClick()
        assertThat(navigator.current).isEqualTo(Route.SettingsSection(SettingsSections.DATA))
    }

    @Test
    fun `finishing a first-run settings import leaves onboarding and opens Home`() {
        val payload = TransferCodec.encode(protect(Transfer(settings = TransferredSettings(AppSettings().shared(), null).encode())))
        val vm = TransferImportViewModel(container.setupTransfer, "AUD", container.setupImport)
        compose.awaitCondition(
            "Opening scanner",
        ) { vm.state.value is ImportUiState.Scanning }
        QrChunks.split(payload, "INIT").forEach { vm.onCode(it.encode()) }
        vm.setCode(fixtureCode)
        val stack = NavBackStack<NavKey>(Route.Home, Route.Onboarding, Route.TransferImport)
        val navigator = Navigator(stack)
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    TransferImportScreen(navigator, vm = vm)
                }
            }
        }
        waitForTag("import")
        compose.onNodeWithTag("import").performClick()
        waitForTag("importFinished")
        compose.onNodeWithTag("importFinished").performClick()
        assertThat(navigator.current).isEqualTo(Route.Home)
        assertThat(stack).containsExactly(Route.Home)
    }

    @Test
    fun `the setup helper's codes set up the connection and its keys`() {
        val seal = TransferSeal(iterations = 1_000)
        val code = seal.newCode()
        val payload =
            TransferCodec.encode(
                protect(
                    Transfer(connection = """{"merchantAccount":"HarbourCoffeeCOM"}"""),
                    """{"ADYEN_API_KEY":"AQE-key"}""",
                    code,
                ),
            )
        val vm = TransferImportViewModel(container.setupTransfer, "AUD", container.setupImport)
        compose.awaitCondition(
            "Opening scanner",
        ) { vm.state.value is ImportUiState.Scanning }
        QrChunks.split(payload, "WEB1").forEach { vm.onCode(it.encode()) }
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.TransferImport))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    TransferImportScreen(navigator, vm = vm)
                }
            }
        }
        waitForTag("import")
        compose.onNodeWithText("Connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("From the setup helper", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Automatic lookup resolves", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Included, with a transfer code").assertDoesNotExist()
        compose.onNodeWithText("Dashes are automatic.", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Scan again").assertDoesNotExist()
        assertImportSummary()
        compose.onNodeWithTag("transferCodeInput").performScrollTo().performTextInput(code)
        compose.onNodeWithTag("import").performClick()
        compose.awaitCondition("Verified helper setup returns Home") { navigator.current == Route.Home }
        compose.awaitCondition("Applying the connection") { container.settingsState.value.terminal.merchantAccount == "HarbourCoffeeCOM" }
        // The other settings stay as they were.
        assertThat(container.settingsState.value.receipt.businessName).isEqualTo("Corner Cafe")
        assertThat(await { container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("AQE-key")
    }

    @Test
    fun `LIVE destination is reviewed before import and scanning alone changes nothing`() = reviewLiveDestination()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese QR preview gives destination text full width`() = reviewLiveDestination()

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `Japanese QR preview gives destination text full width`() = reviewLiveDestination()

    private fun assertImportSummary() {
        compose.onNodeWithTag("transferCodeInput").performScrollTo()
        val summary = compose.onNodeWithTag("transferSummary").fetchSemanticsNode().boundsInRoot
        val input = compose.onNodeWithTag("transferCodeInput").fetchSemanticsNode().boundsInRoot
        assertThat(input.top - summary.bottom).isAtLeast(0f)
        assertThat(input.top - summary.bottom).isLessThan(32f)
        compose
            .onNode(
                hasText(env.context.getString(R.string.transfer_part_secrets)) and
                    hasAnySibling(hasText(env.context.getString(R.string.transfer_included))),
            ).assertExists()
    }

    private fun reviewLiveDestination() {
        val payload = TransferCodec.encode(protect(Transfer(connection = """{"destination":"cloud","environment":"LIVE"}""")))
        val vm = TransferImportViewModel(container.setupTransfer, "AUD", container.setupImport)
        compose.awaitCondition(
            "Opening scanner",
        ) { vm.state.value is ImportUiState.Scanning }
        QrChunks.split(payload, "LIVE").forEach { vm.onCode(it.encode()) }
        vm.setCode(fixtureCode)
        val before = await { container.settings.current() }
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.TransferImport))
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    TransferImportScreen(navigator, vm = vm)
                }
            }
        }
        waitForTag("connectionPreview")
        val label = compose.onNodeWithText(env.context.getString(R.string.settings_mode)).fetchSemanticsNode().boundsInRoot
        val destination = compose.onNodeWithText(env.context.getString(R.string.settings_mode_cloud)).fetchSemanticsNode().boundsInRoot
        assertThat(destination.top).isAtLeast(label.bottom)
        assertThat(destination.width).isAtLeast(label.width)
        compose.onNodeWithText(env.context.getString(R.string.settings_env_live)).assertExists()
        compose.onNodeWithTag("importLiveWarning").performScrollTo().assertIsDisplayed()
        assertImportSummary()
        compose.onNodeWithTag("import").assertIsDisplayed()
        assertThat(await { container.settings.current() }).isEqualTo(before)
        compose.onNodeWithTag("import").performClick()
        compose.waitUntilAtLeastOneExists(
            hasText(env.context.getString(R.string.transfer_missing_fields)),
            15_000,
        )
        compose.onNodeWithTag("back").performClick()
        assertThat(navigator.current).isEqualTo(Route.Home)
        assertThat(await { container.settings.current() }).isEqualTo(before)
    }
}
