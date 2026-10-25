package app.minimpos.app.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.minimpos.app.MiniMposApp
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.clickWhenEnabled
import app.minimpos.app.data.db.GiftCardStatus
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.core.giftcard.GiftCardEntry
import app.minimpos.core.giftcard.GiftCardProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import app.minimpos.app.createRecordingComposeRule as createComposeRule

/** The Gift cards tile and screens on the smallest terminal, at large text and through a whole activation. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class GiftCardUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val container = env.container
    private val svs = GiftCardSetting(GiftCardProvider.SVS, entries = setOf(GiftCardEntry.SWIPED, GiftCardEntry.KEYED))

    private fun simulator(vararg providers: GiftCardSetting) =
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD", giftCards = providers.toList())) }

    private fun openGiftCards() {
        compose.setContent { MiniMposApp(container) }
        compose.waitUntilAtLeastOneExists(hasTestTag("giftCards"), 15_000)
        compose.onNodeWithTag("giftCards").performScrollTo().performClick()
    }

    @Test
    fun `without providers the screen says where to set them up`() {
        simulator()
        openGiftCards()
        compose.waitUntilAtLeastOneExists(hasText(env.context.getString(R.string.gift_card_none_title)), 15_000)
        compose.onNodeWithText(env.context.getString(R.string.gift_card_none_message)).assertIsDisplayed()
        compose.onNodeWithTag("giftCardStart").assertDoesNotExist()
    }

    @Test
    fun `value changes explain the missing Manager PIN while balance stays available`() {
        simulator(svs)
        openGiftCards()
        compose.waitUntilAtLeastOneExists(hasTestTag("giftCardStart"), 15_000)
        compose.onNodeWithTag("giftCardStart").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("giftCardOperation_LOAD").performScrollTo().performClick()
        compose.onNodeWithText(env.context.getString(R.string.gift_card_manager_note)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("giftCardStart").assertIsNotEnabled()
        // Deactivation is Givex only, so SVS never offers it.
        compose.onNodeWithTag("giftCardOperation_DEACTIVATE").assertDoesNotExist()
    }

    @Test
    fun `an activation fits the smallest terminal at large text and ends on its result`() {
        RuntimeEnvironment.setFontScale(1.5f)
        simulator(svs)
        await { container.managerPin.setPin("2468") }
        openGiftCards()
        compose.waitUntilAtLeastOneExists(hasTestTag("giftCardOperation_ACTIVATE"), 15_000)
        compose.onNodeWithTag("giftCardEntry_1").performScrollTo().performClick()
        compose.onNodeWithTag("giftCardOperation_ACTIVATE").performScrollTo().performClick()
        compose.onNodeWithTag("giftCardStart").assertIsDisplayed().assertTextContains("Activate", substring = true)
        compose.clickWhenEnabled("giftCardStart")
        compose.waitUntilAtLeastOneExists(hasTestTag("confirm"), 15_000)
        compose.onNodeWithTag("confirm").performClick()
        // The value change asks for the Manager PIN after confirmation, in place of the screen.
        compose.waitUntilAtLeastOneExists(hasTestTag("pin_2"), 15_000)
        listOf(2, 4, 6, 8).forEach { compose.onNodeWithTag("pin_$it").performClick() }
        compose.onNodeWithTag("pin_OK").performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag("giftCardStatus"), 15_000)
        compose.onNodeWithTag("giftCardStatus").assertIsDisplayed()
        val stage =
            await {
                container.giftCardRecords
                    .recent(0)
                    .first { it.isNotEmpty() }
                    .single()
                    .stages
                    .single()
            }
        assertThat(stage.status).isEqualTo(GiftCardStatus.SUCCEEDED)
        assertThat(stage.entry).isEqualTo(GiftCardEntry.KEYED.name)
    }
}
