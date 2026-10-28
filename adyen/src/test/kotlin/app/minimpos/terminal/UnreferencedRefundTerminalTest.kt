package app.minimpos.terminal

import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutModifications
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.GiftCardAccount
import app.minimpos.terminal.client.GiftCardEntryMode
import app.minimpos.terminal.client.GiftCardPayment
import app.minimpos.terminal.client.GiftCardUse
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TerminalTotalsOutcome
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalTransport
import app.minimpos.terminal.transport.base64Json
import app.minimpos.terminal.transport.nexoGson
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputText
import com.adyen.model.nexo.PaymentReceipt
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.PaymentType
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random

/** Unreferenced refunds: a `Payment` with `PaymentType=Refund`, recovered as a payment, and cancelled while delayed. */
class UnreferencedRefundTerminalTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private var ids = 0
    private val refund = PaymentParams(1250, "EUR", "R-1", unreferencedRefund = true)

    private fun client(reply: (TerminalAPIRequest) -> Delivery) =
        TerminalClient(
            currencyDigits = testCurrencyDigits,
            transport =
                TerminalTransport { request, _ ->
                    sent += request
                    reply(request)
                },
            identity = TerminalIdentity("POS1", "S1F2-123"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1),
            newServiceId = { "UR${++ids}" },
        )

    private fun answer(additional: Map<String, String>) =
        PaymentResponse().apply {
            response =
                Response().apply {
                    result = ResultType.SUCCESS
                    additionalResponse = base64Json(additional)
                }
            paymentResult =
                PaymentResult().apply {
                    paymentType = PaymentType.REFUND
                    amountsResp =
                        AmountsResp().apply {
                            currency = "EUR"
                            authorizedAmount = BigDecimal("12.50")
                        }
                }
        }

    private fun respond(body: SaleToPOIResponse.() -> Unit) =
        Delivery.Answered(TerminalAPIResponse().apply { saleToPOIResponse = SaleToPOIResponse().apply(body) })

    @Test
    fun `the request is a Payment of PaymentType Refund with no original and no card features`() =
        runTest {
            val details =
                (
                    client {
                        respond { paymentResponse = answer(mapOf("transactionType" to "REFUND", "pspReference" to "PSPR1")) }
                    }.pay(refund) as TransactionOutcome.Completed
                ).details
            val message = sent.single().saleToPOIRequest
            assertThat(message.messageHeader.messageCategory).isEqualTo(MessageCategoryType.PAYMENT)
            val request = message.paymentRequest
            assertThat(request.paymentData.paymentType).isEqualTo(PaymentType.REFUND)
            assertThat(request.paymentData.paymentInstrumentData).isNull()
            assertThat(request.paymentTransaction.amountsReq.requestedAmount).isEqualTo(BigDecimal("12.50"))
            assertThat(request.paymentTransaction.amountsReq.currency).isEqualTo("EUR")
            assertThat(request.saleData.saleTransactionID.transactionID).isEqualTo("R-1")
            assertThat(request.saleData.saleToAcquirerData.tenderOption).isNull()
            assertThat(message.reversalRequest).isNull()
            // Accepted without acquirerResponseCode: Adyen processes it asynchronously.
            assertThat(details.success).isTrue()
            assertThat(details.acquirerResponseCode).isNull()
            assertThat(TerminalClient.refundApproved(details.acquirerResponseCode)).isFalse()
            assertThat(details.pspReference).isEqualTo("PSPR1")
        }

    @Test
    fun `acquirer approval is case insensitive while response text stays verbatim`() =
        runTest {
            listOf("Approved", "APPROVED", "approved", " Approved ").forEach { code ->
                val approved =
                    client { respond { paymentResponse = answer(mapOf("acquirerResponseCode" to code)) } }.pay(refund)
                        as TransactionOutcome.Completed
                assertThat(approved.details.acquirerResponseCode).isEqualTo(code.trim())
                assertThat(TerminalClient.refundApproved(approved.details.acquirerResponseCode)).isTrue()
            }
            listOf(" Pending ", "NotApproved", "Approved offline").forEach { code ->
                val other =
                    client { respond { paymentResponse = answer(mapOf("acquirerResponseCode" to code)) } }.pay(refund)
                        as TransactionOutcome.Completed
                assertThat(other.details.acquirerResponseCode).isEqualTo(code.trim())
                assertThat(TerminalClient.refundApproved(other.details.acquirerResponseCode)).isFalse()
            }
        }

    @Test
    fun `approved unreferenced refund preserves named receipt fields without a literal transaction type key`() =
        runTest {
            // TEST S1F2 returned a translated label and value, not the docs' literal transaction_type_refund.
            val fields = listOf("key=txtype&name=Type&value=REFUND", "key=approved&name=APPROVED")
            val details =
                (
                    client {
                        respond {
                            paymentResponse =
                                answer(mapOf("transactionType" to "REFUND", "acquirerResponseCode" to "APPROVED")).apply {
                                    paymentReceipt.addAll(
                                        listOf(
                                            PaymentReceipt().apply {
                                                documentQualifier = DocumentQualifierType.CUSTOMER_RECEIPT
                                                outputContent =
                                                    OutputContent().apply {
                                                        outputText = fields.map { field -> OutputText().apply { text = field } }
                                                    }
                                            },
                                        ),
                                    )
                                }
                        }
                    }.pay(refund) as TransactionOutcome.Completed
                ).details
            assertThat(details.success).isTrue()
            assertThat(TerminalClient.refundApproved(details.acquirerResponseCode)).isTrue()
            assertThat(details.customerReceipt.map { Triple(it.key, it.name, it.value) })
                .containsExactly(Triple("txtype", "Type", "REFUND"), Triple("approved", "APPROVED", null))
                .inOrder()
        }

    @Test
    fun `a missing answer is recovered with a status check of the original Payment, never resent`() =
        runTest {
            val outcome =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) {
                        Delivery.Failed(Fault.TimedOut)
                    } else {
                        respond {
                            transactionStatusResponse =
                                TransactionStatusResponse().apply {
                                    response = Response().apply { result = ResultType.SUCCESS }
                                    repeatedMessageResponse =
                                        RepeatedMessageResponse().apply {
                                            repeatedResponseMessageBody =
                                                RepeatedResponseMessageBody().apply { paymentResponse = answer(emptyMap()) }
                                        }
                                }
                        }
                    }
                }.pay(refund, serviceId = "URREC1") as TransactionOutcome.Completed
            assertThat(outcome.details.success).isTrue()
            assertThat(sent.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
            val reference =
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference
            assertThat(reference.serviceID).isEqualTo("URREC1")
            assertThat(reference.messageCategory).isEqualTo(MessageCategoryType.PAYMENT)
        }

    @Test
    fun `status recovers a network-declined refund without an authorized amount or PSP reference`() =
        runTest {
            val response =
                """
                {"SaleToPOIResponse":{"TransactionStatusResponse":{
                  "Response":{"Result":"Success"},
                  "MessageReference":{"MessageCategory":"Payment","SaleID":"POS1","ServiceID":"URDECLINE"},
                  "RepeatedMessageResponse":{
                    "MessageHeader":{"MessageCategory":"Payment","MessageClass":"Service","MessageType":"Response",
                      "POIID":"S1F2-123","ProtocolVersion":"3.0","SaleID":"POS1","ServiceID":"URDECLINE"},
                    "RepeatedResponseMessageBody":{"PaymentResponse":{
                      "Response":{"Result":"Failure","ErrorCondition":"UnreachableHost",
                        "AdditionalResponse":"transactionType=REFUND&posAuthAmountValue=100&paymentMethod=mc&message=DECLINED"},
                      "PaymentResult":{"PaymentType":"Refund"}
                    }}
                  }
                }}}
                """.trimIndent()
            val recovered =
                client { Delivery.Answered(nexoGson.fromJson(response, TerminalAPIResponse::class.java)) }
                    .status("URDECLINE", TransactionKind.PAYMENT) as TransactionOutcome.Completed
            assertThat(recovered.details.success).isFalse()
            assertThat(recovered.details.amountMinor).isNull()
            assertThat(recovered.details.pspReference).isNull()
            assertThat(recovered.details.additionalData["transactionType"]).isEqualTo("REFUND")
            assertThat(recovered.details.additionalData["posAuthAmountValue"]).isEqualTo("100")
            assertThat(sent.single().saleToPOIRequest.paymentRequest).isNull()
            assertThat(
                sent
                    .single()
                    .saleToPOIRequest.transactionStatusRequest.messageReference.serviceID,
            ).isEqualTo("URDECLINE")
        }

    @Test
    fun `an unanswered refund stays unknown and a later status check asks about the Payment`() =
        runTest {
            val unknown = client { Delivery.Failed(Fault.TimedOut) }.pay(refund, serviceId = "URLOST")
            assertThat(unknown).isInstanceOf(TransactionOutcome.Unknown::class.java)
            sent.clear()
            client { Delivery.Failed(Fault.TimedOut) }.status("URLOST", TransactionKind.PAYMENT)
            assertThat(
                sent
                    .single()
                    .saleToPOIRequest.transactionStatusRequest.messageReference.messageCategory,
            ).isEqualTo(MessageCategoryType.PAYMENT)
        }

    @Test
    fun `unreferenced refunds refuse every other card feature and gift cards keep their own refund`() {
        listOf<() -> PaymentParams>(
            { refund.copy(amountMinor = 0) },
            { refund.copy(preAuthorisation = true) },
            { refund.copy(allowPartialAuthorisation = true) },
            { refund.copy(askGiving = true) },
            { refund.copy(audioGuidance = true) },
            { refund.copy(requestCardAlias = true) },
            { refund.copy(shopperReference = "CUST") },
            { refund.copy(saleReferenceId = "SALE") },
            { refund.copy(cardEntryOverride = CardEntryOverride.MKE) },
            { refund.copy(cashBackMinor = 100) },
            {
                refund.copy(
                    giftCard = GiftCardPayment(GiftCardAccount.onTerminal("givex", GiftCardEntryMode.KEYED), GiftCardUse.REFUND),
                )
            },
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { invalid() } }
    }

    @Test
    fun `the simulator answers asynchronous and synchronous refunds as credits and cancels a pending one once`() =
        runBlocking {
            var config = SimulatorConfig(delayMillis = 0)
            val simulator = TerminalSimulator(config = { config }, random = Random(7))
            val terminal =
                TerminalClient(
                    currencyDigits = testCurrencyDigits,
                    transport = simulator,
                    identity = TerminalIdentity("POS1", "SIMULATOR-000000001"),
                )
            val requested = (terminal.pay(refund) as TransactionOutcome.Completed).details
            assertThat(requested.success).isTrue()
            assertThat(requested.additionalData["transactionType"]).isEqualTo("REFUND")
            assertThat(requested.additionalData["posAuthAmountValue"]).isEqualTo("1250")
            assertThat(TerminalClient.refundApproved(requested.acquirerResponseCode)).isFalse()
            assertThat(requested.amountMinor).isEqualTo(1250)
            assertThat(requested.surchargeMinor).isEqualTo(0)
            config = config.copy(refundsProcessedSynchronously = true, surchargeAmount = BigDecimal.ONE)
            val approved = (terminal.pay(refund.copy(merchantReference = "R-2")) as TransactionOutcome.Completed).details
            assertThat(TerminalClient.refundApproved(approved.acquirerResponseCode)).isTrue()
            assertThat(approved.amountMinor).isEqualTo(1250)
            val totals = (terminal.getTotals() as TerminalTotalsOutcome.Answered).totals.single()
            assertThat(totals.creditMinor).isEqualTo(2500)
            assertThat(totals.debitMinor).isEqualTo(0)
            val modifications: PaymentModifications = simulator.modifications
            val psp = checkNotNull(requested.pspReference)
            assertThat(modifications.cancelPendingRefund(psp, "void-1")).isInstanceOf(ModificationResult.Received::class.java)
            assertThat(modifications.cancelPendingRefund(psp, "void-2")).isInstanceOf(ModificationResult.Failed::class.java)
            assertThat(modifications.cancelPendingRefund("UNKNOWN", "void-3")).isInstanceOf(ModificationResult.Failed::class.java)
            // A refund is never a payment to capture, adjust or refund again.
            val amount = ModificationAmount("EUR", 100)
            assertThat(modifications.capture(psp, amount, "c", "k")).isInstanceOf(ModificationResult.Failed::class.java)
            assertThat(modifications.updateAmount(psp, amount, "a", null, "k")).isInstanceOf(ModificationResult.Failed::class.java)
            assertThat(modifications.refund(psp, amount, "r", "k")).isInstanceOf(ModificationResult.Failed::class.java)
            val pending = checkNotNull(approved.pspReference)
            assertThat(modifications.updateAmount(pending, amount, "a", null, "k")).isInstanceOf(ModificationResult.Failed::class.java)
            config = config.copy(outcome = SimulatedOutcome.DECLINE)
            assertThat((terminal.pay(refund.copy(merchantReference = "R-3")) as TransactionOutcome.Completed).details.success).isFalse()
        }

    @Test
    fun `a delayed refund is cancelled through the Classic Payments API with its PSP reference`() {
        MockWebServer().use { server ->
            server.start()
            val credentials = CheckoutCredentials("secret-api-key", "HarbourCoffeeCOM", TerminalEnvironment.TEST)
            val api =
                CheckoutModifications(credentials, baseUrl = server.url("/v72/"), paymentUrl = server.url("/pal/servlet/Payment/v68"))
            server.enqueue(MockResponse.Builder().body("""{"pspReference":"VOID1","response":"[voidPendingRefund-received]"}""").build())
            assertThat(runBlocking { api.cancelPendingRefund("PSPR1", "void-R1") }).isEqualTo(ModificationResult.Received("VOID1"))
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/pal/servlet/Payment/v68/voidPendingRefund")
            assertThat(request.headers["Idempotency-Key"]).isEqualTo("void-R1")
            assertThat(request.headers["x-api-key"]).isEqualTo("secret-api-key")
            assertThat(JsonParser.parseString(request.body!!.utf8()))
                .isEqualTo(JsonParser.parseString("""{"merchantAccount":"HarbourCoffeeCOM","originalReference":"PSPR1"}"""))

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(403)
                    .body("""{"status":403,"errorCode":"010","message":"Not allowed"}""")
                    .build(),
            )
            assertThat(runBlocking { api.cancelPendingRefund("PSPR1", "void-R1") })
                .isEqualTo(ModificationResult.Failed(Fault.Permission(ApiKey.ADYEN, "Merchant PAL Webservice role")))
            server.enqueue(MockResponse.Builder().body("""{"pspReference":"X","response":"[refund-received]"}""").build())
            val odd = runBlocking { api.cancelPendingRefund("PSPR1", "void-R1") } as ModificationResult.Failed
            assertThat(odd.fault).isInstanceOf(Fault.UnreadableReply::class.java)
            server.enqueue(MockResponse.Builder().body("not json").build())
            assertThat((runBlocking { api.cancelPendingRefund("PSPR1", "void-R1") } as ModificationResult.Failed).fault)
                .isInstanceOf(Fault.UnreadableReply::class.java)
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(500)
                    .body("{}")
                    .build(),
            )
            assertThat((runBlocking { api.cancelPendingRefund("PSPR1", "void-R1") } as ModificationResult.Failed).fault.mayHaveTakenEffect)
                .isTrue()
        }
        val live = CheckoutCredentials("key", "Merchant", TerminalEnvironment.LIVE, " 1797a841fbb37ca7-AdyenDemo ")
        assertThat(live.paymentBaseUrl).isEqualTo("https://1797a841fbb37ca7-AdyenDemo-pal-live.adyenpayments.com/pal/servlet/Payment/v68")
        assertThat(CheckoutCredentials("key", "Merchant", TerminalEnvironment.TEST).paymentBaseUrl)
            .isEqualTo("https://pal-test.adyen.com/pal/servlet/Payment/v68")
        val unsupported =
            object : PaymentModifications {
                override suspend fun capture(
                    paymentPspReference: String,
                    amount: ModificationAmount,
                    reference: String,
                    idempotencyKey: String,
                ) = ModificationResult.Received(null)

                override suspend fun updateAmount(
                    paymentPspReference: String,
                    amount: ModificationAmount,
                    reference: String,
                    adjustAuthorisationData: String?,
                    idempotencyKey: String,
                ) = ModificationResult.Received(null)

                override suspend fun verify(): Fault? = null
            }
        assertThat(runBlocking { unsupported.cancelPendingRefund("P", "k") }).isEqualTo(ModificationResult.Failed(Fault.Unsupported))
    }
}
