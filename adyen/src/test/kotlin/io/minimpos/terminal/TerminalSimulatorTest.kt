package io.minimpos.terminal

import com.adyen.model.nexo.DiagnosisRequest
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.terminal.TerminalAPIRequest
import com.google.common.truth.Truth.assertThat
import io.minimpos.terminal.checkout.ModificationAmount
import io.minimpos.terminal.checkout.ModificationResult
import io.minimpos.terminal.client.PaymentParams
import io.minimpos.terminal.client.PosApplication
import io.minimpos.terminal.client.PrintAlign
import io.minimpos.terminal.client.PrintJob
import io.minimpos.terminal.client.PrintLine
import io.minimpos.terminal.client.PrintOutcome
import io.minimpos.terminal.client.PrintStyle
import io.minimpos.terminal.client.RecoveryPolicy
import io.minimpos.terminal.client.RecurringModel
import io.minimpos.terminal.client.RefundParams
import io.minimpos.terminal.client.RetryAdvice
import io.minimpos.terminal.client.TerminalClient
import io.minimpos.terminal.client.TerminalIdentity
import io.minimpos.terminal.client.TransactionOutcome
import io.minimpos.terminal.simulator.SimulatedModifications
import io.minimpos.terminal.simulator.SimulatedOutcome
import io.minimpos.terminal.simulator.SimulatorConfig
import io.minimpos.terminal.simulator.TerminalSimulator
import io.minimpos.terminal.transport.Delivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.math.BigDecimal
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

class TerminalSimulatorTest {
    private var config = SimulatorConfig(delayMillis = 0)
    private val printed = mutableListOf<PrintJob>()
    private val simulator = TerminalSimulator(config = { config }, random = Random(42), onPrint = { printed += it })
    private val client =
        TerminalClient(
            transport = simulator,
            identity = TerminalIdentity("POS1", "SIMULATOR-000000001"),
            application = PosApplication("Mini mPOS", "1.0", "Mini mPOS", "Android", "13"),
            recovery = RecoveryPolicy(attempts = 2, intervalMillis = 10),
        )
    private val tokenizing =
        PaymentParams(
            amount = BigDecimal("12.00"),
            currency = "AUD",
            merchantReference = "MP-1",
            shopperReference = "CUST-1",
            shopperEmail = "a@b.co",
            recurringProcessingModel = RecurringModel.UNSCHEDULED_CARD_ON_FILE,
        )

    private fun pay(params: PaymentParams = tokenizing) = runBlocking { client.pay(params) as TransactionOutcome.Completed }.details

    @Test
    fun `approves with receipts and tokenization`() {
        val details = pay()
        assertThat(details.success).isTrue()
        assertThat(details.amount).isEqualTo(BigDecimal("12.00"))
        assertThat(details.poiTransactionId).contains(".")
        assertThat(details.poiTimestamp).isNotNull()
        assertThat(details.tokenization!!.shopperReference).isEqualTo("CUST-1")
        assertThat(details.additionalData["shopperEmail"]).isEqualTo("a@b.co")
        assertThat(details.customerReceipt.map { it.name }).contains("APPROVED")
        assertThat(details.cashierReceipt.map { it.name }).contains("MERCHANT COPY")
        assertThat(details.entryMode).isEqualTo("Contactless")
        assertThat(details.signatureRequired).isFalse()
    }

    @Test
    fun `cards come plain or in a mobile wallet, named by the payment method variant`() {
        val payments = (1..40).map { pay() }
        assertThat(payments.map { it.paymentMethodVariant }.toSet()).containsExactly("mc", "visa", "visa_applepay", "mc_googlepay")
        payments.forEach { assertThat(it.paymentMethodVariant).startsWith(it.paymentBrand!!) }
    }

    @Test
    fun `pre-authorisations are labelled on the receipt and reversing one cancels it`() {
        val preAuth = pay(tokenizing.copy(preAuthorisation = true))
        assertThat(preAuth.success).isTrue()
        assertThat(preAuth.customerReceipt.single { it.key == "txtype" }.name).isEqualTo("Pre-authorization")
        assertThat(preAuth.additionalData["adjustAuthorisationData"]).startsWith("BQABAQ")
        val sale = pay()
        assertThat(sale.customerReceipt.single { it.key == "txtype" }.name).isEqualTo("Payment")
        assertThat(sale.additionalData).doesNotContainKey("adjustAuthorisationData")
        val cancel =
            runBlocking {
                client.refund(RefundParams(preAuth.poiTransactionId!!, preAuth.poiTimestamp!!, "C-1")) as TransactionOutcome.Completed
            }.details
        assertThat(cancel.success).isTrue()
        assertThat(cancel.customerReceipt.map { it.name }).containsAtLeast("CANCELLATION", "CANCELLATION REQUESTED")
    }

