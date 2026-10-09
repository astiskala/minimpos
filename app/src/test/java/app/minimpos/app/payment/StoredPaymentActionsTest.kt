package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.refund.RefundChoice
import app.minimpos.app.refund.Refundability
import app.minimpos.app.refund.RefundablePayment
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
        StoredPaymentActions(container.refunds, container.captures, container.settingsState, clock) {
            ZoneOffset.ofHours(2)
        }

    private fun pay(kind: SaleKind = SaleKind.SALE): SaleWithLines {
        val id =
            container.payments.start(
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
        assertThat(actions.refund(payment, RefundChoice.Amount(0))).isFalse()
        assertThat(actions.refund(payment, RefundChoice.Amount(1001))).isFalse()
        assertThat(container.refunds.state.value).isEqualTo(TransactionState.Idle)
        assertThat(actions.refund(payment, RefundChoice.Everything)).isTrue()
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
            val actions = StoredPaymentActions(lifecycle, container.captures, container.settingsState, clock)
            assertThat(actions.refund(refundable(sale), RefundChoice.Everything)).isTrue()
            assertThat(actions.refund(refundable(sale), RefundChoice.Everything)).isFalse()
            assertThat(actions.cancel(held)).isNull()
        }

    @Test
    fun `manager approval is still checked by financial implementation after initiation`() {
        env.useSimulator()
        val payment = refundable(pay())
        await { container.managerPin.setPin("2468") }
        assertThat(actions().refund(payment, RefundChoice.Everything)).isTrue()
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
