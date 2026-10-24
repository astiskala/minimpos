package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.R
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.closeViewModels
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.feature.refund.RefundScreen
import app.minimpos.app.feature.refund.RefundViewModel
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SurchargeUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `original card and fee review remain usable on AMS1`() = review()

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese original card and fee review fit AMS1`() = review()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese original card and fee review fit AMS1`() = review()

    private fun review() {
        env.useSimulator()
        val container = env.container
        seed()
        val vm = RefundViewModel(null, "fee", container.sales, container.storedPaymentActions)
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.Refund(saleId = "fee")))
        try {
            compose.setContent {
                MiniMposTheme {
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        RefundScreen(null, "fee", navigator, vm = vm)
                    }
                }
            }
            compose.awaitCondition("original card loaded") { vm.state.value.original != null }
            compose.onNodeWithTag("startRefund").assertIsDisplayed()
            compose.onNodeWithTag("refundTender_fee-2").performScrollTo().performClick()
            compose.awaitCondition("second original card selected") {
                vm.state.value.original
                    ?.transactionId == "T.P2"
            }
            assertThat(vm.state.value.refundMinor).isEqualTo(650)
            compose.onNodeWithText(env.context.getString(R.string.surcharge_return_fee)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("startRefund").assertIsDisplayed()
        } finally {
            closeViewModels(listOf(vm))
        }
    }

    private fun seed() {
        val container = env.container
        await {
            container.database.saleDao().insert(
                SaleEntity(
                    id = "fee",
                    createdAt = 100,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 1000,
                    taxMinor = 0,
                    totalMinor = 1000,
                    status = SaleStatus.APPROVED,
                    merchantReference = "FEE",
                    terminalPaidMinor = 1200,
                    paidMinor = 1000,
                    surchargeMinor = 100,
                    tipMinor = 100,
                ),
            )
            listOf(1, 2).forEach { sequence ->
                container.database.saleDao().upsertTender(
                    SaleTenderEntity(
                        id = "fee-$sequence",
                        saleId = "fee",
                        sequence = sequence,
                        requestedMinor = 500,
                        authorisedMinor = if (sequence == 1) 550 else 650,
                        poiTransactionId = "T.P$sequence",
                        poiTimestamp = "2026-10-24T00:00:00Z",
                        maskedPan = "**** 111$sequence",
                        surchargeMinor = 50,
                        tipMinor = if (sequence == 1) 0 else 100,
                    ),
                )
            }
        }
    }
}