    @Test
    fun `the simulated Checkout API follows what happened to the simulator's payments`() =
        runBlocking {
            val api = simulator.modifications
            val amount = ModificationAmount("AUD", 1_200)
            // A sale was captured when it was taken.
            val sale = pay()
            assertThat(api.capture(sale.pspReference!!, amount, "MP-1", "k1")).isInstanceOf(ModificationResult.NotProcessed::class.java)
            assertThat(api.updateAmount(sale.pspReference, amount, "MP-1", null, "k2"))
                .isInstanceOf(ModificationResult.NotProcessed::class.java)

            // A pre-authorisation is adjusted while held, then captured (again with the same key), and no longer adjusted.
            val held = pay(tokenizing.copy(preAuthorisation = true))
            val psp = held.pspReference!!
            assertThat(api.updateAmount(psp, amount, "MP-1", held.adjustAuthorisationData, "k3"))
                .isInstanceOf(ModificationResult.Authorised::class.java)
            assertThat(api.capture(psp, amount, "MP-1", "k4")).isInstanceOf(ModificationResult.Received::class.java)
            assertThat(api.capture(psp, amount, "MP-1", "k4")).isInstanceOf(ModificationResult.Received::class.java)
            val captured = api.updateAmount(psp, amount, "MP-1", null, "k5") as ModificationResult.NotProcessed
            assertThat(captured.message).contains("already captured")
            // Reversing it in full now refunds it rather than releasing a hold.
            val refund = client.refund(RefundParams(held.poiTransactionId!!, held.poiTimestamp!!, "R-1")) as TransactionOutcome.Completed
            assertThat(refund.details.customerReceipt.map { it.name }).contains("REFUND REQUESTED")

            // A cancelled pre-authorisation cannot be captured.
            val cancelled = pay(tokenizing.copy(preAuthorisation = true))
            client.refund(RefundParams(cancelled.poiTransactionId!!, cancelled.poiTimestamp!!, "C-1"))
            val refused = api.capture(cancelled.pspReference!!, amount, "MP-1", "k6") as ModificationResult.NotProcessed
            assertThat(refused.message).contains("cancelled")

            // Payments it never saw (from before a restart) are accepted.
            assertThat(api.capture("UNKNOWNPSP", amount, "MP-1", "k7")).isInstanceOf(ModificationResult.Received::class.java)
            assertThat(SimulatedModifications().capture(sale.pspReference, amount, "MP-1", "k8"))
                .isInstanceOf(ModificationResult.Received::class.java)
        }

    @Test
    fun `a status check while a payment is under way is answered in progress`() =
        runBlocking {
            config = config.copy(delayMillis = 10_000)
            val started = CompletableDeferred<String>()
            val payment = async { client.pay(tokenizing) { started.complete(it) } }
            val id = started.await()
            delay(50)
            assertThat(client.status(id)).isInstanceOf(TransactionOutcome.Unknown::class.java)
            client.abort(id)
            payment.await()
            assertThat(client.status(id)).isInstanceOf(TransactionOutcome.Completed::class.java)
        }

    @Test
    fun `approvals without a shopper reference are not tokenized, signatures on request`() {
        config = config.copy(signatureRequired = true)
        val details = pay(tokenizing.copy(shopperReference = null, recurringProcessingModel = null))
        assertThat(details.tokenization).isNull()
        assertThat(details.signatureRequired).isTrue()
        assertThat(details.cashierReceipt.map { it.key }).contains("sigline")
    }

