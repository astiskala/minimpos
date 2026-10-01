package io.minimpos.terminal.simulator

import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.CardData
import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DiagnosisResponse
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.EntryModeType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.GlobalStatusType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.OutputText
import com.adyen.model.nexo.POIData
import com.adyen.model.nexo.POIStatus
import com.adyen.model.nexo.PaymentAcquirerData
import com.adyen.model.nexo.PaymentInstrumentData
import com.adyen.model.nexo.PaymentInstrumentType
import com.adyen.model.nexo.PaymentReceipt
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.PaymentToken
import com.adyen.model.nexo.PaymentType
import com.adyen.model.nexo.PrintOutput
import com.adyen.model.nexo.PrintResponse
import com.adyen.model.nexo.PrinterStatusType
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalRequest
import com.adyen.model.nexo.ReversalResponse
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TokenRequestedType
import com.adyen.model.nexo.TransactionIdentification
import com.adyen.model.nexo.TransactionStatusRequest
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.terminal.SaleToAcquirerData
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import io.minimpos.terminal.client.PrintJob
import io.minimpos.terminal.client.TerminalClient
import io.minimpos.terminal.client.toPrintJob
import io.minimpos.terminal.transport.TerminalProtocolException
import io.minimpos.terminal.transport.TerminalTransport
import kotlinx.coroutines.delay
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlin.time.Duration

/** How the [TerminalSimulator] answers a payment. */
enum class SimulatedOutcome {
    /** Approved; the card is also stored when the payment asked for tokenization. */
    APPROVE,

    /** Declined by the issuer: ErrorCondition `Refusal` with refusal reason `Not enough balance`. */
    DECLINE,

    /** Cancelled by the shopper on the terminal: ErrorCondition `Cancel`. */
    CANCEL,

    /**
     * Refused with ErrorCondition `Busy` before any card is read, naming [TerminalSimulator.BUSY_SERVICE_ID] as the
     * transaction the terminal is busy with.
     */
    BUSY,

    /**
     * Approved, but the reply is lost ([java.net.SocketTimeoutException]), so the client has to recover the result with
     * a transaction status check.
     */
    TIMEOUT,

    /** Approved four times out of five, otherwise declined as with [DECLINE]. */
    RANDOM,
}

/**
 * The simulator's behaviour, read afresh for every request so settings changes apply to the next payment.
 *
 * @property outcome How payments end.
 * @property delayMillis How long a payment takes, standing in for the shopper; an abort ends the wait early.
 * @property hasPrinter False makes print requests fail with ErrorCondition `UnavailableDevice` and diagnoses report no
 *   printer status, like a terminal without a printer.
 * @property signatureRequired True asks for the shopper's signature on the merchant copy of approved payments.
 */
data class SimulatorConfig(
    val outcome: SimulatedOutcome = SimulatedOutcome.APPROVE,
    val delayMillis: Long = 2_500,
    val hasPrinter: Boolean = true,
    val signatureRequired: Boolean = false,
)

/**
 * An in-process stand-in for an Adyen terminal, so every flow can be exercised on an emulator or phone. Responses
 * mimic real Terminal API payloads, including receipt data and tokenization details. Requests and responses pass
 * through the Adyen library's JSON serialisation, exactly as they would on the way to and from a real terminal.
 *
 * It handles payments, reversals, aborts, prints, diagnoses and transaction status checks, and throws
 * [TerminalProtocolException] for any other request. Finished payments and reversals are remembered in memory for
 * status checks, so after a restart they are reported as not found. The timeout passed to [send] is ignored.
 */
