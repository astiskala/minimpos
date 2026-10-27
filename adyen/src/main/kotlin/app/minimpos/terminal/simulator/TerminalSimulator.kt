package app.minimpos.terminal.simulator

import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.NexoVocabulary
import app.minimpos.terminal.client.OfflineProcessingMode
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.toPrintJob
import app.minimpos.terminal.transport.CompletedTransactions
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.FaultException
import app.minimpos.terminal.transport.TerminalTransport
import app.minimpos.terminal.transport.base64Json
import app.minimpos.terminal.transport.fault
import app.minimpos.terminal.transport.nexoGson
import com.adyen.model.nexo.AdminResponse
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.CardData
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.EntryModeType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.GetTotalsResponse
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.POIData
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
import com.adyen.model.nexo.ReconciliationRequest
import com.adyen.model.nexo.ReconciliationResponse
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalRequest
import com.adyen.model.nexo.ReversalResponse
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TokenRequestedType
import com.adyen.model.nexo.TransactionStatusRequest
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.gson.JsonParser
import kotlinx.coroutines.delay
import java.io.IOException
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.encoding.Base64
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
 * @property shopperConfirmed Answer returned by simulated `GetConfirmation` input requests.
 * @property shopperEmailInput Email text returned by simulated `GetText` input requests.
 * @property shopperRating 0–10 rating returned by simulated `GetRating` input requests.
 * @property partialApprovalPercent Percentage (`1..99`) of `requestedAmount` approved when `AllowPartialAuthorisation`
 *   is present; `100` approves in full unless the minor-unit amount ends with Adyen's `139` partial-approval test suffix.
 * @property givingDonationAmount Simulated charity donation amount in major units when `AskGiving` is present; zero skips donation.
 * @property offlineMode Whether payments are approved online or simulated offline (`OFFLINE_EMV` or `STORE_AND_FORWARD`).
 * @property unconfirmedBatchCount Simulated `unconfirmedBatchCount` returned when [offlineMode] is not `ONLINE`.
 * @property surchargeAmount Fixed surcharge in major units, rounded to the requested precision; simulation only.
 * @property refundsProcessedSynchronously Whether approved unreferenced refunds report `acquirerResponseCode=Approved`,
 *   as when the card scheme and country process them synchronously; false answers Adyen's usual asynchronous request.
 */
data class SimulatorConfig(
    val outcome: SimulatedOutcome = SimulatedOutcome.APPROVE,
    val delayMillis: Long = 2_500,
    val hasPrinter: Boolean = true,
    val signatureRequired: Boolean = false,
    val shopperConfirmed: Boolean = true,
    val shopperEmailInput: String = "shopper@example.com",
    val shopperRating: Int = DEFAULT_SIMULATED_RATING,
    val partialApprovalPercent: Int = FULL_APPROVAL_PERCENT,
    val givingDonationAmount: BigDecimal = BigDecimal.ONE,
    val offlineMode: OfflineProcessingMode = OfflineProcessingMode.ONLINE,
    val unconfirmedBatchCount: Int = 1,
    /** Fixed surcharge in major units, rounded to the request precision; simulation only, never a pricing engine. */
    val surchargeAmount: BigDecimal = BigDecimal.ZERO,
    val refundsProcessedSynchronously: Boolean = false,
) {
    private companion object {
        const val DEFAULT_SIMULATED_RATING = 9
        const val FULL_APPROVAL_PERCENT = 100
    }
}