    @Test
    fun `declines, cancellations, busy terminals and random outcomes`() {
        config = config.copy(outcome = SimulatedOutcome.DECLINE)
        assertThat(pay().message).isEqualTo("Not enough balance")
        config = config.copy(outcome = SimulatedOutcome.CANCEL)
        assertThat(pay().decline!!.cancelled).isTrue()
        config = config.copy(outcome = SimulatedOutcome.BUSY)
        val busy = pay()
        assertThat(busy.decline!!.advice).isEqualTo(RetryAdvice.TERMINAL_BUSY)
        assertThat(busy.decline!!.busyServiceId).isEqualTo(TerminalSimulator.BUSY_SERVICE_ID)
        assertThat(busy.pspReference).isNull()
        assertThat(busy.maskedPan).isNull()
        config = config.copy(outcome = SimulatedOutcome.RANDOM)
        assertThat((1..40).map { pay().success }.toSet()).containsExactly(true, false)
    }

    @Test
    fun `timeouts are recovered through transaction status`() {
        config = config.copy(outcome = SimulatedOutcome.TIMEOUT)
        val outcome = runBlocking { client.pay(tokenizing) } as TransactionOutcome.Completed
        assertThat(outcome.recovered).isTrue()
        assertThat(outcome.details.success).isTrue()
    }

    @Test
    fun `abort stops an in-flight payment`() =
        runBlocking {
            config = config.copy(delayMillis = 10_000)
            var serviceId: String? = null
            val payment = async { client.pay(tokenizing) { serviceId = it } }
            while (serviceId == null) delay(10)
            client.abort(serviceId)
            val details = (payment.await() as TransactionOutcome.Completed).details
            assertThat(details.errorCondition).isEqualTo("Aborted")
            assertThat(details.decline!!.cancelled).isTrue()
        }

    @Test
    fun `refunds, prints and diagnoses`() =
        runBlocking {
            val payment = pay()
            val refund = client.refund(RefundParams(payment.poiTransactionId!!, payment.poiTimestamp!!, "MP-R", BigDecimal("2.00"), "AUD"))
            val details = (refund as TransactionOutcome.Completed).details
            assertThat(details.success).isTrue()
            assertThat(details.amount).isEqualTo(BigDecimal("2.00"))
            assertThat(details.customerReceipt.map { it.name }).contains("REFUND REQUESTED")
            val full = client.refund(RefundParams(payment.poiTransactionId, payment.poiTimestamp, "MP-R2")) as TransactionOutcome.Completed
            assertThat(full.details.customerReceipt.map { it.value }).contains("Full amount")

            // What the simulator prints reads back as the jobs that were sent.
            val text =
                PrintJob.Text(
                    listOf(
                        PrintLine.Text("Shop", PrintAlign.CENTER, PrintStyle.BOLD),
                        PrintLine.Columns("Total", "$1", PrintStyle.UNDERLINE),
                        PrintLine.Text("Thanks", PrintAlign.RIGHT),
                        PrintLine.Text(""),
                    ),
                )
            val qr = PrintJob.QrCode("MPR1*x y")
            assertThat(client.print(listOf(text, qr))).isEqualTo(PrintOutcome.Printed)
            assertThat(printed).containsExactly(text, qr).inOrder()
            assertThat(client.diagnose().hasPrinter).isTrue()

            config = config.copy(hasPrinter = false)
            val failed = client.print(listOf(qr)) as PrintOutcome.Failed
            assertThat(failed.noPrinter).isTrue()
            assertThat(failed.message).contains("no printer")
            assertThat(client.diagnose().hasPrinter).isFalse()
        }

    @Test
    fun `unknown transactions are not found and unsupported requests fail`() =
        runBlocking {
            assertThat(client.status("NOPE")).isInstanceOf(TransactionOutcome.NotProcessed::class.java)
            val unsupported = TerminalAPIRequest().apply { saleToPOIRequest = SaleToPOIRequest().apply { messageHeader = MessageHeader() } }
            assertThat(simulator.send(unsupported, 1.seconds)).isInstanceOf(Delivery.MaybeSent::class.java)
            assertThat(simulator.send(TerminalAPIRequest(), 1.seconds)).isInstanceOf(Delivery.MaybeSent::class.java)
            val diagnosis =
                TerminalAPIRequest().apply {
                    saleToPOIRequest =
                        SaleToPOIRequest().apply {
                            messageHeader = MessageHeader()
                            diagnosisRequest = DiagnosisRequest()
                        }
                }
            val answered = simulator.send(diagnosis, 1.seconds) as Delivery.Answered
            assertThat(answered.response!!.saleToPOIResponse.diagnosisResponse).isNotNull()
        }
}
