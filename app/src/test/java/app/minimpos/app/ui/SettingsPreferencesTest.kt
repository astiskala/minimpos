package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.FakeStoreDetails
import app.minimpos.app.MiniMposApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.MerchantLookup
import app.minimpos.terminal.transport.StoreDetails
import app.minimpos.terminal.transport.StoreLookup
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
class SettingsPreferencesTest {
    private val stores = FakeStoreDetails(StoreLookup.Found(StoreDetails("Adyen Cafe", "1 Main St\nSydney", "")))

    @get:Rule(order = 0)
    val env = TestEnvironment(stores = stores)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    private fun open(section: String) {
        compose.setContent { MiniMposApp(container) }
        compose.onNodeWithTag("settings").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("section_$section"), 15_000)
        compose.onNodeWithTag("section_$section").performScrollTo().performClick()
    }

    private fun findStores() {
        compose.waitUntilAtLeastOneExists(hasTestTag("findReceiptBusinesses"), 15_000)
        compose.onNodeWithTag("findReceiptBusinesses").performScrollTo().performClick()
    }

    @Test
    fun `environment switch warns about unfinished payments and cancellation preserves history`() {
        env.useSimulator()
        await {
            container.sales.createPending(
                SaleEntity(
                    id = "pending",
                    createdAt = 1,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 1000,
                    taxMinor = 0,
                    totalMinor = 1000,
                    status = SaleStatus.UNKNOWN,
                    merchantReference = "PENDING",
                ),
                emptyList(),
            )
        }
        open("terminal")
        compose.waitUntilAtLeastOneExists(hasTestTag("terminalMode"), 15_000)
        compose.onNodeWithTag("terminalMode").performScrollTo().performClick()
        compose.onNodeWithTag("terminalMode_CLOUD").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Delete history and switch?"), 15_000)
        compose.onNodeWithText("Unfinished payments exist.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertThat(await { container.history.items().first() }).hasSize(1)
        assertThat(container.settingsState.value.terminal.mode).isEqualTo(TerminalMode.SIMULATOR)
        compose.onNodeWithTag("terminalMode").performScrollTo().performClick()
        compose.onNodeWithTag("terminalMode_CLOUD").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Delete history and switch?"), 15_000)
        compose.onNodeWithTag("confirm").performClick()
        compose.awaitCondition("environment switched") { container.settingsState.value.terminal.mode == TerminalMode.CLOUD }
        assertThat(await { container.history.items().first() }).isEmpty()
    }

    @Test
    fun `receipt tipping selection persists all three modes with disabled as the default`() {
        env.useSimulator()
        open("payments")
        compose.waitUntilAtLeastOneExists(hasTestTag("receiptTipping"), 15_000)
        compose.onNodeWithTag("receiptTipping").performScrollTo().assertTextContains("Disabled")
        listOf(ReceiptTipping.DEFAULT_OFF, ReceiptTipping.DEFAULT_ON, ReceiptTipping.DISABLED).forEach { mode ->
            compose.onNodeWithTag("receiptTipping").performScrollTo().performClick()
            compose.onNodeWithTag("receiptTipping_$mode").performClick()
            compose.awaitCondition("receipt tipping saved") { container.settingsState.value.payment.receiptTipping == mode }
        }
        compose.onNodeWithText("Offer payment links").assertDoesNotExist()
        compose.onNodeWithText("Send shopper email with saved cards").assertDoesNotExist()
    }

    @Test
    fun `business details are reviewed before replacement and editors reflect the confirmed import`() {
        env.useLinks()
        env.updateSettings {
            it.copy(
                terminal = it.terminal.copy(storeId = "ST1"),
                receipt =
                    it.receipt.copy(
                        businessName = "My cafe",
                        addressLines = "Old address",
                        phone = "Original phone",
                        taxId = "123",
                        footer = "Custom footer",
                    ),
            )
        }
        open("receipts")
        findStores()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirmReceiptBusiness"), 15_000)
        compose.onNodeWithTag("confirmReceiptBusiness").assertIsDisplayed()
        assertThat(container.settingsState.value.receipt.businessName).isEqualTo("My cafe")
        compose.onNodeWithText("Cancel").performClick()
        assertThat(container.settingsState.value.receipt.businessName).isEqualTo("My cafe")
        findStores()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirmReceiptBusiness"), 15_000)
        compose.onNodeWithTag("confirmReceiptBusiness").performClick()
        compose.waitUntilDoesNotExist(hasTestTag("confirmReceiptBusiness"), 15_000)
        compose.onNodeWithTag("businessName").performScrollTo().assertTextContains("My cafe")
        val receipt = container.settingsState.value.receipt
        assertThat(receipt.addressLines).isEqualTo("Old address")
        assertThat(receipt.phone).isEqualTo("Original phone")
        assertThat(receipt.taxId).isEqualTo("123")
        assertThat(receipt.footer).isEqualTo("Custom footer")
    }

    @Test
    fun `a store with no importable details cannot erase receipt fields`() {
        env.useLinks()
        stores.storeAnswer = StoreLookup.Found(StoreDetails("", "", ""))
        env.updateSettings { it.copy(terminal = it.terminal.copy(storeId = "ST2")) }
        open("receipts")
        findStores()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirmReceiptBusiness"), 15_000)
        compose.onNodeWithTag("confirmReceiptBusiness").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `a terminal without an assigned store reviews the merchant legal name`() {
        env.useLinks()
        stores.merchantAnswer = MerchantLookup.Found("Harbour Coffee Pty Ltd")
        open("receipts")
        findStores()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirmReceiptBusiness"), 15_000)
        compose.onNodeWithText("Harbour Coffee Pty Ltd").assertIsDisplayed()
        compose.onNodeWithTag("confirmReceiptBusiness").performClick()
        compose.awaitCondition("legal name imported") { container.settingsState.value.receipt.businessName == "Harbour Coffee Pty Ltd" }
    }
}
