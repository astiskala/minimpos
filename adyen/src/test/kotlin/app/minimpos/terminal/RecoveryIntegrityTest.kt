package app.minimpos.terminal

import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageReference
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalResponse
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.StoredValueResponse
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RecoveryIntegrityTest {
    private val categories =
        mapOf(
            TransactionKind.PAYMENT to MessageCategoryType.PAYMENT,
            TransactionKind.REFUND to MessageCategoryType.REVERSAL,
            TransactionKind.STORED_VALUE to MessageCategoryType.STORED_VALUE,
        )

    private fun body(category: MessageCategoryType) =
        RepeatedResponseMessageBody().apply {
            val success = Response().apply { result = ResultType.SUCCESS }
            when {
                category == MessageCategoryType.PAYMENT -> paymentResponse = PaymentResponse().apply { response = success }
                category == MessageCategoryType.REVERSAL -> reversalResponse = ReversalResponse().apply { response = success }
                else -> storedValueResponse = StoredValueResponse().apply { response = success }
            }
        }

    private suspend fun check(
        kind: TransactionKind,
        returned: MessageCategoryType = checkNotNull(categories[kind]),
        alter: TransactionStatusResponse.() -> Unit = {},
    ): TransactionOutcome {
        val client =
            TerminalClient(
                identity = TerminalIdentity("POS", "AMS1-123"),
                currencyDigits = testCurrencyDigits,
                transport =
                    TerminalTransport { request, _ ->
                        val reference = request.saleToPOIRequest.transactionStatusRequest.messageReference
                        val status =
                            TransactionStatusResponse().apply {
                                response = Response().apply { result = ResultType.SUCCESS }
                                messageReference =
                                    MessageReference().apply {
                                        saleID = reference.saleID
                                        serviceID = reference.serviceID
                                        messageCategory = reference.messageCategory
                                    }
                                repeatedMessageResponse = RepeatedMessageResponse().apply { repeatedResponseMessageBody = body(returned) }
                                alter()
                            }
                        Delivery.Answered(
                            TerminalAPIResponse().apply {
                                saleToPOIResponse = SaleToPOIResponse().apply { transactionStatusResponse = status }
                            },
                        )
                    },
            )
        return client.status("ORIGINAL", kind)
    }

    @Test
    fun `status recovery reads only the original message category`() =
        runTest {
            categories.forEach { (kind, category) ->
                assertThat(check(kind)).isInstanceOf(TransactionOutcome.Completed::class.java)
                categories.values.filter { it != category }.forEach { wrong ->
                    assertThat(check(kind, wrong)).isInstanceOf(TransactionOutcome.Unknown::class.java)
                }
            }
        }

    @Test
    fun `a failed status check that is neither missing nor in progress stays unknown`() =
        runTest {
            val outcome = check(TransactionKind.PAYMENT) { response = Response().apply { result = ResultType.FAILURE } }
            assertThat(outcome).isInstanceOf(TransactionOutcome.Unknown::class.java)
        }

    @Test
    fun `a returned reference must match the original sale service and category`() =
        runTest {
            val wrong =
                listOf<MessageReference.() -> Unit>(
                    { saleID = "OTHER" },
                    { serviceID = "OTHER" },
                    { messageCategory = MessageCategoryType.REVERSAL },
                )
            wrong.forEach { change ->
                assertThat(check(TransactionKind.PAYMENT) { messageReference.apply(change) })
                    .isInstanceOf(TransactionOutcome.Unknown::class.java)
            }
        }

    @Test
    fun `a repeated header must match the original terminal identity`() =
        runTest {
            fun header() =
                MessageHeader().apply {
                    saleID = "POS"
                    serviceID = "ORIGINAL"
                    poiid = "AMS1-123"
                    messageCategory = MessageCategoryType.PAYMENT
                    messageType = MessageType.RESPONSE
                }
            assertThat(check(TransactionKind.PAYMENT) { repeatedMessageResponse.messageHeader = header() })
                .isInstanceOf(TransactionOutcome.Completed::class.java)
            val wrong =
                listOf<MessageHeader.() -> Unit>(
                    { saleID = "OTHER" },
                    { serviceID = "OTHER" },
                    { poiid = "AMS1-OTHER" },
                    { messageCategory = MessageCategoryType.REVERSAL },
                    { messageType = MessageType.REQUEST },
                )
            wrong.forEach { change ->
                assertThat(check(TransactionKind.PAYMENT) { repeatedMessageResponse.messageHeader = header().apply(change) })
                    .isInstanceOf(TransactionOutcome.Unknown::class.java)
            }
        }
}
