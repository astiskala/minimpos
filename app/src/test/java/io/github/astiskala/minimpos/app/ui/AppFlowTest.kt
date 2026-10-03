package io.github.astiskala.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.GST_RATES
import io.github.astiskala.minimpos.app.MiniMposApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.awaitCondition
import io.github.astiskala.minimpos.app.data.db.ProductEntity
import io.github.astiskala.minimpos.app.data.db.RefundStatus
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleKind
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.TaxRateEntity
import io.github.astiskala.minimpos.app.data.repo.HistoryItem
import io.github.astiskala.minimpos.app.data.settings.AppSettings
import io.github.astiskala.minimpos.app.data.settings.ShopperReferenceSource
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.core.cart.AppliedTax
import io.github.astiskala.minimpos.terminal.simulator.SimulatedOutcome
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w411dp-h891dp-xhdpi")
class AppFlowTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container

    @Before
    fun setUp() {
        // These flows ask for a customer reference and print on request, unlike a new installation (see below).
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(currencyCode = "AUD", shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE),
                receipt = it.receipt.copy(businessName = "Corner Cafe", autoPrint = false),
            )
        }
        await { container.catalog.seedDefaults(GST_RATES) }
        compose.setContent { MiniMposApp(container) }
    }

    private fun SemanticsNodeInteractionsProvider.waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun waitForText(text: String) = compose.waitUntilAtLeastOneExists(hasText(text), 15_000)

    /**
     * Waits until the product editor has saved and closed. Its own fields show the product's name and tax rate, so
     * waiting for that text alone could press Back before the save finishes (and cancel it).
     */
    private fun awaitEditorClosed() =
        compose.awaitCondition("the product editor closes") { compose.onAllNodesWithTag("productName").fetchSemanticsNodes().isEmpty() }

    /** There are no products in these tests, so a new sale opens the custom item keypad straight away. */
    private fun ringUpCustomAmount(vararg digits: Int) {
        compose.onNodeWithTag("newSale").performClick()
        compose.waitForTag("addCustom")
        digits.forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
    }

    @Test
    fun `rings up a custom amount, pays, prints and starts a new sale`() {
        compose.onNodeWithTag("modeBanner").assertIsDisplayed()
        ringUpCustomAmount(1, 2, 5, 0)
        compose.onNodeWithText("Charge $12.50").assertIsDisplayed()
        compose.onNodeWithTag("charge").performClick()
        compose.waitForTag("pay")
        compose.onNodeWithTag("customerReference").performTextInput("CUST-42")
        compose.onNodeWithTag("tokenize").performClick()
        compose.onNodeWithTag("pay").performClick()

        waitForText("Approved")
        compose.onNodeWithText("$12.50").assertIsDisplayed()
        compose.onNodeWithTag("print").performClick()
        compose.waitForTag("virtualPrinter")
        waitForText("Scan this code for returns")

        val sale = await { container.history.items().first { it.isNotEmpty() } }.single()
        val record = await { container.sales.get(sale.id)!! }
        assertThat(record.sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(record.sale.storedPaymentMethodId).isNotNull()
        assertThat(record.sale.customerReference).isEqualTo("CUST-42")
        assertThat(
            container
                .session(SaleKind.SALE)
                .cart.value.lines,
        ).isEmpty()
    }

    @Test
    fun `a new installation's checkout asks for nothing but payment, and prints the receipt by itself`() {
        val fresh = AppSettings.forNewInstallation("AU")
        env.updateSettings { it.copy(payment = fresh.payment.copy(currencyCode = "AUD"), receipt = fresh.receipt) }
        ringUpCustomAmount(9, 5, 0)
        compose.onNodeWithTag("charge").performClick()
        compose.waitForTag("pay")
        listOf("transactionReference", "customerReference", "email", "tokenize").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag("pay").performClick()
        waitForText("Approved")
        compose.waitForTag("virtualPrinter")
        waitForText("Scan this code for returns")
    }

    @Test
    fun `home says what payments off the terminal still need`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD)) }
        compose.waitForTag("terminalSetup")
        compose
            .onNodeWithTag("terminalSetup")
            .assertTextContains("Finish setting up payments", substring = true)
            .assertTextContains("Enter the merchant account", substring = true)
            .performClick()
        compose.waitForTag("quickSetupImport")
        compose.onNodeWithText("Quick setup", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithTag("quickSetupImport").assertIsDisplayed().performClick()
        waitForText("Point the camera at the QR codes shown on the other device or on the setup helper page.")
        compose.onNodeWithTag("back").performClick()
        compose.waitForTag("terminalMode")
    }

    @Test
    fun `terminal settings offer quick setup while using the simulator`() {
        compose.onNodeWithTag("settings").performClick()
        compose.waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        compose.waitForTag("quickSetupImport")
        compose.onNodeWithText("Quick setup", ignoreCase = true).assertIsDisplayed()
        compose.onNodeWithTag("quickSetupImport").assertIsDisplayed()
    }

    @Test
    fun `declined payments can be retried`() {
        env.useSimulator { it.copy(simulator = it.simulator.copy(outcome = SimulatedOutcome.DECLINE)) }
        ringUpCustomAmount(5, 0, 0)
        compose.onNodeWithTag("charge").performClick()
        compose.waitForTag("pay")
        compose.onNodeWithTag("pay").performClick()
        waitForText("Declined")
        compose.onNodeWithText("Not enough balance").assertIsDisplayed()
        compose.onNodeWithTag("retryAdvice").assertTextEquals("You can try again.")
        compose.onNodeWithTag("abortBusy").assertDoesNotExist()
        compose.onNodeWithTag("tryAgain").performClick()
        compose.waitForTag("pay")
        compose.onNodeWithTag("checkoutAmount").assertIsDisplayed()
        assertThat(
            container
                .session(SaleKind.SALE)
                .cart.value.lines,
        ).hasSize(1)
    }

    @Test
    fun `adds a product and sells it`() {
        compose.onNodeWithTag("products").performClick()
        compose.waitForTag("addFab")
        compose.onNodeWithTag("addFab").performClick()
        compose.waitForTag("productName")
        compose.onNodeWithTag("saveProduct").assertIsNotEnabled()
        compose.onNodeWithTag("productName").performTextInput("Flat white")
        compose.onNodeWithTag("productPrice").performTextInput("4.50")
        compose.onNodeWithTag("saveProduct").performClick()
        awaitEditorClosed()
        waitForText("Flat white")
        compose.onNodeWithTag("back").performClick()

        compose.waitForTag("newSale")
        compose.onNodeWithTag("newSale").performClick()
        waitForText("Flat white")
        // With products to choose from, the custom item keypad only opens on request.
        compose.onNodeWithTag("addCustom").assertDoesNotExist()
        compose.onNodeWithText("Flat white").performClick()
        compose.onNodeWithText("Flat white").performClick()
        compose.onNodeWithText("Charge $9.00").assertIsDisplayed()
        compose.onNodeWithTag("cartButton").performClick()
        compose.waitForTag("cartList")
        compose.onNodeWithText("2 items").assertIsDisplayed()
    }

    @Test
    fun `without products a new sale starts with the custom item keypad, once`() {
        compose.onNodeWithTag("newSale").performClick()
        compose.waitForTag("addCustom")
        compose.onNodeWithText("Cancel").performClick()
        waitForText("No products yet. Add them under Products, or use a custom item.")
        compose.onNodeWithTag("addCustom").assertDoesNotExist()

        compose.onNodeWithTag("customItem").performClick()
        compose.waitForTag("addCustom")
        listOf(3, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        waitForText("Charge $3.00")
        compose.onNodeWithText("Charge $3.00").performClick()
        compose.waitForTag("checkoutAmount")
        compose.onNodeWithTag("back").performClick()
        // Back from checkout, the sale carries on without reopening the keypad.
        waitForText("Charge $3.00")
        compose.onNodeWithTag("addCustom").assertDoesNotExist()
    }

    @Test
    fun `products and custom items are sold without tax with a zero rate`() {
        val free =
            await {
                container.catalog.taxRates
                    .first()
                    .single { it.rateMilliPercent == 0 }
            }
        compose.onNodeWithTag("products").performClick()
        compose.waitForTag("addFab")
        compose.onNodeWithTag("addFab").performClick()
        compose.waitForTag("productName")
        // New products start with the default rate; there is no separate "tax applies" switch.
        compose.onNodeWithTag("taxRatePicker").assertTextContains("GST 10%")
        compose.onNodeWithText("Tax applies").assertDoesNotExist()
        compose.onNodeWithTag("productName").performTextInput("Stamp")
        compose.onNodeWithTag("productPrice").performTextInput("1.20")
        compose.onNodeWithTag("taxRatePicker").performClick()
        compose.onNodeWithText("GST-free 0%").performClick()
        compose.onNodeWithTag("taxRatePicker").assertTextContains("GST-free 0%")
        compose.onNodeWithTag("saveProduct").performClick()
        awaitEditorClosed()
        waitForText("GST-free 0%")
        assertThat(await { container.catalog.products.first { it.isNotEmpty() } }.single().taxRateId).isEqualTo(free.id)
        compose.onNodeWithTag("back").performClick()

        compose.waitForTag("newSale")
        compose.onNodeWithTag("newSale").performClick()
        waitForText("Stamp")
        compose.onNodeWithText("Stamp").performClick()
        compose.onNodeWithTag("customItem").performClick()
        compose.waitForTag("addCustom")
        compose.onNodeWithTag("taxRatePicker").performClick()
        compose.onNodeWithText("GST-free 0%").performClick()
        listOf(5, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        waitForText("Charge $6.20")
        assertThat(
            container.session(SaleKind.SALE).cart.value.lines.map {
                it.tax
            },
        ).containsExactly(AppliedTax("GST-free", 0), AppliedTax("GST-free", 0))
        compose.onNodeWithTag("back").performClick()

        // With tax switched off, products and custom items no longer ask.
        compose.waitForTag("settings")
        compose.onNodeWithTag("settings").performClick()
        compose.waitForTag("section_tax")
        compose.onNodeWithTag("section_tax").performScrollTo().performClick()
        compose.waitForTag("chargeTax")
        compose.onNodeWithTag("chargeTax").performClick()
        compose.awaitCondition("Switching tax off") { !container.settingsState.value.payment.chargeTax }
        compose.onNodeWithTag("addTaxRate").assertDoesNotExist()
        compose.onNodeWithTag("back").performClick()
        waitForText("Off")
        compose.onNodeWithTag("back").performClick()
        compose.waitForTag("products")
        compose.onNodeWithTag("products").performClick()
        compose.waitForTag("addFab")
        compose.onNodeWithText("GST-free 0%").assertDoesNotExist()
        compose.onNodeWithTag("addFab").performClick()
        compose.waitForTag("productName")
        compose.onNodeWithTag("taxRatePicker").assertDoesNotExist()
    }

    @Test
    fun `an admin PIN locks settings`() {
        compose.onNodeWithTag("settings").performClick()
        compose.waitForTag("section_security")
        compose.onNodeWithTag("section_security").performClick()
        compose.waitForTag("setPin")
        compose.onNodeWithTag("setPin").performClick()
        repeat(2) {
            compose.waitForTag("pin_1")
            listOf(1, 3, 5, 7).forEach { digit -> compose.onNodeWithTag("pin_$digit").performClick() }
            compose.onNodeWithTag("pin_OK").performClick()
        }
        compose.waitForTag("setPin")
        // The PIN is saved by work that resumes on the main looper, so blocking the main thread (await) would starve it.
        compose.awaitCondition("the PIN is saved") { runBlocking { container.pinManager.pinConfigured.first() } }
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("back").performClick()

        compose.waitForTag("settings")
        compose.onNodeWithTag("settings").performClick()
        compose.waitForTag("pin_1")
        listOf(9, 9, 9, 9).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        waitForText("Wrong PIN (attempts left: 4)")
        listOf(1, 3, 5, 7).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        compose.waitForTag("section_terminal")
    }

    @Test
    fun `refunds a sale from the history`() {
        ringUpCustomAmount(2, 0, 0)
        container.session(SaleKind.SALE).setQuantity(
            container
                .session(SaleKind.SALE)
                .cart.value.lines
                .single()
                .key,
            2,
        )
        waitForText("Charge $4.00")
        compose.onNodeWithTag("charge").performClick()
        compose.waitForTag("pay")
        compose.onNodeWithTag("pay").performClick()
        waitForText("Approved")
        compose.onNodeWithTag("home").performClick()

        compose.waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        // The list is shown before the history has loaded, so wait for the sale itself.
        waitForText("$4.00")
        compose.onNodeWithText("$4.00", useUnmergedTree = true).performClick()
        compose.waitForTag("detailRefund")
        compose.onNodeWithTag("detailRefund").performClick()
        compose.waitForTag("startRefund")
        compose.onNodeWithTag("option_ITEMS").performClick()
        // Each line shows its unit price, as on the receipt ("2 x $2.00"), not the line total.
        compose.onNodeWithText("$2.00 each · sold: 2 · refunded: 0").assertIsDisplayed()
        compose.onNodeWithTag("startRefund").assertIsNotEnabled()
        compose.onNodeWithTag("option_FULL").performClick()
        compose.onNodeWithTag("startRefund").performClick()
        compose.onNodeWithTag("confirm").performClick()
        waitForText("Refund requested")

        val refund =
            await { container.history.items().first { items -> items.any { it is HistoryItem.Refund } } }
                .filterIsInstance<HistoryItem.Refund>()
                .single()
                .refund
        assertThat(refund.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(refund.full).isTrue()
        compose.onNodeWithTag("refundDone").performClick()
        compose.waitForTag("newSale")
    }

    @Test
    fun `searches the history and narrows it to a payment method`() {
        await {
            listOf(
                Triple("REF-A", "visa" to "visa_applepay", "CUST-1042"),
                Triple("REF-B", "mc" to "mc", null),
            ).forEachIndexed { index, (reference, method, customer) ->
                container.database.saleDao().insert(
                    SaleEntity(
                        id = reference,
                        createdAt = System.currentTimeMillis() - index,
                        currency = "AUD",
                        taxMode = "INCLUSIVE",
                        netMinor = 1000,
                        taxMinor = 100,
                        totalMinor = 1100L + index * 100,
                        status = SaleStatus.APPROVED,
                        merchantReference = reference,
                        customerReference = customer,
                        paymentBrand = method.first,
                        paymentMethodVariant = method.second,
                    ),
                )
            }
        }
        compose.waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        waitForText("REF-B")
        // The wallet shows beside the brand.
        compose.onNodeWithText("VISA Apple Pay · CUST-1042", substring = true, useUnmergedTree = true).assertIsDisplayed()

        // On a phone-sized screen the field is always there, without an app-bar icon.
        compose.onNodeWithTag("openSearch").assertDoesNotExist()
        compose.onNodeWithTag("search").assertIsDisplayed().performTextInput("12")
        compose.awaitCondition("the search narrows the list") { compose.onAllNodesWithText("REF-A").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("REF-B").assertIsDisplayed()
        compose.onNodeWithTag("search").performTextReplacement("")

        compose.onNodeWithTag("paymentMethod").performClick()
        compose.onNodeWithText("Mastercard").assertIsDisplayed()
        compose.onNodeWithText("Apple Pay").performClick()
        compose.onNodeWithTag("paymentMethod").assertIsSelected().assertTextContains("Apple Pay")
        compose.awaitCondition(
            "the payment method narrows the list",
        ) { compose.onAllNodesWithText("REF-B").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("REF-A").assertIsDisplayed()
    }

    @Test
    fun `takes a pre-authorisation of a custom amount without pre-authorisation products`() {
        compose.waitForTag("preAuth")
        compose.onNodeWithTag("preAuth").assertIsDisplayed().performClick()
        // With nothing to choose from, the custom amount keypad opens straight away.
        compose.waitForTag("addCustom")
        listOf(7, 5, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        compose.waitForTag("pay")
        compose.onNodeWithTag("pay").assertTextEquals("Pre-authorize $75.00").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Pre-authorized"), 15_000)
    }

    @Test
    fun `takes a pre-authorisation of its own product and cancels it from the history`() {
        compose.onNodeWithTag("products").performClick()
        compose.waitForTag("addFab")
        compose.onNodeWithTag("addFab").performClick()
        compose.waitForTag("productName")
        compose.onNodeWithTag("productName").performTextInput("Catering deposit")
        compose.onNodeWithTag("productPrice").performTextInput("200")
        compose.onNodeWithTag("productPreAuth").performScrollTo().performClick()
        compose.onNodeWithTag("saveProduct").performClick()
        awaitEditorClosed()
        waitForText("Pre-auth · GST 10%")
        compose.onNodeWithTag("back").performClick()

        // A sale does not show the product (checked in the view model test); a pre-authorisation does.
        compose.waitForTag("preAuth")
        compose.onNodeWithTag("preAuth").performClick()
        waitForText("Catering deposit")
        // There is no cart: choosing the one item, a custom amount here, opens checkout straight away.
        compose.onNodeWithTag("charge").assertDoesNotExist()
        compose.onNodeWithTag("customItem").performClick()
        compose.waitForTag("addCustom")
        listOf(5, 0, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithTag("addCustom").performClick()
        compose.waitForTag("pay")
        compose.onNodeWithTag("pay").assertTextEquals("Pre-authorize $50.00")
        // Back on the Pre-authorise screen, a product replaces the custom amount.
        compose.onNodeWithTag("back").performClick()
        waitForText("Catering deposit")
        compose.onNodeWithText("Catering deposit").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("pay") and hasText("Pre-authorize $200.00"), 15_000)
        assertThat(
            container
                .session(SaleKind.PRE_AUTHORISATION)
                .cart.value.lines
                .map { it.name },
        ).containsExactly("Catering deposit")
        compose.onNodeWithTag("preAuthNote").assertIsDisplayed()
        compose.onNodeWithTag("customerReference").performTextInput("CUST-7")
        // Cards are saved by default for pre-authorisations, for charging late costs.
        compose.onNodeWithTag("tokenize").assertIsOn()
        compose.onNodeWithTag("pay").assertTextEquals("Pre-authorize $200.00").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Pre-authorized"), 15_000)
        compose.onNodeWithTag("newSaleAfter").assertTextEquals("New pre-auth")
        compose.onNodeWithTag("home").performClick()

        compose.waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        waitForText("$200.00")
        compose.onNodeWithText("Pre-authorized", useUnmergedTree = true).performClick()
        compose.waitForTag("cancelPreAuth")
        compose.onNodeWithTag("detailRefund").assertDoesNotExist()
        compose.onNodeWithTag("cancelPreAuth").performClick()
        // The way out of the dialog is not also called "Cancel".
        waitForText("Keep it")
        compose.onNodeWithText("Keep it").assertIsDisplayed()
        compose.onNodeWithTag("confirm").performClick()
        waitForText("Cancellation requested")

        val cancellation =
            await { container.history.items().first { items -> items.any { it is HistoryItem.Refund } } }
                .filterIsInstance<HistoryItem.Refund>()
                .single()
                .refund
        assertThat(cancellation.cancellation).isTrue()
        assertThat(cancellation.status).isEqualTo(RefundStatus.REQUESTED)
        compose.onNodeWithTag("refundDone").performClick()
        compose.waitForTag("preAuth")
    }

    @Test
    fun `takes a sale for a tip on the receipt and enters the tip afterwards`() {
        ringUpCustomAmount(2, 5, 0, 0)
        compose.onNodeWithTag("charge").performClick()
        compose.waitForTag("pay")
        compose
            .onNodeWithTag("tipOnReceipt")
            .performScrollTo()
            .assertIsOff()
            .performClick()
        compose.onNodeWithTag("pay").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Awaiting tip"), 15_000)
        compose.onNodeWithText("Print the receipt so the customer can add a tip and sign it, then enter the tip.").assertIsDisplayed()

        compose.onNodeWithTag("enterTip").performScrollTo().performClick()
        compose.waitForTag("addTip")
        compose.onNodeWithTag("addTip").assertIsNotEnabled()
        // The customer wrote the total.
        compose.onNodeWithTag("tipInput_TOTAL").performClick()
        listOf(3, 1, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithText("Bill $25.00 + tip $6.00 = $31.00.").assertIsDisplayed()
        compose.onNodeWithText("More than 20% of the bill", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("addTip").performClick()
        compose.onNodeWithText("Add a $6.00 tip?").assertIsDisplayed()
        compose.onNodeWithTag("confirm").performClick()

        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Capture requested"), 15_000)
        compose.onNodeWithText("$31.00").assertIsDisplayed()
        compose.onNodeWithText("Bill $25.00 + tip $6.00").assertIsDisplayed()
        compose.onNodeWithTag("enterTip").assertDoesNotExist()
        val sale = await { container.history.items().first { it.isNotEmpty() } }.filterIsInstance<HistoryItem.Sale>().single()
        val record = await { container.sales.get(sale.id)!! }.sale
        assertThat(record.tipMinor).isEqualTo(600)
        assertThat(record.authorisedMinor).isEqualTo(3_100)
        assertThat(record.capturedMinor).isEqualTo(3_100)
    }

    @Test
    fun `captures a pre-authorisation from the history`() {
        await {
            val rate =
                container.catalog.taxRates
                    .first()
                    .first()
            container.catalog.saveProduct(
                ProductEntity(name = "Catering deposit", priceMinor = 20_000, taxRateId = rate.id, kind = SaleKind.PRE_AUTHORISATION),
            )
        }
        compose.waitForTag("preAuth")
        compose.onNodeWithTag("preAuth").performClick()
        waitForText("Catering deposit")
        compose.onNodeWithText("Catering deposit").performClick()
        compose.waitForTag("pay")
        compose.onNodeWithTag("pay").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus") and hasText("Pre-authorized"), 15_000)
        compose.onNodeWithTag("home").performClick()
        compose.waitForTag("history")
        compose.onNodeWithTag("history").performClick()
        waitForText("Pre-authorized")
        compose.onNodeWithText("Pre-authorized", useUnmergedTree = true).performClick()
        compose.waitForTag("capture")
        compose.onNodeWithTag("adjust").assertIsDisplayed()
        compose.onNodeWithTag("capture").performClick()
        compose.waitForTag("submitCapture")
        compose.onNodeWithTag("submitCapture").assertTextEquals("Capture $200.00")
        listOf(1, 8, 5, 0, 0).forEach { compose.onNodeWithTag("key_$it").performClick() }
        compose.onNodeWithText("The rest of the amount held is released.").assertIsDisplayed()
        compose.onNodeWithTag("submitCapture").assertTextEquals("Capture $185.00").performClick()
        compose.onNodeWithTag("confirm").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("detailStatus") and hasText("Capture requested"), 15_000)
        compose.onNodeWithTag("captureNote").assertTextContains("Capture of $185.00 requested", substring = true)
        compose.onNodeWithTag("capture").assertDoesNotExist()
        compose.onNodeWithTag("cancelPreAuth").assertDoesNotExist()
        compose.onNodeWithTag("detailRefund").performScrollTo().assertIsDisplayed()
    }

    private fun awaitSetting(
        description: String,
        condition: () -> Boolean,
    ) = compose.awaitCondition("Saving $description", condition)

    @Test
    fun `any Adyen currency can be chosen, or automatic`() {
        compose.onNodeWithTag("settings").performClick()
        compose.waitForTag("section_payments")
        compose.onNodeWithTag("section_payments").performClick()
        compose.waitForTag("currency")
        compose.onNodeWithTag("currency").assertTextContains("AUD – Australian Dollar", substring = true)
        compose.onNodeWithTag("currency").performClick()
        compose.waitForTag("currencySearch")
        compose.onNodeWithTag("currency_AUD").assertIsSelected()
        compose.onNodeWithTag("currencySearch").performTextInput("real")
        compose.waitForTag("currency_BRL")
        compose.onNodeWithTag("currency_auto").assertDoesNotExist()
        compose.onNodeWithTag("currency_BRL").performClick()
        compose.waitForTag("confirm")
        assertThat(container.settingsState.value.payment.currencyCode).isEqualTo("AUD")
        compose.onNodeWithTag("confirm").performClick()
        awaitSetting("BRL") { container.settingsState.value.payment.currencyCode == "BRL" }
        compose.onNodeWithTag("currency").assertTextContains("BRL – Brazilian Real", substring = true)

        compose.onNodeWithTag("currency").performClick()
        compose.waitForTag("currencySearch")
        compose.onNodeWithTag("currencySearch").performTextInput("no such money")
        waitForText("No matching currency")
        compose.onNodeWithTag("currencySearch").performTextReplacement("")
        compose.waitForTag("currency_auto")
        compose.onNodeWithTag("currency_auto").performScrollTo().performClick()
        compose.waitForTag("confirm")
        assertThat(container.settingsState.value.payment.currencyCode).isEqualTo("BRL")
        compose.onNodeWithTag("confirm").performClick()
        awaitSetting("Automatic") {
            container.settingsState.value.payment.currencyCode
                .isEmpty()
        }
        // The device is in Australia, so Automatic means AUD.
        compose.onNodeWithTag("currency").assertTextContains("Automatic (AUD – Australian Dollar)", substring = true)
        assertThat(container.currency().code).isEqualTo("AUD")
    }

    @Test
    fun `home shows just the tiles, and About shows where payments go and the printer`() {
        compose.onNodeWithText("Settings are not protected", substring = true).assertDoesNotExist()
        compose.onNodeWithText("printer available", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("settings").performClick()
        compose.waitForTag("section_about")
        compose.onNodeWithTag("section_about").performScrollTo().performClick()
        waitForText("Payments go to")
        compose.onNodeWithText("Simulator").assertExists()
        compose.onNodeWithText("Printer").assertExists()
        compose.onNodeWithText("Available").assertExists()
    }

    @Test
    fun `settings screens edit values`() {
        compose.onNodeWithTag("settings").performClick()
        compose.waitForTag("section_receipts")
        compose.onNodeWithTag("section_receipts").performClick()
        compose.waitForTag("businessName")
        compose.onNodeWithTag("businessName").performTextReplacement("Harbour Kiosk")
        compose.awaitCondition("Saving the business name") { container.settingsState.value.receipt.businessName == "Harbour Kiosk" }
        waitForText("Harbour Kiosk")
        compose.onNodeWithTag("back").performClick()
        compose.waitForTag("section_terminal")
        compose.onNodeWithTag("section_terminal").performClick()
        compose.waitForTag("terminalMode")
        compose.onNodeWithTag("terminalMode").assertTextContains("Simulator", substring = true)
        // The simulator needs no connection settings; rarely needed ones wait under Advanced.
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithText("Sale ID").assertDoesNotExist()
        compose.onNodeWithTag("advanced").performClick()
        waitForText("Sale ID")

        // Off-terminal, a terminal on the network needs its address and ID, then the shared key and the Checkout API.
        compose.onNodeWithTag("terminalMode").performClick()
        compose.onNodeWithTag("terminalMode_TERMINAL").performClick()
        awaitSetting("Terminal mode") { container.settingsState.value.terminal.mode == TerminalMode.TERMINAL }
        compose.waitForTag("host")
        compose.onNodeWithTag("poiId").assertExists()
        compose.onNodeWithTag("keyIdentifier").assertExists()
        compose.onNodeWithTag("step_3").assertTextContains("Checkout API")
        compose.onNodeWithTag("merchantAccount").assertExists()

        // A terminal in the cloud needs the API key and its ID, which can be found among those connected, and one test
        // checks both the terminal and the Checkout API.
        compose.onNodeWithTag("terminalMode").performClick()
        compose.onNodeWithTag("terminalMode_CLOUD").performClick()
        awaitSetting("Cloud mode") { container.settingsState.value.terminal.mode == TerminalMode.CLOUD }
        compose.waitForTag("findTerminals")
        compose.onNodeWithTag("apiKey").assertExists()
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("keyIdentifier").assertDoesNotExist()
        compose.onNodeWithTag("testApi").assertDoesNotExist()
        compose.onNodeWithTag("testConnection").assertExists()
        compose.onNodeWithTag("step_1").assertTextContains("Adyen account")
        compose.onNodeWithTag("step_2").assertTextContains("Terminal")

        // Tap to Pay: the Payments app (offered from Google Play while none is installed), the Checkout API, setting it
        // up with the Payments app API key, then the shared key.
        compose.onNodeWithTag("terminalMode").performScrollTo().performClick()
        compose.onNodeWithTag("terminalMode_PAYMENTS_APP").performClick()
        awaitSetting("Tap to Pay mode") { container.settingsState.value.terminal.mode == TerminalMode.PAYMENTS_APP }
        compose.waitForTag("setUpTapToPay")
        compose.onNodeWithText("Not installed").assertExists()
        compose.onNodeWithTag("getPaymentsAppTest").assertExists()
        compose.onNodeWithTag("getPaymentsAppLive").assertExists()
        listOf("Adyen Payments app", "Checkout API", "Tap to Pay", "Shared key").forEachIndexed { index, title ->
            compose.onNodeWithTag("step_${index + 1}").assertTextContains(title)
        }
        compose.onNodeWithTag("keyIdentifier").assertExists()
        compose.onNodeWithTag("paymentsAppKey").assertExists()
        compose.onNodeWithTag("host").assertDoesNotExist()

        compose.onNodeWithTag("terminalMode").performScrollTo().performClick()
        compose.onNodeWithTag("terminalMode_SIMULATOR").performClick()
        // Choosing the device's own default stores Automatic, so it keeps following the device.
        awaitSetting("Automatic mode") { container.settingsState.value.terminal.mode == TerminalMode.AUTO }
    }

    @Test
    fun `tax rates are listed and chosen in settings, not products`() {
        val vat = await { container.catalog.saveTaxRate(TaxRateEntity(name = "VAT", rateMilliPercent = 25_500, sortOrder = 9)) }
        await { container.settings.update { it.copy(payment = it.payment.copy(defaultTaxRateId = vat)) } }
        compose.onNodeWithTag("settings").performClick()
        waitForText("VAT 25.5%")
        compose.onNodeWithTag("section_tax").performScrollTo().performClick()
        compose.waitForTag("taxRow_$vat")
        compose.onNodeWithTag("taxRow_$vat").assertTextContains("25.5%", substring = true)
        compose.onNodeWithTag("taxRow_$vat").assertTextContains("Default", substring = true)
        compose.onNodeWithTag("addTaxRate").performScrollTo().assertIsDisplayed()

        // Tax rates are no longer edited under Products.
        compose.onNodeWithTag("back").performClick()
        compose.waitForTag("section_tax")
        compose.onNodeWithTag("back").performClick()
        compose.waitForTag("products")
        compose.onNodeWithTag("products").performClick()
        compose.waitForTag("tab_1")
        compose.onNodeWithTag("tab_2").assertDoesNotExist()
    }
}
