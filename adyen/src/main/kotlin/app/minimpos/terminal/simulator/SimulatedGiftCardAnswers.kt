package app.minimpos.terminal.simulator

import app.minimpos.terminal.client.NexoVocabulary
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.transport.CompletedTransactions
import app.minimpos.terminal.transport.base64Json
import com.adyen.model.nexo.AmountsResp
import com.adyen.model.nexo.BalanceInquiryRequest
import com.adyen.model.nexo.BalanceInquiryResponse
import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.OutputText
import com.adyen.model.nexo.POIData
import com.adyen.model.nexo.PaymentAccountStatus
import com.adyen.model.nexo.PaymentInstrumentData
import com.adyen.model.nexo.PaymentInstrumentType
import com.adyen.model.nexo.PaymentReceipt
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.PaymentResult
import com.adyen.model.nexo.PaymentType
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.StoredValueAccountID
import com.adyen.model.nexo.StoredValueAccountStatus
import com.adyen.model.nexo.StoredValueAccountType
import com.adyen.model.nexo.StoredValueData
import com.adyen.model.nexo.StoredValueRequest
import com.adyen.model.nexo.StoredValueResponse
import com.adyen.model.nexo.StoredValueResult
import com.adyen.model.nexo.StoredValueTransactionType
import java.math.BigDecimal
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** The gift card a payment request names; null for card payments and scanned wallets. */
internal fun PaymentRequest.giftCard(): StoredValueAccountID? =
    paymentData?.paymentInstrumentData?.storedValueAccountID?.takeIf { it.storedValueAccountType == StoredValueAccountType.GIFT_CARD }

/** Whether this message is a gift-card request: stored value, a balance inquiry or a gift-card payment. */
internal val SaleToPOIRequest.isGiftCardRequest: Boolean
    get() = storedValueRequest != null || balanceInquiryRequest != null || paymentRequest?.giftCard() != null

/**
 * The simulated terminal's gift-card requests, answered from [giftCards] after the simulated shopper ([waitForShopper]),
 * remembered for status checks in [completed]. A lost answer ([SimulatedOutcome.TIMEOUT]) still changes the card, then
 * throws so the client recovers it.
 *
 * @param giftCards The simulated accounts.
 * @param completed Answers status checks repeat.
 * @param ids Makes each answer's identifiers.
 * @param waitForShopper Waits for the simulated shopper; the configured outcome, or null when aborted.
 * @param onPayment Counts an approved gift-card payment in the terminal's totals.
 */
internal class SimulatedGiftCardTerminal(
    private val giftCards: SimulatedGiftCards,
    private val completed: CompletedTransactions,
    private val ids: () -> SimulatedGiftCardIds,
    private val waitForShopper: suspend (serviceId: String, settings: SimulatorConfig) -> SimulatedOutcome?,
    private val onPayment: (request: PaymentRequest, transactionId: String, approved: SimulatedGiftCards.Answer.Approved) -> Unit,
) {
    /**
     * Answers gift-card [message] sent with [serviceId] under [settings].
     *
     * @throws SocketTimeoutException When [settings] lose the answer after it took effect.
     */
    suspend fun answer(
        serviceId: String,
        message: SaleToPOIRequest,
        settings: SimulatorConfig,
    ): SaleToPOIResponse {
        val outcome = waitForShopper(serviceId, settings)
        val ids = ids()
        val response =
            when {
                message.storedValueRequest != null -> storedValue(serviceId, message.storedValueRequest, outcome, ids)
                message.balanceInquiryRequest != null -> balance(message.balanceInquiryRequest, outcome)
                else -> payment(serviceId, message.paymentRequest, outcome, ids)
            }
        if (outcome == SimulatedOutcome.TIMEOUT) throw SocketTimeoutException("Simulated timeout")
        return response
    }

    private fun payment(
        serviceId: String,
        request: PaymentRequest,
        outcome: SimulatedOutcome?,
        ids: SimulatedGiftCardIds,
    ): SaleToPOIResponse {
        val reply = SimulatedGiftCardReply(if (approves(outcome)) giftCards.answerPayment(request, ids.transactionId) else null, outcome)
        reply.approved?.let { onPayment(request, ids.transactionId, it) }
        val response = giftCardPaymentResponse(request, reply, ids, giftCards.lastFour(request.giftCard()))
        completed.remember(serviceId, payment = response)
        return SaleToPOIResponse().apply { paymentResponse = response }
    }

    private fun storedValue(
        serviceId: String,
        request: StoredValueRequest,
        outcome: SimulatedOutcome?,
        ids: SimulatedGiftCardIds,
    ): SaleToPOIResponse {
        val data = request.storedValueData.firstOrNull()
        val reply = SimulatedGiftCardReply(if (approves(outcome)) giftCards.answerStoredValue(data, ids.psp) else null, outcome)
        val response = storedValueResponse(request, reply, ids, data?.storedValueAccountID?.let(giftCards::lastFour))
        completed.remember(serviceId, storedValue = response)
        return SaleToPOIResponse().apply { storedValueResponse = response }
    }

    private fun balance(
        request: BalanceInquiryRequest,
        outcome: SimulatedOutcome?,
    ): SaleToPOIResponse {
        val account = request.paymentAccountReq?.paymentInstrumentData?.storedValueAccountID
        val reply = SimulatedGiftCardReply(if (approves(outcome)) giftCards.balance(giftCards.key(account)) else null, outcome)
        return SaleToPOIResponse().apply { balanceInquiryResponse = balanceInquiryResponse(request, reply, giftCards.lastFour(account)) }
    }
}

