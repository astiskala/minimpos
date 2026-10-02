package io.minimpos.terminal.client

import com.adyen.model.nexo.AbortRequest
import com.adyen.model.nexo.AmountsReq
import com.adyen.model.nexo.DiagnosisRequest
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageReference
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.OriginalPOITransaction
import com.adyen.model.nexo.PaymentReceipt
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentTransaction
import com.adyen.model.nexo.PrintRequest
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalReasonType
import com.adyen.model.nexo.ReversalRequest
import com.adyen.model.nexo.ReversalResponse
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TokenRequestedType
import com.adyen.model.nexo.TransactionIdentification
import com.adyen.model.nexo.TransactionStatusRequest
import com.adyen.model.terminal.SaleToAcquirerData
import com.adyen.model.terminal.SaleToAcquirerData.RecurringProcessingModelEnum
import com.adyen.model.terminal.TerminalAPIRequest
import io.minimpos.terminal.parse.AdditionalResponseParser
import io.minimpos.terminal.parse.ReceiptParser
import io.minimpos.terminal.transport.Delivery
import io.minimpos.terminal.transport.TerminalTransport
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.xml.datatype.DatatypeConstants
import javax.xml.datatype.DatatypeFactory
import javax.xml.datatype.XMLGregorianCalendar
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * High-level Terminal API operations on the Adyen library's nexo models. Payments and refunds that time out or lose
 * their connection are settled with transaction status requests (Adyen "No result received"), so callers get a
 * definite answer whenever the terminal can give one.
 *
 * Every request is a nexo `SaleToPOIRequest` whose `MessageHeader` carries the [identity] and a ServiceID. The
 * ServiceID names the request: a later abort or transaction status check refers to a payment by it, so it must be
 * unique per terminal (see [randomServiceId]).
 *
 * All operations suspend until the terminal answers and are safe to call from any thread. Cancelling a call only stops
 * waiting: a payment the terminal has already started carries on, so use [abort] to stop it.
 */
