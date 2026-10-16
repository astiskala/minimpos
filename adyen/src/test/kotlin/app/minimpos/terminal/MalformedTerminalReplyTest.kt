package app.minimpos.terminal

import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.RefundParams
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal

/**
 * A terminal's answer to a payment or refund, damaged in every way [JsonDamage] knows, must never change what happened: an
 * approval stays an approval or becomes unknown, a decline stays a decline or becomes unknown, and nothing is thrown.
 * A missing or unreadable answer means unknown, never failed.
 */
class MalformedTerminalReplyTest {
    private val gson = TerminalAPIGsonBuilder.create()
    private val payment = PaymentParams(BigDecimal("12.00"), "AUD", "MP-1")

    private fun client(transport: TerminalTransport) =
        TerminalClient(
            transport = transport,
            identity = TerminalIdentity("POS1", "SIMULATOR-000000001"),
            application = PosApplication("Mini mPOS", "1.0", "Mini mPOS", "Android", "13"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 0, maxInProgressChecks = 1),
        )

    private fun TerminalAPIRequest.isPayment() = saleToPOIRequest.paymentRequest != null

    private fun TerminalAPIRequest.isRefund() = saleToPOIRequest.reversalRequest != null

    private fun TerminalAPIRequest.isStatus() = saleToPOIRequest.transactionStatusRequest != null

    /** What happened, as far as a damaged answer may change it. */
    private fun TransactionOutcome.meaning(): String =
        when (this) {
            is TransactionOutcome.Completed -> if (details.success) "approved" else "declined"
            is TransactionOutcome.NotProcessed -> "not processed"
            is TransactionOutcome.Unknown -> "unknown"
        }

    /**
     * [reply] as the transports hand it over: parsed with Adyen's Gson, or unreadable when that fails in any way (Gson and
     * Adyen's type adapters fail with several exception types, which no transport lets escape).
     */
    private fun delivered(reply: JsonObject): Delivery =
        runCatching { Delivery.Answered(gson.fromJson(reply, TerminalAPIResponse::class.java)) }
            .getOrElse { Delivery.Failed(Fault.UnreadableReply()) }

    /**
     * Runs [operation] against the simulator (configured as [outcome]) and returns its outcome and the JSON of the
     * simulator's answer to the first request [answered] selects.
     */
    private suspend fun original(
        outcome: SimulatedOutcome,
        answered: (TerminalAPIRequest) -> Boolean,
        simulator: TerminalSimulator = TerminalSimulator(config = { SimulatorConfig(outcome = outcome, delayMillis = 0) }),
        operation: suspend (TerminalClient) -> TransactionOutcome,
    ): Pair<TransactionOutcome, JsonObject> {
        var reply: JsonObject? = null
        val result =
            operation(
                client { request, timeout ->
                    simulator.send(request, timeout).also { delivery ->
                        if (reply == null && answered(request) && delivery is Delivery.Answered) {
                            reply = JsonParser.parseString(gson.toJson(delivery.response)).asJsonObject
                        }
                    }
                },
            )
        return result to checkNotNull(reply) { "The simulator did not answer" }
    }

    /**
     * Answers each damaged copy of [reply] instead of the request [answered] selects and checks that [operation] ends as
     * [expected] or unknown; status checks get no answer, as after a broken connection, unless [answered] selects them.
     */
    private suspend fun assertDamageNeverChangesTheOutcome(
        expected: String,
        reply: JsonObject,
        answered: (TerminalAPIRequest) -> Boolean,
        unanswered: (TerminalAPIRequest) -> Boolean = { false },
        simulator: () -> TerminalSimulator = { TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) }) },
        operation: suspend (TerminalClient) -> TransactionOutcome,
    ) {
        val variants = JsonDamage.variants(reply)
        assertThat(variants.size).isGreaterThan(20)
        val wrong =
            variants.mapNotNull { (change, variant) ->
                val backup = simulator()
                var used = false
                // runBlocking keeps the coroutine machinery out of runCatching, which only collects what was thrown.
                val meaning =
                    runCatching {
                        runBlocking {
                            operation(
                                client { request, timeout ->
                                    when {
                                        answered(request) && !used -> delivered(variant).also { used = true }
                                        request.isStatus() || unanswered(request) -> Delivery.Failed(Fault.TimedOut)
                                        else -> backup.send(request, timeout)
                                    }
                                },
                            ).meaning()
                        }
                    }.getOrElse { "threw $it" }
                if (meaning == expected || meaning == "unknown") null else "$change: $expected became $meaning"
            }
        assertWithMessage("Damaged answers changed the outcome").that(wrong).isEmpty()
    }

    @Test
    fun `a damaged approval is approved or unknown, never declined`() =
        runTest {
            val (outcome, reply) = original(SimulatedOutcome.APPROVE, { it.isPayment() }) { it.pay(payment) }
            assertThat(outcome.meaning()).isEqualTo("approved")
            assertDamageNeverChangesTheOutcome("approved", reply, { it.isPayment() }) { it.pay(payment) }
        }

    @Test
    fun `a damaged decline is declined or unknown, never approved`() =
        runTest {
            val (outcome, reply) = original(SimulatedOutcome.DECLINE, { it.isPayment() }) { it.pay(payment) }
            assertThat(outcome.meaning()).isEqualTo("declined")
            assertDamageNeverChangesTheOutcome("declined", reply, { it.isPayment() }) { it.pay(payment) }
        }

    @Test
    fun `a damaged refund answer is accepted or unknown`() =
        runTest {
            val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })
            val paid = client(simulator).pay(payment) as TransactionOutcome.Completed
            val refund =
                RefundParams(
                    checkNotNull(paid.details.poiTransactionId),
                    checkNotNull(paid.details.poiTimestamp),
                    "MP-R",
                    BigDecimal("12.00"),
                    "AUD",
                )
            val (outcome, reply) = original(SimulatedOutcome.APPROVE, { it.isRefund() }, simulator) { it.refund(refund) }
            assertThat(outcome.meaning()).isEqualTo("approved")
            assertDamageNeverChangesTheOutcome("approved", reply, { it.isRefund() }, simulator = { simulator }) { it.refund(refund) }
        }

    @Test
    fun `a damaged status answer for a payment whose answer went missing is approved or unknown`() =
        runTest {
            // The simulator approves, loses the answer, and tells a status check what happened.
            val (outcome, reply) = original(SimulatedOutcome.TIMEOUT, { it.isStatus() }) { it.pay(payment) }
            assertThat(outcome.meaning()).isEqualTo("approved")
            assertDamageNeverChangesTheOutcome("approved", reply, { it.isStatus() }, unanswered = { it.isPayment() }) { it.pay(payment) }
        }
}
