package app.minimpos.app.payment

import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.started
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.transport.Fault
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioGuidanceLifecycleTest {
    @get:Rule
    val env = TestEnvironment()

    private val container = env.container
    private val payments = container.payments

    private fun request() =
        PaymentStart(
            totals = Cart().addCustom("Item", 1200, AppliedTax("Zero", 0), "item").totals(TaxMode.INCLUSIVE),
            currency = CurrencySpec("AUD", 2),
            merchantReference = "AUDIO",
            customerReference = null,
            shopperEmail = null,
            tokenization = null,
        )

    private fun finished(id: String) =
        await {
            payments.state.first { it == TransactionState.Finished(id) }
            checkNotNull(container.sales.get(id))
        }

    @Test
    fun `accessible request is durable before send and a recreated runner uses its original status identity`() {
        val ready = CompletableDeferred<TestEnvironment>()
        val sentIds = mutableListOf<String>()
        val terminal =
            FakeTerminal(passphrase = "passphrase", reply = { wire ->
                if (wire.saleToPOIRequest.paymentRequest != null) {
                    val pending =
                        checkNotNull(
                            ready
                                .await()
                                .container.sales
                                .get("durable-audio"),
                        ).sale
                    assertThat(pending.status).isEqualTo(SaleStatus.PENDING)
                    assertThat(pending.audioGuidance).isTrue()
                    assertThat(pending.serviceId).isEqualTo(wire.saleToPOIRequest.messageHeader.serviceID)
                    sentIds += wire.saleToPOIRequest.messageHeader.serviceID
                }
                null
            })
        val isolated = TestEnvironment(terminal = terminal)
        ready.complete(isolated)
        try {
            isolated.useLinks()
            isolated.updateSettings { it.copy(payment = it.payment.copy(audioGuidance = true)) }
            val delegate = SaleBook(isolated.container.sales)
            val unconfirmed =
                object : TransactionBook<PaymentStart> by delegate {
                    override suspend fun settle(
                        id: String,
                        settlement: Settlement,
                    ): Boolean = delegate.settle(id, settlement.copy(status = SettlementStatus.UNKNOWN))
                }
            val lifecycle = TransactionLifecycle(isolated.scope, isolated.container.gateway, unconfirmed, newId = { "durable-audio" })
            val id = checkNotNull(lifecycle.start(request().copy(audioGuidance = true, audioGuidancePoiId = "S1F2-000158213605014")))
            await { lifecycle.state.first { it == TransactionState.Finished(id) } }
            val recreated = TransactionLifecycle(isolated.scope, isolated.container.gateway, delegate)
            await { assertThat(recreated.recheck(id)).isEqualTo(Recheck.Settled) }
            val settled =
                await {
                    isolated.container.sales
                        .get(id)!!
                        .sale
                }
            assertThat(settled.status).isEqualTo(SaleStatus.APPROVED)
            assertThat(settled.audioGuidance).isTrue()
            assertThat(sentIds).containsExactly(settled.serviceId)
            assertThat(settled.context!!.poiId).isEqualTo("S1F2-000158213605014")
        } finally {
            isolated.close()
        }
    }

    @Test
    fun `accessible payment persists request facts before sending and recovers without another payment`() {
        env.useSimulator {
            it.copy(
                payment = it.payment.copy(audioGuidance = true),
                simulator = it.simulator.copy(outcome = SimulatedOutcome.TIMEOUT, delayMillis = 0),
            )
        }
        val id = payments.started(request().copy(audioGuidance = true, audioGuidancePoiId = "SIMULATOR-000000001"))
        val sale = finished(id).sale
        assertThat(sale.status).isEqualTo(SaleStatus.APPROVED)
        assertThat(sale.audioGuidance).isTrue()
        assertThat(sale.serviceId).isNotEmpty()
        assertThat(sale.context!!.poiId).isEqualTo("SIMULATOR-000000001")
        assertThat(sale.customerReceiptJson).isNotEmpty()
    }

    @Test
    fun `changed accessible terminal is not silently replaced with ordinary card collection`() {
        env.useSimulator { it.copy(payment = it.payment.copy(audioGuidance = true)) }
        val id = payments.started(request().copy(audioGuidance = true, audioGuidancePoiId = "AMS1-old"))
        val sale = finished(id).sale
        assertThat(sale.status).isEqualTo(SaleStatus.FAILED)
        assertThat(sale.audioGuidance).isTrue()
        assertThat(sale.poiTransactionId).isNull()
        assertThat(sale.reason).isEqualTo(StoredReason.NotDone(Failure.Remote(Fault.Unsupported)))
    }

    @Test
    fun `audio guidance turned off in Settings after checkout sends no payment`() {
        env.useSimulator()
        val id = payments.started(request().copy(audioGuidance = true, audioGuidancePoiId = "SIMULATOR-000000001"))
        val sale = finished(id).sale
        assertThat(sale.status).isEqualTo(SaleStatus.FAILED)
        assertThat(sale.poiTransactionId).isNull()
        assertThat(sale.reason).isEqualTo(StoredReason.NotDone(Failure.Remote(Fault.Unsupported)))
    }
}
