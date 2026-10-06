package io.github.astiskala.minimpos.app.ui

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
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.settings.ReceiptTipping
import io.github.astiskala.minimpos.terminal.transport.StoreDetails
import io.github.astiskala.minimpos.terminal.transport.StoreDetailsApi
import io.github.astiskala.minimpos.terminal.transport.StoreListing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import io.github.astiskala.minimpos.app.createRecordingComposeRule as createComposeRule

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SettingsPreferencesTest {
    private var listing: StoreListing = StoreListing.Listed(listOf(StoreDetails("ST1", "cafe", "Adyen Cafe", "1 Main St\nSydney", "")))

    @get:Rule(order = 0)
    val env = TestEnvironment(stores = StoreDetailsApi { listing })

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
        compose.onNodeWithTag("receiptBusiness_ST1").assertDoesNotExist()
        compose.onNodeWithTag("confirmReceiptBusiness").assertIsDisplayed()
        assertThat(container.settingsState.value.receipt.businessName).isEqualTo("My cafe")
        compose.onNodeWithText("Cancel").performClick()
        assertThat(container.settingsState.value.receipt.businessName).isEqualTo("My cafe")
        findStores()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirmReceiptBusiness"), 15_000)
        compose.onNodeWithTag("receiptBusiness_ST1").assertDoesNotExist()
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
        listing = StoreListing.Listed(listOf(StoreDetails("ST2", "empty", "", "", "")))
        open("receipts")
        findStores()
        compose.waitUntilAtLeastOneExists(hasTestTag("confirmReceiptBusiness"), 15_000)
        compose.onNodeWithTag("receiptBusiness_ST2").assertDoesNotExist()
        compose.onNodeWithTag("confirmReceiptBusiness").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `an account with no stores has a clear empty result`() {
        env.useLinks()
        listing = StoreListing.Listed(emptyList())
        open("receipts")
        findStores()
        compose.waitUntilAtLeastOneExists(
            hasText("No stores were found for this merchant account."),
            15_000,
        )
        compose.onNodeWithText("No stores were found for this merchant account.").assertIsDisplayed()
        compose.onNodeWithTag("confirmReceiptBusiness").assertDoesNotExist()
    }
}
