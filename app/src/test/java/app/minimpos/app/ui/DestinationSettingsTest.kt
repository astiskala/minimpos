package app.minimpos.app.ui

import android.content.Context
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import app.minimpos.app.FakeDevice
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.PrinterMode
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class DestinationSettingsTest {
    @get:Rule(order = 0)
    val env = TestEnvironment(device = FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST)))

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Before
    fun setUp() {
        await {
            env.container.secrets.set(Secret.ADYEN_API_KEY, "api-key")
            env.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "shared-key")
        }
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.PAYMENTS_APP,
                        environment = TerminalEnvironment.TEST,
                        merchantAccount = "Merchant",
                        paymentsAppInstallationId = "INSTALLATION-1",
                        keyIdentifier = "KEY",
                    ),
                payment =
                    it.payment.copy(
                        allowMoto = true,
                        allowMke = true,
                        terminalTipping = ReceiptTipping.DEFAULT_ON,
                        dynamicTipOverride = true,
                        receiptTipping = ReceiptTipping.DEFAULT_ON,
                        askGiving = ReceiptTipping.DEFAULT_ON,
                    ),
                receipt = it.receipt.copy(printerMode = PrinterMode.ON, autoPrint = true),
            )
        }
    }

    @Test
    fun `Tap to Pay disables saved MOTO and MKE offers then restores them for another destination`() {
        open("payments", "allowMoto")
        listOf("allowMoto", "allowMke").forEach { tag ->
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsNotEnabled()
                .assertIsOff()
        }
        assertThat(env.container.settingsState.value.payment.allowMoto).isTrue()
        assertThat(env.container.settingsState.value.payment.allowMke).isTrue()
        compose.onAllNodesWithText("Not available with Tap to Pay.").assertCountEquals(2)
        compose.onNodeWithTag("allowPartialAuthorisation").performScrollTo().assertIsEnabled()
        env.useSimulator()
        compose.awaitCondition(
            "destination capabilities update",
        ) { env.container.terminalStatus.state.value.capabilities.supportsTerminalFeatures }
        listOf("allowMoto", "allowMke").forEach { tag ->
            compose
                .onNodeWithTag(tag)
                .performScrollTo()
                .assertIsEnabled()
                .assertIsOn()
        }
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h460dp-hdpi")
    fun `unsupported tip and Giving choices stay disabled in Japanese without changing stored preferences`() {
        open("tipping", "terminalTipping")
        listOf("terminalTipping", "receiptTipping", "askGivingSetting", "dynamicTipOverride", "tipOnPreTax").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        val payment = env.container.settingsState.value.payment
        assertThat(payment.terminalTipping).isEqualTo(ReceiptTipping.DEFAULT_ON)
        assertThat(payment.receiptTipping).isEqualTo(ReceiptTipping.DEFAULT_ON)
        assertThat(payment.askGiving).isEqualTo(ReceiptTipping.DEFAULT_ON)
        assertThat(payment.dynamicTipOverride).isTrue()
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Tap to Pay disables print controls but keeps receipt line width usable for email`() {
        open("receipts", "receiptPrinter")
        listOf("receiptPrinter", "autoPrint", "merchantCopy", "printTest").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("charsPerLine").performScrollTo().assertIsEnabled()
        assertThat(env.container.settingsState.value.receipt.printerMode).isEqualTo(PrinterMode.ON)
        assertThat(env.container.settingsState.value.receipt.autoPrint).isTrue()
        assertThat(env.container.terminalStatus.state.value.printerAvailable).isFalse()
    }

    @Test
    @Config(qualifiers = "b+zh+Hant-w320dp-h460dp-hdpi")
    fun `Tap to Pay disables shopper display settings in Traditional Chinese`() {
        open("shopper_display", "showVirtualReceipt")
        listOf("showVirtualReceipt", "confirmBasketOnTerminal", "collectEmailOnTerminal", "showTerminalQrReceipt", "askShopperRating")
            .forEach { tag -> compose.onNodeWithTag(tag).performScrollTo().assertIsNotEnabled() }
        assertThat(env.container.settingsState.value.payment.showVirtualReceipt).isTrue()
    }

    @Test
    fun `capabilities explain all Tap to Pay limits without marking device camera unsupported`() {
        open("capabilities", "cap_tipping")
        compose.awaitCondition("capabilities refreshed") {
            val state = env.container.capabilityDiscovery.state.value
            !state.checking && !state.capabilities.supportsTerminalFeatures
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unsupported = context.getString(R.string.capability_status_unsupported)
        listOf(
            "cap_tipping",
            "cap_dcc",
            "cap_giving",
            "cap_surcharge",
            "cap_gift_cards",
            "cap_moto",
            "cap_offline",
            "cap_reconciliation",
            "cap_shopper_display",
            "cap_scanned_wallets",
            "cap_printing",
            "cap_receipt_tipping",
            "cap_terminal_scanner",
            "cap_abort",
            "cap_diagnosis",
        ).forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assert(hasAnyDescendant(hasText(unsupported)))
        }
        compose.onNodeWithTag("cap_remote_status").performScrollTo().assert(
            hasAnyDescendant(hasText(context.getString(R.string.capability_status_needs_config))),
        )
        val available = context.getString(R.string.capability_status_available)
        listOf("cap_partial_auth", "cap_camera_scanning", "cap_receipt_sharing").forEach { tag ->
            compose.onNodeWithTag(tag).performScrollTo().assert(hasAnyDescendant(hasText(available)))
        }
        assertThat(unsupported).isEqualTo("Unsupported")
    }

    private fun open(
        section: String,
        readyTag: String,
    ) {
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("settings").performScrollTo().performClick()
        compose.awaitCondition("settings list opens") {
            compose.onAllNodesWithTag("section_$section").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("section_$section").performScrollTo().performClick()
        compose.awaitCondition("settings section opens") {
            compose.onAllNodesWithTag(readyTag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