class TerminalClient(
    /** Delivers the messages: the real terminal over HTTPS, or the in-process simulator. */
    private val transport: TerminalTransport,
    /** The SaleID and POIID put in every message header. */
    val identity: TerminalIdentity,
    /** Sent as application info on payments and refunds; null only in tests. */
    private val application: PosApplication? = null,
    /** Stamps the `SaleTransactionID` of payments and refunds. */
    private val clock: Clock = Clock.systemUTC(),
    /** Makes the ServiceIDs of requests the caller does not name itself (aborts, prints, diagnoses, status checks). */
    private val newServiceId: () -> String = ::randomServiceId,
    /** How long and how often to check the status of a payment or refund whose response went missing. */
    private val recovery: RecoveryPolicy = RecoveryPolicy(),
    /** Adyen: check the transaction status if a local payment response has not arrived after 120 seconds. */
    private val transactionTimeout: Duration = DEFAULT_TRANSACTION_TIMEOUT,
    /** The timeout for requests the terminal answers without the shopper: abort, print, diagnosis and status checks. */
    private val shortTimeout: Duration = 30.seconds,
) {
    /**
     * Takes a card payment: the terminal asks the shopper to present a card and the call returns when the payment is
     * approved, declined or cancelled, or once the transaction timeout (by default [DEFAULT_TRANSACTION_TIMEOUT]) has
     * passed without an answer. A [PaymentParams.preAuthorisation] only holds the amount (Adyen pre-authorisation with
     * manual capture); [refund] then cancels it while it is uncaptured.
     *
     * If the terminal cannot be reached, or rejects the request, nothing was charged and the outcome is
     * [TransactionOutcome.NotProcessed]. If the request was sent but the answer went missing (timeout, broken connection,
     * unreadable reply), the payment's status is checked as the [RecoveryPolicy] says until the terminal gives a result;
     * a payment that stays unconfirmed is [TransactionOutcome.Unknown].
     *
     * Callers that persist the [serviceId] before sending can later reconcile the payment with [status].
     *
     * @param params What to charge and how.
     * @param serviceId The payment's ServiceID; pass one to record it before the terminal is called.
     * @param onStarted Called with the ServiceID right before the request is sent.
     * @return The terminal's answer, or why there is none.
     */
    suspend fun pay(
        params: PaymentParams,
        serviceId: String = newServiceId(),
        onStarted: (serviceId: String) -> Unit = {},
    ): TransactionOutcome {
        val request =
            message(MessageClassType.SERVICE, MessageCategoryType.PAYMENT, serviceId).apply {
                paymentRequest =
                    PaymentRequest().apply {
                        saleData =
                            SaleData().apply {
                                saleTransactionID = transaction(params.merchantReference, clock.instant())
                                saleToAcquirerData =
                                    acquirerData {
                                        shopperReference = params.shopperReference
                                        shopperEmail = params.shopperEmail
                                        recurringProcessingModel =
                                            params.recurringProcessingModel?.let { RecurringProcessingModelEnum.valueOf(it.name) }
                                        tenderOption = params.tenderOptions.takeIf { it.isNotEmpty() }?.joinToString(",")
                                        metadata = params.metadata.takeIf { it.isNotEmpty() }
                                        if (params.preAuthorisation) {
                                            authorisationType = PRE_AUTH
                                            additionalData = mapOf(MANUAL_CAPTURE to "true")
                                        }
                                    }
                                if (params.requestCardAlias) tokenRequestedType = TokenRequestedType.CUSTOMER
                            }
                        paymentTransaction =
                            PaymentTransaction().apply {
                                amountsReq =
                                    AmountsReq().apply {
                                        currency = params.currency
                                        requestedAmount = params.amount
                                    }
                            }
                    }
            }
        onStarted(serviceId)
        return execute(serviceId, MessageCategoryType.PAYMENT, request) { it.paymentResponse?.let(::mapPayment) }
    }

    /**
     * Refunds an earlier payment with a nexo reversal (reason `MerchantCancel`), which refers to the original
     * transaction instead of asking for the card. A partial refund also states the original currency in
     * `SaleToAcquirerData`, as Adyen requires. Adyen processes it as a cancel-or-refund: a full reversal of a payment
     * that is not captured yet (such as a pre-authorisation) cancels it instead. Timeouts and lost replies are settled
     * with status checks exactly as for [pay].
     *
     * @param params The payment to refund, and how much.
     * @param serviceId The refund's ServiceID; pass one to record it before the terminal is called.
     * @return The terminal's answer, or why there is none.
     * @throws IllegalArgumentException If [RefundParams.originalTimestamp] is not an XML date-time.
     */
    suspend fun refund(
        params: RefundParams,
        serviceId: String = newServiceId(),
    ): TransactionOutcome {
        val request =
            message(MessageClassType.SERVICE, MessageCategoryType.REVERSAL, serviceId).apply {
                reversalRequest =
                    ReversalRequest().apply {
                        originalPOITransaction =
                            OriginalPOITransaction().apply {
                                poiTransactionID =
                                    TransactionIdentification().apply {
                                        transactionID = params.originalTransactionId
                                        timeStamp = parseTimestamp(params.originalTimestamp)
                                    }
                            }
                        reversalReason = ReversalReasonType.MERCHANT_CANCEL
                        reversedAmount = params.amount
                        saleData =
                            SaleData().apply {
                                saleTransactionID = transaction(params.merchantReference, clock.instant())
                                // Partial refunds must state the original currency.
                                saleToAcquirerData = acquirerData { currency = params.currency?.takeIf { params.amount != null } }
                            }
                    }
            }
        return execute(serviceId, MessageCategoryType.REVERSAL, request) { it.reversalResponse?.let(::mapReversal) }
    }

    /**
     * Asks the terminal to stop the transaction identified by [serviceId] (e.g. a payment waiting for a card, or the one
     * it reports being busy with). Best effort; I/O errors are ignored. Whether the transaction was actually stopped is
     * reported by the transaction itself, with ErrorCondition `Aborted`.
     *
     * @param serviceId The ServiceID of the transaction to stop.
     * @param kind What kind of transaction it is; Adyen documents aborting payments.
     */
    suspend fun abort(
        serviceId: String,
        kind: TransactionKind = TransactionKind.PAYMENT,
    ) {
        val request =
            message(MessageClassType.SERVICE, MessageCategoryType.ABORT, newServiceId()).apply {
                abortRequest =
                    AbortRequest().apply {
                        abortReason = "MerchantAbort"
                        messageReference =
                            MessageReference().apply {
                                messageCategory = kind.category()
                                saleID = identity.saleId
                                serviceID = serviceId
                                poiid = identity.poiId
                            }
                    }
            }
        // Whatever the delivery, the payment result (aborted or not) still arrives on the original request.
        transport.send(wrap(request), shortTimeout)
    }

    /**
     * Prints on the terminal's own printer. Each job is a separate nexo print request (response mode `PrintEnd`, so the
     * terminal answers once the paper is out); sending them back to back makes text and QR codes come out on one slip.
     * Text jobs are printed as `Document`, QR codes as a `CustomerReceipt` bar code with URL-encoded content. Stops at
     * the first failure.
     *
     * @param jobs The documents to print, in order.
     * @return [PrintOutcome.Printed], or the first failure. I/O errors are reported as failures, not thrown.
     */
    suspend fun print(jobs: List<PrintJob>): PrintOutcome = jobs.firstNotNullOfOrNull { printFailure(it) } ?: PrintOutcome.Printed

    /** Prints one job; null when it printed. */
    private suspend fun printFailure(job: PrintJob): PrintOutcome.Failed? {
        val request =
            message(MessageClassType.DEVICE, MessageCategoryType.PRINT, newServiceId()).apply {
                printRequest = PrintRequest().apply { printOutput = job.toNexo() }
            }
        val response =
            when (val delivery = transport.send(wrap(request), shortTimeout)) {
                is Delivery.Answered -> {
                    delivery.response
                        ?.saleToPOIResponse
                        ?.printResponse
                        ?.response
                }

                is Delivery.Failed -> {
                    return PrintOutcome.Failed(delivery.reason, noPrinter = false)
                }
            } ?: return PrintOutcome.Failed("No print response from the terminal", noPrinter = false)
        if (response.result == ResultType.SUCCESS) return null
        val message = AdditionalResponseParser.parse(response.additionalResponse)["message"] ?: "Printing failed"
        val noPrinter =
            response.errorCondition == ErrorConditionType.UNAVAILABLE_DEVICE || message.contains("no printer", ignoreCase = true)
        return PrintOutcome.Failed(message, noPrinter)
    }

    /**
     * Checks that the terminal is reachable and accepts our shared key, without charging anything: a nexo diagnosis
     * request with `HostDiagnosisFlag` false, so only the terminal is checked, not its connection to Adyen. Also reports
     * the terminal's overall status and whether it has a printer.
     *
     * @return The result; I/O errors (unreachable host, untrusted certificate, wrong key) are reported in it, not thrown.
     */
    suspend fun diagnose(): DiagnosisResult {
        val request =
            message(MessageClassType.SERVICE, MessageCategoryType.DIAGNOSIS, newServiceId()).apply {
                diagnosisRequest = DiagnosisRequest().apply { setHostDiagnosisFlag(false) }
            }
        val response =
            when (val delivery = transport.send(wrap(request), shortTimeout)) {
                is Delivery.Answered -> delivery.response?.saleToPOIResponse?.diagnosisResponse
                is Delivery.Failed -> return DiagnosisResult(reachable = false, message = delivery.reason)
            } ?: return DiagnosisResult(reachable = false, message = "No diagnosis response from the terminal")
        val additional = AdditionalResponseParser.parse(response.response?.additionalResponse)
        return DiagnosisResult(
            reachable = response.response?.result == ResultType.SUCCESS,
            message = additional["message"],
            globalStatus = response.poiStatus?.globalStatus?.value(),
            printerStatus = response.poiStatus?.printerStatus?.value(),
        )
    }

    /**
     * One transaction status check for an earlier payment or reversal (e.g. to settle an "unknown" sale later). The
     * terminal looks the transaction up by our SaleID and its ServiceID and repeats its original response, receipts
     * included.
     *
     * @param serviceId The ServiceID the payment or refund was sent with.
     * @param kind Whether it was a payment or a refund.
     * @return [TransactionOutcome.Completed] (marked as recovered) with the original result;
     *   [TransactionOutcome.NotProcessed] if the terminal has no record of it; otherwise [TransactionOutcome.Unknown],
     *   also while the transaction is still in progress. I/O errors are not thrown.
     */
    suspend fun status(
        serviceId: String,
        kind: TransactionKind = TransactionKind.PAYMENT,
    ): TransactionOutcome =
        (checkStatus(serviceId, kind.category()) as? StatusCheck.Settled)?.outcome
            ?: TransactionOutcome.Unknown(serviceId, "The terminal could not confirm the result yet")

    private suspend fun execute(
        serviceId: String,
        category: MessageCategoryType,
        request: SaleToPOIRequest,
        extract: (SaleToPOIResponse) -> TransactionDetails?,
    ): TransactionOutcome {
        val response =
            when (val delivery = transport.send(wrap(request), transactionTimeout)) {
                is Delivery.Answered -> delivery.response
                is Delivery.NotSent -> return TransactionOutcome.NotProcessed(serviceId, delivery.reason)
                is Delivery.MaybeSent -> return recover(serviceId, category, delivery.reason)
            }
        val details =
            response?.saleToPOIResponse?.let(extract) ?: return recover(serviceId, category, "Unexpected response")
        return TransactionOutcome.Completed(serviceId, details)
    }

    private suspend fun recover(
        serviceId: String,
        category: MessageCategoryType,
        reason: String?,
    ): TransactionOutcome {
        var unanswered = 0
        var inProgress = 0
        while (unanswered < recovery.attempts && inProgress < recovery.maxInProgressChecks) {
            delay(recovery.intervalMillis)
            when (val check = checkStatus(serviceId, category)) {
                is StatusCheck.Settled -> return check.outcome
                StatusCheck.InProgress -> inProgress++
                StatusCheck.NoAnswer -> unanswered++
            }
        }
        return TransactionOutcome.Unknown(serviceId, reason ?: "No response from the terminal")
    }

    private sealed interface StatusCheck {
        data class Settled(
            val outcome: TransactionOutcome,
        ) : StatusCheck

        /** The terminal is still waiting for the shopper or the issuer. */
        data object InProgress : StatusCheck

        data object NoAnswer : StatusCheck
    }

    private suspend fun checkStatus(
        serviceId: String,
        category: MessageCategoryType,
    ): StatusCheck {
        val delivery = transport.send(wrap(statusRequest(serviceId, category)), shortTimeout)
        val status =
            (delivery as? Delivery.Answered)?.response?.saleToPOIResponse?.transactionStatusResponse ?: return StatusCheck.NoAnswer
        val response = status.response
        return when {
            response?.result == ResultType.SUCCESS -> {
                val body = status.repeatedMessageResponse?.repeatedResponseMessageBody
                val details = body?.paymentResponse?.let(::mapPayment) ?: body?.reversalResponse?.let(::mapReversal)
                details?.let { StatusCheck.Settled(TransactionOutcome.Completed(serviceId, it, recovered = true)) } ?: StatusCheck.NoAnswer
            }

            response?.errorCondition == ErrorConditionType.NOT_FOUND -> {
                StatusCheck.Settled(TransactionOutcome.NotProcessed(serviceId, "The terminal has no record of this transaction"))
            }

            response?.errorCondition == ErrorConditionType.IN_PROGRESS -> {
                StatusCheck.InProgress
            }

            else -> {
                StatusCheck.NoAnswer
            }
        }
    }

    private fun statusRequest(
        serviceId: String,
        category: MessageCategoryType,
    ) = message(MessageClassType.SERVICE, MessageCategoryType.TRANSACTION_STATUS, newServiceId()).apply {
        transactionStatusRequest =
            TransactionStatusRequest().apply {
                // Ask for the receipts again, so a recovered payment can be printed like any other.
                setReceiptReprintFlag(true)
                documentQualifier += listOf(DocumentQualifierType.CASHIER_RECEIPT, DocumentQualifierType.CUSTOMER_RECEIPT)
                messageReference =
                    MessageReference().apply {
                        messageCategory = category
                        saleID = identity.saleId
                        serviceID = serviceId
                    }
            }
    }

    private fun acquirerData(configure: SaleToAcquirerData.() -> Unit): SaleToAcquirerData =
        SaleToAcquirerData().apply {
            application?.let { applicationInfo = it.toApplicationInfo() }
            configure()
        }

    private fun message(
        messageClass: MessageClassType,
        category: MessageCategoryType,
        serviceId: String,
    ) = SaleToPOIRequest().apply {
        messageHeader =
            MessageHeader().apply {
                protocolVersion = PROTOCOL_VERSION
                this.messageClass = messageClass
                messageCategory = category
                messageType = MessageType.REQUEST
                serviceID = serviceId
                saleID = identity.saleId
                poiid = identity.poiId
            }
    }

    private fun wrap(request: SaleToPOIRequest) = TerminalAPIRequest().apply { saleToPOIRequest = request }

    private fun TransactionKind.category(): MessageCategoryType =
        when (this) {
            TransactionKind.PAYMENT -> MessageCategoryType.PAYMENT
            TransactionKind.REFUND -> MessageCategoryType.REVERSAL
        }

    /** Terminal API formats and the mapping of nexo responses to [TransactionDetails]. */
    companion object {
        /** The nexo protocol version sent in every message header. */
        const val PROTOCOL_VERSION = "3.0"

        /** How long to wait for a payment or refund response before checking its status, as Adyen advises. */
        val DEFAULT_TRANSACTION_TIMEOUT: Duration = 120.seconds
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * Adyen's `authorisationType` for an amount that can be adjusted and is captured later; the simulator reads it
         * the way a terminal does.
         */
        internal const val PRE_AUTH = "PreAuth"

        /** The `additionalData` key that leaves a payment uncaptured until it is captured manually. */
        private const val MANUAL_CAPTURE = "manualCapture"
        private const val SERVICE_ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val SERVICE_ID_LENGTH = 10

        /** Android has no built-in DatatypeFactory; Xerces provides it (see the build file). */
        private val datatypes: DatatypeFactory by lazy { DatatypeFactory.newInstance() }

        /** Formats [instant] as a Terminal API timestamp: UTC with milliseconds, e.g. `2026-01-02T03:04:05.006Z`. */
        fun formatTimestamp(instant: Instant): String = TIMESTAMP.format(instant)

        /** [instant] as the `XMLGregorianCalendar` the nexo models use, in the format of [formatTimestamp]. */
        fun timestamp(instant: Instant): XMLGregorianCalendar = datatypes.newXMLGregorianCalendar(formatTimestamp(instant))

        /**
         * Parses a timestamp received from the terminal (e.g. [TransactionDetails.poiTimestamp]) without changing its
         * precision or time zone, so it can be sent back exactly as received.
         *
         * @throws IllegalArgumentException If [value] is not an XML date-time.
         */
        fun parseTimestamp(value: String): XMLGregorianCalendar = datatypes.newXMLGregorianCalendar(value.trim())

        /**
         * The instant of a timestamp received from the terminal, as [parseTimestamp] reads it; null when [value] is not
         * an XML date-time or has no time zone (then it names no single instant).
         */
        fun instantOf(value: String): Instant? =
            runCatching { parseTimestamp(value) }
                .getOrNull()
                ?.takeIf { it.timezone != DatatypeConstants.FIELD_UNDEFINED }
                ?.toGregorianCalendar()
                ?.toInstant()

        /** A nexo `TransactionIdentification`: a transaction's ID plus the time it happened ([at]). */
        fun transaction(
            id: String,
            at: Instant,
        ) = TransactionIdentification().apply {
            transactionID = id
            timeStamp = timestamp(at)
        }

        /**
         * A new random ServiceID. Adyen requires 1-10 alphanumeric characters, unique per terminal within 48 hours; ten
         * random characters from 36 make a repeat practically impossible.
         */
        fun randomServiceId(): String =
            (1..SERVICE_ID_LENGTH)
                .map {
                    SERVICE_ID_CHARS[Random.nextInt(SERVICE_ID_CHARS.length)]
                }.joinToString("")

        internal fun mapPayment(response: PaymentResponse): TransactionDetails {
            val additional = AdditionalResponseParser.parse(response.response?.additionalResponse)
            val card = response.paymentResult?.paymentInstrumentData?.cardData
            val storedId =
                additional["tokenization.storedPaymentMethodId"] ?: additional["recurring.recurringDetailReference"]
            return details(
                response = response.response,
                additional = additional,
                poi = response.poiData?.poiTransactionID,
                receipts = response.paymentReceipt,
            ).copy(
                merchantReference = response.saleData?.saleTransactionID?.transactionID,
                amount = response.paymentResult?.amountsResp?.authorizedAmount,
                currency = response.paymentResult?.amountsResp?.currency,
                paymentBrand = card?.paymentBrand ?: additional["paymentMethod"],
                paymentMethodVariant = additional["paymentMethodVariant"],
                maskedPan = card?.maskedPAN,
                entryMode = card?.entryMode?.firstOrNull()?.value() ?: additional["posEntryMode"],
                approvalCode = response.paymentResult?.paymentAcquirerData?.approvalCode ?: additional["authCode"],
                tokenization =
                    storedId?.let {
                        Tokenization(
                            storedPaymentMethodId = it,
                            shopperReference =
                                additional["tokenization.shopperReference"] ?: additional["recurring.shopperReference"],
                            operationType = additional["tokenization.store.operationType"],
                            cardAlias = card?.paymentToken?.tokenValue ?: additional["alias"],
                        )
                    },
            )
        }

        internal fun mapReversal(response: ReversalResponse): TransactionDetails {
            val additional = AdditionalResponseParser.parse(response.response?.additionalResponse)
            return details(
                response = response.response,
                additional = additional,
                poi = response.poiData?.poiTransactionID,
                receipts = response.paymentReceipt,
            ).copy(
                merchantReference = additional["merchantReference"],
                amount = response.reversedAmount,
            )
        }

        private fun details(
            response: Response?,
            additional: Map<String, String>,
            poi: TransactionIdentification?,
            receipts: List<PaymentReceipt>?,
        ): TransactionDetails {
            val success = response?.result == ResultType.SUCCESS
            val refusalReason = additional["refusalReason"]
            return TransactionDetails(
                success = success,
                errorCondition = response?.errorCondition?.value(),
                message = refusalReason ?: additional["message"] ?: additional["errors"] ?: additional["warnings"],
                refusalReason = refusalReason,
                poiTransactionId = poi?.transactionID,
                poiTimestamp = poi?.timeStamp?.toXMLFormat(),
                pspReference =
                    additional["pspReference"]
                        ?: poi?.transactionID?.substringAfter('.', missingDelimiterValue = "")?.ifEmpty { null },
                merchantReference = null,
                amount = null,
                currency = null,
                paymentBrand = null,
                maskedPan = null,
                entryMode = null,
                approvalCode = null,
                customerReceipt = ReceiptParser.fields(receipts, DocumentQualifierType.CUSTOMER_RECEIPT),
                cashierReceipt = ReceiptParser.fields(receipts, DocumentQualifierType.CASHIER_RECEIPT),
                signatureRequired = ReceiptParser.signatureRequired(receipts),
                additionalData = additional,
                tokenization = null,
            )
        }
    }
}