/** Whether this gift-card payment request is an unreferenced refund onto the card. */
internal val PaymentRequest.giftCardRefund: Boolean get() = paymentData?.paymentType == PaymentType.REFUND

/** Whether this gift-card payment request pays the card's value out. */
internal val PaymentRequest.giftCardCashOut: Boolean
    get() = saleData?.saleToAcquirerData?.redemptionType == TerminalClient.CASHBACK

/**
 * The identifiers a simulated gift-card answer carries.
 *
 * @property tender The tender reference.
 * @property psp The PSP reference.
 * @property poiData The terminal's transaction ID and reconciliation period.
 * @property at When it was answered, for the receipt.
 */
internal class SimulatedGiftCardIds(
    val tender: String,
    val psp: String,
    val poiData: POIData,
    val at: ZonedDateTime,
) {
    /** The terminal transaction ID, `<tender>.<psp>`. */
    val transactionId: String get() = "$tender.$psp"
}

/**
 * What the ledger answered, or null when the configured [outcome] refused before it was asked.
 *
 * @property answer The ledger's answer.
 * @property outcome The configured outcome; null when the request was aborted.
 */
internal class SimulatedGiftCardReply(
    val answer: SimulatedGiftCards.Answer?,
    val outcome: SimulatedOutcome?,
) {
    /** The approved answer; null when it was refused. */
    val approved: SimulatedGiftCards.Answer.Approved? get() = answer as? SimulatedGiftCards.Answer.Approved

    /** Why it was not done: the provider's refusal, else the configured outcome's; null when done. */
    val refusal: Pair<ErrorConditionType, String>?
        get() =
            when {
                approved != null -> null
                answer is SimulatedGiftCards.Answer.Refused -> ErrorConditionType.REFUSAL to answer.refusal
                else -> simulatedRefusal(outcome)
            }
}

/** Whether [outcome] lets the simulated provider act: approved, or approved with a lost answer. */
internal fun approves(outcome: SimulatedOutcome?): Boolean = outcome == SimulatedOutcome.APPROVE || outcome == SimulatedOutcome.TIMEOUT

/** What the ledger does with gift-card payment [request]: a refund onto the card, a cash-out or a redemption. */
internal fun SimulatedGiftCards.answerPayment(
    request: PaymentRequest,
    transactionId: String,
): SimulatedGiftCards.Answer {
    val account = request.giftCard()
    val key = key(account)
    val amounts = request.paymentTransaction.amountsReq
    return when {
        request.giftCardRefund -> {
            refund(key, amounts.requestedAmount, amounts.currency)
        }

        request.giftCardCashOut -> {
            cashOut(key, amounts.requestedAmount, amounts.currency, account?.storedValueProvider)
        }

        else -> {
            val partial =
                TerminalClient.ALLOW_PARTIAL_AUTHORISATION in
                    request.saleData
                        ?.saleToAcquirerData
                        ?.tenderOption
                        .orEmpty()
            redeem(key, amounts.requestedAmount, amounts.currency, partial, transactionId)
        }
    }
}

