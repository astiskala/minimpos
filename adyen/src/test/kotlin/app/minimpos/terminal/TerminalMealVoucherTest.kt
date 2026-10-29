package app.minimpos.terminal

import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.MexicanPayment
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.RefundParams
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
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.io.encoding.Base64
import kotlin.time.Duration.Companion.seconds

class TerminalMealVoucherTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private val params = PaymentParams(3000, "BRL", "VOUCHER", mealVoucherAccount = true)
    private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })

    private fun client(reply: suspend (TerminalAPIRequest) -> Delivery) =
        TerminalClient(
            transport =
                TerminalTransport { request, _ ->
                    sent += request
                    reply(request)
                },
            identity = TerminalIdentity("POS1", "S1F2-123"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1),
            currencyDigits = testCurrencyDigits,
        )

    private fun saleToPoiData(request: TerminalAPIRequest) =
        request.saleToPOIRequest.paymentRequest.saleData.saleToPOIData
            ?.let { JsonParser.parseString(String(Base64.decode(it))).asJsonObject }

    @Test
    fun `app-selected Brazilian voucher sends only the documented account type and reads the voucher answer`() =
        runTest {
            val paid = client { simulator.send(it, 30.seconds) }.pay(params, "BRV1") as TransactionOutcome.Completed
            val data = checkNotNull(saleToPoiData(sent.single()))
            assertThat(data.keySet()).containsExactly("selectedAccountType")
            assertThat(data.get("selectedAccountType").asString).isEqualTo("MEAL_VOUCHER")
            val wire = sent.single().saleToPOIRequest.paymentRequest
            assertThat(wire.paymentTransaction.transactionConditions).isNull()
            assertThat(wire.saleData.saleToAcquirerData.tenderOption).isNull()
            assertThat(paid.details.fundingSource).isEqualTo("PREPAID")
            assertThat(paid.details.paymentMethodVariant).isEqualTo("elo_voucher_br")
        }

    @Test
    fun `ordinary terminal-recognized vouchers send no account type and their facts stay unreported`() =
        runTest {
            val paid =
                client { simulator.send(it, 30.seconds) }
                    .pay(PaymentParams(1500, "EUR", "EUVOUCHER", allowPartialAuthorisation = true), "EUV1") as TransactionOutcome.Completed
            assertThat(saleToPoiData(sent.single())).isNull()
            assertThat(paid.details.fundingSource).isNull()
        }

    @Test
    fun `the account type shares the one SaleToPOIData owner and conflicting features are refused`() {
        listOf(
            { params.copy(currency = "EUR") },
            { params.copy(allowPartialAuthorisation = true) },
            { params.copy(terminalTip = TerminalTipOptions()) },
            { params.copy(askGiving = true) },
            { params.copy(audioGuidance = true) },
            { params.copy(preAuthorisation = true) },
            { params.copy(cardEntryOverride = CardEntryOverride.MKE) },
            { params.copy(currency = "MXN", mexicanPayment = MexicanPayment.DEBIT) },
            { params.copy(requestCardAlias = true) },
        ).forEach { make -> assertThrows(IllegalArgumentException::class.java) { make() } }
    }

    @Test
    fun `a missing voucher answer is recovered once by status with the original identity`() =
        runTest {
            val client =
                client { request ->
                    val reply = simulator.send(request, 30.seconds)
                    if (request.saleToPOIRequest.paymentRequest != null) Delivery.Failed(Fault.TimedOut) else reply
                }
            val paid = client.pay(params, "BRVREC") as TransactionOutcome.Completed
            assertThat(paid.details.fundingSource).isEqualTo("PREPAID")
            assertThat(sent.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference.serviceID,
            ).isEqualTo("BRVREC")
            val refund =
                client.refund(
                    RefundParams(
                        originalTransactionId = checkNotNull(paid.details.poiTransactionId),
                        originalTimestamp = checkNotNull(paid.details.poiTimestamp),
                        merchantReference = "REFUND",
                        amountMinor = 1000,
                        currency = "BRL",
                    ),
                ) as TransactionOutcome.Completed
            assertThat(refund.details.success).isTrue()
            assertThat(sent.last().saleToPOIRequest.reversalRequest).isNotNull()
        }

    @Test
    fun `a short voucher balance is a refusal and a blank funding source is unreported`() =
        runTest {
            val refused =
                client {
                    Delivery.Answered(
                        TerminalAPIResponse().apply {
                            saleToPOIResponse =
                                SaleToPOIResponse().apply {
                                    paymentResponse =
                                        PaymentResponse().apply {
                                            response =
                                                Response().apply {
                                                    result = ResultType.FAILURE
                                                    errorCondition = ErrorConditionType.REFUSAL
                                                    additionalResponse = "refusalReason=Not%20enough%20balance&fundingSource=%20"
                                                }
                                        }
                                }
                        },
                    )
                }.pay(params) as TransactionOutcome.Completed
            assertThat(refused.details.success).isFalse()
            assertThat(refused.details.fundingSource).isNull()
            assertThat(client { Delivery.Answered(null) }.pay(params)).isInstanceOf(TransactionOutcome.Unknown::class.java)
        }

    @Test
    fun `a terminal-chosen installment count reported only in additional data is read`() =
        runTest {
            suspend fun answered(additional: String) =
                client {
                    Delivery.Answered(
                        TerminalAPIResponse().apply {
                            saleToPOIResponse =
                                SaleToPOIResponse().apply {
                                    paymentResponse =
                                        PaymentResponse().apply {
                                            response =
                                                Response().apply {
                                                    result = ResultType.SUCCESS
                                                    additionalResponse = additional
                                                }
                                        }
                                }
                        },
                    )
                }.pay(params) as TransactionOutcome.Completed
            val chosen = answered("installments=3&fundingSource=PREPAID").details
            assertThat(chosen.returnedInstallments).isEqualTo("3")
            assertThat(chosen.returnedPaymentType).isNull()
            listOf("installments=0", "installments=1", "installments=x", "installments=%20", "fundingSource=PREPAID").forEach {
                assertThat(answered(it).details.returnedInstallments).isNull()
            }
        }
}
