package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.feature.sale.TokenRow
import app.minimpos.app.payment.TokenDeletionStatus
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.components.SetupGateHost
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.StoredCard
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class TokenManagementUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private fun waitForTag(tag: String) = compose.waitUntilAtLeastOneExists(hasTestTag(tag), 15_000)

    private fun openTokens(manager: Boolean) {
        env.useSimulator {
            it.copy(payment = it.payment.copy(shopperReferenceSource = ShopperReferenceSource.CUSTOMER_REFERENCE))
        }
        await {
            env.container.database.saleDao().insert(
                SaleEntity(
                    id = "saved",
                    createdAt = 1,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 1_000,
                    taxMinor = 0,
                    totalMinor = 1_000,
                    status = SaleStatus.APPROVED,
                    merchantReference = "earlier",
                    shopperReference = "CUST-001",
                    storedPaymentMethodId = "TOKEN1",
                    paymentBrand = "visa",
                    maskedPan = "411111******1111",
                    context = PaymentContext("SIMULATOR", "SIM", "POS", "", null, simulated = true),
                ),
            )
            env.container.pinManager.setPin("1357")
            if (manager) env.container.managerPin.setPin("2468")
            env.container.settings.update {
                it.copy(
                    security =
                        it.security.copy(
                            managerRequirements = mapOf(ManagerAction.SAVED_CARD_LOOKUP.name to manager),
                        ),
                )
            }
        }
        compose.setContent { MiniMposApp(env.container) }
        compose.onNodeWithTag("savedCard").performScrollTo().performClick()
        if (manager) {
            waitForTag("pin_2")
            listOf(2, 4, 6, 8).forEach { compose.onNodeWithTag("pin_$it").performClick() }
            compose.onNodeWithTag("pin_OK").performClick()
        }
        waitForTag("savedCardReference")
        compose.onNodeWithTag("savedCardReference").performTextInput("CUST-001")
        compose.onNodeWithTag("findCards").performScrollTo()
        compose.clickWhenEnabled("findCards")
        waitForTag("chargeToken_TOKEN1")
    }

    @Test
    fun `an unchargeable listed token keeps its Delete action enabled`() {
        env.useSimulator()
        var removed: String? = null
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    SetupGateHost(onSetUp = {}) {
                        TokenRow(
                            StoredCard("OTHER_MODEL"),
                            usable = false,
                            removable = true,
                            onChoose = { error("Cannot charge this token") },
                            onDelete = { removed = it.id },
                        )
                    }
                }
            }
        }
        compose.onNodeWithTag("chargeToken_OTHER_MODEL").assertIsNotEnabled()
        compose.onNodeWithTag("deleteToken_OTHER_MODEL").performClick()
        assertThat(removed).isEqualTo("OTHER_MODEL")
    }

    @Test
    fun `a listed token has a separate confirmed Delete action on AMS1`() = deleteToken()

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese token deletion confirmation scrolls and remains usable on AMS1`() = deleteToken()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese token deletion confirmation scrolls and remains usable on AMS1`() = deleteToken()

    private fun deleteToken() {
        openTokens(manager = true)
        compose.onNodeWithTag("deleteToken_TOKEN1").performScrollTo().performClick()
        compose.waitUntilAtLeastOneExists(hasText("TOKEN1", substring = true), 15_000)
        compose.onNodeWithText("TOKEN1", substring = true).assertTextContains("CUST-001", substring = true)
        compose.onNodeWithTag("confirm").assertIsDisplayed()
        // Both callbacks arrive in the same input batch; only one confirmed operation may start.
        compose.onNodeWithTag("confirm").performTouchInput {
            click()
            click()
        }
        compose.awaitCondition(
            "deleted at simulator",
        ) { env.container.savedCardCharges.deletion.value.status == TokenDeletionStatus.DELETED }
        waitForTag("tokenDeletionOutcome")
        compose.onNodeWithTag("findCards").performScrollTo()
        compose.clickWhenEnabled("findCards")
        waitForTag("noSavedCards")
        assertThat(await { env.container.sales.get("saved") }?.sale?.storedPaymentMethodId).isEqualTo("TOKEN1")
        assertThat(env.container.sessionLock.unlocked.value).isFalse()
    }

    @Test
    fun `without a configured Manager PIN deletion explains what to configure`() {
        openTokens(manager = false)
        compose.onNodeWithTag("findCards").performScrollTo()
        compose.onNodeWithText(env.context.getString(R.string.token_delete_manager)).assertExists()
        compose.onNodeWithTag("deleteToken_TOKEN1").performScrollTo().assertIsNotEnabled()
        assertThat(env.container.savedCardCharges.deletion.value.status).isEqualTo(TokenDeletionStatus.IDLE)
    }
}
