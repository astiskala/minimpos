package app.minimpos.terminal

import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.client.BarcodeScan
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.OfflineProcessingMode
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.client.PrintAlign
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintOutcome
import app.minimpos.terminal.client.PrintStyle
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.RecurringModel
import app.minimpos.terminal.client.RefundParams
import app.minimpos.terminal.client.RetryAdvice
import app.minimpos.terminal.client.ScannedPayment
import app.minimpos.terminal.client.ShopperSessionFlow
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalCurrencyTotals
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TerminalTipSuggestions
import app.minimpos.terminal.client.TerminalTotalsOutcome
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.client.VirtualReceipt
import app.minimpos.terminal.client.VirtualReceiptLine
import app.minimpos.terminal.simulator.SimulatedLedger
import app.minimpos.terminal.simulator.SimulatedModifications
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import com.adyen.model.nexo.AmountsReq
import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DiagnosisRequest
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.GlobalStatusType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.PaymentInstrumentType
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.PaymentTransaction
import com.adyen.model.nexo.PaymentType
import com.adyen.model.nexo.PrintOutput
import com.adyen.model.nexo.PrintRequest
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.TokenRequestedType
import com.adyen.model.nexo.TransactionIdentification
import com.adyen.model.terminal.TerminalAPIRequest
import com.google.common.truth.Truth.assertThat
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
            currencyDigits = testCurrencyDigits,
            transport = simulator,
            identity = TerminalIdentity("POS1", "SIMULATOR-000000001"),
            application = PosApplication("Mini mPOS", "1.0", "Mini mPOS", "Android", "13"),
            recovery = RecoveryPolicy(attempts = 2, intervalMillis = 10),
        )
    private val tokenizing =
        PaymentParams(
            amountMinor = 1200,
            currency = "AUD",
            merchantReference = "MP-1",
            shopperReference = "CUST-1",
            shopperEmail = "a@b.co",
            recurringProcessingModel = RecurringModel.UNSCHEDULED_CARD_ON_FILE,
        )

    private fun pay(params: PaymentParams = tokenizing) = runBlocking { client.pay(params) as TransactionOutcome.Completed }.details

    @Test
    fun `accessible simulator exits guidance on cancellation recovery and signature refusal`() =
        runBlocking {
            config = config.copy(delayMillis = 60_000)
            val pending = async { client.pay(tokenizing.copy(audioGuidance = true), "AUDIOCAN1") }
            while (client.status("AUDIOCAN1") != TransactionOutcome.Unknown(Fault.StillInProgress)) delay(1)
            client.abort("AUDIOCAN1")
            assertThat((pending.await() as TransactionOutcome.Completed).details.success).isFalse()
            config = config.copy(delayMillis = 0, signatureRequired = true)
            assertThat(pay(tokenizing.copy(audioGuidance = true)).success).isFalse()
            assertThat(pay().signatureRequired).isTrue()
            config = config.copy(signatureRequired = false, outcome = SimulatedOutcome.TIMEOUT)
            assertThat(pay(tokenizing.copy(audioGuidance = true)).success).isTrue()
            config = config.copy(outcome = SimulatedOutcome.APPROVE)
            assertThat(pay().success).isTrue()
        }

    @Test
    fun `scanned wallet demos report the wallet without inventing a card or successful tokenization`() {
        val details = pay(tokenizing.copy(scannedPayment = ScannedPayment("wechatpay_pos", "133341022926803846")))
        assertThat(details.success).isTrue()
        assertThat(details.paymentBrand).isEqualTo("wechatpay_pos")
        assertThat(details.maskedPan).isNull()
        assertThat(details.tokenization).isNull()
        assertThat(details.entryMode).isEqualTo("Scanned")
        assertThat(details.customerReceipt.toString()).doesNotContain("133341022926803846")
    }

    @Test
    fun `approves with receipts and tokenization`() {
        val details = pay()
        assertThat(details.success).isTrue()
        assertThat(details.amountMinor).isEqualTo(1200)
        assertThat(details.poiTransactionId).contains(".")
        assertThat(details.poiTimestamp).isNotNull()
        assertThat(details.tokenization!!.storedPaymentMethodId).isNotEmpty()
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
            val notCaptured = api.capture(sale.pspReference!!, amount, "MP-1", "k1") as ModificationResult.Failed
            assertThat(notCaptured.fault.mayHaveTakenEffect).isFalse()
            val notAdjusted = api.updateAmount(sale.pspReference, amount, "MP-1", null, "k2") as ModificationResult.Failed
            assertThat(notAdjusted.fault.mayHaveTakenEffect).isFalse()

            // A pre-authorisation is adjusted while held, then captured (again with the same key), and no longer adjusted.
            val held = pay(tokenizing.copy(preAuthorisation = true))
            val psp = held.pspReference!!
            assertThat(api.updateAmount(psp, amount, "MP-1", held.adjustAuthorisationData, "k3"))
                .isInstanceOf(ModificationResult.Authorised::class.java)
            assertThat(api.capture(psp, amount, "MP-1", "k4")).isInstanceOf(ModificationResult.Received::class.java)
            assertThat(api.capture(psp, amount, "MP-1", "k4")).isInstanceOf(ModificationResult.Received::class.java)
            val captured = api.updateAmount(psp, amount, "MP-1", null, "k5") as ModificationResult.Failed
            assertThat((captured.fault as Fault.AdyenRejected).said?.text).contains("already captured")
            // Reversing it in full now refunds it rather than releasing a hold.
            val refund = client.refund(RefundParams(held.poiTransactionId!!, held.poiTimestamp!!, "R-1")) as TransactionOutcome.Completed
            assertThat(refund.details.customerReceipt.map { it.name }).contains("REFUND REQUESTED")

            // A cancelled pre-authorisation cannot be captured.
            val cancelled = pay(tokenizing.copy(preAuthorisation = true))
            client.refund(RefundParams(cancelled.poiTransactionId!!, cancelled.poiTimestamp!!, "C-1"))
            val refused = api.capture(cancelled.pspReference!!, amount, "MP-1", "k6") as ModificationResult.Failed
            assertThat((refused.fault as Fault.AdyenRejected).said?.text).contains("cancelled")

            // Payments it never saw (from before a restart) are accepted.
            assertThat(api.capture("UNKNOWNPSP", amount, "MP-1", "k7")).isInstanceOf(ModificationResult.Received::class.java)
            assertThat(SimulatedModifications(Random.Default, SimulatedLedger()).capture(sale.pspReference, amount, "MP-1", "k8"))
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
            simulator.acknowledge(id)
            // Acknowledgement only retires late App Links, not a terminal's own recoverable ledger.
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
        assertThat(pay().message?.text).isEqualTo("Not enough balance")
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
            val refund = client.refund(RefundParams(payment.poiTransactionId!!, payment.poiTimestamp!!, "MP-R", 200, "AUD"))
            val details = (refund as TransactionOutcome.Completed).details
            assertThat(details.success).isTrue()
            assertThat(details.amountMinor).isEqualTo(200)
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
            val image = PrintJob.Image(byteArrayOf(1, 2, 3))
            assertThat(client.print(listOf(image, text, qr))).isEqualTo(PrintOutcome.Printed)
            assertThat(printed).containsExactly(image, text, qr).inOrder()
            assertThat(client.diagnose().hasPrinter).isTrue()

            config = config.copy(hasPrinter = false)
            val failed = client.print(listOf(qr)) as PrintOutcome.Failed
            assertThat(failed.noPrinter).isTrue()
            assertThat((failed.fault as Fault.TerminalRejected).said?.text).contains("no printer")
            assertThat(client.diagnose().hasPrinter).isFalse()
        }

    @Test
    fun `unknown transactions are not found and unsupported requests fail`() =
        runBlocking {
            assertThat(client.status("NOPE")).isInstanceOf(TransactionOutcome.NotProcessed::class.java)
            val unsupported = TerminalAPIRequest().apply { saleToPOIRequest = SaleToPOIRequest().apply { messageHeader = MessageHeader() } }
            assertThat(simulator.send(unsupported, 1.seconds)).isEqualTo(Delivery.Failed(Fault.Unsupported))
            assertThat(simulator.send(TerminalAPIRequest(), 1.seconds)).isEqualTo(Delivery.Failed(Fault.UnreadableReply()))
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

    @Test
    fun `simulates native terminal tipping with default and dynamic SaleToPOIData options`() =
        runBlocking {
            val defaultCfg = SimulatorConfig()
            assertThat(defaultCfg.outcome).isEqualTo(SimulatedOutcome.APPROVE)
            assertThat(defaultCfg.delayMillis).isEqualTo(2_500L)
            assertThat(defaultCfg.hasPrinter).isTrue()
            assertThat(defaultCfg.signatureRequired).isFalse()
            assertThat(defaultCfg.partialApprovalPercent).isEqualTo(100)
            assertThat(defaultCfg.givingDonationAmount).isEqualTo(BigDecimal.ONE)
            assertThat(defaultCfg.offlineMode).isEqualTo(OfflineProcessingMode.ONLINE)
            assertThat(defaultCfg.unconfirmedBatchCount).isEqualTo(1)
            assertThat(defaultCfg.shopperConfirmed).isTrue()
            assertThat(defaultCfg.shopperEmailInput).isEqualTo("shopper@example.com")
            assertThat(defaultCfg.shopperRating).isEqualTo(9)

            val plainTip =
                pay(
                    tokenizing.copy(
                        amountMinor = 2000,
                        terminalTip = TerminalTipOptions(),
                    ),
                )
            assertThat(plainTip.tipMinor).isEqualTo(200)
            assertThat(plainTip.amountMinor).isEqualTo(2200)

            val fixedTip =
                pay(
                    tokenizing.copy(
                        amountMinor = 600,
                        terminalTip =
                            TerminalTipOptions(
                                suggestions = TerminalTipSuggestions.FixedAmounts(listOf(125, 200, 300), allowCustom = false),
                            ),
                    ),
                )
            assertThat(fixedTip.tipMinor).isEqualTo(125)
            assertThat(fixedTip.amountMinor).isEqualTo(725)

            val percentTipNoCustom =
                pay(
                    tokenizing.copy(
                        amountMinor = 5000,
                        terminalTip =
                            TerminalTipOptions(
                                suggestions = TerminalTipSuggestions.Percentages(listOf(10, 20), allowCustom = false),
                            ),
                    ),
                )
            assertThat(percentTipNoCustom.tipMinor).isEqualTo(500)
            assertThat(percentTipNoCustom.amountMinor).isEqualTo(5500)

            val preTaxPercentTip =
                pay(
                    tokenizing.copy(
                        amountMinor = 11_000,
                        terminalTip =
                            TerminalTipOptions(
                                suggestions = TerminalTipSuggestions.Percentages(listOf(15, 18, 20), allowCustom = true),
                                amountToTipOnMinor = 10_000,
                            ),
                    ),
                )
            assertThat(preTaxPercentTip.tipMinor).isEqualTo(1500)
            assertThat(preTaxPercentTip.amountMinor).isEqualTo(12_500)
        }

    @Test
    fun `refunds of payments the simulator did not take count only in the currency they state`() =
        runBlocking<Unit> {
            client.refund(RefundParams("UNKNOWN.1", "2026-09-30T01:02:03.456Z", "MP-R1", 300, "AUD"))
            client.refund(RefundParams("UNKNOWN.2", "2026-09-30T01:02:03.456Z", "MP-R2"))
            val totals = client.getTotals() as TerminalTotalsOutcome.Answered
            assertThat(totals.totals).containsExactly(TerminalCurrencyTotals(currency = "AUD", creditMinor = 300, creditCount = 1))
        }

    @Test
    fun `getTotals and reconcile report and reset period totals`() =
        runBlocking {
            val pUsd = pay(tokenizing.copy(amountMinor = 500, currency = "USD"))
            assertThat(pUsd.poiReconciliationId).isEqualTo("1000")
            val p1 = pay(tokenizing.copy(amountMinor = 1000, currency = "AUD"))
            assertThat(p1.poiReconciliationId).isEqualTo("1000")
            client.refund(RefundParams(p1.poiTransactionId!!, p1.poiTimestamp!!, "MP-R", 300, "AUD"))

            val totals = client.getTotals() as TerminalTotalsOutcome.Answered
            assertThat(totals.poiReconciliationId).isEqualTo("1000")
            assertThat(totals.closedPeriod).isFalse()
            assertThat(totals.totals)
                .containsExactly(
                    TerminalCurrencyTotals(
                        currency = "AUD",
                        debitMinor = 1000,
                        debitCount = 1,
                        creditMinor = 300,
                        creditCount = 1,
                    ),
                    TerminalCurrencyTotals(
                        currency = "USD",
                        debitMinor = 500,
                        debitCount = 1,
                        creditMinor = 0,
                        creditCount = 0,
                    ),
                ).inOrder()

            val closed = client.reconcile() as TerminalTotalsOutcome.Answered
            assertThat(closed.poiReconciliationId).isEqualTo("1000")
            assertThat(closed.closedPeriod).isTrue()
            assertThat(closed.totals).isEqualTo(totals.totals)

            val nextPeriod = client.getTotals() as TerminalTotalsOutcome.Answered
            assertThat(nextPeriod.poiReconciliationId).isEqualTo("1001")
            assertThat(nextPeriod.totals).isEmpty()
        }

    @Test
    fun `chains shopper display and input session around payment`() =
        runBlocking {
            val flow =
                ShopperSessionFlow(
                    virtualReceipt =
                        VirtualReceipt(
                            header = "Order",
                            lines = listOf(VirtualReceiptLine(1, "Coffee", "AUD", 450)),
                            totalMinor = 450,
                            currency = "AUD",
                        ),
                    confirmationPrompt = "Confirm order" to "AUD 4.50",
                    emailPrompt = "Enter your email address:",
                    qrReceipt = "MP-1",
                    ratingPrompt = "Rate us" to null,
                )
            val details = pay(tokenizing.copy(shopperEmail = null, shopperFlow = flow))
            assertThat(details.success).isTrue()
            assertThat(details.collectedEmail).isEqualTo("shopper@example.com")
            assertThat(details.shopperRating).isEqualTo(9)

            config = config.copy(shopperConfirmed = false)
            val declinedConfirm = pay(tokenizing.copy(shopperFlow = flow))
            assertThat(declinedConfirm.success).isFalse()
            assertThat(declinedConfirm.decline?.cancelled).isTrue()

            config = config.copy(shopperConfirmed = true)
            val scanned = client.scanBarcode(1)
            assertThat(scanned).isEqualTo(BarcodeScan.NotRead)
            client.endBarcodeScan(1)

            config = config.copy(outcome = SimulatedOutcome.CANCEL)
            assertThat(client.scanBarcode(1)).isEqualTo(BarcodeScan.NotRead)
            val cancelledWithPrompt = pay(tokenizing.copy(shopperFlow = ShopperSessionFlow(confirmationPrompt = "Confirm" to "Sub")))
            assertThat(cancelledWithPrompt.decline?.cancelled).isTrue()
        }

    @Test
    fun `simulates partial authorisation via 139 suffix and configured ratio`() =
        runBlocking {
            val notPartial =
                pay(
                    tokenizing.copy(
                        amountMinor = 1000,
                        allowPartialAuthorisation = true,
                    ),
                )
            assertThat(notPartial.partial).isFalse()
            assertThat(notPartial.amountMinor).isEqualTo(1000)

            val triggerPartial =
                pay(
                    tokenizing.copy(
                        amountMinor = 1139,
                        allowPartialAuthorisation = true,
                        saleReferenceId = "MP-SPLIT-1",
                    ),
                )
            assertThat(triggerPartial.success).isTrue()
            assertThat(triggerPartial.partial).isTrue()
            assertThat(triggerPartial.amountMinor).isEqualTo(570)

            config = config.copy(partialApprovalPercent = 40)
            val ratioPartial =
                pay(
                    tokenizing.copy(
                        amountMinor = 2000,
                        allowPartialAuthorisation = true,
                    ),
                )
            assertThat(ratioPartial.success).isTrue()
            assertThat(ratioPartial.partial).isTrue()
            assertThat(ratioPartial.amountMinor).isEqualTo(800)
        }

    @Test
    fun `simulates AskGiving with two-step AdyenGiving status lookup`() =
        runBlocking {
            config = config.copy(givingDonationAmount = BigDecimal("2.50"))
            val details = pay(tokenizing.copy(amountMinor = 1500, askGiving = true))
            assertThat(details.success).isTrue()
            assertThat(details.amountMinor).isEqualTo(1500)
            assertThat(details.givingServiceId).isEqualTo("GIVINGSIM1")
            assertThat(details.donationMinor).isEqualTo(250)
        }

    @Test
    fun `simulates MOTO and Manual Key Entry overrides`() =
        runBlocking {
            val moto = pay(tokenizing.copy(cardEntryOverride = CardEntryOverride.MOTO))
            assertThat(moto.success).isTrue()
            assertThat(moto.entryMode).isEqualTo("Keyed")
            assertThat(moto.additionalData["posEntryMode"]).isEqualTo("MOTO")

            val mke = pay(tokenizing.copy(cardEntryOverride = CardEntryOverride.MKE))
            assertThat(mke.success).isTrue()
            assertThat(mke.entryMode).isEqualTo("Keyed")
            assertThat(mke.additionalData["posEntryMode"]).isEqualTo("MKE")
            assertThat(mke.signatureRequired).isTrue()
        }

    @Test
    fun `simulates offline EMV and store-and-forward payments without PSP reference`() =
        runBlocking {
            config = config.copy(offlineMode = OfflineProcessingMode.OFFLINE_EMV, unconfirmedBatchCount = 2)
            val emvOffline = pay()
            assertThat(emvOffline.success).isTrue()
            assertThat(emvOffline.offlineMode).isEqualTo(OfflineProcessingMode.OFFLINE_EMV)
            assertThat(emvOffline.unconfirmedBatchCount).isEqualTo(2)
            assertThat(emvOffline.pspReference).isNull()
            assertThat(emvOffline.poiTransactionId).doesNotContain(".")
            assertThat(emvOffline.approvalCode).isNotNull()

            config = config.copy(offlineMode = OfflineProcessingMode.STORE_AND_FORWARD, unconfirmedBatchCount = 3)
            val safOffline = pay()
            assertThat(safOffline.success).isTrue()
            assertThat(safOffline.offlineMode).isEqualTo(OfflineProcessingMode.STORE_AND_FORWARD)
            assertThat(safOffline.unconfirmedBatchCount).isEqualTo(3)
            assertThat(safOffline.pspReference).isNull()
            assertThat(safOffline.partial).isFalse()
            assertThat(safOffline.maskedPan).isNotEmpty()
            assertThat(safOffline.tokenization?.storedPaymentMethodId).isNotEmpty()

            val refundOut =
                client.refund(
                    RefundParams(
                        originalTransactionId = safOffline.poiTransactionId!!,
                        originalTimestamp = safOffline.poiTimestamp!!,
                        merchantReference = "REF-SIM-1",
                        amountMinor = 500,
                        currency = "AUD",
                    ),
                    serviceId = "REV-SIM-1",
                ) as TransactionOutcome.Completed
            assertThat(refundOut.details.success).isTrue()
            assertThat(refundOut.details.pspReference).isNotEmpty()
            assertThat(refundOut.details.poiTransactionId).isNotEmpty()
            val repeatedRefund =
                client.status(
                    "REV-SIM-1",
                    TransactionKind.REFUND,
                ) as TransactionOutcome.Completed
            assertThat(repeatedRefund.details.pspReference).isEqualTo(refundOut.details.pspReference)
        }

    private fun paymentWireRequest(serviceId: String): TerminalAPIRequest {
        val header =
            MessageHeader().apply {
                protocolVersion = "3.0"
                messageClass = MessageClassType.SERVICE
                messageCategory = MessageCategoryType.PAYMENT
                messageType = MessageType.REQUEST
                serviceID = serviceId
                saleID = "MiniMPOS"
                poiid = "SIM-1"
            }
        val payment =
            PaymentRequest().apply {
                saleData = SaleData().apply { saleTransactionID = TransactionIdentification().apply { transactionID = "MREF-WIRE" } }
                paymentTransaction =
                    PaymentTransaction().apply {
                        amountsReq =
                            AmountsReq().apply {
                                currency = "EUR"
                                requestedAmount = BigDecimal("10.00")
                            }
                    }
            }
        return TerminalAPIRequest().apply {
            saleToPOIRequest =
                SaleToPOIRequest().apply {
                    messageHeader = header
                    paymentRequest = payment
                }
        }
    }

    @Test
    fun `populates raw wire message headers and response fields`() =
        runBlocking {
            val diagReq =
                TerminalAPIRequest().apply {
                    saleToPOIRequest =
                        SaleToPOIRequest().apply {
                            messageHeader =
                                MessageHeader().apply {
                                    protocolVersion = "3.0"
                                    messageClass = MessageClassType.SERVICE
                                    messageCategory = MessageCategoryType.DIAGNOSIS
                                    messageType = MessageType.REQUEST
                                    serviceID = "DIAG-1"
                                    saleID = "MiniMPOS"
                                    poiid = "SIM-1"
                                }
                            diagnosisRequest = DiagnosisRequest()
                        }
                }
            val diagResp = (simulator.send(diagReq, 5.seconds) as Delivery.Answered).response!!.saleToPOIResponse
            assertThat(diagResp.messageHeader.protocolVersion).isEqualTo("3.0")
            assertThat(diagResp.messageHeader.messageClass).isEqualTo(MessageClassType.SERVICE)
            assertThat(diagResp.messageHeader.messageCategory).isEqualTo(MessageCategoryType.DIAGNOSIS)
            assertThat(diagResp.messageHeader.messageType).isEqualTo(MessageType.RESPONSE)
            assertThat(diagResp.messageHeader.serviceID).isEqualTo("DIAG-1")
            assertThat(diagResp.messageHeader.saleID).isEqualTo("MiniMPOS")
            assertThat(diagResp.messageHeader.poiid).isEqualTo("SIM-1")
            assertThat(diagResp.diagnosisResponse.poiStatus.globalStatus).isEqualTo(GlobalStatusType.OK)
            assertThat(diagResp.diagnosisResponse.poiStatus.isPEDOKFlag).isTrue()

            config = SimulatorConfig(delayMillis = 0)
            val payWireResp =
                (simulator.send(paymentWireRequest("PW-1"), 5.seconds) as Delivery.Answered)
                    .response!!
                    .saleToPOIResponse
                    .paymentResponse
            assertThat(payWireResp.saleData.saleTransactionID.transactionID).isEqualTo("MREF-WIRE")
            assertThat(payWireResp.paymentResult.paymentType).isEqualTo(PaymentType.NORMAL)
            assertThat(payWireResp.paymentResult.amountsResp.currency).isEqualTo("EUR")
            assertThat(payWireResp.paymentResult.isOnlineFlag).isTrue()
            assertThat(payWireResp.paymentResult.paymentAcquirerData.approvalCode).isNotEmpty()
            val cardInst = payWireResp.paymentResult.paymentInstrumentData
            assertThat(cardInst.paymentInstrumentType).isEqualTo(PaymentInstrumentType.CARD)
            assertThat(cardInst.cardData.paymentBrand).isNotEmpty()
            assertThat(cardInst.cardData.paymentToken.tokenRequestedType).isEqualTo(TokenRequestedType.CUSTOMER)
            assertThat(cardInst.cardData.paymentToken.tokenValue).isNotEmpty()
            val receiptContent = payWireResp.paymentReceipt.first().outputContent
            assertThat(receiptContent.outputFormat).isEqualTo(OutputFormatType.TEXT)
            assertThat(receiptContent.outputText.any { it.characterStyle == CharacterStyleType.BOLD }).isTrue()
            assertThat(receiptContent.outputText.all { it.isEndOfLineFlag == true }).isTrue()
        }

    @Test
    fun `populates raw wire fields for busy offline and printer responses`() =
        runBlocking {
            val payWireReq = paymentWireRequest("PW-2")
            config = SimulatorConfig(delayMillis = 0, outcome = SimulatedOutcome.BUSY)
            val busyWireResp =
                (simulator.send(payWireReq, 5.seconds) as Delivery.Answered)
                    .response!!
                    .saleToPOIResponse
                    .paymentResponse
            assertThat(busyWireResp.saleData.saleTransactionID.transactionID).isEqualTo("MREF-WIRE")

            config = SimulatorConfig(delayMillis = 0, outcome = SimulatedOutcome.APPROVE, offlineMode = OfflineProcessingMode.OFFLINE_EMV)
            val offlineWireResp =
                (simulator.send(payWireReq, 5.seconds) as Delivery.Answered)
                    .response!!
                    .saleToPOIResponse
                    .paymentResponse
            assertThat(offlineWireResp.paymentResult.isOnlineFlag).isFalse()
            assertThat(offlineWireResp.paymentResult.paymentAcquirerData.approvalCode).isNull()

            config = SimulatorConfig(delayMillis = 0, hasPrinter = false)
            val printWireReq =
                TerminalAPIRequest().apply {
                    saleToPOIRequest =
                        SaleToPOIRequest().apply {
                            messageHeader = payWireReq.saleToPOIRequest.messageHeader
                            printRequest =
                                PrintRequest().apply {
                                    printOutput =
                                        PrintOutput().apply {
                                            documentQualifier = DocumentQualifierType.CUSTOMER_RECEIPT
                                        }
                                }
                        }
                }
            val noPrinterResp =
                (simulator.send(printWireReq, 5.seconds) as Delivery.Answered)
                    .response!!
                    .saleToPOIResponse
                    .printResponse
            assertThat(noPrinterResp.documentQualifier).isEqualTo(DocumentQualifierType.CUSTOMER_RECEIPT)
            assertThat(noPrinterResp.response.result).isEqualTo(ResultType.FAILURE)
            assertThat(noPrinterResp.response.errorCondition).isEqualTo(ErrorConditionType.UNAVAILABLE_DEVICE)
            config = SimulatorConfig(delayMillis = 0, hasPrinter = true)
            val okPrinterResp =
                (simulator.send(printWireReq, 5.seconds) as Delivery.Answered)
                    .response!!
                    .saleToPOIResponse
                    .printResponse
            assertThat(okPrinterResp.documentQualifier).isEqualTo(DocumentQualifierType.CUSTOMER_RECEIPT)
            assertThat(okPrinterResp.response.result).isEqualTo(ResultType.SUCCESS)
        }
}
