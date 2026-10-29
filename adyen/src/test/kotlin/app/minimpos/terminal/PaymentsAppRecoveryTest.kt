package app.minimpos.terminal

import app.minimpos.terminal.FakePaymentsApp.Companion.RETURN_URL
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.paymentsapp.AppLinkAnswer
import app.minimpos.terminal.paymentsapp.PaymentsAppTransport
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalKey
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PaymentsAppRecoveryTest {
    private val key = TerminalKey("key-id", "correct horse battery staple", 1)
    private val app = FakePaymentsApp(key)
    private val transport = PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL)
    private val client =
        TerminalClient(
            transport,
            TerminalIdentity("MiniMPOS", "INSTALLATION-1"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1, maxInProgressChecks = 1, reprintReceipts = false),
            currencyDigits = testCurrencyDigits,
        )
    private val payment = PaymentParams(1250, "EUR", "260930-1")

    @Test
    fun `status without message reference recovers only with the complete original repeated header`() {
        runBlocking { client.pay(payment, "RESTART") }
        val mutations: List<(MessageHeader) -> Unit> =
            listOf(
                {},
                { it.saleID = "FOREIGN" },
                { it.serviceID = "FOREIGN" },
                { it.poiid = "FOREIGN" },
                { it.messageCategory = MessageCategoryType.REVERSAL },
                { it.messageClass = MessageClassType.DEVICE },
                { it.messageType = MessageType.REQUEST },
            )
        mutations.forEachIndexed { index, mutation ->
            app.transform = {
                it.saleToPOIResponse.transactionStatusResponse.apply {
                    messageReference = null
                    repeatedMessageResponse.messageHeader =
                        MessageHeader().apply {
                            saleID = "MiniMPOS"
                            serviceID = "RESTART"
                            poiid = "INSTALLATION-1"
                            messageCategory = MessageCategoryType.PAYMENT
                            messageClass = MessageClassType.SERVICE
                            messageType = MessageType.RESPONSE
                            mutation(this)
                        }
                }
            }
            val result = runBlocking { client.status("RESTART") }
            if (index == 0) {
                assertThat(result).isInstanceOf(TransactionOutcome.Completed::class.java)
            } else {
                assertThat(result).isEqualTo(TransactionOutcome.Unknown(Fault.UnreadableReply()))
            }
        }
        assertThat(app.requests.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
    }

    @Test
    fun `retained inconclusive callbacks are discarded without releasing financial answers`() {
        app.retainAnswers = true
        repeat(8) {
            assertThat(runBlocking { client.status("MISSING") }).isEqualTo(TransactionOutcome.Unknown(Fault.NoLateReply))
            assertThat(app.lateReplies()).isEmpty()
        }
        app.transform = {
            it.saleToPOIResponse.transactionStatusResponse.apply {
                response =
                    Response().apply {
                        result = ResultType.FAILURE
                        errorCondition = ErrorConditionType.IN_PROGRESS
                    }
                repeatedMessageResponse = null
            }
        }
        repeat(8) {
            assertThat(runBlocking { client.status("MISSING") }).isEqualTo(TransactionOutcome.Unknown(Fault.StillInProgress))
            assertThat(app.lateReplies()).isEmpty()
        }
        app.transform = null
        assertThat(runBlocking { client.pay(payment, "ORIGINAL") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        val original = app.lateReplies().single()
        assertThat(runBlocking { client.status("MISSING") }).isEqualTo(TransactionOutcome.Unknown(Fault.NoLateReply))
        assertThat(app.lateReplies()).containsExactly(original)
        assertThat(runBlocking { client.status("ORIGINAL") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        transport.acknowledge("MISSING")
        assertThat(app.lateReplies()).containsExactly(original)
        transport.acknowledge("ORIGINAL")
        assertThat(app.lateReplies()).isEmpty()
        assertThat(app.requests.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
    }

    @Test
    fun `a restarted transport discards stale status only callbacks even without remote status support`() {
        app.retainAnswers = true
        listOf(ErrorConditionType.NOT_FOUND, ErrorConditionType.IN_PROGRESS).forEach { condition ->
            app.transform = {
                it.saleToPOIResponse.transactionStatusResponse.response.errorCondition = condition
            }
            runBlocking { client.status("MISSING") }
            app.retain(app.callbacks.last())
            val restarted =
                TerminalClient(
                    PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL) { "1.6.0" },
                    TerminalIdentity("MiniMPOS", "INSTALLATION-1"),
                    currencyDigits = testCurrencyDigits,
                )
            assertThat(runBlocking { restarted.status("MISSING") }).isEqualTo(TransactionOutcome.Unknown(Fault.NoLateReply))
            assertThat(app.lateReplies()).isEmpty()
        }
        assertThat(app.opened).hasSize(2)
    }

    @Test
    fun `an inconclusive envelope carrying financial evidence waits for durable acknowledgement`() {
        app.retainAnswers = true
        runBlocking { client.pay(payment, "EVIDENCE") }
        val original = app.lateReplies().single()
        transport.acknowledge("EVIDENCE")
        app.transform = {
            it.saleToPOIResponse.transactionStatusResponse.response.apply {
                result = ResultType.FAILURE
                errorCondition = ErrorConditionType.NOT_FOUND
            }
        }
        assertThat(runBlocking { client.status("EVIDENCE") }).isEqualTo(TransactionOutcome.Unknown(Fault.NoLateReply))
        assertThat(app.lateReplies()).hasSize(1)
        app.retain(original)
        assertThat(runBlocking { client.status("EVIDENCE") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        assertThat(app.lateReplies()).hasSize(2)
        transport.acknowledge("EVIDENCE")
        assertThat(app.lateReplies()).isEmpty()
    }

    @Test
    fun `acknowledging an original never releases a foreign repeated body with no reference`() {
        app.retainAnswers = true
        runBlocking { client.pay(payment, "ORIGINAL") }
        val original = app.lateReplies().single()
        transport.acknowledge("ORIGINAL")
        app.transform = {
            it.saleToPOIResponse.transactionStatusResponse.apply {
                response.result = ResultType.FAILURE
                response.errorCondition = ErrorConditionType.NOT_FOUND
                messageReference = null
                repeatedMessageResponse.messageHeader =
                    MessageHeader().apply {
                        saleID = "MiniMPOS"
                        serviceID = "FOREIGN"
                        poiid = "INSTALLATION-1"
                        messageCategory = MessageCategoryType.PAYMENT
                        messageClass = MessageClassType.SERVICE
                        messageType = MessageType.RESPONSE
                    }
            }
        }
        assertThat(runBlocking { client.status("ORIGINAL") }).isEqualTo(TransactionOutcome.Unknown(Fault.NoLateReply))
        val foreign = app.lateReplies().single()
        app.retain(original)
        assertThat(runBlocking { client.status("ORIGINAL") }).isInstanceOf(TransactionOutcome.Completed::class.java)
        transport.acknowledge("ORIGINAL")
        assertThat(app.lateReplies()).containsExactly(foreign)
    }

    @Test
    fun `status cleanup leaves foreign and unverified callbacks untouched`() {
        app.retainAnswers = true
        val mutations: List<(SaleToPOIResponse) -> Unit> =
            listOf(
                { it.messageHeader.saleID = "FOREIGN" },
                { it.messageHeader.poiid = "FOREIGN" },
                { it.transactionStatusResponse.messageReference.serviceID = "FOREIGN" },
                { it.transactionStatusResponse.messageReference.saleID = "FOREIGN" },
                { it.transactionStatusResponse.messageReference.messageCategory = MessageCategoryType.REVERSAL },
            )
        mutations.forEach { mutation ->
            app.transform = {
                it.saleToPOIResponse.transactionStatusResponse.messageReference =
                    app.requests
                        .last()
                        .saleToPOIRequest.transactionStatusRequest.messageReference
                mutation(it.saleToPOIResponse)
            }
            assertThat(runBlocking { client.status("MISSING") }).isEqualTo(TransactionOutcome.Unknown(Fault.UnreadableReply()))
        }
        val retained = app.lateReplies()
        app.retain("$RETURN_URL/nexo?response=unverified")
        app.unanswered = AppLinkAnswer.TimedOut
        assertThat(runBlocking { client.status("MISSING") }).isEqualTo(TransactionOutcome.Unknown(Fault.TimedOut))
        transport.acknowledge("MISSING")
        assertThat(app.lateReplies()).containsAtLeastElementsIn(retained)
        assertThat(app.lateReplies()).hasSize(mutations.size + 1)
    }
}
