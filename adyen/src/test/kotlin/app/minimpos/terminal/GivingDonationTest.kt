package app.minimpos.terminal

import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.ErrorConditionType
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
import org.junit.Test
import java.math.BigDecimal

class GivingDonationTest {
    private val params = PaymentParams(2000, "AUD", "GIVE", askGiving = true)
    private val statusRequests = mutableListOf<TerminalAPIRequest>()

    private fun result(
        type: ResultType,
        condition: ErrorConditionType? = null,
        additional: String? = null,
    ) = Response().apply {
        result = type
        errorCondition = condition
        additionalResponse = additional
    }

    private fun payment(
        response: Response,
        amount: String = "20.00",
    ) = PaymentResponse().apply {
        this.response = response
        paymentResult =
            PaymentResult().apply {
                amountsResp =
                    AmountsResp().apply {
                        currency = "AUD"
                        authorizedAmount = BigDecimal(amount)
                    }
            }
    }

    private fun answer(status: TransactionStatusResponse) =
        Delivery.Answered(
            TerminalAPIResponse().apply { saleToPOIResponse = SaleToPOIResponse().apply { transactionStatusResponse = status } },
        )

    private fun status(
        response: Response,
        donation: PaymentResponse? = null,
    ) = TransactionStatusResponse().apply {
        this.response = response
        donation?.let {
            repeatedMessageResponse =
                RepeatedMessageResponse().apply {
                    repeatedResponseMessageBody = RepeatedResponseMessageBody().apply { paymentResponse = it }
                }
        }
    }

    private suspend fun donation(statuses: List<Delivery>): Long? {
        statusRequests.clear()
        val client =
            TerminalClient(
                transport =
                    TerminalTransport { request, _ ->
                        if (request.saleToPOIRequest.transactionStatusRequest == null) {
                            Delivery.Answered(
                                TerminalAPIResponse().apply {
                                    saleToPOIResponse =
                                        SaleToPOIResponse().apply {
                                            paymentResponse =
                                                payment(result(ResultType.SUCCESS, additional = "adyen_giving_service_id=GIV-1"))
                                        }
                                },
                            )
                        } else {
                            statusRequests += request
                            statuses[minOf(statusRequests.size, statuses.size) - 1]
                        }
                    },
                identity = TerminalIdentity("POS1", "S1F2-123"),
                recovery = RecoveryPolicy(attempts = 2, intervalMillis = 1, maxInProgressChecks = 3),
                currencyDigits = testCurrencyDigits,
            )
        val paid = client.pay(params) as TransactionOutcome.Completed
        assertThat(paid.details.success).isTrue()
        assertThat(paid.details.givingServiceId).isEqualTo("GIV-1")
        return paid.details.donationMinor
    }

    private val inProgress = answer(status(result(ResultType.FAILURE, ErrorConditionType.IN_PROGRESS)))

    @Test
    fun `follows an in-progress donation until it completes`() =
        runTest {
            val done = answer(status(result(ResultType.SUCCESS), payment(result(ResultType.SUCCESS), "2.00")))
            assertThat(donation(listOf(inProgress, inProgress, done))).isEqualTo(200)
            assertThat(statusRequests).hasSize(3)
            val reference = statusRequests.first().saleToPOIRequest.transactionStatusRequest
            assertThat(reference.messageReference.saleID).isEqualTo(TerminalClient.GIVING_SALE_ID)
            assertThat(reference.messageReference.serviceID).isEqualTo("GIV-1")
            assertThat(reference.isReceiptReprintFlag).isNotEqualTo(true)
        }

    @Test
    fun `declined, skipped and unconfirmed donations leave no donation`() =
        runTest {
            val notNow = answer(status(result(ResultType.SUCCESS), payment(result(ResultType.FAILURE, ErrorConditionType.CANCEL))))
            assertThat(donation(listOf(inProgress, notNow))).isNull()
            assertThat(statusRequests).hasSize(2)

            assertThat(donation(listOf(answer(status(result(ResultType.FAILURE, ErrorConditionType.NOT_FOUND)))))).isNull()
            assertThat(statusRequests).hasSize(1)

            assertThat(donation(listOf(inProgress))).isNull()
            assertThat(statusRequests).hasSize(3)

            val bodiless = answer(status(result(ResultType.SUCCESS)))
            val refused = answer(status(result(ResultType.FAILURE, ErrorConditionType.REFUSAL)))
            listOf(bodiless, refused, Delivery.Answered(null)).forEach { unusable ->
                assertThat(donation(listOf(unusable))).isNull()
                assertThat(statusRequests).hasSize(2)
            }
        }
}
