package app.minimpos.terminal

import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.MexicanPayment
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.RefundParams
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import app.minimpos.terminal.transport.nexoGson
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.Instalment
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.PaymentType
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

class TerminalMexicanPaymentTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private val params = PaymentParams(1200, "MXN", "MEXICO")
    private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })

    private fun client(reply: suspend (TerminalAPIRequest) -> Delivery) =
        TerminalClient(
            transport =
                TerminalTransport { request, _ ->
                    sent += request
                    reply(request)
                },
            identity = TerminalIdentity("POS1", "AMS1-123"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1),
            currencyDigits = testCurrencyDigits,
        )

    @Test
    fun `SDK models send debit credit and every supported installment count without acquirer extensions`() =
        runTest {
            MexicanPayment.entries.forEach { choice ->
                val client = client { simulator.send(it, 30.seconds) }
                val result = client.pay(params.copy(mexicanPayment = choice), "MX${choice.ordinal}") as TransactionOutcome.Completed
                val wire = sent.last().saleToPOIRequest.paymentRequest
                assertThat(wire.paymentTransaction.transactionConditions.isDebitPreferredFlag).isEqualTo(choice.debit)
                assertThat(nexoGson.toJson(wire)).contains("\"DebitPreferredFlag\":${choice.debit}")
                assertThat(
                    wire.paymentData
                        ?.instalment
                        ?.totalNbOfPayments
                        ?.toInt(),
                ).isEqualTo(choice.count)
                assertThat(wire.paymentData?.paymentType).isEqualTo(choice.count?.let { PaymentType.INSTALMENT })
                assertThat(result.details.returnedInstallments).isEqualTo(choice.count?.toString())
            }
            assertThat(MexicanPayment.entries.mapNotNull { it.count }).containsExactly(3, 6, 9, 12, 18)
            listOf(0, 1, 2, 4, 5, 7, 15, 19).forEach { count ->
                assertThat(MexicanPayment.entries.none { it.count == count }).isTrue()
            }
            assertThrows(IllegalArgumentException::class.java) { params.copy(currency = "AUD", mexicanPayment = MexicanPayment.CREDIT) }
            assertThrows(IllegalArgumentException::class.java) {
                params.copy(mexicanPayment = MexicanPayment.DEBIT, cardEntryOverride = CardEntryOverride.MKE)
            }
            assertThrows(IllegalArgumentException::class.java) {
                params.copy(mexicanPayment = MexicanPayment.MONTHS_3, allowPartialAuthorisation = true)
            }
        }

    @Test
    fun `missing answer is recovered with original identity and refunds reverse the original payment`() =
        runTest {
            val client =
                client { request ->
                    val reply = simulator.send(request, 30.seconds)
                    if (request.saleToPOIRequest.paymentRequest != null) Delivery.Failed(Fault.TimedOut) else reply
                }
            val paid = client.pay(params.copy(mexicanPayment = MexicanPayment.MONTHS_12), "MXRECOVER") as TransactionOutcome.Completed
            assertThat(paid.details.returnedInstallments).isEqualTo("12")
            assertThat(sent.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference.serviceID,
            ).isEqualTo("MXRECOVER")
            val refund =
                client.refund(
                    RefundParams(
                        originalTransactionId = checkNotNull(paid.details.poiTransactionId),
                        originalTimestamp = checkNotNull(paid.details.poiTimestamp),
                        merchantReference = "REFUND",
                        amountMinor = 400,
                        currency = "MXN",
                    ),
                ) as TransactionOutcome.Completed
            assertThat(refund.details.success).isTrue()
            assertThat(sent.last().saleToPOIRequest.reversalRequest).isNotNull()
            assertThat(sent.last().saleToPOIRequest.paymentRequest).isNull()
        }

    @Test
    fun `issuer refusal partial answer and absent plan are preserved without guessing confirmation`() =
        runTest {
            listOf(ResultType.FAILURE, ResultType.PARTIAL, ResultType.SUCCESS).forEach { result ->
                val client =
                    client {
                        Delivery.Answered(
                            TerminalAPIResponse().apply {
                                saleToPOIResponse =
                                    SaleToPOIResponse().apply {
                                        paymentResponse =
                                            PaymentResponse().apply {
                                                response =
                                                    Response().apply {
                                                        this.result = result
                                                        errorCondition = ErrorConditionType.REFUSAL.takeIf { result == ResultType.FAILURE }
                                                        additionalResponse = "refusalReason=Unsupported%20issuer"
                                                    }
                                                paymentResult =
                                                    PaymentResult().apply {
                                                        amountsResp =
                                                            AmountsResp().apply {
                                                                currency = "MXN"
                                                                authorizedAmount = BigDecimal("6.00")
                                                            }
                                                        if (result == ResultType.PARTIAL) {
                                                            paymentType = PaymentType.INSTALMENT
                                                            instalment =
                                                                Instalment().apply {
                                                                    totalNbOfPayments = 6.toBigInteger()
                                                                    planID = "Issuer plan"
                                                                }
                                                        }
                                                    }
                                            }
                                    }
                            },
                        )
                    }
                val answer = (client.pay(params.copy(mexicanPayment = MexicanPayment.MONTHS_6)) as TransactionOutcome.Completed).details
                assertThat(answer.success).isEqualTo(result != ResultType.FAILURE)
                assertThat(answer.partial).isEqualTo(result == ResultType.PARTIAL)
                assertThat(answer.returnedPlanId).isEqualTo("Issuer plan".takeIf { result == ResultType.PARTIAL })
                assertThat(answer.returnedPaymentType).isEqualTo("Instalment".takeIf { result == ResultType.PARTIAL })
                assertThat(answer.returnedInstallments).isEqualTo("6".takeIf { result == ResultType.PARTIAL })
            }
            assertThat(client { Delivery.Answered(null) }.pay(params.copy(mexicanPayment = MexicanPayment.CREDIT)))
                .isInstanceOf(TransactionOutcome.Unknown::class.java)
        }
}
