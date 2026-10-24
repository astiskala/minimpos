package app.minimpos.app.payment

import app.minimpos.app.AppContainer
import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.repo.HistoryItem
import app.minimpos.app.history.HistoryAccounting
import app.minimpos.app.history.RefundChoice
import app.minimpos.app.history.Refundability
import app.minimpos.app.history.RefundablePayment
import app.minimpos.app.started
import app.minimpos.app.terminal.Attempt
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.client.TerminalTotalsOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class SurchargeWorkflowTest {
    @Test
    fun `split card fees survive a missing answer recreated recovery and individual refunds`() =
        runTest {
            val fixture = Fixture()
            val env = TestEnvironment(terminal = fixture.terminal)
            try {
                env.useLinks()
                val container = env.container
                val id = container.payments.started(request())
                await { container.payments.state.first { it == TransactionState.Finished(id) } }
                val partial = await { checkNotNull(container.sales.get(id)) }
                assertThat(partial.sale.paidMinor).isEqualTo(500)
                assertThat(partial.sale.amountMinor).isEqualTo(550)
                assertThat(partial.tenders.single().surchargeMinor).isEqualTo(50)
                container.payments.acknowledge()
                fixture.hide = true
                fixture.config = fixture.config.copy(partialApprovalPercent = 100)
                val continuing = TransactionLifecycle(this, container.gateway, SaleBook(container.sales))
                continuing.started(checkNotNull(Checkout.remainingPayment(partial.sale)))
                continuing.state.first { it == TransactionState.Finished(id) }
                val unknown = await { checkNotNull(container.sales.get(id)) }
                assertThat(unknown.sale.status).isEqualTo(SaleStatus.UNKNOWN)
                assertThat(unknown.sale.serviceId).isEqualTo(fixture.sent.last())
                assertThat(unknown.sale.serviceId).isNotEqualTo(partial.sale.serviceId)
                assertThat(unknown.sale.tenderRequestedMinor).isEqualTo(500)
                fixture.hide = false
                val recreated = TransactionLifecycle(env.scope, container.gateway, SaleBook(container.sales))
                await { recreated.recheck(id) }
                val paid = await { checkNotNull(container.sales.get(id)) }
                assertThat(paid.sale.paidMinor).isEqualTo(1000)
                assertThat(paid.sale.surchargeMinor).isEqualTo(100)
                assertThat(paid.sale.amountMinor).isEqualTo(1100)
                assertThat(paid.tenders).hasSize(2)
                assertThat(fixture.sent).hasSize(2)
                await { recreated.recheck(id) }
                assertThat(await { container.sales.get(id) }!!.tenders).hasSize(2)
                verifyAccounting(container, paid)
                refundCards(container, paid) { fixture.reversals }
            } finally {
                env.close()
            }
        }

    private fun request() =
        PaymentStart(
            totals = Cart().addCustom("Item", 1000, AppliedTax("Zero", 0), "item").totals(TaxMode.INCLUSIVE),
            currency = CurrencySpec.of("AUD"),
            merchantReference = "FEE",
            customerReference = null,
            shopperEmail = null,
            tokenization = null,
            allowPartialAuthorisation = true,
        )

    private fun verifyAccounting(
        container: AppContainer,
        paid: SaleWithLines,
    ) {
        val document = container.receiptFactory.sale(paid, await { container.settings.current() }.receipt)
        val text = PlainTextReceiptRenderer(48).render(document)
        assertThat(text).contains("1.00")
        assertThat(text).contains("11.00")
        assertThat(text.split("Surcharge").size).isAtLeast(3)
        val totals = await { container.gateway.totals(false) { true } }
        val remote = (totals as Attempt.Made).result as TerminalTotalsOutcome.Answered
        val comparison =
            HistoryAccounting.ReconciliationAccumulator(
                "S1F2-000158213605014",
                remote,
                Instant.now().atZone(ZoneOffset.UTC).toLocalDate(),
                ZoneOffset.UTC,
            )
        comparison.add(listOf(HistoryItem.Sale(paid.sale, paid.tenders)))
        assertThat(comparison.report().allMatch).isTrue()
    }

    private fun refundCards(
        container: AppContainer,
        paid: SaleWithLines,
        reversals: () -> Int,
    ) {
        val id = paid.sale.id
        val payment = (RefundablePayment.check(paid) as Refundability.Refundable).payment
        val refund = checkNotNull(payment.request(RefundChoice.Everything, "", Instant.now(), ZoneOffset.UTC))
        val refundId = checkNotNull(container.operations.refund(refund))
        await { container.operations.refundProgress.first { it == TransactionState.Finished(refundId) } }
        assertThat(await { container.refundRecords.get(refundId) }!!.status).isEqualTo(RefundStatus.REQUESTED)
        val after = await { checkNotNull(container.sales.get(id)) }
        assertThat(after.sale.refundedMinor).isEqualTo(550)
        assertThat((RefundablePayment.check(after) as Refundability.Refundable).payment.transactionId)
            .isNotEqualTo(payment.transactionId)
        container.operations.acknowledgeRefund()
        val staleId = checkNotNull(container.operations.refund(refund))
        await { container.operations.refundProgress.first { it == TransactionState.Finished(staleId) } }
        assertThat(await { container.refundRecords.get(staleId) }!!.status).isEqualTo(RefundStatus.FAILED)
        assertThat(await { container.sales.get(id) }!!.sale.refundedMinor).isEqualTo(550)
        assertThat(reversals()).isEqualTo(1)
        container.operations.acknowledgeRefund()
        val second = (RefundablePayment.check(after) as Refundability.Refundable).payment
        val partialReturn = checkNotNull(second.request(RefundChoice.Amount(500), "", Instant.now(), ZoneOffset.UTC))
        assertThat(partialReturn.surchargeMinor).isEqualTo(0)
        val partialId = checkNotNull(container.operations.refund(partialReturn))
        await { container.operations.refundProgress.first { it == TransactionState.Finished(partialId) } }
        container.operations.acknowledgeRefund()
        val feeOnly = (RefundablePayment.check(await { checkNotNull(container.sales.get(id)) }) as Refundability.Refundable).payment
        val last = checkNotNull(feeOnly.request(RefundChoice.Everything, "", Instant.now(), ZoneOffset.UTC))
        assertThat(last.amountMinor).isEqualTo(50)
        assertThat(last.surchargeMinor).isEqualTo(50)
        assertThat(last.full).isFalse()
        val lastId = checkNotNull(container.operations.refund(last))
        await { container.operations.refundProgress.first { it == TransactionState.Finished(lastId) } }
        assertThat(await { container.sales.get(id) }!!.sale.refundedMinor).isEqualTo(1100)
        assertThat(reversals()).isEqualTo(3)
    }

    private class Fixture {
        var config = SimulatorConfig(delayMillis = 0, partialApprovalPercent = 50, surchargeAmount = BigDecimal("0.50"))
        val simulator = TerminalSimulator({ config })
        var hide = false
        val sent = mutableListOf<String>()
        var reversals = 0
        val terminal =
            FakeTerminal(passphrase = "passphrase", reply = { request ->
                val body = request.saleToPOIRequest
                if (body.reversalRequest != null) reversals++
                if (body.paymentRequest != null) {
                    sent += body.messageHeader.serviceID
                    val answer = simulator.send(request, 10.seconds)
                    if (hide) Delivery.Failed(Fault.TimedOut) else answer
                } else if (hide && body.transactionStatusRequest != null) {
                    Delivery.Failed(Fault.TimedOut)
                } else {
                    simulator.send(request, 10.seconds)
                }
            })
    }
}
