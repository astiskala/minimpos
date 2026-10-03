package io.github.astiskala.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.GST_RATES
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.data.transfer.TransferContents
import io.github.astiskala.minimpos.app.feature.transfer.TransferImportScreen
import io.github.astiskala.minimpos.app.feature.transfer.TransferImportViewModel
import io.github.astiskala.minimpos.app.ui.components.LocalAppContainer
import io.github.astiskala.minimpos.app.ui.navigation.Navigator
import io.github.astiskala.minimpos.app.ui.navigation.Route
import io.github.astiskala.minimpos.app.ui.theme.MiniMposTheme
import io.github.astiskala.minimpos.core.codec.QrChunks
import io.github.astiskala.minimpos.core.codec.SealedSecrets
import io.github.astiskala.minimpos.core.codec.Transfer
import io.github.astiskala.minimpos.core.codec.TransferCodec
import kotlinx.coroutines.flow.first
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        compose.onNodeWithTag("shareSecrets").performScrollTo().assertIsOff()
        compose.onNodeWithText("None set on this device").assertExists()
        compose.onNodeWithTag("shareCatalogue").performClick()
        compose.onNodeWithTag("shareSettings").performClick()
        compose.onNodeWithTag("showCodes").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("back").performClick()

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
        val source = TestEnvironment()
        try {
            source.useSimulator { it.copy(receipt = it.receipt.copy(businessName = "Harbour Coffee Co.", footer = "Ta!")) }
            await { source.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse") }
            val export = await { source.container.setupTransfer.export(TransferContents(catalogue = false), "AUD") }
            val vm = TransferImportViewModel(container.setupTransfer, "AUD")
            QrChunks.split(export.payload, "TEST").forEach { vm.onCode(it.encode()) }
            compose.setContent {
                MiniMposTheme {
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        TransferImportScreen(Navigator(NavBackStack<NavKey>(Route.Home)), vm = vm)
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
            compose.onNodeWithTag("transferCodeInput").performTextReplacement(export.code!!.lowercase())
            compose.onNodeWithTag("import").performClick()
            waitForTag("importDone")
            compose.onNodeWithText("Shared key passphrase").assertIsDisplayed()
            compose.onNodeWithTag("importFinished").assertIsDisplayed()
            compose.awaitCondition("Applying the settings") { container.settingsState.value.receipt.businessName == "Harbour Coffee Co." }
            assertThat(container.settingsState.value.receipt.footer).isEqualTo("Ta!")
        } finally {
            source.close()
        }
    }

    @Test
    fun `the setup helper's codes set up the connection and its keys`() {
        val seal = TransferSeal(iterations = 1_000)
        val code = seal.newCode()
        val sealed = SealedSecrets(seal.seal("""{"ADYEN_API_KEY":"AQE-key"}""".toByteArray(), code))
        val payload = TransferCodec.encode(Transfer(sealedSecrets = sealed, connection = """{"merchantAccount":"HarbourCoffeeCOM"}"""))
        val vm = TransferImportViewModel(container.setupTransfer, "AUD")
        QrChunks.split(payload, "WEB1").forEach { vm.onCode(it.encode()) }
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    TransferImportScreen(Navigator(NavBackStack<NavKey>(Route.Home)), vm = vm)
                }
            }
        }
        waitForTag("import")
        compose.onNodeWithText("Connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("From the setup helper", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("transferCodeInput").performScrollTo().performTextInput(code)
        compose.onNodeWithTag("import").performClick()
        waitForTag("importDone")
        compose.onNodeWithText("Connection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Adyen API key").performScrollTo().assertIsDisplayed()
        compose.awaitCondition("Applying the connection") { container.settingsState.value.terminal.merchantAccount == "HarbourCoffeeCOM" }
        // The other settings stay as they were.
        assertThat(container.settingsState.value.receipt.businessName).isEqualTo("Corner Cafe")
        assertThat(await { container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("AQE-key")
    }
}