/** What the ledger does with stored-value [data], a load being recorded as [psp]. */
internal fun SimulatedGiftCards.answerStoredValue(
    data: StoredValueData?,
    psp: String,
): SimulatedGiftCards.Answer {
    val account = data?.storedValueAccountID
    val key = key(account)
    val amount = data?.itemAmount ?: BigDecimal.ZERO
    val currency = data?.currency.orEmpty()
    return when (data?.storedValueTransactionType) {
        StoredValueTransactionType.ACTIVATE -> {
            activate(key, amount, currency)
        }

        StoredValueTransactionType.LOAD -> {
            load(key, amount, currency, psp)
        }

        StoredValueTransactionType.UNLOAD -> {
            deactivate(key, account?.storedValueProvider)
        }

        StoredValueTransactionType.REVERSE -> {
            undo(
                data.originalPOITransaction
                    ?.poiTransactionID
                    ?.transactionID
                    .orEmpty(),
            )
        }

        StoredValueTransactionType.RESERVE, StoredValueTransactionType.DUPLICATE, null -> {
            SimulatedGiftCards.Answer.Refused("Unsupported stored value operation (simulated)")
        }
    }
}

/** The terminal's answer to gift-card payment [request], as [reply] decided, with the card's [lastFour]. */
internal fun giftCardPaymentResponse(
    request: PaymentRequest,
    reply: SimulatedGiftCardReply,
    ids: SimulatedGiftCardIds,
    lastFour: String,
): PaymentResponse {
    val account = request.giftCard()
    val amounts = request.paymentTransaction.amountsReq
    val approved = reply.approved
    val type =
        when {
            request.giftCardRefund -> "Gift card refund"
            request.giftCardCashOut -> "Gift card cash out"
            else -> "Gift card payment"
        }
    return PaymentResponse().apply {
        response =
            giftCardResult(approved, reply.refusal, ids.psp, mapOf(NexoVocabulary.PAYMENT_METHOD to account?.storedValueProvider.orEmpty()))
        saleData = SaleData().apply { saleTransactionID = request.saleData?.saleTransactionID }
        poiData = ids.poiData
        paymentResult =
            PaymentResult().apply {
                paymentType = if (request.giftCardRefund) PaymentType.REFUND else PaymentType.NORMAL
                paymentInstrumentData = giftCardInstrument(account, lastFour)
                amountsResp =
                    AmountsResp().apply {
                        currency = amounts.currency
                        authorizedAmount = approved?.approved ?: BigDecimal.ZERO
                        // Simulated gift cards carry no surcharge; whether real ones report the field is unverified.
                        totalFeesAmount = BigDecimal.ZERO.setScale(amounts.requestedAmount.scale())
                    }
            }
        val amount = "${amounts.currency} ${(approved?.approved ?: amounts.requestedAmount).toPlainString()}"
        paymentReceipt += giftCardReceipt(GiftCardReceiptFacts(type, amount, account?.storedValueProvider, lastFour, approved != null), ids)
    }
}

/** The terminal's answer to stored-value [request], as [reply] decided, with the card's [lastFour] when it named one. */
internal fun storedValueResponse(
    request: StoredValueRequest,
    reply: SimulatedGiftCardReply,
    ids: SimulatedGiftCardIds,
    lastFour: String?,
): StoredValueResponse {
    val data = request.storedValueData.firstOrNull()
    val approved = reply.approved
    val currency = approved?.currency ?: data?.currency
    val type =
        when (data?.storedValueTransactionType) {
            StoredValueTransactionType.ACTIVATE -> {
                "Gift card activation"
            }

            StoredValueTransactionType.LOAD -> {
                "Gift card load"
            }

            StoredValueTransactionType.REVERSE -> {
                "Gift card load undone"
            }

            StoredValueTransactionType.UNLOAD, StoredValueTransactionType.RESERVE, StoredValueTransactionType.DUPLICATE, null -> {
                "Gift card deactivation"
            }
        }
    return StoredValueResponse().apply {
        response = giftCardResult(approved, reply.refusal, ids.psp, emptyMap())
        saleData = SaleData().apply { saleTransactionID = request.saleData?.saleTransactionID }
        poiData = ids.poiData
        storedValueResult +=
            StoredValueResult().apply {
                storedValueTransactionType = data?.storedValueTransactionType
                this.currency = currency?.ifEmpty { null }
                itemAmount = approved?.balance
                storedValueAccountStatus = accountStatus(data?.storedValueAccountID?.storedValueProvider, lastFour, approved?.balance)
            }
        val amount = "${currency.orEmpty()} ${(approved?.approved ?: data?.itemAmount ?: BigDecimal.ZERO).toPlainString()}"
        val facts = GiftCardReceiptFacts(type, amount, data?.storedValueAccountID?.storedValueProvider, lastFour, approved != null)
        paymentReceipt = listOf(giftCardReceipt(facts, ids))
    }
}