class TerminalSimulator(
    /** The current settings, read at the start of each request. */
    private val config: () -> SimulatorConfig,
    /** Stamps transactions and receipts. */
    private val clock: Clock = Clock.systemUTC(),
    /** Picks cards, references and, for [SimulatedOutcome.RANDOM], the outcome. */
    private val random: Random = Random.Default,
    /** The time zone of the date and time printed on receipts. */
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Receives every print request that succeeds, read back the way a terminal prints it, e.g. to show it on screen. */
    private val onPrint: (PrintJob) -> Unit = {},
) : TerminalTransport {
    private val gson = TerminalAPIGsonBuilder.create()
    private val completed = ConcurrentHashMap<String, RepeatedResponseMessageBody>()
    private val aborted = ConcurrentHashMap.newKeySet<String>()

    override suspend fun send(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): TerminalAPIResponse? {
        val received = gson.fromJson(gson.toJson(request), TerminalAPIRequest::class.java)
        val response = handle(received) ?: return null
        return gson.fromJson(gson.toJson(response), TerminalAPIResponse::class.java)
    }

    private suspend fun handle(request: TerminalAPIRequest): TerminalAPIResponse? {
        val message = request.saleToPOIRequest ?: throw TerminalProtocolException("Empty request")
        val header = message.messageHeader
        val settings = config()
        return when {
            message.paymentRequest != null -> {
                payment(header, message.paymentRequest, settings)
            }

            message.reversalRequest != null -> {
                respond(header) { reversalResponse = reversal(header, message.reversalRequest) }
            }

            message.printRequest != null -> {
                respond(header) { printResponse = print(message.printRequest.printOutput, settings) }
            }

            message.transactionStatusRequest != null -> {
                respond(header) {
                    transactionStatusResponse =
                        status(message.transactionStatusRequest)
                }
            }

            message.abortRequest != null -> {
                message.abortRequest.messageReference
                    ?.serviceID
                    ?.let(aborted::add)
                null
            }

            message.diagnosisRequest != null -> {
                respond(header) {
                    diagnosisResponse =
                        DiagnosisResponse().apply {
                            response = Response().apply { result = ResultType.SUCCESS }
                            poiStatus =
                                POIStatus().apply {
                                    globalStatus = GlobalStatusType.OK
                                    setPEDOKFlag(true)
                                    printerStatus = if (settings.hasPrinter) PrinterStatusType.OK else null
                                }
                        }
                }
            }

            else -> {
                throw TerminalProtocolException("The simulator does not support this request")
            }
        }
    }

    private suspend fun payment(
        header: MessageHeader,
        request: PaymentRequest,
        settings: SimulatorConfig,
    ): TerminalAPIResponse {
        val serviceId = header.serviceID.orEmpty()
        var waited = 0L
        while (waited < settings.delayMillis && serviceId !in aborted) {
            delay(POLL_MILLIS)
            waited += POLL_MILLIS
        }
        val outcome =
            when {
                aborted.remove(serviceId) -> {
                    null
                }

                settings.outcome == SimulatedOutcome.RANDOM -> {
                    if (random.nextInt(PERCENT) < RANDOM_APPROVAL_PERCENT) SimulatedOutcome.APPROVE else SimulatedOutcome.DECLINE
                }

                else -> {
                    settings.outcome
                }
            }
        val response = paymentResponse(request, outcome, settings)
        completed[serviceId] = RepeatedResponseMessageBody().apply { paymentResponse = response }
        if (outcome == SimulatedOutcome.TIMEOUT) throw SocketTimeoutException("Simulated timeout")
        return respond(header) { paymentResponse = response }
    }

    private fun paymentResponse(
        request: PaymentRequest,
        outcome: SimulatedOutcome?,
        settings: SimulatorConfig,
    ): PaymentResponse {
        if (outcome == SimulatedOutcome.BUSY) return busy(request)
        val amount = request.paymentTransaction.amountsReq
        val payment =
            SimulatedPayment(
                card = CARDS[random.nextInt(CARDS.size)],
                psp = randomString(PSP_LENGTH, UPPER),
                tender = randomString(TENDER_PREFIX_LENGTH, ALNUM) + randomString(TENDER_DIGITS, DIGITS),
                authCode = randomString(AUTH_CODE_LENGTH, DIGITS),
                merchantReference =
                    request.saleData
                        ?.saleTransactionID
                        ?.transactionID
                        .orEmpty(),
                amountText = "${amount.currency} ${amount.requestedAmount.toPlainString()}",
                approved = outcome == SimulatedOutcome.APPROVE || outcome == SimulatedOutcome.TIMEOUT,
            )
        val additional =
            linkedMapOf(
                "pspReference" to payment.psp,
                "merchantReference" to payment.merchantReference,
                "paymentMethod" to payment.card.brand,
                "cardSummary" to payment.card.maskedPan.takeLast(4),
                "posEntryMode" to "CLESS_CHIP",
            )
        val refusal = if (payment.approved) null else refusal(outcome)
        if (refusal == null) {
            additional["authCode"] = payment.authCode
            additional += tokenization(payment.card, request.saleData?.saleToAcquirerData)
        } else {
            additional["refusalReason"] = refusal.second
        }
        return PaymentResponse().apply {
            response =
                Response().apply {
                    result = if (payment.approved) ResultType.SUCCESS else ResultType.FAILURE
                    errorCondition = refusal?.first
                    additionalResponse = base64Json(additional)
                }
            saleData = SaleData().apply { saleTransactionID = request.saleData?.saleTransactionID }
            poiData = poiData(payment.tender, payment.psp)
            paymentResult = paymentResult(payment, amount.currency, amount.requestedAmount)
            paymentReceipt +=
                listOf(
                    receipt(DocumentQualifierType.CUSTOMER_RECEIPT, "CARDHOLDER COPY", payment, signature = false),
                    receipt(
                        DocumentQualifierType.CASHIER_RECEIPT,
                        "MERCHANT COPY",
                        payment,
                        signature = payment.approved && settings.signatureRequired,
                    ),
                )
        }
    }

    private fun refusal(outcome: SimulatedOutcome?): Pair<ErrorConditionType, String> =
        when (outcome) {
            SimulatedOutcome.CANCEL -> ErrorConditionType.CANCEL to "Cancelled by shopper"

            null -> ErrorConditionType.ABORTED to "Transaction aborted"

            SimulatedOutcome.APPROVE,
            SimulatedOutcome.DECLINE,
            SimulatedOutcome.BUSY,
            SimulatedOutcome.TIMEOUT,
            SimulatedOutcome.RANDOM,
            -> ErrorConditionType.REFUSAL to "Not enough balance"
        }

    /** The stored-card details a terminal returns when the request asked to tokenize the card for a shopper. */
    private fun tokenization(
        card: SimulatedCard,
        acquirerData: SaleToAcquirerData?,
    ): Map<String, String> {
        val shopperReference = acquirerData?.shopperReference
        val model = acquirerData?.recurringProcessingModel
        if (shopperReference == null || model == null) return emptyMap()
        val token = randomString(PSP_LENGTH, DIGITS)
        return buildMap {
            put("alias", card.alias)
            put("recurring.recurringDetailReference", token)
            put("recurring.shopperReference", shopperReference)
            put("recurringProcessingModel", model.toString())
            put("tokenization.shopperReference", shopperReference)
            put("tokenization.storedPaymentMethodId", token)
            put("tokenization.store.operationType", "created")
            acquirerData.shopperEmail?.let { put("shopperEmail", it) }
        }
    }

    private fun paymentResult(
        payment: SimulatedPayment,
        requestedCurrency: String,
        requestedAmount: BigDecimal,
    ) = PaymentResult().apply {
        paymentType = PaymentType.NORMAL
        paymentInstrumentData =
            PaymentInstrumentData().apply {
                paymentInstrumentType = PaymentInstrumentType.CARD
                cardData =
                    CardData().apply {
                        entryMode += EntryModeType.CONTACTLESS
                        maskedPAN = payment.card.maskedPan
                        paymentBrand = payment.card.brand
                        paymentToken =
                            PaymentToken().apply {
                                tokenRequestedType = TokenRequestedType.CUSTOMER
                                tokenValue = payment.card.alias
                            }
                    }
            }
        amountsResp =
            AmountsResp().apply {
                currency = requestedCurrency
                authorizedAmount = if (payment.approved) requestedAmount else BigDecimal.ZERO
            }
        setOnlineFlag(true)
        paymentAcquirerData = PaymentAcquirerData().apply { approvalCode = payment.authCode.takeIf { payment.approved } }
    }

    /** A busy terminal never starts the payment (no card, no PSP reference), but names the transaction it is busy with. */
    private fun busy(request: PaymentRequest) =
        PaymentResponse().apply {
            response =
                Response().apply {
                    result = ResultType.FAILURE
                    errorCondition = ErrorConditionType.BUSY
                    additionalResponse =
                        base64Json(
                            mapOf(
                                "message" to "Forbidden Request, Service Dialogue PaymentRequest is in Progress",
                                "serviceId" to BUSY_SERVICE_ID,
                            ),
                        )
                }
            saleData = SaleData().apply { saleTransactionID = request.saleData?.saleTransactionID }
        }

    private fun reversal(
        header: MessageHeader,
        request: ReversalRequest,
    ): ReversalResponse {
        val psp = randomString(PSP_LENGTH, UPPER)
        val merchantReference =
            request.saleData
                ?.saleTransactionID
                ?.transactionID
                .orEmpty()
        val amount = request.reversedAmount
        val response =
            ReversalResponse().apply {
                response =
                    Response().apply {
                        result = ResultType.SUCCESS
                        additionalResponse = base64Json(mapOf("pspReference" to psp, "merchantReference" to merchantReference))
                    }
                poiData = poiData(randomString(TENDER_PREFIX_LENGTH, ALNUM) + randomString(TENDER_DIGITS, DIGITS), psp)
                reversedAmount = amount
                paymentReceipt +=
                    PaymentReceipt().apply {
                        documentQualifier = DocumentQualifierType.CUSTOMER_RECEIPT
                        outputContent =
                            text(
                                listOf(
                                    line("filler"),
                                    line("txtype", "REFUND", bold = true),
                                    line("mref", "Reference", merchantReference),
                                    line("totalAmount", "Refund", amount?.toPlainString() ?: "Full amount"),
                                    line("filler"),
                                    line("approved", "REFUND REQUESTED", bold = true),
                                ),
                            )
                    }
            }
        completed[header.serviceID.orEmpty()] = RepeatedResponseMessageBody().apply { reversalResponse = response }
        return response
    }

    private fun print(
        output: PrintOutput,
        settings: SimulatorConfig,
    ): PrintResponse {
        if (!settings.hasPrinter) {
            return PrintResponse().apply {
                documentQualifier = output.documentQualifier
                response =
                    Response().apply {
                        result = ResultType.FAILURE
                        errorCondition = ErrorConditionType.UNAVAILABLE_DEVICE
                        additionalResponse = "message=The%20device%20has%20no%20printer."
                    }
            }
        }
        output.toPrintJob()?.let(onPrint)
        return PrintResponse().apply {
            documentQualifier = output.documentQualifier
            response = Response().apply { result = ResultType.SUCCESS }
        }
    }

    private fun status(request: TransactionStatusRequest): TransactionStatusResponse {
        val body = request.messageReference?.serviceID?.let(completed::get)
        return TransactionStatusResponse().apply {
            if (body == null) {
                response =
                    Response().apply {
                        result = ResultType.FAILURE
                        errorCondition = ErrorConditionType.NOT_FOUND
                    }
            } else {
                response = Response().apply { result = ResultType.SUCCESS }
                messageReference = request.messageReference
                repeatedMessageResponse = RepeatedMessageResponse().apply { repeatedResponseMessageBody = body }
            }
        }
    }

    private fun poiData(
        tender: String,
        psp: String,
    ) = POIData().apply { poiTransactionID = TerminalClient.transaction("$tender.$psp", clock.instant()) }

    private fun receipt(
        qualifier: DocumentQualifierType,
        copyLabel: String,
        payment: SimulatedPayment,
        signature: Boolean,
    ): PaymentReceipt {
        val card = payment.card
        val approved = payment.approved
        val now = clock.instant().atZone(zone)
        val lines =
            buildList {
                add(line("header1", "Mini mPOS Simulator"))
                add(line("filler"))
                add(line("txdate", "Date", DateTimeFormatter.ofPattern("dd/MM/yyyy").format(now)))
                add(line("txtime", "Time", DateTimeFormatter.ofPattern("HH:mm:ss").format(now)))
                add(line("filler"))
                add(line("txtype", "Payment", bold = true))
                add(line("totalAmount", "TOTAL", payment.amountText, bold = true))
                add(line("filler"))
                add(line("cardholderHeader", copyLabel))
                add(line("paymentMethod", card.label))
                add(line("pan", "Card", card.maskedPan.takeLast(9)))
                add(line("posEntryMode", "Entry", "Contactless"))
                add(line("aid", "AID", card.aid))
                add(line("mid", "MID", "SIMULATOR"))
                add(line("tid", "TID", "00000001"))
                add(line("txRef", "Tender", payment.tender))
                add(line("mref", "Reference", payment.merchantReference))
                add(line("filler"))
                add(line(if (approved) "approved" else "refused", if (approved) "APPROVED" else "DECLINED", bold = true))
                if (signature) {
                    add(line("filler"))
                    add(line("sigline", "Signature"))
                    add(line("signature", "______________________"))
                }
                add(line("filler"))
                add(line("thanks", "Please retain for your records"))
            }
        return PaymentReceipt().apply {
            documentQualifier = qualifier
            setRequiredSignatureFlag(signature)
            outputContent = text(lines)
        }
    }

    private fun text(lines: List<OutputText>) =
        OutputContent().apply {
            outputFormat = OutputFormatType.TEXT
            outputText = lines
        }

    private fun line(
        key: String,
        name: String? = null,
        value: String? = null,
        bold: Boolean = false,
    ): OutputText {
        val fields = listOfNotNull("key" to key, name?.let { "name" to it }, value?.let { "value" to it })
        return OutputText().apply {
            text = formEncode(fields.toMap())
            if (bold) characterStyle = CharacterStyleType.BOLD
            setEndOfLineFlag(true)
        }
    }

    /** Requests carry SaleToAcquirerData as Base64 JSON, so the terminal answers AdditionalResponse in kind. */
    private fun base64Json(values: Map<String, String>): String =
        Base64.getEncoder().encodeToString(gson.toJson(values).toByteArray(Charsets.UTF_8))

    private fun formEncode(values: Map<String, String>): String =
        values.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun randomString(
        length: Int,
        alphabet: String,
    ) = (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    private fun respond(
        header: MessageHeader,
        body: SaleToPOIResponse.() -> Unit,
    ) = TerminalAPIResponse().apply {
        saleToPOIResponse =
            SaleToPOIResponse().apply {
                messageHeader =
                    MessageHeader().apply {
                        protocolVersion = header.protocolVersion
                        messageClass = header.messageClass
                        messageCategory = header.messageCategory
                        messageType = MessageType.RESPONSE
                        serviceID = header.serviceID
                        saleID = header.saleID
                        poiid = header.poiid
                    }
                body()
            }
    }

    private data class SimulatedCard(
        val brand: String,
        val label: String,
        val maskedPan: String,
        val aid: String,
        val alias: String,
    )

    private data class SimulatedPayment(
        val card: SimulatedCard,
        val psp: String,
        val tender: String,
        val authCode: String,
        val merchantReference: String,
        val amountText: String,
        val approved: Boolean,
    )

    /** Fixed values that tests and callers can rely on. */
    companion object {
        /** The ServiceID a [SimulatedOutcome.BUSY] payment names as the transaction the terminal is busy with. */
        const val BUSY_SERVICE_ID = "SIMBUSY001"
        private const val POLL_MILLIS = 100L
        private const val PERCENT = 100
        private const val RANDOM_APPROVAL_PERCENT = 80
        private const val PSP_LENGTH = 16
        private const val TENDER_PREFIX_LENGTH = 4
        private const val TENDER_DIGITS = 15
        private const val AUTH_CODE_LENGTH = 6
        private const val DIGITS = "0123456789"
        private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val CARDS =
            listOf(
                SimulatedCard("mc", "MasterCard", "541333 **** 9999", "A0000000041010", "M469509594859802"),
                SimulatedCard("visa", "Visa", "411111 **** 1111", "A0000000031010", "K421000000000001"),
            )
    }
}
