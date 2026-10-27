package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.history.RefundChoice
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
import app.minimpos.app.started
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StoredPaymentActionsTest {
    @get:Rule
    val env = TestEnvironment()
    private val container get() = env.container
    private val clock = Clock.fixed(Instant.parse("2026-10-07T23:30:00Z"), ZoneOffset.UTC)

    private fun actions() =
        StoredPaymentActions(container.operations, container.captures, container.settingsState, clock, zone = { ZoneOffset.ofHours(2) })

    private fun pay(kind: SaleKind = SaleKind.SALE): SaleWithLines {
        val id =
            container.payments.started(
                PaymentStart(
                    Cart().addCustom("Item", 1000, AppliedTax.NONE, "item").totals(TaxMode.INCLUSIVE),
                    CurrencySpec("AUD", 2),
                    "ORIGINAL",
                    null,
                    null,
                    null,
                    kind,
                ),
            )
        val record =
            await {
                container.payments.state.first { it == TransactionState.Finished(id) }
                checkNotNull(container.sales.get(id))
            }
        container.payments.acknowledge()
        return record
    }

    private fun refundable(record: SaleWithLines) = (RefundablePayment.check(record) as Refundability.Refundable).payment

    private fun finishedRefund() =
        await {
            val finished = container.refunds.state.first { it is TransactionState.Finished } as TransactionState.Finished
            checkNotNull(container.refundRecords.get(finished.id))
        }

    @Test
    fun `refund initiation validates amounts and reads current prefix with operation time and zone`() {
        env.useSimulator { it.copy(payment = it.payment.copy(referencePrefix = " OLD ")) }
        val payment = refundable(pay())
        val actions = actions()
        env.updateSettings { it.copy(payment = it.payment.copy(referencePrefix = " NEW ")) }
        assertThat(actions.refund(payment, RefundChoice.Amount(0), payment.proposal(RefundChoice.Amount(0)))).isEqualTo(RefundStarted.NONE)
        assertThat(
            actions.refund(payment, RefundChoice.Amount(1001), payment.proposal(RefundChoice.Amount(1001))),
        ).isEqualTo(RefundStarted.NONE)
        assertThat(container.refunds.state.value).isEqualTo(TransactionState.Idle)
        assertThat(
            actions.refund(payment, RefundChoice.Everything, payment.proposal(RefundChoice.Everything)),
        ).isEqualTo(RefundStarted.SINGLE)
        val refund = finishedRefund()
        assertThat(refund.status).isEqualTo(RefundStatus.REQUESTED)
        assertThat(refund.amountMinor).isEqualTo(1000)
        assertThat(refund.full).isTrue()
        assertThat(refund.merchantReference).matches("NEW-R-261008-013000-[A-Z0-9]{4}")
        assertThat(refund.originalReference).isEqualTo("ORIGINAL")
    }

    @Test
    fun `cancellation initiation retains full reversal meaning and original context`() {
        env.useSimulator { it.copy(payment = it.payment.copy(referencePrefix = "SHOP")) }
        val record = pay(SaleKind.PRE_AUTHORISATION)
        val id = actions().cancel(record)
        assertThat(id).isNotNull()
        val refund = finishedRefund()
        assertThat(refund.id).isEqualTo(id)
        assertThat(refund.cancellation).isTrue()
        assertThat(refund.full).isTrue()
        assertThat(refund.context).isEqualTo(record.sale.context)
        assertThat(refund.merchantReference).matches("SHOP-C-261008-013000-[A-Z0-9]{4}")
        assertThat(actions().cancel(pay())).isNull()
    }

    @Test
    fun `busy lifecycle rejects another refund and cancellation without view models`() =
        runTest {
            env.useSimulator()
            val sale = pay()
            val held = pay(SaleKind.PRE_AUTHORISATION)
            val lifecycle = TransactionLifecycle(backgroundScope, container.gateway, RefundBook(container.refundRecords))
            val checkout = CheckoutRefunds(backgroundScope, container.refundRecords, container.sales, container.api::target)
            val operations =
                FinancialOperations(
                    container.payments,
                    FinancialOperations.Refunds(
                        lifecycle,
                        TransactionLifecycle(backgroundScope, container.gateway, UnreferencedRefundBook(container.refundRecords)),
                        checkout,
                        container.refundRecords,
                    ),
                    container.savedCardCharges,
                    container.links,
                    container.sales,
                    container.giftCards,
                )
            val actions = StoredPaymentActions(operations, container.captures, container.settingsState, clock)
            val payment = refundable(sale)
            val everything = payment.proposal(RefundChoice.Everything)
            assertThat(actions.refund(payment, RefundChoice.Everything, everything)).isEqualTo(RefundStarted.SINGLE)
            assertThat(operations.running).isTrue()
            assertThat(actions.refund(payment, RefundChoice.Everything, everything)).isEqualTo(RefundStarted.NONE)
            assertThat(actions.cancel(held)).isNull()
        }

    @Test
    fun `manager approval is still checked by financial implementation after initiation`() {
        env.useSimulator()
        val payment = refundable(pay())
        await {
            container.pinManager.setPin("1357")
            container.managerPin.setPin("2468")
        }
        assertThat(
            actions().refund(payment, RefundChoice.Everything, payment.proposal(RefundChoice.Everything)),
        ).isEqualTo(RefundStarted.SINGLE)
        val refund = finishedRefund()
        assertThat(refund.status).isEqualTo(RefundStatus.FAILED)
        assertThat(refund.reason).isEqualTo(StoredReason.NotDone(Failure.NotSetUp(SetupProblem.MANAGER_APPROVAL)))
    }

    @Test
    fun `capture retry keeps capture implementation eligibility`() {
        env.useSimulator()
        assertThat(await { actions().retryCapture(pay().sale.id) }).isEqualTo(CaptureResult.NotAllowed)
    }
}
