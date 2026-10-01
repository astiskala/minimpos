package io.minimpos.terminal

import com.adyen.model.nexo.AlignmentType
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.BarcodeType
import com.adyen.model.nexo.CardData
import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DiagnosisResponse
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.EntryModeType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.GlobalStatusType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.POIData
import com.adyen.model.nexo.POIStatus
import com.adyen.model.nexo.PaymentAcquirerData
import com.adyen.model.nexo.PaymentInstrumentData
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.PaymentToken
import com.adyen.model.nexo.PrintResponse
import com.adyen.model.nexo.PrinterStatusType
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalReasonType
import com.adyen.model.nexo.ReversalResponse
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TokenRequestedType
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
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
import io.minimpos.terminal.client.TransactionKind
import io.minimpos.terminal.client.TransactionOutcome
import io.minimpos.terminal.transport.TerminalProtocolException
import io.minimpos.terminal.transport.TerminalRejectedException
import io.minimpos.terminal.transport.TerminalTransport
import io.minimpos.terminal.transport.TerminalUnreachableException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class TerminalClientTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private var ids = 0
    private val clock = Clock.fixed(Instant.parse("2026-09-30T01:02:03.456Z"), ZoneOffset.UTC)

    private fun client(
        application: PosApplication? = PosApplication("Mini mPOS", "1.0", "Mini mPOS", "Android", "13"),
        recovery: RecoveryPolicy = RecoveryPolicy(attempts = 3, intervalMillis = 10, maxInProgressChecks = 3),
        reply: (TerminalAPIRequest) -> TerminalAPIResponse?,
    ) = TerminalClient(
        transport =
            TerminalTransport { request, _ ->
                sent += request
                reply(request)
            },
        identity = TerminalIdentity("POS1", "S1F2-000158213605014"),
        application = application,
        clock = clock,
        newServiceId = { "S${++ids}" },
        recovery = recovery,
    )

    private fun respond(body: SaleToPOIResponse.() -> Unit) =
        TerminalAPIResponse().apply {
            saleToPOIResponse =
                SaleToPOIResponse().apply(body)
        }

    private fun statusReply(
        response: Response,
        body: RepeatedResponseMessageBody? = null,
    ) = respond {
        transactionStatusResponse =
            TransactionStatusResponse().apply {
                this.response = response
                body?.let { repeatedMessageResponse = RepeatedMessageResponse().apply { repeatedResponseMessageBody = it } }
            }
    }

    private fun paymentBody(payment: PaymentResponse) = RepeatedResponseMessageBody().apply { paymentResponse = payment }

    private fun printReply(response: Response) = respond { printResponse = PrintResponse().apply { this.response = response } }

    private fun result(
        type: ResultType,
        condition: ErrorConditionType? = null,
        additional: String? = null,
    ) = Response().apply {
        result = type
        errorCondition = condition
        additionalResponse = additional
    }

    private fun base64(json: String) = Base64.getEncoder().encodeToString(json.toByteArray())

    private fun approval() =
        PaymentResponse().apply {
            response =
                result(
                    ResultType.SUCCESS,
                    additional =
                        base64(
                            """{"additionalData":{"pspReference":"PSP123","tokenization.storedPaymentMethodId":"TOKEN1",""" +
                                """"tokenization.shopperReference":"CUST-1","tokenization.store.operationType":"created"}}""",
                        ),
                )
            saleData = SaleData().apply { saleTransactionID = TerminalClient.transaction("MP-1", clock.instant()) }
            poiData = POIData().apply { poiTransactionID = TerminalClient.transaction("TENDER.PSP123", clock.instant()) }
            paymentResult =
                PaymentResult().apply {
                    amountsResp =
                        AmountsResp().apply {
                            currency = "AUD"
                            authorizedAmount = BigDecimal("12.50")
                        }
                    paymentAcquirerData = PaymentAcquirerData().apply { approvalCode = "123456" }
                    paymentInstrumentData = PaymentInstrumentData().apply { cardData = approvedCard() }
                }
        }

    private fun approvedCard() =
        CardData().apply {
            paymentBrand = "mc"
            maskedPAN = "541333 **** 9999"
            entryMode += EntryModeType.CONTACTLESS
            paymentToken =
                PaymentToken().apply {
                    tokenRequestedType = TokenRequestedType.CUSTOMER
                    tokenValue = "ALIAS1"
                }
        }

    private val params =
        PaymentParams(
            amount = BigDecimal("12.50"),
            currency = "AUD",
            merchantReference = "MP-1",
            shopperReference = "CUST-1",
            shopperEmail = "a@b.co",
            recurringProcessingModel = RecurringModel.CARD_ON_FILE,
            tenderOptions = listOf("ReceiptHandler", "AskGratuity"),
            metadata = mapOf("customerReference" to "C1"),
            requestCardAlias = true,
        )

    @Test
    fun `builds the payment request and maps an approval`() =
        runTest {
            var started: String? = null
            val outcome = client { respond { paymentResponse = approval() } }.pay(params) { started = it }
            assertThat(started).isEqualTo("S1")
            val request = sent.single().saleToPOIRequest
            assertThat(request.messageHeader.messageClass).isEqualTo(MessageClassType.SERVICE)
            assertThat(request.messageHeader.messageCategory).isEqualTo(MessageCategoryType.PAYMENT)
            assertThat(request.messageHeader.saleID).isEqualTo("POS1")
            assertThat(request.messageHeader.poiid).isEqualTo("S1F2-000158213605014")
            val saleData = request.paymentRequest.saleData
            assertThat(saleData.saleTransactionID.transactionID).isEqualTo("MP-1")
            assertThat(saleData.tokenRequestedType).isEqualTo(TokenRequestedType.CUSTOMER)
            val acquirer = saleData.saleToAcquirerData
            assertThat(acquirer.shopperEmail).isEqualTo("a@b.co")
            assertThat(acquirer.recurringProcessingModel.toString()).isEqualTo("CardOnFile")
            assertThat(acquirer.tenderOption).isEqualTo("ReceiptHandler,AskGratuity")
            assertThat(acquirer.metadata).containsExactly("customerReference", "C1")
            assertThat(acquirer.applicationInfo.merchantApplication.name).isEqualTo("Mini mPOS")
            assertThat(request.paymentRequest.paymentTransaction.amountsReq.requestedAmount).isEqualTo(BigDecimal("12.50"))

            val details = (outcome as TransactionOutcome.Completed).details
            assertThat(outcome.recovered).isFalse()
            assertThat(details.success).isTrue()
            assertThat(details.advice).isEqualTo(RetryAdvice.DO_NOT_RETRY)
            assertThat(details.pspReference).isEqualTo("PSP123")
            assertThat(details.poiTransactionId).isEqualTo("TENDER.PSP123")
            assertThat(details.poiTimestamp).isEqualTo("2026-09-30T01:02:03.456Z")
            assertThat(details.merchantReference).isEqualTo("MP-1")
            assertThat(details.amount).isEqualTo(BigDecimal("12.50"))
            assertThat(details.currency).isEqualTo("AUD")
            assertThat(details.paymentBrand).isEqualTo("mc")
            assertThat(details.maskedPan).isEqualTo("541333 **** 9999")
            assertThat(details.entryMode).isEqualTo("Contactless")
            assertThat(details.approvalCode).isEqualTo("123456")
            assertThat(details.tokenization!!.storedPaymentMethodId).isEqualTo("TOKEN1")
            assertThat(details.tokenization.shopperReference).isEqualTo("CUST-1")
            assertThat(details.tokenization.cardAlias).isEqualTo("ALIAS1")
        }

    @Test
    fun `pre-authorisations ask for PreAuth with manual capture, other payments for neither`() =
        runTest {
            val terminal = client { respond { paymentResponse = approval() } }
            terminal.pay(params.copy(preAuthorisation = true))
            terminal.pay(params)
            val (preAuth, sale) = sent.map { it.saleToPOIRequest.paymentRequest.saleData.saleToAcquirerData }
            assertThat(preAuth.authorisationType).isEqualTo("PreAuth")
            assertThat(preAuth.additionalData).containsExactly("manualCapture", "true")
            val wire = String(Base64.getDecoder().decode(preAuth.toBase64()))
            assertThat(wire).containsMatch("\"authorisationType\":\\s*\"PreAuth\"")
            assertThat(wire).containsMatch("\"additionalData\":\\s*\\{\\s*\"manualCapture\":\\s*\"true\"\\s*}")
            assertThat(sale.authorisationType).isNull()
            assertThat(sale.additionalData).isNull()
        }

    @Test
    fun `maps declines, busy terminals and legacy fields`() =
        runTest {
            val declined =
                PaymentResponse().apply {
                    response =
                        result(
                            ResultType.FAILURE,
                            ErrorConditionType.REFUSAL,
                            "refusalReason=Do%20Not%20Honor&paymentMethod=visa&authCode=9&recurring.recurringDetailReference=R1&alias=A1",
                        )
                }
            val details = (client { respond { paymentResponse = declined } }.pay(params) as TransactionOutcome.Completed).details
            assertThat(details.success).isFalse()
            assertThat(details.errorCondition).isEqualTo("Refusal")
            assertThat(details.message).isEqualTo("Do Not Honor")
            assertThat(details.advice).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
            assertThat(details.paymentBrand).isEqualTo("visa")
            assertThat(details.approvalCode).isEqualTo("9")
            assertThat(details.tokenization!!.storedPaymentMethodId).isEqualTo("R1")
            assertThat(details.tokenization.cardAlias).isEqualTo("A1")
            assertThat(details.busyServiceId).isNull()

            val busy =
                PaymentResponse().apply {
                    response =
                        result(ResultType.FAILURE, ErrorConditionType.BUSY, "message=ADMIN_MENU&serviceId=OTHER1")
                }
            val busyDetails = (client { respond { paymentResponse = busy } }.pay(params) as TransactionOutcome.Completed).details
            assertThat(busyDetails.advice).isEqualTo(RetryAdvice.TERMINAL_BUSY)
            assertThat(busyDetails.busyServiceId).isEqualTo("OTHER1")
            assertThat(busyDetails.message).isEqualTo("ADMIN_MENU")

            val format =
                PaymentResponse().apply {
                    response =
                        result(ResultType.FAILURE, ErrorConditionType.MESSAGE_FORMAT, "errors=Currency%20missing")
                }
            assertThat((client { respond { paymentResponse = format } }.pay(params) as TransactionOutcome.Completed).details.message)
                .isEqualTo("Currency missing")
        }

    @Test
    fun `unreachable or rejecting terminals mean not processed`() =
        runTest {
            val unreachable = client { throw TerminalUnreachableException("Cannot connect") }.pay(params)
            assertThat((unreachable as TransactionOutcome.NotProcessed).reason).isEqualTo("Cannot connect")
            val rejected = client { throw TerminalRejectedException("Terminal rejected the request") }.pay(params)
            assertThat(rejected).isInstanceOf(TransactionOutcome.NotProcessed::class.java)
        }

    @Test
    fun `timeouts are settled with transaction status, polling while in progress`() =
        runTest {
            var calls = 0
            val outcome =
                client { request ->
                    calls++
                    when {
                        request.saleToPOIRequest.paymentRequest != null -> throw SocketTimeoutException("timeout")
                        calls == 2 -> statusReply(result(ResultType.FAILURE, ErrorConditionType.IN_PROGRESS))
                        calls == 3 -> throw TerminalProtocolException("garbled")
                        else -> statusReply(result(ResultType.SUCCESS), paymentBody(approval()))
                    }
                }.pay(params)
            assertThat((outcome as TransactionOutcome.Completed).recovered).isTrue()
            assertThat(outcome.details.pspReference).isEqualTo("PSP123")
            val status = sent.last().saleToPOIRequest
            assertThat(status.messageHeader.messageCategory).isEqualTo(MessageCategoryType.TRANSACTION_STATUS)
            assertThat(status.transactionStatusRequest.messageReference.serviceID).isEqualTo("S1")
            assertThat(status.transactionStatusRequest.messageReference.messageCategory).isEqualTo(MessageCategoryType.PAYMENT)
            assertThat(status.transactionStatusRequest.isReceiptReprintFlag).isTrue()
            assertThat(status.transactionStatusRequest.documentQualifier)
                .containsExactly(DocumentQualifierType.CASHIER_RECEIPT, DocumentQualifierType.CUSTOMER_RECEIPT)
        }

    @Test
    fun `status not found means not processed, silence and endless progress mean unknown`() =
        runTest {
            val notFound =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) {
                        null
                    } else {
                        statusReply(result(ResultType.FAILURE, ErrorConditionType.NOT_FOUND))
                    }
                }.pay(params)
            assertThat(notFound).isInstanceOf(TransactionOutcome.NotProcessed::class.java)
            val silent =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) throw SocketTimeoutException("t")
                    null
                }.pay(params)
            assertThat((silent as TransactionOutcome.Unknown).reason).isEqualTo("t")
            val busy =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) {
                        respond { }
                    } else {
                        statusReply(result(ResultType.FAILURE, ErrorConditionType.IN_PROGRESS))
                    }
                }.pay(params)
            assertThat((busy as TransactionOutcome.Unknown).reason).isEqualTo("Unexpected response")
            val successWithoutBody =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) {
                        null
                    } else {
                        statusReply(result(ResultType.SUCCESS))
                    }
                }.pay(params)
            assertThat(successWithoutBody).isInstanceOf(TransactionOutcome.Unknown::class.java)
        }

    @Test
    fun `refunds are referenced reversals with the original currency on partial refunds`() =
        runTest {
            val reversal =
                ReversalResponse().apply {
                    response = result(ResultType.SUCCESS, additional = "pspReference=REF1&merchantReference=MP-R")
                    reversedAmount = BigDecimal("4.50")
                }
            val client = client { respond { reversalResponse = reversal } }
            val partial =
                client.refund(
                    RefundParams("TENDER.PSP123", "2026-09-29T11:04:00.000Z", "MP-R", BigDecimal("4.50"), "AUD"),
                )
            val details = (partial as TransactionOutcome.Completed).details
            assertThat(details.pspReference).isEqualTo("REF1")
            assertThat(details.merchantReference).isEqualTo("MP-R")
            assertThat(details.amount).isEqualTo(BigDecimal("4.50"))
            val request = sent.last().saleToPOIRequest.reversalRequest
            assertThat(request.reversalReason).isEqualTo(ReversalReasonType.MERCHANT_CANCEL)
            assertThat(request.originalPOITransaction.poiTransactionID.transactionID).isEqualTo("TENDER.PSP123")
            assertThat(
                request.originalPOITransaction.poiTransactionID.timeStamp
                    .toXMLFormat(),
            ).isEqualTo("2026-09-29T11:04:00.000Z")
            assertThat(request.reversedAmount).isEqualTo(BigDecimal("4.50"))
            assertThat(request.saleData.saleToAcquirerData.currency).isEqualTo("AUD")
            assertThat(request.saleData.saleToAcquirerData.applicationInfo.externalPlatform.name).isEqualTo("Mini mPOS")
            assertThat(request.saleData.saleTransactionID.transactionID).isEqualTo("MP-R")

            client.refund(RefundParams("TENDER.PSP123", "2026-09-29T11:04:00Z", "MP-R2"))
            val full = sent.last().saleToPOIRequest.reversalRequest
            assertThat(full.reversedAmount).isNull()
            assertThat(full.saleData.saleToAcquirerData.currency).isNull()
            assertThrows(IllegalArgumentException::class.java) { RefundParams("T", "t", "R", BigDecimal.ONE) }
        }

    @Test
    fun `reversal recovery maps reversal responses`() =
        runTest {
            val outcome =
                client { request ->
                    if (request.saleToPOIRequest.reversalRequest != null) {
                        throw SocketTimeoutException("t")
                    } else {
                        val reversal = ReversalResponse().apply { response = result(ResultType.SUCCESS, additional = "pspReference=R9") }
                        statusReply(result(ResultType.SUCCESS), RepeatedResponseMessageBody().apply { reversalResponse = reversal })
                    }
                }.refund(RefundParams("T.P", "2026-09-29T11:04:00Z", "MP-R"))
            assertThat((outcome as TransactionOutcome.Completed).details.pspReference).isEqualTo("R9")
            val reference =
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference
            assertThat(reference.messageCategory).isEqualTo(MessageCategoryType.REVERSAL)
        }

    @Test
    fun `abort references the transaction and swallows errors`() =
        runTest {
            val client = client { throw TerminalUnreachableException("gone") }
            client.abort("PAY1")
            val abort = sent.single().saleToPOIRequest
            assertThat(abort.messageHeader.messageCategory).isEqualTo(MessageCategoryType.ABORT)
            assertThat(abort.abortRequest.abortReason).isEqualTo("MerchantAbort")
            assertThat(abort.abortRequest.messageReference.serviceID).isEqualTo("PAY1")
            assertThat(abort.abortRequest.messageReference.messageCategory).isEqualTo(MessageCategoryType.PAYMENT)
            client.abort("REF1", TransactionKind.REFUND)
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.abortRequest.messageReference.messageCategory,
            ).isEqualTo(MessageCategoryType.REVERSAL)
        }

    @Test
    fun `prints jobs in order and reports failures`() =
        runTest {
            val job = PrintJob.Text(listOf(PrintLine.Text("x")))
            var replies = 0
            val client =
                client {
                    replies++
                    when (replies) {
                        1, 2 -> printReply(result(ResultType.SUCCESS))
                        3 -> printReply(result(ResultType.FAILURE, ErrorConditionType.UNAVAILABLE_DEVICE))
                        4 -> printReply(result(ResultType.FAILURE, additional = "message=Paper%20jam"))
                        5 -> null
                        else -> throw TerminalUnreachableException("gone")
                    }
                }
            assertThat(client.print(listOf(job, job))).isEqualTo(PrintOutcome.Printed)
            assertThat(
                sent
                    .first()
                    .saleToPOIRequest.messageHeader.messageClass,
            ).isEqualTo(MessageClassType.DEVICE)
            assertThat(
                sent
                    .first()
                    .saleToPOIRequest.printRequest.printOutput.documentQualifier,
            ).isEqualTo(DocumentQualifierType.DOCUMENT)
            assertThat(client.print(listOf(job))).isEqualTo(PrintOutcome.Failed("Printing failed", noPrinter = true))
            assertThat(client.print(listOf(job))).isEqualTo(PrintOutcome.Failed("Paper jam", noPrinter = false))
            assertThat((client.print(listOf(job)) as PrintOutcome.Failed).message).contains("No print response")
            assertThat((client.print(listOf(job)) as PrintOutcome.Failed).message).isEqualTo("gone")
        }

    @Test
    fun `print jobs become nexo text and URL-encoded QR codes`() =
        runTest {
            val client = client { printReply(result(ResultType.SUCCESS)) }
            val text =
                PrintJob.Text(
                    listOf(
                        PrintLine.Text("Shop", PrintAlign.CENTER, PrintStyle.BOLD),
                        PrintLine.Columns("Total", "$1", PrintStyle.UNDERLINE),
                        PrintLine.Text("right", PrintAlign.RIGHT),
                        PrintLine.Text(""),
                    ),
                )
            assertThat(client.print(listOf(text, PrintJob.QrCode("MPR1*a.b*1"), PrintJob.QrCode("A B")))).isEqualTo(PrintOutcome.Printed)
            val outputs = sent.map { it.saleToPOIRequest.printRequest.printOutput }
            assertThat(outputs.map { it.documentQualifier })
                .containsExactly(
                    DocumentQualifierType.DOCUMENT,
                    DocumentQualifierType.CUSTOMER_RECEIPT,
                    DocumentQualifierType.CUSTOMER_RECEIPT,
                ).inOrder()
            val lines = outputs[0].outputContent.outputText
            assertThat(outputs[0].outputContent.outputFormat).isEqualTo(OutputFormatType.TEXT)
            assertThat(lines.map { it.text }).containsExactly("Shop", "Total", "$1", "right", "").inOrder()
            assertThat(lines[0].alignment).isEqualTo(AlignmentType.CENTRED)
            assertThat(lines[0].characterStyle).isEqualTo(CharacterStyleType.BOLD)
            assertThat(lines[1].isEndOfLineFlag).isFalse()
            assertThat(lines[1].characterStyle).isEqualTo(CharacterStyleType.UNDERLINED)
            assertThat(lines[2].alignment).isEqualTo(AlignmentType.RIGHT)
            assertThat(lines[3].alignment).isEqualTo(AlignmentType.RIGHT)
            assertThat(lines[4].characterStyle).isNull()
            val qr = outputs[1].outputContent
            assertThat(qr.outputFormat).isEqualTo(OutputFormatType.BAR_CODE)
            assertThat(qr.outputBarcode.barcodeType).isEqualTo(BarcodeType.QRCODE)
            assertThat(qr.outputBarcode.barcodeValue).isEqualTo("MPR1*a.b*1")
            assertThat(outputs[2].outputContent.outputBarcode.barcodeValue).isEqualTo("A+B")
        }

    @Test
    fun `diagnosis reports reachability and printer status`() =
        runTest {
            val ok =
                client {
                    respond {
                        diagnosisResponse =
                            DiagnosisResponse().apply {
                                response = result(ResultType.SUCCESS)
                                poiStatus =
                                    POIStatus().apply {
                                        globalStatus = GlobalStatusType.OK
                                        printerStatus = PrinterStatusType.PAPER_LOW
                                    }
                            }
                    }
                }.diagnose()
            assertThat(ok.reachable).isTrue()
            assertThat(ok.globalStatus).isEqualTo("OK")
            assertThat(ok.printerStatus).isEqualTo("PaperLow")
            assertThat(ok.hasPrinter).isTrue()
            assertThat(client { null }.diagnose().reachable).isFalse()
            assertThat(client { throw TerminalUnreachableException("gone") }.diagnose().message).isEqualTo("gone")
        }

    @Test
    fun `explicit status checks settle earlier transactions`() =
        runTest {
            val settled =
                client { statusReply(result(ResultType.SUCCESS), paymentBody(approval())) }.status("OLD1")
            assertThat((settled as TransactionOutcome.Completed).details.pspReference).isEqualTo("PSP123")
            assertThat(client { null }.status("OLD2", TransactionKind.REFUND)).isInstanceOf(TransactionOutcome.Unknown::class.java)
            assertThat(
                sent
                    .last()
                    .saleToPOIRequest.transactionStatusRequest.messageReference.messageCategory,
            ).isEqualTo(MessageCategoryType.REVERSAL)
        }

    @Test
    fun `service ids and timestamps follow Terminal API formats`() {
        assertThat(TerminalClient.randomServiceId()).matches("[0-9A-Z]{10}")
        assertThat(TerminalClient.formatTimestamp(Instant.parse("2026-01-02T03:04:05.006Z"))).isEqualTo("2026-01-02T03:04:05.006Z")
        assertThat(TerminalClient.parseTimestamp(" 2026-01-02T03:04:05+02:00 ").toXMLFormat()).isEqualTo("2026-01-02T03:04:05+02:00")
        assertThat(TerminalClient.instantOf("2026-01-02T03:04:05+02:00")).isEqualTo(Instant.parse("2026-01-02T01:04:05Z"))
        assertThat(TerminalClient.instantOf("2026-01-02T03:04:05.006Z")).isEqualTo(Instant.parse("2026-01-02T03:04:05.006Z"))
        assertThat(TerminalClient.instantOf("2026-01-02T03:04:05")).isNull()
        assertThat(TerminalClient.instantOf("not a time")).isNull()
        assertThat(TerminalClient.DEFAULT_TRANSACTION_TIMEOUT.inWholeSeconds).isEqualTo(120)
    }

    @Test
    fun `requests without application info still carry the library details`() =
        runTest {
            val plain = params.copy(tenderOptions = emptyList(), metadata = emptyMap(), requestCardAlias = false)
            client(application = null) { null }.pay(plain)
            val saleData =
                sent
                    .first()
                    .saleToPOIRequest.paymentRequest.saleData
            assertThat(saleData.saleToAcquirerData.applicationInfo.adyenLibrary.name).isEqualTo("adyen-java-api-library")
            assertThat(saleData.saleToAcquirerData.tenderOption).isNull()
            assertThat(saleData.tokenRequestedType).isNull()
        }
}
