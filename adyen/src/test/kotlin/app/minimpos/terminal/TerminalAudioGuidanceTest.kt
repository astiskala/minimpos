package app.minimpos.terminal

import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.ScannedPayment
import app.minimpos.terminal.client.ShopperSessionFlow
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.time.Duration

class TerminalAudioGuidanceTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private val params = PaymentParams(1250, "AUD", "MP-1")
    private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })

    private fun client(reply: (TerminalAPIRequest) -> Delivery) =
        TerminalClient(
            transport =
                TerminalTransport { request, _ ->
                    sent += request
                    reply(request)
                },
            identity = TerminalIdentity("POS1", "AMS1-123"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1, maxInProgressChecks = 2),
            currencyDigits = testCurrencyDigits,
        )

    private fun result(
        type: ResultType,
        condition: ErrorConditionType,
    ) = Response().apply {
        result = type
        errorCondition = condition
    }

    private fun statusReply(response: Response) =
        Delivery.Answered(
            TerminalAPIResponse().apply {
                saleToPOIResponse =
                    SaleToPOIResponse().apply {
                        transactionStatusResponse = TransactionStatusResponse().apply { this.response = response }
                    }
            },
        )

    @Test
    fun `accessible payment stays unknown while the shopper is still using the terminal`() =
        runTest {
            val outcome =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) {
                        Delivery.Failed(Fault.TimedOut)
                    } else {
                        statusReply(result(ResultType.FAILURE, ErrorConditionType.IN_PROGRESS))
                    }
                }.pay(params.copy(audioGuidance = true), "AUDIO2")
            assertThat(outcome).isEqualTo(TransactionOutcome.Unknown(Fault.StillInProgress))
            assertThat(sent.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
            assertThat(sent.count { it.saleToPOIRequest.abortRequest != null }).isEqualTo(0)
        }

    @Test
    fun `audio guidance models include AMS1 and require the documented software generation`() {
        listOf("AMS1", "S1F2", "S1F2L", "S1E2L", "S1U2").forEach {
            assertThat(TerminalClient.audioGuidanceMinimumSoftware("$it-123")).isEqualTo("1.117")
        }
        listOf("M450", "P630", "SFO1").forEach {
            assertThat(TerminalClient.audioGuidanceMinimumSoftware("$it-123")).isEqualTo("1.124")
        }
        listOf(null, "", "S1E-123", "S1F4Pro-123", "AMS10-123", "P400-123").forEach {
            assertThat(TerminalClient.audioGuidanceMinimumSoftware(it)).isNull()
        }
        assertThat(TerminalClient.audioGuidanceMinimumSoftware("ams1-123")).isEqualTo("1.117")
    }

    @Test
    fun `audio guidance sends the payment variant and preserves identity across timeout status recovery`() =
        runTest {
            val timeouts = mutableListOf<Duration>()
            val client =
                TerminalClient(
                    transport =
                        TerminalTransport { request, timeout ->
                            sent += request
                            timeouts += timeout
                            if (request.saleToPOIRequest.paymentRequest != null) {
                                simulator.send(request, timeout)
                                Delivery.Failed(Fault.TimedOut)
                            } else {
                                simulator.send(request, timeout)
                            }
                        },
                    identity = TerminalIdentity("POS1", "AMS1-123"),
                    recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1),
                    currencyDigits = testCurrencyDigits,
                )
            val outcome = client.pay(PaymentParams(1200, "AUD", "MP-1", audioGuidance = true), "AUDIO1")
            assertThat(outcome).isInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(timeouts.first()).isEqualTo(TerminalClient.ACCESSIBLE_TRANSACTION_TIMEOUT)
            assertThat(sent.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
            val data =
                sent
                    .first()
                    .saleToPOIRequest.paymentRequest.saleData.saleToPOIData
            assertThat(String(Base64.decode(data), Charsets.UTF_8))
                .isEqualTo("""{"Operation":[{"Type":"Payment","Variant":"EnableAccessibilityMode"}]}""")
            val reference =
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference
            assertThat(reference.serviceID).isEqualTo("AUDIO1")
            assertThat(reference.saleID).isEqualTo("POS1")
            assertThat(reference.messageCategory).isEqualTo(MessageCategoryType.PAYMENT)
        }

    @Test
    fun `audio guidance refuses incompatible requests before sending`() {
        val params = PaymentParams(1200, "AUD", "MP-1", audioGuidance = true)
        assertThrows(IllegalArgumentException::class.java) { params.copy(terminalTip = TerminalTipOptions()) }
        assertThrows(IllegalArgumentException::class.java) { params.copy(askGiving = true) }
        assertThrows(IllegalArgumentException::class.java) { params.copy(cardEntryOverride = CardEntryOverride.MKE) }
        assertThrows(IllegalArgumentException::class.java) { params.copy(shopperFlow = ShopperSessionFlow()) }
        assertThrows(IllegalArgumentException::class.java) { params.copy(scannedPayment = ScannedPayment("wechatpay_pos", "123")) }
        assertThat(sent).isEmpty()
    }
}
