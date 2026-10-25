package app.minimpos.terminal.client

import app.minimpos.terminal.parse.AdditionalResponseParser
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import com.adyen.model.nexo.BalanceInquiryRequest
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.OriginalPOITransaction
import com.adyen.model.nexo.PaymentAccountReq
import com.adyen.model.nexo.PaymentData
import com.adyen.model.nexo.PaymentInstrumentData
import com.adyen.model.nexo.PaymentInstrumentType
import com.adyen.model.nexo.PaymentType
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleData
import com.adyen.model.nexo.StoredValueData
import com.adyen.model.nexo.StoredValueRequest
import com.adyen.model.nexo.StoredValueResponse
import com.adyen.model.nexo.StoredValueTransactionType
import com.adyen.model.nexo.TransactionIdentification
import java.math.BigDecimal

/**
 * Activates, loads, undoes the latest load of, or deactivates a gift card: a `StoredValueRequest` with message category
 * `StoredValue`. The shopper may have to swipe or key in the card, so it waits like a payment; a missing answer is
 * settled with transaction status checks for category `StoredValue`, never by sending it again. The answer's balance is
 * [TransactionDetails.balanceMinor]; its transaction ID identifies a load for a later undo. Main-safe.
 *
 * @param params What to do with which card.
 * @param serviceId The request's ServiceID; pass one to record it before the terminal is called.
 * @return The terminal's answer, or why there is none.
 * @throws IllegalArgumentException If an undo's original timestamp is not an XML date-time.
 */
suspend fun TerminalClient.storedValue(
    params: StoredValueParams,
    serviceId: String = newServiceId(),
): TransactionOutcome {
    val data = storedValueData(params)
    val request =
        exchange.message(MessageClassType.SERVICE, MessageCategoryType.STORED_VALUE, serviceId).apply {
            storedValueRequest =
                StoredValueRequest().apply {
                    saleData =
                        SaleData().apply { saleTransactionID = TerminalClient.transaction(params.merchantReference, clock.instant()) }
                    storedValueData += data
                }
        }
    return exchange.execute(serviceId, MessageCategoryType.STORED_VALUE, request) {
        it.storedValueResponse?.let { answer -> mapStoredValue(answer, params.account?.lastFour, money::minor) }
    }
}

/** [params] as nexo's `StoredValueData`: the operation, the card or the load it undoes, and the amount. */
private fun TerminalClient.storedValueData(params: StoredValueParams): StoredValueData =
    StoredValueData().apply {
        storedValueTransactionType =
            when (params.operation) {
                StoredValueOperation.ACTIVATE -> StoredValueTransactionType.ACTIVATE
                StoredValueOperation.LOAD -> StoredValueTransactionType.LOAD
                StoredValueOperation.UNDO_LOAD -> StoredValueTransactionType.REVERSE
                StoredValueOperation.DEACTIVATE -> StoredValueTransactionType.UNLOAD
            }
        storedValueAccountID = params.account?.toNexo()
        originalPOITransaction = params.originalTransactionId?.let { original(it, checkNotNull(params.originalTimestamp)) }
        if (params.operation != StoredValueOperation.UNDO_LOAD) {
            itemAmount = money.major(params.amountMinor, params.currency)
            currency = params.currency
        }
    }

/** The load an undo refers to, by its transaction [id] and [timestamp] exactly as received. */
private fun original(
    id: String,
    timestamp: String,
) = OriginalPOITransaction().apply {
    poiTransactionID =
        TransactionIdentification().apply {
            transactionID = id
            timeStamp = TerminalClient.parseTimestamp(timestamp)
        }
}

/**
 * Reads a gift card's balance (`BalanceInquiryRequest`, message category `BalanceInquiry`). Nothing changes on the card,
 * so a missing answer is simply a failure to read: it is never recovered with a status check. Main-safe.
 *
 * @param account The card; a scanned number is consumed.
 * @return The balance, or why there is none. I/O errors are not thrown.
 */
suspend fun TerminalClient.balance(account: GiftCardAccount): GiftCardBalance {
    val request =
        exchange.message(MessageClassType.SERVICE, MessageCategoryType.BALANCE_INQUIRY, newServiceId()).apply {
            balanceInquiryRequest =
                BalanceInquiryRequest().apply {
                    paymentAccountReq = PaymentAccountReq().apply { paymentInstrumentData = account.instrument() }
                }
        }
    val body =
        when (val answer = readTerminalAnswer(exchange.send(request, exchange.transactionTimeout)) { it.balanceInquiryResponse }) {
            is TerminalAnswer.Unread -> return GiftCardBalance.Failed(answer.fault)
            is TerminalAnswer.Read -> answer.body
        }
    val response = body.response
    val status = body.paymentAccountStatus
    val currency = status?.currency
    val balance = money.minor(status?.currentBalance, currency)
    return when {
        response?.result == ResultType.SUCCESS && balance != null && currency != null -> {
            val reported = lastFourDigits(status.paymentInstrumentData?.storedValueAccountID?.storedValueID)
            GiftCardBalance.Read(balance, currency, reported ?: account.lastFour)
        }

        response?.result == null || response.result == ResultType.SUCCESS -> {
            GiftCardBalance.Failed(Fault.UnreadableReply())
        }

        else -> {
            val additional = AdditionalResponseParser.parse(response.additionalResponse)
            GiftCardBalance.Refused(
                Decline.of(false, response.errorCondition?.value(), additional[NexoVocabulary.REFUSAL_REASON], additional),
                ExternalText.of(additional[NexoVocabulary.REFUSAL_REASON] ?: additional[NexoVocabulary.MESSAGE]),
            )
        }
    }
}

/** This card as a StoredValue payment instrument, consuming a scanned number. */
private fun GiftCardAccount.instrument() =
    PaymentInstrumentData().apply {
        paymentInstrumentType = PaymentInstrumentType.STORED_VALUE
        storedValueAccountID = toNexo()
    }

/** This gift-card payment as nexo's `PaymentData`: the card, and `PaymentType=Refund` for a refund onto it. */
internal fun GiftCardPayment.paymentData(): PaymentData =
    PaymentData().apply {
        if (use == GiftCardUse.REFUND) paymentType = PaymentType.REFUND
        paymentInstrumentData = account.instrument()
    }

/**
 * The gift-card management [response] said, with the balance it reports and the last four digits it reports or the
 * scanned card ended with ([scannedLastFour]); null, so the operation stays unknown, when it has no result this version
 * can read.
 */
internal fun mapStoredValue(
    response: StoredValueResponse,
    scannedLastFour: String?,
    minor: (BigDecimal?, String?) -> Long?,
): TransactionDetails? {
    if (response.response?.result == null) return null
    val additional = AdditionalResponseParser.parse(response.response?.additionalResponse)
    val result = response.storedValueResult?.firstOrNull()
    val account = result?.storedValueAccountStatus
    val currency = result?.currency
    val lastFour = lastFourDigits(account?.storedValueAccountID?.storedValueID) ?: scannedLastFour
    return TerminalClient
        .details(
            response = response.response,
            additional = additional,
            poiData = response.poiData,
            receipts = response.paymentReceipt,
        ).copy(
            merchantReference = response.saleData?.saleTransactionID?.transactionID,
            balanceMinor = minor(account?.currentBalance ?: result?.itemAmount, currency),
            currency = currency,
            paymentBrand = account?.storedValueAccountID?.storedValueProvider,
            maskedPan = lastFour?.let { "**** $it" },
        )
}