/** The terminal's answer to balance [request], as [reply] decided, with the card's [lastFour]. */
internal fun balanceInquiryResponse(
    request: BalanceInquiryRequest,
    reply: SimulatedGiftCardReply,
    lastFour: String,
): BalanceInquiryResponse {
    val read = reply.approved
    val refusal = reply.refusal
    return BalanceInquiryResponse().apply {
        response =
            Response().apply {
                result = if (read != null) ResultType.SUCCESS else ResultType.FAILURE
                errorCondition = refusal?.first
                refusal?.let { additionalResponse = base64Json(mapOf(NexoVocabulary.REFUSAL_REASON to it.second)) }
            }
        paymentAccountStatus =
            read?.let {
                PaymentAccountStatus().apply {
                    currency = it.currency
                    currentBalance = it.balance
                    paymentInstrumentData =
                        giftCardInstrument(request.paymentAccountReq?.paymentInstrumentData?.storedValueAccountID, lastFour)
                }
            }
    }
}

private fun accountStatus(
    provider: String?,
    lastFour: String?,
    balance: BigDecimal?,
) = StoredValueAccountStatus().apply {
    currentBalance = balance
    storedValueAccountID =
        StoredValueAccountID().apply {
            storedValueProvider = provider
            storedValueID = lastFour?.let { "**** $it" }
        }
}

private fun giftCardResult(
    approved: SimulatedGiftCards.Answer.Approved?,
    refusal: Pair<ErrorConditionType, String>?,
    psp: String,
    extra: Map<String, String>,
) = Response().apply {
    result =
        when {
            approved == null -> ResultType.FAILURE
            approved.partial -> ResultType.PARTIAL
            else -> ResultType.SUCCESS
        }
    errorCondition = refusal?.first
    additionalResponse =
        base64Json(
            buildMap {
                put(NexoVocabulary.PSP_REFERENCE, psp)
                putAll(extra)
                refusal?.let { put(NexoVocabulary.REFUSAL_REASON, it.second) }
            },
        )
}

private fun giftCardInstrument(
    account: StoredValueAccountID?,
    lastFour: String,
) = PaymentInstrumentData().apply {
    paymentInstrumentType = PaymentInstrumentType.STORED_VALUE
    storedValueAccountID =
        StoredValueAccountID().apply {
            storedValueAccountType = account?.storedValueAccountType
            storedValueProvider = account?.storedValueProvider
            storedValueID = "**** $lastFour"
        }
}

/** What a simulated gift-card receipt says. */
private class GiftCardReceiptFacts(
    val type: String,
    val amount: String,
    val provider: String?,
    val lastFour: String?,
    val approved: Boolean,
)

private fun giftCardReceipt(
    facts: GiftCardReceiptFacts,
    ids: SimulatedGiftCardIds,
): PaymentReceipt {
    val lines =
        buildList {
            add(receiptLine("header1", "Mini mPOS Simulator"))
            add(receiptLine("filler"))
            add(receiptLine("txdate", "Date", DateTimeFormatter.ofPattern("dd/MM/yyyy").format(ids.at)))
            add(receiptLine("txtime", "Time", DateTimeFormatter.ofPattern("HH:mm:ss").format(ids.at)))
            add(receiptLine("filler"))
            add(receiptLine("txtype", facts.type, bold = true))
            add(receiptLine("totalAmount", "TOTAL", facts.amount, bold = true))
            add(receiptLine("filler"))
            add(receiptLine("paymentMethod", facts.provider.orEmpty()))
            facts.lastFour?.let { add(receiptLine("pan", "Card", "**** $it")) }
            add(receiptLine("txRef", "Tender", ids.tender))
            add(receiptLine("filler"))
            add(receiptLine(if (facts.approved) "approved" else "refused", if (facts.approved) "APPROVED" else "DECLINED", bold = true))
        }
    return PaymentReceipt().apply {
        documentQualifier = DocumentQualifierType.CUSTOMER_RECEIPT
        outputContent = receiptText(lines)
    }
}

/** Simulated receipt [lines] as nexo text output. */
internal fun receiptText(lines: List<OutputText>) =
    OutputContent().apply {
        outputFormat = OutputFormatType.TEXT
        outputText = lines
    }

/** One simulated receipt line, form-encoded the way terminals send them. */
internal fun receiptLine(
    key: String,
    name: String? = null,
    value: String? = null,
    bold: Boolean = false,
): OutputText {
    val fields = listOfNotNull("key" to key, name?.let { "name" to it }, value?.let { "value" to it })
    return OutputText().apply {
        text = fields.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
        if (bold) characterStyle = CharacterStyleType.BOLD
        setEndOfLineFlag(true)
    }
}

private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
