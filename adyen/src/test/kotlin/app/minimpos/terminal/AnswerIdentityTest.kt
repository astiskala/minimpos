package app.minimpos.terminal

import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** A financial answer is this request's outcome only when every identity its header echoes names this request. */
class AnswerIdentityTest {
    private fun header(change: MessageHeader.() -> Unit = {}) =
        MessageHeader().apply {
            saleID = "POS"
            serviceID = "PAY1"
            poiid = "AMS1-123"
            messageCategory = MessageCategoryType.PAYMENT
            messageType = MessageType.RESPONSE
            change()
        }

    private suspend fun pay(answer: MessageHeader?): TransactionOutcome =
        TerminalClient(
            identity = TerminalIdentity("POS", "AMS1-123"),
            currencyDigits = testCurrencyDigits,
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 0, maxInProgressChecks = 1),
            transport =
                TerminalTransport { request, _ ->
                    if (request.saleToPOIRequest.paymentRequest == null) {
                        Delivery.Failed(Fault.TimedOut)
                    } else {
                        Delivery.Answered(
                            TerminalAPIResponse().apply {
                                saleToPOIResponse =
                                    SaleToPOIResponse().apply {
                                        messageHeader = answer
                                        paymentResponse =
                                            PaymentResponse().apply { response = Response().apply { result = ResultType.SUCCESS } }
                                    }
                            },
                        )
                    }
                },
        ).pay(PaymentParams(1200, "AUD", "MP-1"), serviceId = "PAY1")

    @Test
    fun `an answer naming this request, or echoing nothing, is its outcome`() =
        runTest {
            assertThat(pay(header())).isInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(pay(null)).isInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(pay(header { poiid = null })).isInstanceOf(TransactionOutcome.Completed::class.java)
        }

    @Test
    fun `an answer naming another terminal or request stays unknown`() =
        runTest {
            listOf<MessageHeader.() -> Unit>(
                { poiid = "AMS1-OTHER" },
                { serviceID = "OTHER" },
                { saleID = "OTHER" },
                { messageCategory = MessageCategoryType.REVERSAL },
            ).forEach { change ->
                assertThat(pay(header(change))).isInstanceOf(TransactionOutcome.Unknown::class.java)
            }
        }
}
