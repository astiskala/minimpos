package app.minimpos.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.awaitCondition
import app.minimpos.app.createRecordingComposeRule
import app.minimpos.app.data.db.ProductEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.db.TenderKind
import app.minimpos.app.feature.refund.RefundLegsScreen
import app.minimpos.app.feature.refund.RefundScreen
import app.minimpos.app.feature.refund.RefundViewModel
import app.minimpos.app.feature.sale.SplitMode
import app.minimpos.app.feature.sale.SplitScreen
import app.minimpos.app.feature.sale.SplitViewModel
import app.minimpos.app.payment.Checkout
import app.minimpos.app.payment.RefundStarted
import app.minimpos.app.payment.TransactionState
import app.minimpos.app.payment.splitStart
import app.minimpos.app.ui.components.LocalAppContainer
import app.minimpos.app.ui.navigation.Navigator
import app.minimpos.app.ui.navigation.Route
import app.minimpos.app.ui.theme.MiniMposTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset

/** Splitting a sale across a share in cash and the rest on a card, and returning it, at AMS1 size. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rAU-w320dp-h460dp-hdpi")
class SplitPaymentUiTest {
    @get:Rule(order = 0)
    val env = TestEnvironment()

    @get:Rule(order = 1)
    val compose = createRecordingComposeRule()

    @Test
    fun `a share paid in cash and the balance on a card fit AMS1`() = split()

    @Test
    @Config(qualifiers = "ja-rJP-w320dp-h460dp-hdpi")
    fun `Japanese split and cash confirmation fit AMS1`() = split()

    @Test
    @Config(qualifiers = "zh-rHK-w320dp-h460dp-hdpi")
    fun `Traditional Chinese split and cash confirmation fit AMS1`() = split()

    @Test
    fun `returning cash waits for a Manager PIN and each leg is shown`() {
        val container = env.container
        val saleId = cashThenCard()
        val vm = RefundViewModel(null, saleId, container.sales, container.storedPaymentActions)
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.Refund(saleId = saleId)))
        try {
            show {
                val route = navigator.current
                if (route is Route.Refund) RefundScreen(null, saleId, navigator, vm = vm)
                if (route == Route.RefundLegs) RefundLegsScreen(navigator)
            }
            compose.awaitCondition("split return proposed") { vm.state.value.allocation.size == 2 }
            assertThat(vm.state.value.returnsCash).isTrue()
            compose.onNodeWithTag("blockedReason").assertIsDisplayed()
            compose.onNodeWithTag("refundTender_${vm.state.value.allocation.keys.first()}").performScrollTo().assertIsDisplayed()
            await { container.managerPin.setPin("2468") }
            container.managerLock.unlock()
            compose.awaitCondition("cash return allowed") { vm.refund() == RefundStarted.LEGS }
            navigator.replace(Route.RefundLegs)
            compose.awaitCondition("legs finished") {
                container.refundLegs.state.value
                    ?.finished == true
            }
            compose.onNodeWithTag("refundLeg_0").assertIsDisplayed()
            compose.onNodeWithTag("refundLeg_1").performScrollTo().assertIsDisplayed()
            assertThat(
                container.refundLegs.state.value!!
                    .legs
                    .map { it.status },
            ).containsExactly(RefundStatus.REQUESTED, RefundStatus.REQUESTED)
            compose.onNodeWithTag("home").performClick()
            compose.awaitCondition("run left") { container.refundLegs.state.value == null }
        } finally {
            close(listOf(vm))
        }
    }

    /** Cancels [models] while draining the main looper, as their flows may still be resuming from Room or the CPU pool. */
    private fun close(models: List<ViewModel>) {
        val jobs = models.mapNotNull { it.viewModelScope.coroutineContext[Job] }
        models.forEach { it.viewModelScope.cancel() }
        compose.awaitCondition("view models closed") { jobs.all { it.isCompleted } }
    }

    private fun show(content: @Composable () -> Unit) =
        compose.setContent {
            MiniMposTheme { CompositionLocalProvider(LocalAppContainer provides env.container) { content() } }
        }

    private fun ringUp() {
        val container = env.container
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        await {
            val taxId = container.catalog.saveTaxRate(TaxRateEntity(name = "GST", rateMilliPercent = 10_000))
            val latte =
                container.catalog.product(
                    container.catalog.saveProduct(ProductEntity(name = "Latte", priceMinor = 450, taxRateId = taxId)),
                )!!
            val session = container.session(SaleKind.SALE)
            session.addProduct(latte, TaxRateEntity(taxId, "GST", 10_000))
            session.addProduct(latte, TaxRateEntity(taxId, "GST", 10_000))
        }
    }

    private fun splitViewModel(saleId: String?): SplitViewModel {
        val container = env.container
        val session = container.session(SaleKind.SALE)
        return SplitViewModel(
            saleId,
            session,
            session.checkout(container.settingsState, container.terminalStatus.state, currency = container::currency),
            container.storedPayments,
            container.payments,
            container.cashTenders,
        )
    }

    private fun split() {
        ringUp()
        val container = env.container
        val models = mutableMapOf<String?, SplitViewModel>()
        val navigator = Navigator(NavBackStack<NavKey>(Route.Home, Route.Checkout(), Route.Split()))
        try {
            show {
                val route = navigator.current
                if (route is Route.Split) {
                    SplitScreen(
                        route.saleId,
                        navigator,
                        vm = models.getOrPut(route.saleId) { splitViewModel(route.saleId) },
                    )
                }
            }
            compose.awaitCondition("split loaded") { models[null]?.state?.value?.balance != null }
            val first = checkNotNull(models[null])
            compose.onNodeWithTag("splitMode_${SplitMode.SHARES.name}").performClick()
            compose.awaitCondition("first share chosen") { first.state.value.amountMinor == 450L }
            compose.onNodeWithTag("splitCard").assertIsDisplayed()
            compose.onNodeWithTag("splitCash").assertIsDisplayed().performClick()
            compose.awaitCondition("cash entry") { first.state.value.cashStage }
            compose
                .onNodeWithTag("confirmCash")
                .assertIsDisplayed()
                .assertIsEnabled()
                .performClick()
            compose.awaitCondition("cash recorded") { navigator.current is Route.SaleResult }
            val id = checkNotNull(navigator.current as? Route.SaleResult).saleId
            val stored = await { container.sales.get(id)!! }
            assertThat(stored.sale.isPartiallyPaid).isTrue()
            assertThat(stored.tenders.single().kind).isEqualTo(TenderKind.CASH)

            navigator.push(Route.Split(id))
            compose.awaitCondition("balance loaded") {
                models[id]
                    ?.state
                    ?.value
                    ?.balance
                    ?.unpaidMinor == 450L
            }
            val rest = checkNotNull(models[id])
            assertThat(rest.state.value.sharesFixed).isTrue()
            compose.onNodeWithTag("splitMode_${SplitMode.SHARES.name}").performClick()
            compose.awaitCondition("last share chosen") { rest.state.value.amountMinor == 450L }
            compose.onNodeWithTag("splitCard").assertIsDisplayed().performClick()
            compose.awaitCondition("card collected") { container.payments.state.value is TransactionState.Finished }
            container.payments.acknowledge()
            assertThat(await { container.sales.get(id)!! }.sale.isPartiallyPaid).isFalse()
        } finally {
            close(models.values.toList())
        }
    }

    /** A 9.00 sale paid 4.00 in cash and 5.00 on a card. */
    private fun cashThenCard(): String {
        ringUp()
        val container = env.container
        val session = container.session(SaleKind.SALE)
        val checkout = await { session.checkout(container.settingsState, currency = container::currency).first { it.canPay } }
        val start = checkNotNull(session.splitStart(checkout, Instant.now(), ZoneOffset.UTC, 400, null))
        val id = checkNotNull(await { container.cashTenders.payNew(start, 500, createdAt = 1) })
        val sale = await { container.sales.get(id)!! }.sale
        val card = checkNotNull(container.payments.start(checkNotNull(Checkout.remainingPayment(sale))))
        await { container.payments.state.first { it == TransactionState.Finished(card) } }
        container.payments.acknowledge()
        return id
    }
}
