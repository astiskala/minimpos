package app.minimpos.app.ui

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.feature.refund.RefundResultScreen
import app.minimpos.app.feature.sale.SaleResultScreen
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A result whose outcome is still unknown offers checking it, never a fresh charge or refund of the same thing. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class UnresolvedResultTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    private val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.Sale, Route.SaleResult("sale"), Route.RefundResult("refund")))

    private lateinit var back: OnBackPressedDispatcher

    private fun showSale(status: SaleStatus) {
        env.useSimulator()
        await {
            env.container.database.saleDao().insert(
                SaleEntity(
                    id = "sale",
                    createdAt = 1,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 100,
                    taxMinor = 0,
                    totalMinor = 100,
                    status = status,
                    merchantReference = "reference",
                    serviceId = "SERVICE1",
                ),
            )
        }
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    back = checkNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
                    SaleResultScreen("sale", navigator)
                }
            }
        }
        compose.waitUntilAtLeastOneExists(hasTestTag("resultStatus"), 15_000)
    }

    @Test
    fun `an unknown payment offers no way back to its cart and leaves for Home`() {
        showSale(SaleStatus.UNKNOWN)
        compose.onNodeWithTag("checkAgain").assertExists()
        compose.onNodeWithTag("backToSale").assertDoesNotExist()
        compose.runOnUiThread { back.onBackPressed() }
        compose.awaitCondition("Back leaves an unknown payment for Home") { navigator.current == Route.Home }
    }

    @Test
    fun `a failed payment still goes back to its sale`() {
        showSale(SaleStatus.FAILED)
        compose.onNodeWithTag("backToSale").assertExists()
    }

    @Test
    fun `an unknown refund offers no new refund`() {
        env.useSimulator()
        await {
            env.container.database.refundDao().insert(
                RefundEntity(
                    id = "refund",
                    saleId = null,
                    createdAt = 1,
                    merchantReference = "R-1",
                    originalTransactionId = "T.1",
                    originalTimestamp = "2026-09-29T11:04:00.000Z",
                    originalReference = null,
                    currency = "AUD",
                    amountMinor = 100,
                    full = false,
                    status = RefundStatus.UNKNOWN,
                    serviceId = "REFUND1",
                ),
            )
        }
        compose.setContent {
            MiniMposTheme {
                CompositionLocalProvider(LocalAppContainer provides env.container) {
                    RefundResultScreen("refund", navigator)
                }
            }
        }
        compose.waitUntilAtLeastOneExists(hasTestTag("checkAgain"), 15_000)
        compose.onNodeWithTag("newRefund").assertDoesNotExist()
    }
}
