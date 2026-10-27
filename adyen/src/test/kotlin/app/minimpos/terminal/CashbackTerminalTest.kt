package app.minimpos.terminal

import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.RecurringModel
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
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
import java.math.BigDecimal

/** Exact SDK wire fields and cashback-specific Partial semantics, separate from ordinary partial authorizations. */
class CashbackTerminalTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private var ids = 0

    private fun client(reply: (TerminalAPIRequest) -> Delivery) =
        TerminalClient(
            currencyDigits = testCurrencyDigits,
            transport =
                TerminalTransport { request, _ ->
                    sent += request
                    reply(request)
                },
            identity = TerminalIdentity("POS1", "AMS1-123"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1),
            newServiceId = { "CB${++ids}" },
        )

    private fun respond(body: SaleToPOIResponse.() -> Unit) =
        Delivery.Answered(
            TerminalAPIResponse().apply { saleToPOIResponse = SaleToPOIResponse().apply(body) },
        )

    private fun result(type: ResultType) = Response().apply { result = type }

    private fun approval() =
        PaymentResponse().apply {
            response = result(ResultType.SUCCESS)
            paymentResult =
                PaymentResult().apply {
                    amountsResp =
                        AmountsResp().apply {
                            currency = "AUD"
                            authorizedAmount = BigDecimal("12.50")
                        }
                }
        }

    private fun paymentBody(payment: PaymentResponse) = RepeatedResponseMessageBody().apply { paymentResponse = payment }

    private fun statusReply(
        response: Response,
        body: RepeatedResponseMessageBody,
    ) = respond {
        transactionStatusResponse =
            TransactionStatusResponse().apply {
                this.response = response
                repeatedMessageResponse = RepeatedMessageResponse().apply { repeatedResponseMessageBody = body }
            }
    }

    @Test
    fun `cashback requests SDK total and cash fields and retains the authoritative partial answer`() =
        runTest {
            val answer =
                approval().apply {
                    response = result(ResultType.PARTIAL)
                    paymentResult.amountsResp.authorizedAmount = BigDecimal("10.00")
                    paymentResult.amountsResp.cashBackAmount = BigDecimal.ZERO
                }
            val outcome =
                client { respond { paymentResponse = answer } }.pay(
                    PaymentParams(1200, "AUD", "CASH", cashBackMinor = 200),
                ) as TransactionOutcome.Completed
            val request =
                sent
                    .single()
                    .saleToPOIRequest.paymentRequest.paymentTransaction.amountsReq
            assertThat(request.requestedAmount).isEqualTo(BigDecimal("12.00"))
            assertThat(request.cashBackAmount).isEqualTo(BigDecimal("2.00"))
            assertThat(outcome.details.success).isTrue()
            assertThat(outcome.details.partial).isTrue()
            assertThat(outcome.details.cashBackMinor).isEqualTo(0)
            assertThat(outcome.details.amountMinor).isEqualTo(1000)
        }

    @Test
    fun `standalone cash-out requests no merchandise and recovers the original Payment without replay`() =
        runTest {
            val answer =
                approval().apply {
                    paymentResult.amountsResp.authorizedAmount = BigDecimal("2.00")
                    paymentResult.amountsResp.cashBackAmount = BigDecimal("2.00")
                }
            val outcome =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) {
                        Delivery.Failed(Fault.TimedOut)
                    } else {
                        statusReply(result(ResultType.SUCCESS), paymentBody(answer))
                    }
                }.pay(PaymentParams(200, "AUD", "CASHOUT", cashBackMinor = 200)) as TransactionOutcome.Completed
            assertThat(outcome.details.cashBackMinor).isEqualTo(200)
            assertThat(sent.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference.serviceID,
            ).isEqualTo(
                sent
                    .first()
                    .saleToPOIRequest.messageHeader.serviceID,
            )

            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference.messageCategory,
            ).isEqualTo(MessageCategoryType.PAYMENT)
        }

    @Test
    fun `cashback refuses unsupported protocol combinations and excessive or negative cash`() {
        val base = PaymentParams(1200, "AUD", "CB", cashBackMinor = 200)
        listOf<() -> PaymentParams>(
            { base.copy(cashBackMinor = 0) },
            { base.copy(cashBackMinor = -1) },
            { base.copy(cashBackMinor = 1201) },
            { base.copy(preAuthorisation = true) },
            { base.copy(allowPartialAuthorisation = true) },
            { base.copy(askGiving = true) },
            { base.copy(audioGuidance = true) },
            { base.copy(requestCardAlias = true) },
            { base.copy(recurringProcessingModel = RecurringModel.CARD_ON_FILE) },
            { base.copy(cardEntryOverride = CardEntryOverride.MOTO) },
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }
}
