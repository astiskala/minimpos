package app.minimpos.terminal

import app.minimpos.terminal.client.BarcodeScan
import app.minimpos.terminal.client.CardEntryOverride
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
import app.minimpos.terminal.client.TerminalMoney
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TerminalTipSuggestions
import app.minimpos.terminal.client.TerminalTotalsOutcome
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.client.VirtualReceipt
import app.minimpos.terminal.client.VirtualReceiptLine
import app.minimpos.terminal.client.toPrintJob
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.AdminResponse
import com.adyen.model.nexo.AlignmentType
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.BarcodeType
import com.adyen.model.nexo.CardData
import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DeviceType
import com.adyen.model.nexo.DiagnosisResponse
import com.adyen.model.nexo.DisplayResponse
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.EntryModeType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.GetTotalsResponse
import com.adyen.model.nexo.GlobalStatusType
import com.adyen.model.nexo.InfoQualifyType
import com.adyen.model.nexo.Input
import com.adyen.model.nexo.InputCommandType
import com.adyen.model.nexo.InputResponse
import com.adyen.model.nexo.InputResult
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.OutputBarcode
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.OutputResult
import com.adyen.model.nexo.OutputText
import com.adyen.model.nexo.POIData
import com.adyen.model.nexo.POIStatus
import com.adyen.model.nexo.PaymentAcquirerData
import com.adyen.model.nexo.PaymentInstrumentData
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.PaymentToken
import com.adyen.model.nexo.PaymentTotals
import com.adyen.model.nexo.PrintOutput
import com.adyen.model.nexo.PrintResponse
import com.adyen.model.nexo.PrinterStatusType
import com.adyen.model.nexo.ReconciliationResponse
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalReasonType
import com.adyen.model.nexo.ReversalResponse
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TokenRequestedType
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.nexo.TransactionTotals
import com.adyen.model.nexo.TransactionType
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.encoding.Base64

class TerminalClientTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private var ids = 0
    private val clock = Clock.fixed(Instant.parse("2026-09-30T01:02:03.456Z"), ZoneOffset.UTC)

    private fun client(
        application: PosApplication? = PosApplication("Mini mPOS", "1.0", "Mini mPOS", "Android", "13"),
        recovery: RecoveryPolicy = RecoveryPolicy(attempts = 3, intervalMillis = 10, maxInProgressChecks = 3),
        reply: (TerminalAPIRequest) -> Delivery,
    ) = TerminalClient(
        currencyDigits = testCurrencyDigits,
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
        Delivery.Answered(
            TerminalAPIResponse().apply {
                saleToPOIResponse =
                    SaleToPOIResponse().apply(body)
            },
        )

    private val empty = Delivery.Answered(null)

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

    private fun base64(json: String) = Base64.encode(json.toByteArray())

    private fun approval() =
        PaymentResponse().apply {
            response =
                result(
                    ResultType.SUCCESS,
                    additional =
                        base64(
                            """{"additionalData":{"pspReference":"PSP123","paymentMethodVariant":"mc_applepay",""" +
                                """"tokenization.storedPaymentMethodId":"TOKEN1",""" +
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
            amountMinor = 1250,
            currency = "AUD",
            merchantReference = "MP-1",
            shopperReference = "CUST-1",
            shopperEmail = "a@b.co",
            recurringProcessingModel = RecurringModel.CARD_ON_FILE,
            appPrintsReceipts = true,
            metadata = mapOf("customerReference" to "C1"),
            requestCardAlias = true,
        )

    @Test
    fun `native barcode session reads a single code and ends the same session`() =
        runTest {
            val terminal =
                client {
                    respond {
                        adminResponse =
                            AdminResponse().apply {
                                response =
                                    result(
                                        ResultType.SUCCESS,
                                        additional = base64("""{"Barcode":{"Data":"000190468703","Symbology":"QR_CODE"}}"""),
                                    )
                            }
                    }
                }
            val scanned = terminal.scanBarcode(42)
            assertThat(scanned).isInstanceOf(BarcodeScan.Read::class.java)
            assertThat((scanned as BarcodeScan.Read).code).isEqualTo("000190468703")
            assertThat(scanned.toString()).doesNotContain("000190468703")
            terminal.endBarcodeScan(42)
            val bodies = sent.map { String(Base64.decode(it.saleToPOIRequest.adminRequest.serviceIdentification)) }
            assertThat(bodies.first()).contains("\"Type\":\"Once\"")
            assertThat(bodies.last()).contains("\"Type\":\"End\"")
            assertThat(bodies).containsNoDuplicates()
            assertThat(sent.all { it.saleToPOIRequest.paymentRequest == null }).isTrue()

            val cancelledScan =
                client {
                    respond {
                        adminResponse = AdminResponse().apply { response = result(ResultType.FAILURE, ErrorConditionType.CANCEL) }
                    }
                }.scanBarcode(42)
            assertThat(cancelledScan).isEqualTo(BarcodeScan.NotRead)

            val blankScan =
                client {
                    respond {
                        adminResponse =
                            AdminResponse().apply {
                                response = result(ResultType.SUCCESS, additional = base64("""{"Barcode":{"Data":" "}}"""))
                            }
                    }
                }.scanBarcode(42)
            assertThat(blankScan).isEqualTo(BarcodeScan.NotRead)
            assertThat(client { empty }.scanBarcode(42)).isEqualTo(BarcodeScan.NotRead)
            assertThat(client { Delivery.Failed(UNREACHABLE) }.scanBarcode(42)).isEqualTo(BarcodeScan.NotRead)
        }

    @Test
    fun `scanned wallets preserve payment facts and constrain the stored value instrument`() =
        runTest {
            val payment = ScannedPayment("wechatpay_pos", "133341022926803846")
            client { respond { paymentResponse = approval() } }.pay(params.copy(scannedPayment = payment), "WALLET1")
            val request = sent.single().saleToPOIRequest
            val instrument = request.paymentRequest.paymentData.paymentInstrumentData
            assertThat(instrument.paymentInstrumentType.value()).isEqualTo("StoredValue")
            assertThat(instrument.storedValueAccountID.storedValueID).isEqualTo("133341022926803846")
            assertThat(instrument.storedValueAccountID.storedValueAccountType.value()).isEqualTo("Other")
            assertThat(instrument.storedValueAccountID.identificationType.value()).isEqualTo("BarCode")
            assertThat(instrument.storedValueAccountID.entryMode.map { it.value() }).containsExactly("Scanned")
            assertThat(request.paymentRequest.paymentTransaction.transactionConditions.allowedPaymentBrand).containsExactly("wechatpay_pos")
            assertThat(request.paymentRequest.paymentTransaction.amountsReq.requestedAmount).isEqualTo(BigDecimal("12.50"))
            assertThat(request.messageHeader.serviceID).isEqualTo("WALLET1")
            assertThat(payment.toString()).doesNotContain("133341022926803846")
            assertThrows(IllegalStateException::class.java) { payment.consume() }
        }

    @Test
    fun `unanswered scanned payment checks status without replaying its code`() =
        runTest {
            val payment = ScannedPayment("paypal_pos", "000190468703")
            val outcome =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) Delivery.Failed(Fault.TimedOut) else empty
                }.pay(params.copy(scannedPayment = payment), "WALLET2")
            assertThat(outcome).isInstanceOf(TransactionOutcome.Unknown::class.java)
            assertThat(sent.count { it.saleToPOIRequest.paymentRequest != null }).isEqualTo(1)
            assertThat(
                sent
                    .first()
                    .saleToPOIRequest.paymentRequest.paymentData.paymentInstrumentData.storedValueAccountID.storedValueID,
            ).isEqualTo("000190468703")
            assertThrows(IllegalStateException::class.java) { payment.consume() }
        }

    @Test
    fun `ordinary payments carry shopper email and references without saving a card`() =
        runTest {
            client { respond { paymentResponse = approval() } }.pay(params.copy(recurringProcessingModel = null, requestCardAlias = false))
            val saleData =
                sent
                    .single()
                    .saleToPOIRequest.paymentRequest.saleData
            assertThat(saleData.saleTransactionID.transactionID).isEqualTo("MP-1")
            assertThat(saleData.saleToAcquirerData.shopperReference).isEqualTo("CUST-1")
            assertThat(saleData.saleToAcquirerData.shopperEmail).isEqualTo("a@b.co")
            assertThat(saleData.saleToAcquirerData.metadata).containsExactly("customerReference", "C1")
            assertThat(saleData.saleToAcquirerData.recurringProcessingModel).isNull()
            assertThat(saleData.tokenRequestedType).isNull()
        }

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
            assertThat(acquirer.tenderOption).isEqualTo("ReceiptHandler")
            assertThat(acquirer.metadata).containsExactly("customerReference", "C1")
            assertThat(acquirer.applicationInfo.merchantApplication.name).isEqualTo("Mini mPOS")
            assertThat(request.paymentRequest.paymentTransaction.amountsReq.requestedAmount).isEqualTo(BigDecimal("12.50"))

            val details = (outcome as TransactionOutcome.Completed).details
            assertThat(sent.all { it.saleToPOIRequest.transactionStatusRequest == null }).isTrue()
            assertThat(details.success).isTrue()
            assertThat(details.decline).isNull()
            assertThat(details.pspReference).isEqualTo("PSP123")
            assertThat(details.poiTransactionId).isEqualTo("TENDER.PSP123")
            assertThat(details.poiTimestamp).isEqualTo("2026-09-30T01:02:03.456Z")
            assertThat(details.merchantReference).isEqualTo("MP-1")
            assertThat(details.amountMinor).isEqualTo(1250)
            assertThat(details.currency).isEqualTo("AUD")
            assertThat(details.paymentBrand).isEqualTo("mc")
            assertThat(details.paymentMethodVariant).isEqualTo("mc_applepay")
            assertThat(details.maskedPan).isEqualTo("541333 **** 9999")
            assertThat(details.entryMode).isEqualTo("Contactless")
            assertThat(details.approvalCode).isEqualTo("123456")
            assertThat(details.tokenization!!.storedPaymentMethodId).isEqualTo("TOKEN1")
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
            val wire = String(Base64.decode(preAuth.toBase64()))
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
            assertThat(details.message?.text).isEqualTo("Do Not Honor")
            assertThat(details.decline!!.advice).isEqualTo(RetryAdvice.DIFFERENT_PAYMENT_METHOD)
            assertThat(details.decline!!.cancelled).isFalse()
            assertThat(details.paymentBrand).isEqualTo("visa")
            assertThat(details.paymentMethodVariant).isNull()
            assertThat(details.approvalCode).isEqualTo("9")
            assertThat(details.tokenization!!.storedPaymentMethodId).isEqualTo("R1")
            assertThat(details.decline!!.busyServiceId).isNull()

            val busy =
                PaymentResponse().apply {
                    response =
                        result(ResultType.FAILURE, ErrorConditionType.BUSY, "message=ADMIN_MENU&serviceId=OTHER1")
                }
            val busyDetails = (client { respond { paymentResponse = busy } }.pay(params) as TransactionOutcome.Completed).details
            assertThat(busyDetails.decline!!.advice).isEqualTo(RetryAdvice.TERMINAL_BUSY)
            assertThat(busyDetails.decline!!.busyServiceId).isEqualTo("OTHER1")
            assertThat(busyDetails.message?.text).isEqualTo("ADMIN_MENU")

            val format =
                PaymentResponse().apply {
                    response =
                        result(ResultType.FAILURE, ErrorConditionType.MESSAGE_FORMAT, "errors=Currency%20missing")
                }
            assertThat((client { respond { paymentResponse = format } }.pay(params) as TransactionOutcome.Completed).details.message?.text)
                .isEqualTo("Currency missing")
        }

    @Test
    fun `unavailable service preserves decoded diagnostics for support`() =
        runTest {
            val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
            val traceParent = "00-$traceId-00f067aa0ba902b7-01"
            val declined =
                PaymentResponse().apply {
                    response =
                        result(
                            ResultType.FAILURE,
                            ErrorConditionType.UNAVAILABLE_SERVICE,
                            "refusalReason=Unavailable&message=Tap+to+Pay%3A+not%20enabled%26retry%3Dfalse" +
                                "&errorCode=APP%2F42&traceparent=$traceParent&shopperEmail=private%40example.com",
                        )
                }
            val details = (client { respond { paymentResponse = declined } }.pay(params) as TransactionOutcome.Completed).details
            assertThat(details.success).isFalse()
            assertThat(details.errorCondition).isEqualTo("UnavailableService")
            assertThat(details.refusalReason).isEqualTo("Unavailable")
            assertThat(details.message?.text).isEqualTo(
                "Unavailable\nTap to Pay: not enabled&retry=false\nerrorCode=APP/42\ntraceparent=$traceParent\ntraceId=$traceId",
            )
            assertThat(details.decline!!.advice).isEqualTo(RetryAdvice.NOT_ENABLED)
        }

    @Test
    fun `base64 additional response retains support diagnostics without other payment fields`() =
        runTest {
            val parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
            val json =
                """{"message":"Service unavailable","additionalData":{"errorCode":"42","traceparent":"$parent","token":"private"}}"""
            val declined =
                PaymentResponse().apply {
                    response = result(ResultType.FAILURE, ErrorConditionType.UNAVAILABLE_SERVICE, Base64.encode(json.toByteArray()))
                }
            val details = (client { respond { paymentResponse = declined } }.pay(params) as TransactionOutcome.Completed).details
            assertThat(details.message?.text)
                .isEqualTo("Service unavailable\nerrorCode=42\ntraceparent=$parent\ntraceId=4bf92f3577b34da6a3ce929d0e0e4736")
        }

    @Test
    fun `unreachable or rejecting terminals mean not processed`() =
        runTest {
            val unreachable = client { Delivery.Failed(UNREACHABLE) }.pay(params)
            assertThat((unreachable as TransactionOutcome.NotProcessed).fault).isEqualTo(UNREACHABLE)
            val rejected = client { Delivery.Failed(Fault.KeyRejected(null)) }.pay(params)
            assertThat(rejected).isEqualTo(TransactionOutcome.NotProcessed(Fault.KeyRejected(null)))
        }

    @Test
    fun `timeouts are settled with transaction status, polling while in progress`() =
        runTest {
            var calls = 0
            val outcome =
                client { request ->
                    calls++
                    when {
                        request.saleToPOIRequest.paymentRequest != null -> Delivery.Failed(Fault.TimedOut)
                        calls == 2 -> statusReply(result(ResultType.FAILURE, ErrorConditionType.IN_PROGRESS))
                        calls == 3 -> Delivery.Failed(Fault.TimedOut)
                        else -> statusReply(result(ResultType.SUCCESS), paymentBody(approval()))
                    }
                }.pay(params)
            assertThat(calls).isEqualTo(4)
            assertThat((outcome as TransactionOutcome.Completed).details.pspReference).isEqualTo("PSP123")
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
                        empty
                    } else {
                        statusReply(result(ResultType.FAILURE, ErrorConditionType.NOT_FOUND))
                    }
                }.pay(params)
            assertThat(notFound).isInstanceOf(TransactionOutcome.NotProcessed::class.java)
            val silent =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) Delivery.Failed(Fault.TimedOut) else empty
                }.pay(params)
            assertThat((silent as TransactionOutcome.Unknown).fault).isEqualTo(Fault.TimedOut)
            val busy =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) {
                        respond { }
                    } else {
                        statusReply(result(ResultType.FAILURE, ErrorConditionType.IN_PROGRESS))
                    }
                }.pay(params)
            assertThat((busy as TransactionOutcome.Unknown).fault).isEqualTo(Fault.StillInProgress)
            val successWithoutBody =
                client { request ->
                    if (request.saleToPOIRequest.paymentRequest != null) {
                        empty
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
                    RefundParams("TENDER.PSP123", "2026-09-29T11:04:00.000Z", "MP-R", 450, "AUD"),
                )
            val details = (partial as TransactionOutcome.Completed).details
            assertThat(details.pspReference).isEqualTo("REF1")
            assertThat(details.merchantReference).isEqualTo("MP-R")
            assertThat(details.amountMinor).isEqualTo(450)
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
            assertThrows(IllegalArgumentException::class.java) { RefundParams("T", "t", "R", 100) }
        }

    @Test
    fun `reversal recovery maps reversal responses`() =
        runTest {
            val outcome =
                client { request ->
                    if (request.saleToPOIRequest.reversalRequest != null) {
                        Delivery.Failed(Fault.TimedOut)
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
            val client = client { Delivery.Failed(UNREACHABLE) }
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
                        5 -> empty
                        else -> Delivery.Failed(UNREACHABLE)
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
            assertThat(client.print(listOf(job))).isEqualTo(PrintOutcome.Failed(Fault.TerminalRejected(null), noPrinter = true))
            assertThat(
                client.print(listOf(job)),
            ).isEqualTo(PrintOutcome.Failed(Fault.TerminalRejected(ExternalText("Paper jam")), noPrinter = false))
            assertThat(client.print(listOf(job))).isEqualTo(PrintOutcome.Failed(Fault.UnreadableReply(), noPrinter = false))
            assertThat(client.print(listOf(job))).isEqualTo(PrintOutcome.Failed(UNREACHABLE, noPrinter = false))
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
            assertThat(client { empty }.diagnose().reachable).isFalse()
            assertThat(client { Delivery.Failed(UNREACHABLE) }.diagnose().fault).isEqualTo(UNREACHABLE)
        }

    @Test
    fun `explicit status checks settle earlier transactions`() =
        runTest {
            val settled =
                client { statusReply(result(ResultType.SUCCESS), paymentBody(approval())) }.status("OLD1")
            assertThat((settled as TransactionOutcome.Completed).details.pspReference).isEqualTo("PSP123")
            assertThat(client { empty }.status("OLD2", TransactionKind.REFUND)).isInstanceOf(TransactionOutcome.Unknown::class.java)
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
            val plain = params.copy(appPrintsReceipts = false, metadata = emptyMap(), requestCardAlias = false)
            client(application = null) { empty }.pay(plain)
            val saleData =
                sent
                    .first()
                    .saleToPOIRequest.paymentRequest.saleData
            assertThat(saleData.saleToAcquirerData.applicationInfo.adyenLibrary.name).isEqualTo("adyen-java-api-library")
            assertThat(saleData.saleToAcquirerData.tenderOption).isNull()
            assertThat(saleData.tokenRequestedType).isNull()
        }

    private companion object {
        val UNREACHABLE = Fault.Unreachable("192.168.1.20:8443", terminal = true)
    }
}

class ShopperDisplayAndTotalsTest {
    private var ids = 0
    private val clock = Clock.fixed(Instant.parse("2026-09-30T01:02:03.456Z"), ZoneOffset.UTC)
    private val params = PaymentParams(1250, "AUD", "MP-1")
    private val empty = Delivery.Answered(null)
    private val money = TerminalMoney(testCurrencyDigits)

    private fun client(reply: (TerminalAPIRequest) -> Delivery) =
        TerminalClient(
            currencyDigits = testCurrencyDigits,
            transport = TerminalTransport { request, _ -> reply(request) },
            identity = TerminalIdentity("POS1", "S1F2-000158213605014"),
            application = PosApplication("Mini mPOS", "1.0", "Mini mPOS", "Android", "13"),
            clock = clock,
            newServiceId = { "S${++ids}" },
            recovery = RecoveryPolicy(attempts = 3, intervalMillis = 10, maxInProgressChecks = 3),
        )

    private fun respond(body: SaleToPOIResponse.() -> Unit) =
        Delivery.Answered(
            TerminalAPIResponse().apply {
                saleToPOIResponse = SaleToPOIResponse().apply(body)
            },
        )

    private fun result(
        type: ResultType,
        condition: ErrorConditionType? = null,
        additional: String? = null,
    ) = Response().apply {
        result = type
        errorCondition = condition
        additionalResponse = additional
    }

    private fun approval() =
        PaymentResponse().apply {
            response = result(ResultType.SUCCESS)
            saleData = SaleData().apply { saleTransactionID = TerminalClient.transaction("MP-1", clock.instant()) }
            poiData = POIData().apply { poiTransactionID = TerminalClient.transaction("TENDER.PSP123", clock.instant()) }
            paymentResult =
                PaymentResult().apply {
                    amountsResp =
                        AmountsResp().apply {
                            currency = "AUD"
                            authorizedAmount = BigDecimal("12.50")
                        }
                }
        }

    private fun inputClient(
        res: Response,
        confirmed: Boolean? = null,
        text: String? = null,
        digit: String? = null,
    ) = client {
        respond {
            adminResponse = AdminResponse().apply { response = result(ResultType.SUCCESS) }
            displayResponse =
                DisplayResponse().apply {
                    outputResult += OutputResult().apply { response = result(ResultType.SUCCESS) }
                }
            paymentResponse = approval()
            inputResponse =
                InputResponse().apply {
                    inputResult =
                        InputResult().apply {
                            response = res
                            input =
                                Input().apply {
                                    setConfirmedFlag(confirmed)
                                    textInput = text
                                    digitInput = digit
                                }
                        }
                }
        }
    }

    @Test
    fun `validates payment and tip model preconditions`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentParams(100, "AUD", "MP-1", scannedPayment = ScannedPayment("wechatpay_pos", "123"), preAuthorisation = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentParams(100, "AUD", "MP-1", terminalTip = TerminalTipOptions(), preAuthorisation = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentParams(100, "AUD", "MP-1", allowPartialAuthorisation = true, preAuthorisation = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentParams(100, "AUD", "MP-1", askGiving = true, preAuthorisation = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PaymentParams(
                100,
                "AUD",
                "MP-1",
                scannedPayment = ScannedPayment("wechatpay_pos", "123"),
                cardEntryOverride = CardEntryOverride.MOTO,
            )
        }
        assertThrows(IllegalArgumentException::class.java) { TerminalTipSuggestions.Percentages(emptyList(), true) }
        assertThrows(IllegalArgumentException::class.java) { TerminalTipSuggestions.Percentages(listOf(1, 2, 3, 4), true) }
        assertThrows(IllegalArgumentException::class.java) { TerminalTipSuggestions.Percentages(listOf(0), true) }
        assertThrows(IllegalArgumentException::class.java) { TerminalTipSuggestions.Percentages(listOf(101), true) }
        assertThat(TerminalTipSuggestions.Percentages(listOf(1, 100, 10), false).allowCustom).isFalse()
        assertThat(TerminalTipSuggestions.Percentages(listOf(10), true).allowCustom).isTrue()
        assertThrows(IllegalArgumentException::class.java) { TerminalTipSuggestions.FixedAmounts(emptyList(), true) }
        assertThrows(IllegalArgumentException::class.java) { TerminalTipSuggestions.FixedAmounts(listOf(1, 2, 3, 4), true) }
        assertThrows(IllegalArgumentException::class.java) { TerminalTipSuggestions.FixedAmounts(listOf(0), true) }
        assertThat(TerminalTipSuggestions.FixedAmounts(listOf(1, 2, 3), false).allowCustom).isFalse()
        assertThat(TerminalTipSuggestions.FixedAmounts(listOf(1), true).allowCustom).isTrue()
        assertThrows(IllegalArgumentException::class.java) {
            TerminalTipOptions(suggestions = TerminalTipSuggestions.Percentages(listOf(10)), amountToTipOnMinor = 0)
        }
        assertThrows(IllegalArgumentException::class.java) { TerminalTipOptions(amountToTipOnMinor = 100) }
        assertThat(
            TerminalTipOptions(
                suggestions = TerminalTipSuggestions.Percentages(listOf(10)),
                amountToTipOnMinor = 1,
            ).amountToTipOnMinor,
        ).isEqualTo(1L)
    }

    @Test
    fun `validates scanned payment and shopper flow preconditions`() {
        assertThrows(IllegalArgumentException::class.java) { ScannedPayment(" ", "123") }
        assertThrows(IllegalArgumentException::class.java) { ScannedPayment("wechatpay_pos", "") }
        assertThrows(IllegalArgumentException::class.java) { ScannedPayment("wechatpay_pos", "1".repeat(129)) }
        assertThrows(IllegalArgumentException::class.java) { ScannedPayment("wechatpay_pos", "/") }
        assertThrows(IllegalArgumentException::class.java) { ScannedPayment("wechatpay_pos", ":") }
        assertThat(ScannedPayment("wechatpay_pos", "09".repeat(64)).consume()).isEqualTo("09".repeat(64))
        assertThrows(IllegalArgumentException::class.java) { ShopperSessionFlow(sessionId = 0) }
        assertThat(ShopperSessionFlow(sessionId = 1).sessionId).isEqualTo(1)
        val discardable = ScannedPayment("wechatpay_pos", "123")
        assertThat(discardable.toString()).isEqualTo("ScannedPayment(brand=wechatpay_pos, code=[redacted])")
        discardable.discard()
        assertThrows(IllegalStateException::class.java) { discardable.consume() }
        assertThat(
            VirtualReceipt(header = "Order", lines = emptyList(), totalMinor = 100, currency = "EUR").toXml(money = money),
        ).contains("""<screen name="virtual-receipt.xslt">""")
        assertThat(PaymentParams(100, "EUR", "M", saleReferenceId = "SR-1").saleReferenceId).isEqualTo("SR-1")
        assertThat(RecoveryPolicy(intervalMillis = 2_000L).intervalMillis).isEqualTo(2_000L)
        assertThat(PrintOutcome.Failed(Fault.Unsupported, noPrinter = false).noPrinter).isFalse()
        val sampleFlow =
            ShopperSessionFlow(
                confirmLabel = "Yes",
                cancelLabel = "No",
                emailPrompt = "Email?",
                qrReceipt = "https://adyen.link/1",
                qrHeader = "Scan",
            )
        assertThat(sampleFlow.confirmLabel).isEqualTo("Yes")
        assertThat(sampleFlow.cancelLabel).isEqualTo("No")
        assertThat(sampleFlow.emailPrompt).isEqualTo("Email?")
        assertThat(sampleFlow.qrReceipt).isEqualTo("https://adyen.link/1")
        assertThat(sampleFlow.qrHeader).isEqualTo("Scan")
        assertThat(sampleFlow.virtualReceipt).isNull()
    }

    @Test
    fun `converts print outputs with invalid barcodes and justified italic text`() {
        assertThat(PrintOutput().toPrintJob()).isNull()
        val invalidBarcode =
            PrintOutput().apply {
                outputContent =
                    OutputContent().apply {
                        outputFormat = OutputFormatType.BAR_CODE
                        outputBarcode = OutputBarcode().apply { barcodeValue = "%zz" }
                    }
            }
        assertThat(invalidBarcode.toPrintJob()).isEqualTo(PrintJob.QrCode("%zz"))
        val oddText =
            PrintOutput().apply {
                outputContent =
                    OutputContent().apply {
                        outputFormat = OutputFormatType.TEXT
                        outputText =
                            listOf(
                                OutputText().apply {
                                    text = "line"
                                    alignment = AlignmentType.JUSTIFIED
                                    characterStyle = CharacterStyleType.ITALIC
                                    isEndOfLineFlag = false
                                },
                            )
                    }
            }
        assertThat(oddText.toPrintJob()).isEqualTo(PrintJob.Text(listOf(PrintLine.Text("line", PrintAlign.LEFT, PrintStyle.NORMAL))))
    }

    @Test
    fun `reconcile and getTotals sort multi-currency totals and handle reverse debit and errors`() =
        runTest {
            val usdTotals =
                TransactionTotals().apply {
                    paymentCurrency = "USD"
                    paymentTotals.addAll(
                        listOf(
                            PaymentTotals().apply {
                                transactionType = TransactionType.DEBIT
                                transactionAmount = BigDecimal("20.00")
                                transactionCount = BigInteger.valueOf(2)
                            },
                            PaymentTotals().apply {
                                transactionType = TransactionType.REVERSE_DEBIT
                                transactionAmount = BigDecimal("5.00")
                                transactionCount = BigInteger.valueOf(1)
                            },
                            PaymentTotals().apply {
                                transactionType = TransactionType.FAILED
                                transactionAmount = BigDecimal("99.00")
                                transactionCount = BigInteger.valueOf(3)
                            },
                        ),
                    )
                }
            val audTotals =
                TransactionTotals().apply {
                    paymentCurrency = "AUD"
                    paymentTotals.add(
                        PaymentTotals().apply {
                            transactionType = TransactionType.CREDIT
                            transactionAmount = BigDecimal("4.00")
                            transactionCount = BigInteger.valueOf(1)
                        },
                    )
                }
            val okClient =
                client {
                    respond {
                        getTotalsResponse =
                            GetTotalsResponse().apply {
                                response = result(ResultType.SUCCESS)
                                poiReconciliationID = "42"
                                transactionTotals.addAll(listOf(usdTotals, audTotals))
                            }
                    }
                }
            val answered = okClient.getTotals() as TerminalTotalsOutcome.Answered
            assertThat(answered.poiReconciliationId).isEqualTo("42")
            assertThat(answered.closedPeriod).isFalse()
            assertThat(answered.totals)
                .containsExactly(
                    TerminalCurrencyTotals("AUD", 0, 0, 400, 1),
                    TerminalCurrencyTotals("USD", 2000, 2, 500, 1),
                ).inOrder()
            val firstTotal = answered.totals.first()
            assertThat(firstTotal.currency).isEqualTo("AUD")
            assertThat(firstTotal.debitMinor to firstTotal.debitCount).isEqualTo(0L to 0)
            assertThat(firstTotal.creditMinor to firstTotal.creditCount).isEqualTo(400L to 1)
        }

    @Test
    fun `reconcile surfaces failure faults and unreadable replies`() =
        runTest {
            val failedResp =
                client {
                    respond {
                        reconciliationResponse =
                            ReconciliationResponse().apply {
                                response = result(ResultType.FAILURE, additional = "message=Busy")
                            }
                    }
                }
            val recFailed = failedResp.reconcile() as TerminalTotalsOutcome.Failed
            assertThat(recFailed).isEqualTo(TerminalTotalsOutcome.Failed(Fault.TerminalRejected(ExternalText("Busy"))))
            assertThat(recFailed.fault).isEqualTo(Fault.TerminalRejected(ExternalText("Busy")))
            assertThat(client { empty }.reconcile()).isEqualTo(TerminalTotalsOutcome.Failed(Fault.UnreadableReply()))
            assertThat(client { Delivery.Failed(UNREACHABLE) }.reconcile()).isEqualTo(TerminalTotalsOutcome.Failed(UNREACHABLE))
        }

    @Test
    fun `shopper display virtual receipts and QR codes handle responses through pay`() =
        runTest {
            val receipt =
                VirtualReceipt(
                    header = "A & B <Shop>",
                    lines = listOf(VirtualReceiptLine(2, "Item \"1\" 'x'", "AUD", 900)),
                    subtotalMinor = 818,
                    taxMinor = 82,
                    totalMinor = 900,
                    currency = "AUD",
                )
            assertThat(receipt.header).isEqualTo("A & B <Shop>")
            assertThat(receipt.lines.single()).isEqualTo(VirtualReceiptLine(2, "Item \"1\" 'x'", "AUD", 900))
            assertThat(receipt.subtotalMinor to receipt.taxMinor).isEqualTo(818L to 82L)
            assertThat(receipt.totalMinor to receipt.currency).isEqualTo(900L to "AUD")
            assertThat(receipt.totalLabel).isEqualTo("Total")
            assertThat(receipt.toXml("M400-123456789", money)).contains("virtual_receipt02.xslt")
            assertThat(receipt.toXml("M400-123456789", money)).contains("&amp;")
            assertThat(receipt.toXml("S1F2-123456789", money)).contains("virtual-receipt.xslt")

            val displayOk =
                client {
                    respond {
                        adminResponse = AdminResponse().apply { response = result(ResultType.SUCCESS) }
                        displayResponse =
                            DisplayResponse().apply {
                                outputResult.add(OutputResult().apply { response = result(ResultType.SUCCESS) })
                            }
                        paymentResponse = approval()
                    }
                }
            for (flow in listOf(
                ShopperSessionFlow(virtualReceipt = receipt, qrReceipt = "https://adyen.link/PL1", qrHeader = "Scan"),
                ShopperSessionFlow(qrReceipt = "http://adyen.link/PL1"),
                ShopperSessionFlow(qrReceipt = "MPR1*data", qrHeader = "Header"),
            )) {
                val outcome = displayOk.pay(params.copy(shopperFlow = flow)) as TransactionOutcome.Completed
                assertThat(outcome.details.success).isTrue()
            }

            val displayFailed =
                client { req ->
                    if (req.saleToPOIRequest.paymentRequest != null) {
                        respond { paymentResponse = approval() }
                    } else {
                        Delivery.Failed(UNREACHABLE)
                    }
                }.pay(params.copy(shopperFlow = ShopperSessionFlow(virtualReceipt = receipt, qrReceipt = "https://adyen.link/PL1")))
                    as TransactionOutcome.Completed
            assertThat(displayFailed.details.success).isTrue()
        }

    @Test
    fun `input prompts handle confirmation text and rating responses through pay`() =
        runTest {
            val confirmFlow = ShopperSessionFlow(confirmationPrompt = "Title" to "Sub")
            val confirmed =
                inputClient(result(ResultType.SUCCESS), confirmed = true)
                    .pay(params.copy(shopperFlow = confirmFlow)) as TransactionOutcome.Completed
            assertThat(confirmed.details.success).isTrue()

            for (cancelledClient in listOf(
                inputClient(result(ResultType.SUCCESS), confirmed = false),
                inputClient(result(ResultType.FAILURE, ErrorConditionType.CANCEL)),
                inputClient(result(ResultType.FAILURE, ErrorConditionType.ABORTED)),
            )) {
                val cancelled = cancelledClient.pay(params.copy(shopperFlow = confirmFlow)) as TransactionOutcome.Completed
                assertThat(cancelled.details.decline?.cancelled).isTrue()
            }

            val busyConfirm =
                inputClient(result(ResultType.FAILURE, ErrorConditionType.BUSY))
                    .pay(params.copy(shopperFlow = confirmFlow)) as TransactionOutcome.NotProcessed
            assertThat(busyConfirm.fault).isInstanceOf(Fault.TerminalRejected::class.java)
            assertThat((client { empty }.pay(params.copy(shopperFlow = confirmFlow)) as TransactionOutcome.NotProcessed).fault)
                .isEqualTo(Fault.UnreadableReply())
            assertThat(
                (client { Delivery.Failed(UNREACHABLE) }.pay(params.copy(shopperFlow = confirmFlow)) as TransactionOutcome.NotProcessed)
                    .fault,
            ).isEqualTo(UNREACHABLE)

            val emailFlow = ShopperSessionFlow(emailPrompt = "Email")
            val enteredText =
                inputClient(result(ResultType.SUCCESS), text = "a@b.co")
                    .pay(params.copy(shopperEmail = null, shopperFlow = emailFlow)) as TransactionOutcome.Completed
            assertThat(enteredText.details.collectedEmail).isEqualTo("a@b.co")

            for (noEmailClient in listOf(
                inputClient(result(ResultType.SUCCESS), text = " "),
                inputClient(result(ResultType.FAILURE, ErrorConditionType.CANCEL)),
                inputClient(result(ResultType.FAILURE, ErrorConditionType.BUSY)),
            )) {
                val noEmail =
                    noEmailClient.pay(params.copy(shopperEmail = null, shopperFlow = emailFlow)) as TransactionOutcome.Completed
                assertThat(noEmail.details.collectedEmail).isNull()
            }

            val ratingFlow = ShopperSessionFlow(ratingPrompt = "Rate" to "Sub")
            val ratingZero =
                inputClient(result(ResultType.SUCCESS), digit = "0")
                    .pay(params.copy(shopperFlow = ratingFlow)) as TransactionOutcome.Completed
            assertThat(ratingZero.details.shopperRating).isEqualTo(0)
            val ratingTen =
                inputClient(result(ResultType.SUCCESS), digit = "10")
                    .pay(params.copy(shopperFlow = ShopperSessionFlow(ratingPrompt = "Rate" to null))) as TransactionOutcome.Completed
            assertThat(ratingTen.details.shopperRating).isEqualTo(10)
            for (invalid in listOf("-1", "11", "abc")) {
                val invalidRating =
                    inputClient(result(ResultType.SUCCESS), digit = invalid)
                        .pay(params.copy(shopperFlow = ratingFlow)) as TransactionOutcome.Completed
                assertThat(invalidRating.details.shopperRating).isNull()
            }
            val cancelledRating =
                inputClient(result(ResultType.FAILURE, ErrorConditionType.CANCEL))
                    .pay(params.copy(shopperFlow = ratingFlow)) as TransactionOutcome.Completed
            assertThat(cancelledRating.details.shopperRating).isNull()
        }

    @Test
    fun `shopper flow handles cancellation null input decline and missing giving donation`() =
        runTest {
            var cancelledServiceId: String? = null
            val cancelledOutcome =
                inputClient(result(ResultType.FAILURE, ErrorConditionType.CANCEL))
                    .pay(
                        params.copy(shopperFlow = ShopperSessionFlow(confirmationPrompt = "Confirm" to "Sub")),
                        serviceId = "CUSTOM-S1",
                        onStarted = { cancelledServiceId = it },
                    ) as TransactionOutcome.Completed
            assertThat(cancelledServiceId).isNull()
            assertThat(cancelledOutcome.details.success).isFalse()
            assertThat(cancelledOutcome.details.errorCondition).isEqualTo("Cancel")
            assertThat(cancelledOutcome.details.decline?.cancelled).isTrue()
            assertThat(cancelledOutcome.details.merchantReference).isEqualTo("MP-1")

            val nullInputClient =
                client {
                    respond {
                        inputResponse =
                            InputResponse().apply {
                                inputResult = InputResult().apply { response = result(ResultType.SUCCESS) }
                            }
                    }
                }
            val nullInputOutcome =
                nullInputClient.pay(params.copy(shopperFlow = ShopperSessionFlow(confirmationPrompt = "Title" to "Sub")))
                    as TransactionOutcome.NotProcessed
            assertThat(nullInputOutcome.fault).isEqualTo(Fault.UnreadableReply())

            val flowDeclined =
                client {
                    respond {
                        paymentResponse =
                            PaymentResponse().apply {
                                response = result(ResultType.FAILURE, ErrorConditionType.REFUSAL)
                            }
                    }
                }.pay(params.copy(shopperFlow = ShopperSessionFlow()), serviceId = "FLOW-DEC") as TransactionOutcome.Completed
            assertThat(flowDeclined.details.success).isFalse()

            val givingMissing =
                client { req ->
                    if (req.saleToPOIRequest.transactionStatusRequest != null) {
                        empty
                    } else {
                        respond {
                            paymentResponse =
                                approval().apply {
                                    response = result(ResultType.SUCCESS, additional = "adyen_giving_service_id=GIVING-MISS")
                                }
                        }
                    }
                }.pay(params.copy(shopperFlow = ShopperSessionFlow()), serviceId = "FLOW-GIV") as TransactionOutcome.Completed
            assertThat(givingMissing.details.givingServiceId).isEqualTo("GIVING-MISS")
            assertThat(givingMissing.details.donationMinor).isNull()
        }

    @Test
    fun `emits expected nexo wire fields for display and QR requests`() =
        runTest {
            val captured = mutableListOf<SaleToPOIRequest>()
            val capturingClient =
                client { req ->
                    captured += req.saleToPOIRequest
                    respond {
                        adminResponse = AdminResponse().apply { response = result(ResultType.SUCCESS) }
                        displayResponse =
                            DisplayResponse().apply {
                                outputResult += OutputResult().apply { response = result(ResultType.SUCCESS) }
                            }
                        paymentResponse = approval()
                    }
                }
            val receipt = VirtualReceipt(header = "Order", lines = emptyList(), totalMinor = 100, currency = "EUR")
            capturingClient.pay(
                params.copy(shopperFlow = ShopperSessionFlow(virtualReceipt = receipt, qrReceipt = "RAW TEXT", qrHeader = "Head")),
            )
            val displays = captured.mapNotNull { it.displayRequest }
            val vrOut = displays[0].displayOutput.single()
            assertThat(vrOut.device to vrOut.infoQualify).isEqualTo(DeviceType.CUSTOMER_DISPLAY to InfoQualifyType.DISPLAY)
            assertThat(vrOut.outputContent.outputFormat).isEqualTo(OutputFormatType.XHTML)
            assertThat(String(vrOut.outputContent.outputXHTML, Charsets.UTF_8)).contains("<receipt>")

            val qrOuts = displays[1].displayOutput
            assertThat(qrOuts).hasSize(2)
            assertThat(qrOuts[0].outputContent.outputFormat).isEqualTo(OutputFormatType.TEXT)
            assertThat(
                qrOuts[0]
                    .outputContent.outputText
                    .single()
                    .text,
            ).isEqualTo("Head")
            assertThat(qrOuts[1].minimumDisplayTime to qrOuts[1].isResponseRequiredFlag).isEqualTo(10 to true)
            assertThat(qrOuts[1].outputContent.outputFormat).isEqualTo(OutputFormatType.BAR_CODE)
            assertThat(qrOuts[1].outputContent.outputBarcode.barcodeType).isEqualTo(BarcodeType.QRCODE)
            assertThat(qrOuts[1].outputContent.outputBarcode.barcodeValue).isEqualTo("RAW+TEXT")

            capturingClient.pay(params.copy(shopperFlow = ShopperSessionFlow(qrReceipt = "http://adyen.link/1", qrHeader = " ")))
            val httpQr =
                captured
                    .mapNotNull { it.displayRequest }
                    .last()
                    .displayOutput
                    .single()
            assertThat(httpQr.outputContent.outputBarcode.barcodeValue).isEqualTo("http://adyen.link/1")
        }

    @Test
    fun `emits expected nexo wire fields for input requests`() =
        runTest {
            val captured = mutableListOf<SaleToPOIRequest>()
            val capturingClient =
                client { req ->
                    captured += req.saleToPOIRequest
                    respond {
                        adminResponse = AdminResponse().apply { response = result(ResultType.SUCCESS) }
                        paymentResponse = approval()
                        inputResponse =
                            InputResponse().apply {
                                inputResult =
                                    InputResult().apply {
                                        response = result(ResultType.SUCCESS)
                                        input =
                                            Input().apply {
                                                setConfirmedFlag(true)
                                                textInput = "x@y.z"
                                                digitInput = "8"
                                            }
                                    }
                            }
                    }
                }
            capturingClient.pay(
                params.copy(
                    shopperEmail = null,
                    shopperFlow =
                        ShopperSessionFlow(
                            confirmationPrompt = "Title" to "Body",
                            cancelLabel = "No",
                            confirmLabel = "Yes",
                            emailPrompt = "Email",
                            ratingPrompt = "Rate" to " ",
                        ),
                ),
            )
            val inputs = captured.mapNotNull { it.inputRequest }
            val confirmReq = inputs[0]
            assertThat(confirmReq.displayOutput.device).isEqualTo(DeviceType.CUSTOMER_DISPLAY)
            assertThat(confirmReq.displayOutput.infoQualify).isEqualTo(InfoQualifyType.DISPLAY)
            assertThat(confirmReq.displayOutput.outputContent.outputFormat).isEqualTo(OutputFormatType.TEXT)
            assertThat(confirmReq.displayOutput.outputContent.predefinedContent.referenceID).isEqualTo("GetConfirmation")
            assertThat(
                confirmReq.displayOutput.outputContent.outputText
                    .map { it.text },
            ).containsExactly("Title", "Body", "No", "Yes")
            assertThat(confirmReq.inputData.device).isEqualTo(DeviceType.CUSTOMER_INPUT)
            assertThat(confirmReq.inputData.infoQualify).isEqualTo(InfoQualifyType.INPUT)
            assertThat(confirmReq.inputData.inputCommand).isEqualTo(InputCommandType.GET_CONFIRMATION)
            assertThat(confirmReq.inputData.maxInputTime).isEqualTo(BigInteger.valueOf(60))
            assertThat(confirmReq.inputData.defaultInputString).isNull()

            assertThat(inputs[1].inputData.defaultInputString).isEqualTo("name@domain.com")
            assertThat(
                inputs[2]
                    .displayOutput.outputContent.outputText
                    .map { it.text },
            ).containsExactly("Rate")
        }

    @Test
    fun `emits expected nexo wire fields for admin and giving requests`() =
        runTest {
            val captured = mutableListOf<SaleToPOIRequest>()
            val capturingClient =
                client { req ->
                    captured += req.saleToPOIRequest
                    respond {
                        adminResponse = AdminResponse().apply { response = result(ResultType.SUCCESS) }
                        paymentResponse =
                            approval().apply {
                                response = result(ResultType.SUCCESS, additional = "adyen_giving_service_id=GIV-9")
                            }
                        transactionStatusResponse =
                            TransactionStatusResponse().apply {
                                response = result(ResultType.SUCCESS)
                                repeatedMessageResponse =
                                    RepeatedMessageResponse().apply {
                                        repeatedResponseMessageBody =
                                            RepeatedResponseMessageBody().apply {
                                                paymentResponse =
                                                    approval().apply {
                                                        paymentResult.amountsResp.authorizedAmount = BigDecimal("3.00")
                                                    }
                                            }
                                    }
                            }
                    }
                }
            val outcome =
                capturingClient.pay(params.copy(shopperFlow = ShopperSessionFlow(sessionId = 7))) as TransactionOutcome.Completed
            assertThat(outcome.details.donationMinor).isEqualTo(300)
            val admins = captured.mapNotNull { it.adminRequest }
            assertThat(
                String(
                    Base64.decode(admins.first().serviceIdentification),
                    Charsets.UTF_8,
                ),
            ).contains("Begin")
            assertThat(
                String(
                    Base64.decode(admins.last().serviceIdentification),
                    Charsets.UTF_8,
                ),
            ).contains("End")

            val givReq = captured.mapNotNull { it.transactionStatusRequest }.single()
            assertThat(givReq.isReceiptReprintFlag).isNotEqualTo(true)
            assertThat(givReq.messageReference.messageCategory).isEqualTo(MessageCategoryType.PAYMENT)
            assertThat(givReq.messageReference.saleID).isEqualTo(TerminalClient.GIVING_SALE_ID)
            assertThat(givReq.messageReference.serviceID).isEqualTo("GIV-9")
        }

    private companion object {
        val UNREACHABLE = Fault.Unreachable("192.168.1.20:8443", terminal = true)
    }
}
