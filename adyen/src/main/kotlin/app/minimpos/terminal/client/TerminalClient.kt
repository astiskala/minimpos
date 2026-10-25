package app.minimpos.terminal.client

import app.minimpos.terminal.parse.AdditionalResponseParser
import app.minimpos.terminal.parse.ReceiptParser
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import app.minimpos.terminal.transport.base64Json
import app.minimpos.terminal.transport.responseText
import com.adyen.model.nexo.AbortRequest
import com.adyen.model.nexo.AdminRequest
import com.adyen.model.nexo.AmountsReq
import com.adyen.model.nexo.DiagnosisRequest
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.EntryModeType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.ForceEntryModeType
import com.adyen.model.nexo.GetTotalsRequest
import com.adyen.model.nexo.IdentificationType
import com.adyen.model.nexo.Instalment
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageReference
import com.adyen.model.nexo.OriginalPOITransaction
import com.adyen.model.nexo.POIData
import com.adyen.model.nexo.PaymentData
import com.adyen.model.nexo.PaymentInstrumentData
import com.adyen.model.nexo.PaymentInstrumentType
import com.adyen.model.nexo.PaymentReceipt
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentTransaction
import com.adyen.model.nexo.PaymentType
import com.adyen.model.nexo.PrintRequest
import com.adyen.model.nexo.ReconciliationRequest
import com.adyen.model.nexo.ReconciliationType
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalReasonType
import com.adyen.model.nexo.ReversalRequest
import com.adyen.model.nexo.ReversalResponse
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.StoredValueAccountID
import com.adyen.model.nexo.StoredValueAccountType
import com.adyen.model.nexo.TokenRequestedType
import com.adyen.model.nexo.TotalFilter
import com.adyen.model.nexo.TransactionConditions
import com.adyen.model.nexo.TransactionIdentification
import com.adyen.model.nexo.TransactionTotals
import com.adyen.model.nexo.TransactionType
import com.adyen.model.terminal.SaleToAcquirerData
import com.adyen.model.terminal.SaleToAcquirerData.RecurringProcessingModelEnum
import com.adyen.model.terminal.TerminalAPIRequest
import java.math.BigDecimal
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
    internal val clock: Clock = Clock.systemUTC(),
    /** Makes the ServiceIDs of requests the caller does not name itself (aborts, prints, diagnoses, status checks). */
    internal val newServiceId: () -> String = ::randomServiceId,
    /** How long and how often to check the status of a payment or refund whose response went missing. */
    recovery: RecoveryPolicy = RecoveryPolicy(),
    /** Adyen: check the transaction status if a local payment response has not arrived after 120 seconds. */
    private val transactionTimeout: Duration = DEFAULT_TRANSACTION_TIMEOUT,
    /** The timeout for requests the terminal answers without the shopper: abort, print, diagnosis and status checks. */
    private val shortTimeout: Duration = 30.seconds,
    /**
     * The Adyen decimals of an ISO 4217 currency, null for one Adyen does not support: amounts cross this client in
     * minor units, and only here become the major units nexo carries.
     */
    currencyDigits: (currency: String) -> Int?,
) {
    /** Converts amounts between this client's minor units and nexo's major units. */
    internal val money = TerminalMoney(currencyDigits)

    /** Builds, sends and recovers this client's messages; shared with the stored-value requests. */
    internal val exchange = TerminalExchange(transport, identity, newServiceId, recovery, transactionTimeout, shortTimeout, money)

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
     * Audio guidance allows [ACCESSIBLE_TRANSACTION_TIMEOUT] before recovery, covering the default extended payment
     * and PIN screens. Longer Support-configured screens can still yield an unknown result; no timeout aborts a payment.
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
    ): TransactionOutcome =
        executeShopperFlow(params) { effectiveParams ->
            val request =
                exchange.message(MessageClassType.SERVICE, MessageCategoryType.PAYMENT, serviceId).apply {
                    paymentRequest =
                        PaymentRequest().apply {
                            saleData =
                                SaleData().apply {
                                    saleTransactionID = transaction(effectiveParams.merchantReference, clock.instant())
                                    saleReferenceID = effectiveParams.saleReferenceId
                                    saleToAcquirerData =
                                        acquirerData().apply {
                                            shopperReference = effectiveParams.shopperReference
                                            shopperEmail = effectiveParams.shopperEmail
                                            recurringProcessingModel =
                                                effectiveParams.recurringProcessingModel?.let {
                                                    RecurringProcessingModelEnum.valueOf(
                                                        it.name,
                                                    )
                                                }
                                            tenderOption = effectiveParams.tenderOption()
                                            if (effectiveParams.giftCard?.use == GiftCardUse.CASH_OUT) redemptionType = CASHBACK
                                            metadata = effectiveParams.metadata.takeIf { it.isNotEmpty() }
                                            if (effectiveParams.preAuthorisation) {
                                                authorisationType = PRE_AUTH
                                                additionalData = mapOf(MANUAL_CAPTURE to "true")
                                            }
                                        }
                                    saleToPOIData = paymentOperations(effectiveParams)
                                    if (effectiveParams.requestCardAlias) tokenRequestedType = TokenRequestedType.CUSTOMER
                                }
                            paymentData =
                                paymentInstrumentChoice(effectiveParams)
                            paymentTransaction = paymentAmounts(effectiveParams)
                        }
                }
            onStarted(serviceId)
            exchange.execute(
                serviceId,
                MessageCategoryType.PAYMENT,
                request,
                timeout = if (params.audioGuidance) maxOf(transactionTimeout, ACCESSIBLE_TRANSACTION_TIMEOUT) else transactionTimeout,
            ) {
                it.paymentResponse?.let { answer ->
                    mapPayment(answer, money::minor)
                }
            }
        }

    /**
     * Activates one native scan for at most 30 seconds, identified by positive [sessionId].
     * Uses the documented Admin service extension; nexo headers and Admin wire models remain SDK-owned.
     * The result contains transient scanner data only, never a payment outcome. Main-safe; no retries.
     */
    suspend fun scanBarcode(sessionId: Long): BarcodeScan {
        val request = barcodeRequest(sessionId, once = true)
        val response =
            (readTerminalAnswer(transport.send(request, 35.seconds)) { it.adminResponse?.response } as? TerminalAnswer.Read)
                ?.body
        val code =
            response
                ?.takeIf { it.result == ResultType.SUCCESS }
                ?.additionalResponse
                ?.let(AdditionalResponseParser::parse)
                ?.get(NexoVocabulary.BARCODE_DATA)
        return code?.takeIf { it.isNotBlank() }?.let(BarcodeScan::Read) ?: BarcodeScan.NotRead
    }

    /** Ends only native scanning session [sessionId]; best effort, never aborts or establishes a payment outcome. */
    suspend fun endBarcodeScan(sessionId: Long): Boolean {
        val delivery = transport.send(barcodeRequest(sessionId, once = false), shortTimeout)
        val response = (readTerminalAnswer(delivery) { it.adminResponse?.response } as? TerminalAnswer.Read)?.body
        return response?.result == ResultType.SUCCESS
    }

    private fun barcodeRequest(
        sessionId: Long,
        once: Boolean,
    ): TerminalAPIRequest {
        require(sessionId > 0) { "Invalid scan session" }
        val service =
            buildMap {
                put("Session", mapOf("Id" to sessionId, "Type" to if (once) "Once" else "End"))
                if (once) put("Operation", listOf(mapOf("Type" to "ScanBarcode", "TimeoutMs" to 30_000)))
            }
        return exchange
            .message(MessageClassType.SERVICE, MessageCategoryType.ADMIN, newServiceId())
            .apply {
                adminRequest = AdminRequest().apply { serviceIdentification = base64Json(service) }
            }.let { TerminalAPIRequest().apply { saleToPOIRequest = it } }
    }

    private fun paymentInstrumentChoice(params: PaymentParams): PaymentData? =
        params.scannedPayment?.let(::scannedInstrument) ?: params.giftCard?.paymentData()
            ?: params.mexicanPayment?.count?.let { count ->
                PaymentData().apply {
                    paymentType = PaymentType.INSTALMENT
                    instalment = Instalment().apply { totalNbOfPayments = count.toBigInteger() }
                }
            }

    private fun paymentAmounts(params: PaymentParams): PaymentTransaction =
        PaymentTransaction().apply {
            transactionConditions =
                when {
                    params.scannedPayment != null -> {
                        walletConditions(params.scannedPayment)
                    }

                    params.mexicanPayment != null -> {
                        TransactionConditions().apply { isDebitPreferredFlag = params.mexicanPayment.debit }
                    }

                    params.cardEntryOverride == CardEntryOverride.MKE -> {
                        TransactionConditions().apply { forceEntryMode += ForceEntryModeType.KEYED }
                    }

                    else -> {
                        null
                    }
                }
            amountsReq =
                AmountsReq().apply {
                    currency = params.currency
                    requestedAmount = money.major(params.amountMinor, params.currency)
                }
        }

    private fun walletConditions(payment: ScannedPayment): TransactionConditions =
        TransactionConditions().apply { allowedPaymentBrand += payment.brand }

    private fun scannedInstrument(payment: ScannedPayment): PaymentData =
        PaymentData().apply {
            paymentInstrumentData =
                PaymentInstrumentData().apply {
                    paymentInstrumentType = PaymentInstrumentType.STORED_VALUE
                    storedValueAccountID =
                        StoredValueAccountID().apply {
                            storedValueAccountType = StoredValueAccountType.OTHER
                            entryMode += EntryModeType.SCANNED
                            identificationType = IdentificationType.BAR_CODE
                            storedValueID = payment.consume()
                        }
                }
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
            exchange.message(MessageClassType.SERVICE, MessageCategoryType.REVERSAL, serviceId).apply {
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
                        reversedAmount = params.amountMinor?.let { money.major(it, checkNotNull(params.currency)) }
                        saleData =
                            SaleData().apply {
                                saleTransactionID = transaction(params.merchantReference, clock.instant())
                                // Partial refunds must state the original currency.
                                saleToAcquirerData =
                                    acquirerData().apply {
                                        currency = params.currency?.takeIf { params.amountMinor != null }
                                    }
                            }
                    }
            }
        return exchange.execute(serviceId, MessageCategoryType.REVERSAL, request) {
            it.reversalResponse?.let { answer -> mapReversal(answer, params.currency, money::minor) }
        }
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
            exchange.message(MessageClassType.SERVICE, MessageCategoryType.ABORT, newServiceId()).apply {
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
        transport.send(exchange.wrap(request), shortTimeout)
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
            exchange.message(MessageClassType.DEVICE, MessageCategoryType.PRINT, newServiceId()).apply {
                printRequest = PrintRequest().apply { printOutput = job.toNexo() }
            }
        val response =
            when (val answer = readTerminalAnswer(transport.send(exchange.wrap(request), shortTimeout)) { it.printResponse?.response }) {
                is TerminalAnswer.Unread -> return PrintOutcome.Failed(answer.fault, noPrinter = false)
                is TerminalAnswer.Read -> answer.body
            }
        if (response.result == ResultType.SUCCESS) return null
        val message = AdditionalResponseParser.parse(response.additionalResponse)[NexoVocabulary.MESSAGE]
        val noPrinter =
            response.errorCondition == ErrorConditionType.UNAVAILABLE_DEVICE || message.orEmpty().contains("no printer", ignoreCase = true)
        return PrintOutcome.Failed(Fault.TerminalRejected(ExternalText.of(message)), noPrinter)
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
            exchange.message(MessageClassType.SERVICE, MessageCategoryType.DIAGNOSIS, newServiceId()).apply {
                diagnosisRequest = DiagnosisRequest().apply { setHostDiagnosisFlag(false) }
            }
        val response =
            when (val answer = readTerminalAnswer(transport.send(exchange.wrap(request), shortTimeout)) { it.diagnosisResponse }) {
                is TerminalAnswer.Unread -> return DiagnosisResult(reachable = false, fault = answer.fault)
                is TerminalAnswer.Read -> answer.body
            }
        val additional = AdditionalResponseParser.parse(response.response?.additionalResponse)
        val reachable = response.response?.result == ResultType.SUCCESS
        return DiagnosisResult(
            reachable = reachable,
            fault = Fault.TerminalRejected(ExternalText.of(additional[NexoVocabulary.MESSAGE])).takeUnless { reachable },
            globalStatus = response.poiStatus?.globalStatus?.value(),
            printerStatus = response.poiStatus?.printerStatus?.value(),
        )
    }

    /** Retrieves the terminal's current reconciliation period totals (`GetTotalsRequest`) without closing the period. */
    suspend fun getTotals(): TerminalTotalsOutcome = reconcile(closePeriod = false)

    /**
     * Retrieves the terminal's current reconciliation period totals (`GetTotalsRequest`), or starts a new period
     * (`SaleReconciliation`) when [closePeriod] is true.
     *
     * @param closePeriod True to close the current reconciliation period (`SaleReconciliation`); false for `GetTotals`.
     * @return [TerminalTotalsOutcome.Answered] with totals per currency, or [TerminalTotalsOutcome.Failed].
     */
    suspend fun reconcile(closePeriod: Boolean = true): TerminalTotalsOutcome {
        val request =
            if (closePeriod) {
                exchange.message(MessageClassType.SERVICE, MessageCategoryType.RECONCILIATION, newServiceId()).apply {
                    reconciliationRequest =
                        ReconciliationRequest().apply {
                            reconciliationType = ReconciliationType.SALE_RECONCILIATION
                        }
                }
            } else {
                exchange.message(MessageClassType.SERVICE, MessageCategoryType.GET_TOTALS, newServiceId()).apply {
                    getTotalsRequest =
                        GetTotalsRequest().apply {
                            totalFilter = TotalFilter().apply { poiid = identity.poiId }
                        }
                }
            }
        return when (
            val answer =
                readTerminalAnswer(transport.send(exchange.wrap(request), shortTimeout)) { response ->
                    when {
                        closePeriod -> {
                            response.reconciliationResponse?.let {
                                TotalsBody(it.response, it.poiReconciliationID, it.transactionTotals)
                            }
                        }

                        else -> {
                            response.getTotalsResponse?.let {
                                TotalsBody(it.response, it.poiReconciliationID, it.transactionTotals)
                            }
                        }
                    }
                }
        ) {
            is TerminalAnswer.Unread -> {
                TerminalTotalsOutcome.Failed(answer.fault)
            }

            is TerminalAnswer.Read -> {
                val totals = answer.body
                mapTotalsResponse(
                    totals.response,
                    totals.poiReconciliationId,
                    totals.totals,
                    closedPeriod = closePeriod,
                )
            }
        }
    }

    /** The body of a `GetTotals` or `SaleReconciliation` answer, whichever was asked for. */
    private data class TotalsBody(
        val response: Response?,
        val poiReconciliationId: String?,
        val totals: List<TransactionTotals>?,
    )

    private fun mapTotalsResponse(
        response: Response?,
        poiReconciliationId: String?,
        totals: List<TransactionTotals>?,
        closedPeriod: Boolean,
    ): TerminalTotalsOutcome {
        if (response?.result != ResultType.SUCCESS) {
            val message = AdditionalResponseParser.parse(response?.additionalResponse)[NexoVocabulary.MESSAGE]
            return TerminalTotalsOutcome.Failed(Fault.TerminalRejected(ExternalText.of(message)))
        }
        val byCurrency = linkedMapOf<String, TerminalCurrencyTotals>()
        totals.orEmpty().forEach { group ->
            val currency =
                group.paymentCurrency
                    ?.trim()
                    ?.uppercase()
                    .orEmpty()
            if (currency.isNotEmpty()) {
                var current = byCurrency[currency] ?: TerminalCurrencyTotals(currency)
                group.paymentTotals.orEmpty().forEach { entry ->
                    val amount = money.minor(entry.transactionAmount ?: BigDecimal.ZERO, currency) ?: 0L
                    val count = entry.transactionCount?.toInt() ?: 0
                    current =
                        when (entry.transactionType) {
                            TransactionType.DEBIT -> {
                                current.copy(
                                    debitMinor = current.debitMinor + amount,
                                    debitCount = current.debitCount + count,
                                )
                            }

                            TransactionType.CREDIT, TransactionType.REVERSE_DEBIT -> {
                                current.copy(
                                    creditMinor = current.creditMinor + amount,
                                    creditCount = current.creditCount + count,
                                )
                            }

                            else -> {
                                current
                            }
                        }
                }
                byCurrency[currency] = current
            }
        }
        return TerminalTotalsOutcome.Answered(
            poiReconciliationId = poiReconciliationId?.trim()?.ifEmpty { null },
            totals = byCurrency.values.sortedBy { it.currency },
            closedPeriod = closedPeriod,
        )
    }

    /**
     * One transaction status check for an earlier payment or reversal (e.g. to settle an "unknown" sale later). The
     * terminal looks the transaction up by our SaleID and its ServiceID and repeats its original response, receipts
     * included.
     *
     * @param serviceId The ServiceID the payment or refund was sent with.
     * @param kind Whether it was a payment or a refund.
     * @return [TransactionOutcome.Completed] with the original result;
     *   [TransactionOutcome.NotProcessed] ([Fault.NoRecord]) if the terminal has no record of it; otherwise
     *   [TransactionOutcome.Unknown] with why, [Fault.StillInProgress] while the transaction is still in progress. I/O
     *   errors are not thrown.
     */
    suspend fun status(
        serviceId: String,
        kind: TransactionKind = TransactionKind.PAYMENT,
    ): TransactionOutcome =
        when (val check = exchange.checkStatus(serviceId, kind.category())) {
            is TerminalExchange.StatusCheck.Settled -> check.outcome
            TerminalExchange.StatusCheck.InProgress -> TransactionOutcome.Unknown(Fault.StillInProgress)
            is TerminalExchange.StatusCheck.NoAnswer -> TransactionOutcome.Unknown(check.fault)
        }

    private fun acquirerData(): SaleToAcquirerData =
        SaleToAcquirerData().apply {
            application?.let { applicationInfo = it.toApplicationInfo() }
        }

    internal suspend fun sendShortMessage(
        messageClass: MessageClassType,
        category: MessageCategoryType,
        configure: SaleToPOIRequest.() -> Unit,
    ): Delivery = transport.send(exchange.wrap(exchange.message(messageClass, category, newServiceId()).apply(configure)), shortTimeout)

    // One owner composes every Payment operation, so adding a variant cannot overwrite tip overrides.
    private fun paymentOperations(params: PaymentParams): String? {
        val operation =
            buildMap<String, Any> {
                put("Type", "Payment")
                if (params.audioGuidance) put(NexoVocabulary.VARIANT, NexoVocabulary.ENABLE_ACCESSIBILITY)
                params.terminalTip?.let { tip ->
                    tip.suggestions?.let { suggestions ->
                        put(
                            NexoVocabulary.TIP_OPTIONS,
                            tipOptions(tip.amountToTipOnMinor?.let { money.major(it, params.currency) }, suggestions),
                        )
                    }
                }
            }
        return if (operation.size == 1) null else base64Json(mapOf(NexoVocabulary.OPERATION to listOf(operation)))
    }

    private fun tipOptions(
        amountToTipOn: BigDecimal?,
        configured: TerminalTipSuggestions,
    ): Map<String, Any> {
        val suggestions =
            when (configured) {
                is TerminalTipSuggestions.Percentages -> {
                    buildMap<String, Any> {
                        for (index in configured.percentages.indices) {
                            put("${NexoVocabulary.TIP_PERCENTAGE}${index + 1}", configured.percentages[index])
                        }
                        put(NexoVocabulary.TIP_CUSTOM, configured.allowCustom)
                    }
                }

                is TerminalTipSuggestions.FixedAmounts -> {
                    buildMap<String, Any> {
                        for (index in configured.amountsMinor.indices) {
                            put("${NexoVocabulary.TIP_AMOUNT}${index + 1}", configured.amountsMinor[index])
                        }
                        put(NexoVocabulary.TIP_CUSTOM, configured.allowCustom)
                    }
                }
            }
        return buildMap<String, Any> {
            amountToTipOn?.let { put(NexoVocabulary.AMOUNT_TO_TIP_ON, it) }
            put(NexoVocabulary.TIP_SUGGESTIONS, suggestions)
        }
    }

    private fun TransactionKind.category(): MessageCategoryType =
        when (this) {
            TransactionKind.PAYMENT -> MessageCategoryType.PAYMENT
            TransactionKind.REFUND -> MessageCategoryType.REVERSAL
            TransactionKind.STORED_VALUE -> MessageCategoryType.STORED_VALUE
        }

    /** Terminal API formats and the mapping of nexo responses to [TransactionDetails]. */
    companion object {
        /** The nexo protocol version sent in every message header. */
        const val PROTOCOL_VERSION = "3.0"

        /** How long to wait for a payment or refund response before checking its status, as Adyen advises. */
        val DEFAULT_TRANSACTION_TIMEOUT: Duration = 120.seconds

        /** Covers the default two-minute payment and five-minute PIN screens, plus transitions; timeout still means unknown. */
        val ACCESSIBLE_TRANSACTION_TIMEOUT: Duration = 600.seconds

        /**
         * Minimum Adyen software for POS-requested audio guidance on [poiId], or null for an unlisted/unknown model.
         * Model eligibility alone does not establish installed software or Support enablement (checked October 24, 2026).
         */
        fun audioGuidanceMinimumSoftware(poiId: String?): String? =
            when (poiId?.substringBefore('-')?.uppercase()) {
                "AMS1", "S1F2", "S1F2L", "S1E2L", "S1U2" -> "1.117"
                "M450", "P630", "SFO1" -> "1.124"
                else -> null
            }

        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /**
         * Adyen's `authorisationType` for an amount that can be adjusted and is captured later; the simulator reads it
         * the way a terminal does.
         */
        internal const val PRE_AUTH = "PreAuth"

        /** Tender option: the app prints the receipts, card details included, instead of the terminal. */
        const val RECEIPT_HANDLER = "ReceiptHandler"

        /** Tender option that prompts the shopper for a tip on the terminal. */
        const val ASK_GRATUITY = "AskGratuity"

        /** Tender option that allows the terminal/issuer to approve a smaller amount than requested. */
        const val ALLOW_PARTIAL_AUTHORISATION = "AllowPartialAuthorisation"

        /** Tender option that prompts the shopper for an Adyen Giving charity donation after purchase. */
        const val ASK_GIVING = "AskGiving"

        /** Fixed SaleID Adyen uses for two-step Adyen Giving donation status lookups. */
        const val GIVING_SALE_ID = "AdyenGiving"

        /** Tender option for Mail Order / Telephone Order card entry on the terminal. */
        const val MOTO = "MOTO"

        /** `SaleToAcquirerData.redemptionType` that pays a gift card's value out to the shopper. */
        internal const val CASHBACK = "cashback"

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

        /**
         * The payment [response] said; null when it has no result this version can read (missing, or a value it does
         * not know), which leaves the payment unknown rather than declined.
         */
        internal fun mapPayment(
            response: PaymentResponse,
            minor: (BigDecimal?, String?) -> Long?,
        ): TransactionDetails? {
            if (response.response?.result == null) return null
            val additional = AdditionalResponseParser.parse(response.response?.additionalResponse)
            val card = response.paymentResult?.paymentInstrumentData?.cardData
            val storedId =
                additional[NexoVocabulary.TOKENIZATION_STORED_PAYMENT_METHOD_ID] ?: additional[NexoVocabulary.RECURRING_DETAIL_REFERENCE]
            val online = response.paymentResult?.isOnlineFlag != false && additional[NexoVocabulary.OFFLINE] != "true"
            val offlineMode =
                when {
                    online -> OfflineProcessingMode.ONLINE
                    !additional[NexoVocabulary.OFFLINE_AUTH_CODE].isNullOrBlank() -> OfflineProcessingMode.OFFLINE_EMV
                    else -> OfflineProcessingMode.STORE_AND_FORWARD
                }
            return details(
                response = response.response,
                additional = additional,
                poiData = response.poiData,
                receipts = response.paymentReceipt,
            ).copy(
                merchantReference = response.saleData?.saleTransactionID?.transactionID,
                // SDK getPaymentType defaults missing fields to Normal. Only explicit installment types are evidence.
                returnedPaymentType =
                    response.paymentResult
                        ?.paymentType
                        ?.takeIf {
                            it == PaymentType.INSTALMENT || it == PaymentType.ISSUER_INSTALMENT
                        }?.value(),
                returnedInstallments =
                    response.paymentResult
                        ?.instalment
                        ?.totalNbOfPayments
                        ?.toString(),
                returnedPlanId = response.paymentResult?.instalment?.planID,
                amountMinor = minor(response.paymentResult?.amountsResp?.authorizedAmount, response.paymentResult?.amountsResp?.currency),
                tipMinor = minor(response.paymentResult?.amountsResp?.tipAmount, response.paymentResult?.amountsResp?.currency),
                surchargeMinor = minor(response.paymentResult?.amountsResp?.totalFeesAmount, response.paymentResult?.amountsResp?.currency),
                cashBackMinor = minor(response.paymentResult?.amountsResp?.cashBackAmount, response.paymentResult?.amountsResp?.currency),
                currency = response.paymentResult?.amountsResp?.currency,
                paymentBrand = card?.paymentBrand ?: additional[NexoVocabulary.PAYMENT_METHOD],
                paymentMethodVariant = additional[NexoVocabulary.PAYMENT_METHOD_VARIANT],
                maskedPan = card?.maskedPAN,
                entryMode = card?.entryMode?.firstOrNull()?.value() ?: additional[NexoVocabulary.POS_ENTRY_MODE],
                approvalCode =
                    response.paymentResult?.paymentAcquirerData?.approvalCode
                        ?: additional[NexoVocabulary.AUTH_CODE]
                        ?: additional[NexoVocabulary.OFFLINE_AUTH_CODE],
                tokenization =
                    storedId?.let {
                        Tokenization(storedPaymentMethodId = it)
                    },
                givingServiceId = additional[NexoVocabulary.ADYEN_GIVING_SERVICE_ID]?.trim()?.ifEmpty { null },
                offlineMode = offlineMode,
                unconfirmedBatchCount = additional[NexoVocabulary.UNCONFIRMED_BATCH_COUNT]?.trim()?.toIntOrNull()?.takeIf { it >= 0 },
            )
        }

        /**
         * The refund [response] said, its reversed amount read in [currency] (null when the request named none); null, so
         * the refund stays unknown, when it has no result this version can read.
         */
        internal fun mapReversal(
            response: ReversalResponse,
            currency: String?,
            minor: (BigDecimal?, String?) -> Long?,
        ): TransactionDetails? {
            if (response.response?.result == null) return null
            val additional = AdditionalResponseParser.parse(response.response?.additionalResponse)
            return details(
                response = response.response,
                additional = additional,
                poiData = response.poiData,
                receipts = response.paymentReceipt,
            ).copy(
                merchantReference = additional[NexoVocabulary.MERCHANT_REFERENCE],
                amountMinor = minor(response.reversedAmount, currency),
            )
        }

        internal fun details(
            response: Response?,
            additional: Map<String, String>,
            poiData: POIData?,
            receipts: List<PaymentReceipt>?,
        ): TransactionDetails {
            val poi = poiData?.poiTransactionID
            val partial = response?.result == ResultType.PARTIAL
            val success = response?.result == ResultType.SUCCESS || partial
            val refusalReason = additional[NexoVocabulary.REFUSAL_REASON]
            return TransactionDetails(
                success = success,
                partial = partial,
                errorCondition = response?.errorCondition?.value(),
                message =
                    responseText(
                        messages =
                            listOf(
                                refusalReason,
                                additional[NexoVocabulary.MESSAGE] ?: additional[NexoVocabulary.ERRORS]
                                    ?: additional[NexoVocabulary.WARNINGS],
                            ),
                        errorCode = additional[NexoVocabulary.ERROR_CODE],
                        traceParent = additional[NexoVocabulary.TRACE_PARENT],
                        requestId = additional[NexoVocabulary.REQUEST_ID],
                    ),
                refusalReason = refusalReason,
                poiTransactionId = poi?.transactionID,
                poiTimestamp = poi?.timeStamp?.toXMLFormat(),
                poiReconciliationId = poiData?.poiReconciliationID?.trim()?.ifEmpty { null },
                pspReference =
                    additional[NexoVocabulary.PSP_REFERENCE]
                        ?: poi?.transactionID?.substringAfter('.', missingDelimiterValue = "")?.ifEmpty { null },
                merchantReference = null,
                amountMinor = null,
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

/** The tender options of this payment (`SaleToAcquirerData.tenderOption`), comma-separated; null for none. */
internal fun PaymentParams.tenderOption(): String? =
    buildList {
        if (appPrintsReceipts) add(TerminalClient.RECEIPT_HANDLER)
        if (terminalTip != null) add(TerminalClient.ASK_GRATUITY)
        if (allowPartialAuthorisation) add(TerminalClient.ALLOW_PARTIAL_AUTHORISATION)
        if (askGiving) add(TerminalClient.ASK_GIVING)
        if (cardEntryOverride == CardEntryOverride.MOTO) add(TerminalClient.MOTO)
    }.takeIf { it.isNotEmpty() }?.joinToString(",")