/**
 * An in-process stand-in for an Adyen terminal, so every flow can be exercised on an emulator or phone. Responses
 * mimic real Terminal API payloads, including receipt data and tokenization details. Requests and responses pass
 * through the Adyen library's JSON serialisation, exactly as they would on the way to and from a real terminal.
 *
 * It handles payments, reversals, aborts, prints, diagnoses and transaction status checks; any other request is
 * [Fault.Unsupported], and a simulated timeout [Fault.TimedOut]. A status check of a payment still under way is answered
 * `InProgress`; finished payments and reversals are remembered in memory for status checks, so after a restart they are
 * reported as not found. Approved payments are remembered the same way, shared with the simulated Checkout API
 * ([modifications]): a full reversal of an uncaptured pre-authorisation is answered as a cancellation, and captures
 * follow what happened to a payment. The timeout passed to [send] is ignored.
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
    private val account = SimulatedAccount()
    private val completed = account.completed
    private val aborted = account.aborted
    private val inProgress = ConcurrentHashMap.newKeySet<String>()
    private val ledger = account.ledger
    private val reconciliationId = AtomicInteger(INITIAL_RECONCILIATION_ID)
    private val periodTotals = ConcurrentHashMap<String, SimulatedPeriodTotals>()
    private val paymentAmountsByTransactionId = account.paymentAmounts
    private val giftCards = SimulatedGiftCards(clock)
    private val giftCardTerminal =
        SimulatedGiftCardTerminal(giftCards, completed, ::giftCardIds, ::waitForShopper, ::recordGiftCardPayment)

    /** Adyen's Checkout API for the payments this simulator takes, answering from what happened to them. */
    val modifications: SimulatedModifications = SimulatedModifications(random, ledger)

    /**
     * Simulated saved cards: the ones the app knows were saved under a shopper reference ([cards]), charged with this
     * simulator's outcome and remembered in the ledger [modifications] shares.
     */
    fun savedCards(cards: suspend (shopperReference: String) -> List<StoredCard>): SimulatedSavedCards =
        SimulatedSavedCards(random, account, cards) { config().outcome }

    /** Forgets all history identities after confirmed purge, retaining independent reconciliation period totals. */
    fun forgetHistory() = account.forgetHistory()

    /** Releases only durably deleted history identities, preserving unfinished retries and terminal period totals. */
    fun forget(removed: SimulatorRemoval) = account.forget(removed)

    override suspend fun send(
        request: TerminalAPIRequest,
        timeout: Duration,
    ): Delivery {
        val received = nexoGson.fromJson(nexoGson.toJson(request), TerminalAPIRequest::class.java)
        val response =
            try {
                handle(received)
            } catch (e: IOException) {
                return Delivery.Failed(e.fault())
            }
        return Delivery.Answered(response?.let { nexoGson.fromJson(nexoGson.toJson(it), TerminalAPIResponse::class.java) })
    }

    private suspend fun handle(request: TerminalAPIRequest): TerminalAPIResponse? {
        val message = request.saleToPOIRequest ?: throw FaultException(Fault.UnreadableReply())
        val header = message.messageHeader
        val settings = config()
        return when {
            message.isGiftCardRequest -> {
                handleGiftCards(header, message, settings)
            }

            message.paymentRequest != null -> {
                payment(header, message.paymentRequest, settings)
            }

            message.reversalRequest != null -> {
                respond(header, SaleToPOIResponse().apply { reversalResponse = reversal(header, message.reversalRequest) })
            }

            message.printRequest != null -> {
                respond(header, SaleToPOIResponse().apply { printResponse = print(message.printRequest.printOutput, settings) })
            }

            message.transactionStatusRequest != null -> {
                respond(
                    header,
                    SaleToPOIResponse().apply {
                        transactionStatusResponse =
                            status(message.transactionStatusRequest)
                    },
                )
            }

            message.abortRequest != null -> {
                message.abortRequest.messageReference
                    ?.serviceID
                    ?.let(aborted::add)
                null
            }

            message.diagnosisRequest != null -> {
                respond(header, SaleToPOIResponse().apply { diagnosisResponse = simulateDiagnosisResponse(settings) })
            }

            else -> {
                handleTotals(header, message, settings)
            }
        }
    }

    private fun handleTotals(
        header: MessageHeader,
        message: SaleToPOIRequest,
        settings: SimulatorConfig,
    ): TerminalAPIResponse =
        when {
            message.getTotalsRequest != null -> {
                respond(header, SaleToPOIResponse().apply { getTotalsResponse = totalsResponse() })
            }

            message.reconciliationRequest != null -> {
                respond(
                    header,
                    SaleToPOIResponse().apply {
                        reconciliationResponse = reconcileResponse(message.reconciliationRequest)
                    },
                )
            }

            message.adminRequest != null -> {
                respond(
                    header,
                    SaleToPOIResponse().apply {
                        adminResponse = AdminResponse().apply { response = Response().apply { result = ResultType.SUCCESS } }
                    },
                )
            }

            message.displayRequest != null -> {
                respond(header, SaleToPOIResponse().apply { displayResponse = simulateDisplayResponse(message.displayRequest) })
            }

            message.inputRequest != null -> {
                respond(header, SaleToPOIResponse().apply { inputResponse = simulateInputResponse(message.inputRequest, settings) })
            }

            else -> {
                throw FaultException(Fault.Unsupported)
            }
        }

    /** Waits for the simulated shopper, ending early on an abort; the configured outcome, or null when aborted. */
    private suspend fun waitForShopper(
        serviceId: String,
        settings: SimulatorConfig,
        refuse: Boolean = false,
    ): SimulatedOutcome? {
        inProgress += serviceId
        var waited = 0L
        try {
            while (waited < settings.delayMillis && serviceId !in aborted) {
                delay(POLL_MILLIS)
                waited += POLL_MILLIS
            }
        } finally {
            inProgress -= serviceId
        }
        return if (aborted.remove(serviceId)) {
            null
        } else if (refuse) {
            SimulatedOutcome.DECLINE
        } else if (settings.outcome == SimulatedOutcome.RANDOM) {
            if (random.nextInt(PERCENT) < RANDOM_APPROVAL_PERCENT) SimulatedOutcome.APPROVE else SimulatedOutcome.DECLINE
        } else {
            settings.outcome
        }
    }

    private suspend fun payment(
        header: MessageHeader,
        request: PaymentRequest,
        settings: SimulatorConfig,
    ): TerminalAPIResponse {
        val serviceId = header.serviceID.orEmpty()
        val accessible = simulatedAudioGuidance(request.saleData)
        val outcome = waitForShopper(serviceId, settings, refuse = accessible && settings.signatureRequired)
        val response = paymentResponse(request, outcome, settings)
        completed.remember(serviceId, payment = response)
        if (outcome == SimulatedOutcome.TIMEOUT) throw SocketTimeoutException("Simulated timeout")
        return respond(header, SaleToPOIResponse().apply { paymentResponse = response })
    }

    private fun paymentResponse(
        request: PaymentRequest,
        outcome: SimulatedOutcome?,
        settings: SimulatorConfig,
    ): PaymentResponse {
        if (outcome == SimulatedOutcome.BUSY) return busy(request)
        val amount = request.paymentTransaction.amountsReq
        val (payment, authorizedAmount) = buildSimulatedPayment(request, outcome, settings)
        if (payment.approved) recordApprovedPayment(payment, amount.currency, authorizedAmount)
        val refusal = if (payment.approved) null else simulatedRefusal(outcome)
        val additional =
            buildPaymentAdditional(payment, request.saleData?.saleToAcquirerData, refusal, random) {
                randomString(TOKEN_LENGTH, DIGITS)
            } + refundAdditional(payment, authorizedAmount, settings)
        return PaymentResponse().apply {
            response =
                Response().apply {
                    result =
                        when {
                            !payment.approved -> ResultType.FAILURE
                            payment.partial -> ResultType.PARTIAL
                            else -> ResultType.SUCCESS
                        }
                    errorCondition = refusal?.first
                    additionalResponse = base64Json(additional)
                }
            saleData = SaleData().apply { saleTransactionID = request.saleData?.saleTransactionID }
            poiData = poiData(payment.tender, payment.psp.takeUnless { payment.isOffline })
            paymentResult =
                paymentResult(payment, amount.currency, authorizedAmount).apply {
                    amountsResp.cashBackAmount = if (payment.approved) amount.cashBackAmount else null
                    request.paymentData?.instalment?.let {
                        paymentType = PaymentType.INSTALMENT
                        instalment = it
                    }
                }
            paymentReceipt +=
                listOf(
                    receipt(DocumentQualifierType.CUSTOMER_RECEIPT, "CARDHOLDER COPY", payment, signature = false),
                    receipt(
                        DocumentQualifierType.CASHIER_RECEIPT,
                        "MERCHANT COPY",
                        payment,
                        signature =
                            payment.approved &&
                                (settings.signatureRequired || payment.entryOverride == CardEntryOverride.MKE),
                    ),
                )
        }
    }

    // A scanned wallet's brand, Adyen's Elo voucher test card for an app-selected Brazilian voucher, else a random card.
    private fun presentedCard(
        request: PaymentRequest,
        wallet: Boolean,
        brand: String,
    ): SimulatedCard =
        when {
            wallet -> SimulatedCard(brand, brand, brand.uppercase(), "", "", "")
            mealVoucherRequested(request.saleData) -> MEAL_VOUCHER
            else -> CARDS[random.nextInt(CARDS.size)]
        }

    private fun buildSimulatedPayment(
        request: PaymentRequest,
        outcome: SimulatedOutcome?,
        settings: SimulatorConfig,
    ): Pair<SimulatedPayment, BigDecimal> {
        val amount = request.paymentTransaction.amountsReq
        val wallet = request.paymentData?.paymentInstrumentData?.paymentInstrumentType == PaymentInstrumentType.STORED_VALUE
        val brand =
            request.paymentTransaction.transactionConditions
                ?.allowedPaymentBrand
                ?.singleOrNull()
                .orEmpty()
        val approved = outcome == SimulatedOutcome.APPROVE || outcome == SimulatedOutcome.TIMEOUT
        val refund = request.unreferencedRefund
        val (partialBase, tipAmount, surcharge) = simulatedAmounts(request, approved, settings)
        val baseApproved = partialBase ?: amount.requestedAmount
        val authorizedAmount = if (approved) baseApproved + (tipAmount ?: BigDecimal.ZERO) + surcharge else BigDecimal.ZERO
        val payment =
            SimulatedPayment(
                card = presentedCard(request, wallet, brand),
                wallet = wallet,
                psp = SimulatedModifications.pspReference(random),
                tender = randomString(TENDER_PREFIX_LENGTH, ALNUM) + randomString(TENDER_DIGITS, DIGITS),
                authCode = randomString(AUTH_CODE_LENGTH, DIGITS),
                merchantReference =
                    request.saleData
                        ?.saleTransactionID
                        ?.transactionID
                        .orEmpty(),
                amountText = "${amount.currency} ${authorizedAmount.toPlainString()}",
                approved = approved,
                preAuthorisation = request.saleData?.saleToAcquirerData?.authorisationType == TerminalClient.PRE_AUTH,
                tipAmount = tipAmount,
                surchargeAmount = surcharge,
                partial = partialBase != null,
                givingServiceId = simulatedGivingServiceId(request.saleData, approved, partialBase != null, settings),
                entryOverride = simulatedCardEntryOverride(request),
                offlineMode = if (refund) OfflineProcessingMode.ONLINE else settings.offlineMode,
                unconfirmedBatchCount = settings.unconfirmedBatchCount,
                refund = refund,
            )
        payment.givingServiceId?.let { givingId ->
            completed.remember(
                givingId,
                ownerTransactionId = payment.transactionId,
                payment =
                    PaymentResponse().apply {
                        response = Response().apply { result = ResultType.SUCCESS }
                        paymentResult =
                            PaymentResult().apply {
                                amountsResp =
                                    AmountsResp().apply {
                                        currency = amount.currency
                                        this.authorizedAmount = settings.givingDonationAmount
                                    }
                            }
                    },
            )
        }
        return payment to authorizedAmount
    }

    private fun recordApprovedPayment(
        payment: SimulatedPayment,
        currency: String,
        authorizedAmount: BigDecimal,
    ) {
        if (payment.refund) ledger.refunded(payment.psp) else ledger.approved(payment.psp, manualCapture = payment.preAuthorisation)
        if (payment.preAuthorisation) return
        // An unreferenced refund is a credit with no payment a later reversal could refer to.
        if (!payment.refund) paymentAmountsByTransactionId[payment.transactionId] = currency to authorizedAmount
        periodTotals.compute(currency) { _, current -> (current ?: SimulatedPeriodTotals(currency)).plus(authorizedAmount, payment.refund) }
    }

    private fun paymentResult(
        payment: SimulatedPayment,
        requestedCurrency: String,
        authorized: BigDecimal,
    ) = PaymentResult().apply {
        paymentType = if (payment.refund) PaymentType.REFUND else PaymentType.NORMAL
        paymentInstrumentData = paymentInstrument(payment)
        amountsResp =
            AmountsResp().apply {
                currency = requestedCurrency
                authorizedAmount = authorized
                tipAmount = payment.tipAmount
                totalFeesAmount = payment.surchargeAmount
            }
        setOnlineFlag(!payment.isOffline)
        paymentAcquirerData =
            PaymentAcquirerData().apply {
                approvalCode = payment.authCode.takeIf { payment.approved && !payment.isOffline }
            }
    }

    private fun paymentInstrument(payment: SimulatedPayment): PaymentInstrumentData =
        if (payment.wallet) {
            PaymentInstrumentData().apply { paymentInstrumentType = PaymentInstrumentType.STORED_VALUE }
        } else {
            PaymentInstrumentData().apply {
                paymentInstrumentType = PaymentInstrumentType.CARD
                cardData =
                    CardData().apply {
                        entryMode +=
                            if (payment.entryOverride == CardEntryOverride.NORMAL) {
                                EntryModeType.CONTACTLESS
                            } else {
                                EntryModeType.KEYED
                            }
                        maskedPAN = payment.card.maskedPan
                        paymentBrand = payment.card.brand
                        paymentToken =
                            PaymentToken().apply {
                                tokenRequestedType = TokenRequestedType.CUSTOMER
                                tokenValue = payment.card.alias
                            }
                    }
            }
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
                                NexoVocabulary.MESSAGE to "Forbidden Request, Service Dialogue PaymentRequest is in Progress",
                                NexoVocabulary.BUSY_SERVICE_ID to BUSY_SERVICE_ID,
                            ),
                        )
                }
            saleData = SaleData().apply { saleTransactionID = request.saleData?.saleTransactionID }
        }

    private fun reversal(
        header: MessageHeader,
        request: ReversalRequest,
    ): ReversalResponse {
        val psp = SimulatedModifications.pspReference(random)
        val merchantReference =
            request.saleData
                ?.saleTransactionID
                ?.transactionID
                .orEmpty()
        val amount = request.reversedAmount
        // Like Adyen's cancel-or-refund: a full reversal of an uncaptured pre-authorisation cancels it. The transaction
        // ID is `<tender reference>.<PSP reference>`.
        val cancels =
            amount == null &&
                request.originalPOITransaction
                    ?.poiTransactionID
                    ?.transactionID
                    ?.substringAfter('.')
                    ?.let(ledger::cancel) == true
        if (!cancels) recordApprovedReversal(request)
        request.originalPOITransaction
            ?.poiTransactionID
            ?.transactionID
            ?.let { giftCards.reverseRedemption(it, amount) }
        val response =
            ReversalResponse().apply {
                response =
                    Response().apply {
                        result = ResultType.SUCCESS
                        additionalResponse =
                            base64Json(mapOf(NexoVocabulary.PSP_REFERENCE to psp, NexoVocabulary.MERCHANT_REFERENCE to merchantReference))
                    }
                poiData = poiData(randomString(TENDER_PREFIX_LENGTH, ALNUM) + randomString(TENDER_DIGITS, DIGITS), psp)
                reversedAmount = amount
                paymentReceipt +=
                    PaymentReceipt().apply {
                        documentQualifier = DocumentQualifierType.CUSTOMER_RECEIPT
                        outputContent =
                            receiptText(
                                listOf(
                                    receiptLine("filler"),
                                    receiptLine("txtype", if (cancels) "CANCELLATION" else "REFUND", bold = true),
                                    receiptLine("mref", "Reference", merchantReference),
                                    receiptLine(
                                        "totalAmount",
                                        if (cancels) "Released" else "Refund",
                                        amount?.toPlainString() ?: "Full amount",
                                    ),
                                    receiptLine("filler"),
                                    receiptLine("approved", if (cancels) "CANCELLATION REQUESTED" else "REFUND REQUESTED", bold = true),
                                ),
                            )
                    }
            }
        completed.remember(header.serviceID.orEmpty(), reversal = response)
        return response
    }

    private fun recordApprovedReversal(request: ReversalRequest) {
        val originalId =
            request.originalPOITransaction
                ?.poiTransactionID
                ?.transactionID
                .orEmpty()
        val original = paymentAmountsByTransactionId[originalId]
        // A refund of a payment this simulator did not take states its currency only when partial; a full one of an
        // unknown payment has no currency to count it in, so it is left out of the totals rather than counted as EUR.
        val currency = original?.first ?: request.saleData?.saleToAcquirerData?.currency ?: return
        val reversed = request.reversedAmount ?: original?.second ?: BigDecimal.ZERO
        if (reversed > BigDecimal.ZERO) {
            periodTotals.compute(currency) { _, current ->
                val base = current ?: SimulatedPeriodTotals(currency)
                base.copy(creditAmount = base.creditAmount + reversed, creditCount = base.creditCount + 1)
            }
        }
    }

    private suspend fun handleGiftCards(
        header: MessageHeader,
        message: SaleToPOIRequest,
        settings: SimulatorConfig,
    ): TerminalAPIResponse = respond(header, giftCardTerminal.answer(header.serviceID.orEmpty(), message, settings))

    /** Counts an approved gift-card payment in the period totals: a refund onto the card as a credit. */
    private fun recordGiftCardPayment(
        request: PaymentRequest,
        transactionId: String,
        approved: SimulatedGiftCards.Answer.Approved,
    ) {
        val currency = request.paymentTransaction.amountsReq.currency
        periodTotals.compute(currency) { _, current ->
            val base = current ?: SimulatedPeriodTotals(currency)
            if (request.giftCardRefund) {
                base.copy(creditAmount = base.creditAmount + approved.approved, creditCount = base.creditCount + 1)
            } else {
                base.copy(debitAmount = base.debitAmount + approved.approved, debitCount = base.debitCount + 1)
            }
        }
        if (!request.giftCardRefund) paymentAmountsByTransactionId[transactionId] = currency to approved.approved
    }

    private fun giftCardIds(): SimulatedGiftCardIds {
        val tender = randomString(TENDER_PREFIX_LENGTH, ALNUM) + randomString(TENDER_DIGITS, DIGITS)
        val psp = SimulatedModifications.pspReference(random)
        return SimulatedGiftCardIds(tender, psp, poiData(tender, psp), clock.instant().atZone(zone))
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
        val serviceId = request.messageReference?.serviceID
        return completed.repeat(request)
            ?: CompletedTransactions.failed(
                if (serviceId != null && serviceId in inProgress) ErrorConditionType.IN_PROGRESS else ErrorConditionType.NOT_FOUND,
            )
    }

    private fun poiData(
        tender: String,
        psp: String?,
    ) = POIData().apply {
        val txId = if (psp == null) tender else "$tender.$psp"
        poiTransactionID = TerminalClient.transaction(txId, clock.instant())
        poiReconciliationID = reconciliationId.get().toString()
    }

    private fun totalsResponse(): GetTotalsResponse =
        GetTotalsResponse().apply {
            response = Response().apply { result = ResultType.SUCCESS }
            poiReconciliationID = reconciliationId.get().toString()
            transactionTotals += buildSimulatedTransactionTotals(periodTotals.values.toList())
        }

    private fun reconcileResponse(request: ReconciliationRequest): ReconciliationResponse {
        val currentId = reconciliationId.getAndIncrement().toString()
        val snapshot = periodTotals.values.toList()
        periodTotals.clear()
        return ReconciliationResponse().apply {
            response = Response().apply { result = ResultType.SUCCESS }
            reconciliationType = request.reconciliationType
            poiReconciliationID = currentId
            transactionTotals += buildSimulatedTransactionTotals(snapshot)
        }
    }

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
                add(receiptLine("header1", "Mini mPOS Simulator"))
                add(receiptLine("filler"))
                add(receiptLine("txdate", "Date", DateTimeFormatter.ofPattern("dd/MM/yyyy").format(now)))
                add(receiptLine("txtime", "Time", DateTimeFormatter.ofPattern("HH:mm:ss").format(now)))
                add(receiptLine("filler"))
                add(receiptLine("txtype", payment.typeLabel, bold = true))
                add(receiptLine("surcharge", "Surcharge", payment.surchargeAmount.toPlainString()))
                add(receiptLine("totalAmount", "TOTAL", payment.amountText, bold = true))
                add(receiptLine("filler"))
                add(receiptLine("cardholderHeader", copyLabel))
                add(receiptLine("paymentMethod", card.label))
                if (!payment.wallet) add(receiptLine("pan", "Card", card.maskedPan.takeLast(9)))
                add(receiptLine("posEntryMode", "Entry", if (payment.wallet) "Scanned" else "Contactless"))
                if (!payment.wallet) add(receiptLine("aid", "AID", card.aid))
                add(receiptLine("mid", "MID", "SIMULATOR"))
                add(receiptLine("tid", "TID", "00000001"))
                add(receiptLine("txRef", "Tender", payment.tender))
                add(receiptLine("mref", "Reference", payment.merchantReference))
                add(receiptLine("filler"))
                add(receiptLine(if (approved) "approved" else "refused", if (approved) "APPROVED" else "DECLINED", bold = true))
                if (signature) {
                    add(receiptLine("filler"))
                    add(receiptLine("sigline", "Signature"))
                    add(receiptLine("signature", "______________________"))
                }
                add(receiptLine("filler"))
                add(receiptLine("thanks", "Please retain for your records"))
            }
        return PaymentReceipt().apply {
            documentQualifier = qualifier
            setRequiredSignatureFlag(signature)
            outputContent = receiptText(lines)
        }
    }

    private fun randomString(
        length: Int,
        alphabet: String,
    ) = (1..length).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

    private fun respond(
        header: MessageHeader,
        response: SaleToPOIResponse,
    ) = TerminalAPIResponse().apply {
        saleToPOIResponse =
            response.apply {
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
            }
    }

    /** Fixed values that tests and callers can rely on. */
    companion object {
        /** The ServiceID a [SimulatedOutcome.BUSY] payment names as the transaction the terminal is busy with. */
        const val BUSY_SERVICE_ID = "SIMBUSY001"
        private const val INITIAL_RECONCILIATION_ID = 1000
        private const val POLL_MILLIS = 100L
        private const val PERCENT = 100
        private const val RANDOM_APPROVAL_PERCENT = 80
        private const val TOKEN_LENGTH = 16
        private const val TENDER_PREFIX_LENGTH = 4
        private const val TENDER_DIGITS = 15
        private const val AUTH_CODE_LENGTH = 6
        private const val DIGITS = "0123456789"
        private const val ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        private val CARDS =
            listOf(
                SimulatedCard("mc", "mc", "MasterCard", "541333 **** 9999", "A0000000041010", "M469509594859802"),
                SimulatedCard("visa", "visa", "Visa", "411111 **** 1111", "A0000000031010", "K421000000000001"),
                // Cards in a mobile wallet: the brand stays the scheme's, the variant names the wallet.
                SimulatedCard("visa", "visa_applepay", "Visa", "476173 **** 0119", "A0000000031010", "K421000000000002"),
                SimulatedCard("mc", "mc_googlepay", "MasterCard", "555555 **** 4444", "A0000000041010", "M469509594859803"),
            )

        // Adyen's Elo voucher test card: an app-selected Brazilian voucher answers with its documented PAT facts.
        private val MEAL_VOUCHER = SimulatedCard("elo", "elo_voucher_br", "Elo", "506723 **** 0002", "A0000004941010", "E469509594859804")
    }
}

/** Reads the actual wire account: an app-selected Brazilian meal or food voucher. */
internal fun mealVoucherRequested(saleData: SaleData?): Boolean =
    saleData?.saleToPOIData?.let { encoded ->
        runCatching {
            JsonParser
                .parseString(String(Base64.decode(encoded), Charsets.UTF_8))
                .asJsonObject
                .get(NexoVocabulary.SELECTED_ACCOUNT_TYPE)
                ?.asString == NexoVocabulary.MEAL_VOUCHER
        }.getOrDefault(false)
    } == true
