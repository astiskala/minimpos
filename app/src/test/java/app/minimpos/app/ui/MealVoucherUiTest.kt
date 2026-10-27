package app.minimpos.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.feature.sale.SplitScreen
import app.minimpos.app.feature.sale.SplitViewModel
import app.minimpos.app.payment.MealVoucherAvailability
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import app.minimpos.core.voucher.MealVoucherScheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Paying the eligible items of a mixed basket by meal voucher in a split, at AMS1 size and in CJK languages. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS-w320dp-h460dp-hdpi")
class MealVoucherUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `English voucher stage fits an AMS1 with large text`() {
        RuntimeEnvironment.setFontScale(1.5f)
        payEligibleItems()
    }

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese voucher stage fits an AMS1`() = payEligibleItems()

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h460dp-hdpi")
    fun `Chinese voucher stage fits an AMS1`() = payEligibleItems()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese voucher stage fits an AMS1`() = payEligibleItems()

    private fun payEligibleItems() {
        val container = env.container
        ringUp()
        val vm = splitViewModel()
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.Checkout(), Route.Split()))
        try {
            compose.setContent {
                MiniMposTheme {
                    CompositionLocalProvider(LocalAppContainer provides container) {
                        if (navigator.current is Route.Split) SplitScreen(null, navigator, vm = vm)
                    }
                }
            }
            compose.awaitCondition("voucher offered") { vm.state.value.voucher != null }
            compose.onNodeWithTag("splitMealVoucher").assertIsDisplayed().performClick()
            compose.awaitCondition("voucher stage") { vm.state.value.voucherStage }
            val scheme = "voucherScheme_${MealVoucherScheme.EUROPE_CARD_SCHEME.name}"
            compose.onNodeWithTag("splitChoices").performScrollToNode(hasTestTag(scheme))
            compose.onNodeWithTag(scheme).assertIsDisplayed().performClick()
            compose.awaitCondition("eligible amount offered") {
                val state = vm.state.value
                val balance = state.balance
                state.voucherReady && balance != null && state.amountMinor == balance.eligibleMinor &&
                    state.amountMinor < balance.totalMinor
            }
            val eligible = vm.state.value.amountMinor
            val total =
                vm.state.value.balance!!
                    .totalMinor
            compose.onNodeWithTag("splitChoices").performScrollToNode(hasTestTag("mealVoucher"))
            compose.onNodeWithTag("mealVoucher").assertIsDisplayed()
            compose.onNodeWithTag("payMealVoucher").assertIsDisplayed().assertIsEnabled()
            compose.onNode(hasTestTag("payMealVoucher")).performClick()
            compose.awaitCondition("voucher collected") { container.payments.state.value is TransactionState.Finished }
            val id = (container.payments.state.value as TransactionState.Finished).id
            container.payments.acknowledge()
            val record = await { container.sales.get(id)!! }
            assertThat(record.tenders.single().mealVoucher).isEqualTo(MealVoucherScheme.EUROPE_CARD_SCHEME.name)
            assertThat(record.sale.paidMinor).isEqualTo(eligible)
            assertThat(record.sale.remainingMinor).isEqualTo(total - eligible)
        } finally {
            val job = vm.viewModelScope.coroutineContext[Job]
            vm.viewModelScope.cancel()
            compose.awaitCondition("view model closed") { job?.isCompleted != false }
        }
    }

    /** A EUR sale of an eligible salad and an ineligible wine, in the simulator with vouchers configured. */
    private fun ringUp() {
        val container = env.container
        env.useSimulator {
            it.copy(payment = it.payment.copy(currencyCode = "EUR", mealVouchers = true, allowPartialAuthorisation = true))
        }
        await {
            container.walletDiscovery.refresh()
            val taxId = container.catalog.saveTaxRate(TaxRateEntity(name = "TVA", rateMilliPercent = 10_000))
            val tax = TaxRateEntity(taxId, "TVA", 10_000)
            val salad =
                container.catalog.product(
                    container.catalog.saveProduct(
                        ProductEntity(name = "Salad", priceMinor = 900, taxRateId = taxId, mealVoucherEligible = true),
                    ),
                )!!
            val wine =
                container.catalog.product(
                    container.catalog.saveProduct(ProductEntity(name = "Wine", priceMinor = 600, taxRateId = taxId)),
                )!!
            val session = container.session(SaleKind.SALE)
            session.addProduct(salad, tax)
            session.addProduct(wine, tax)
        }
    }

    private fun splitViewModel(): SplitViewModel {
        val container = env.container
        val session = container.session(SaleKind.SALE)
        return SplitViewModel(
            null,
            session,
            session.checkout(container.settingsState, container.terminalStatus.state, currency = container::currency),
            container.storedPayments,
            container.payments,
            container.cashTenders,
            vouchers =
                combine(container.settingsState, container.walletDiscovery.state) { settings, discovery ->
                    { currency: String ->
                        MealVoucherAvailability(
                            settings.payment.mealVouchers,
                            discovery.mealVoucherSchemes(currency),
                            discovery.context,
                            discovery.identity,
                            discovery.simulated,
                            settings.payment.mealVoucherAccountSelection,
                        )
                    }
                },
        )
    }
}
