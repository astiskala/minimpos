package app.minimpos.terminal.client

import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageReference
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.nexo.TransactionStatusRequest
import com.adyen.model.nexo.TransactionStatusResponse
import com.adyen.model.terminal.TerminalAPIRequest
import kotlinx.coroutines.delay
import kotlin.time.Duration

/**
 * The message plumbing of one [TerminalClient]: nexo headers addressed with [identity], the Terminal API envelope, and
 * sending a financial request whose missing answer is recovered with transaction status checks of its original message
 * category, as [recovery] says. Shared by the client and its stored-value requests so every category recovers the same
 * way. Safe to call from any thread.
 *
 * @param transport Delivers the messages.
 * @param identity The SaleID and POIID of every header.
 * @param newServiceId Makes the ServiceIDs of status checks.
 * @param recovery How missing answers are recovered.
 * @property transactionTimeout How long a financial request waits before recovery.
 * @param shortTimeout How long a status check waits.
 * @param money Converts the amounts of recovered answers.
 */
internal class TerminalExchange(
    private val transport: TerminalTransport,
    private val identity: TerminalIdentity,
    private val newServiceId: () -> String,
    private val recovery: RecoveryPolicy,
    val transactionTimeout: Duration,
    private val shortTimeout: Duration,
    private val money: TerminalMoney,
) {
    /**
     * Sends [request] (ServiceID [serviceId], message [category]) and reads its answer with [extract]; a missing or
     * unreadable answer is recovered with status checks of the same category, never by sending it again.
     */
    internal suspend fun execute(
        serviceId: String,
        category: MessageCategoryType,
        request: SaleToPOIRequest,
        timeout: Duration = transactionTimeout,
        extract: (SaleToPOIResponse) -> TransactionDetails?,
    ): TransactionOutcome {
        val response =
            when (val delivery = transport.send(wrap(request), timeout)) {
                is Delivery.Answered -> delivery.response
                is Delivery.Failed if !delivery.fault.mayHaveTakenEffect -> return TransactionOutcome.NotProcessed(delivery.fault)
                is Delivery.Failed -> return recover(serviceId, category, delivery.fault)
            }
        val details =
            response?.saleToPOIResponse?.let(extract) ?: return recover(serviceId, category, Fault.UnreadableReply())
        return TransactionOutcome.Completed(details)
    }

    /**
     * Checks the status of the transaction whose answer went missing because of [fault]; while it stays unconfirmed the
     * outcome is unknown, for [fault] or, when the terminal kept reporting it in progress, [Fault.StillInProgress].
     */
    private suspend fun recover(
        serviceId: String,
        category: MessageCategoryType,
        fault: Fault,
    ): TransactionOutcome {
        var unanswered = 0
        var inProgress = 0
        while (unanswered < recovery.attempts && inProgress < recovery.maxInProgressChecks) {
            delay(recovery.intervalMillis)
            when (val check = checkStatus(serviceId, category)) {
                is StatusCheck.Settled -> return check.outcome
                StatusCheck.InProgress -> inProgress++
                is StatusCheck.NoAnswer -> unanswered++
            }
        }
        return TransactionOutcome.Unknown(if (inProgress >= recovery.maxInProgressChecks) Fault.StillInProgress else fault)
    }

    /** What one status check found. */
    internal sealed interface StatusCheck {
        data class Settled(
            val outcome: TransactionOutcome,
        ) : StatusCheck

        /** The terminal is still waiting for the shopper or the issuer. */
        data object InProgress : StatusCheck

        /** No usable status came back, because of [fault]. */
        data class NoAnswer(
            val fault: Fault,
        ) : StatusCheck
    }

    /** One status check of the transaction sent with [serviceId] in [category]. */
    internal suspend fun checkStatus(
        serviceId: String,
        category: MessageCategoryType,
    ): StatusCheck {
        val sent = transport.send(wrap(statusRequest(serviceId, category)), shortTimeout)
        val status =
            when (val answer = readTerminalAnswer(sent) { it.transactionStatusResponse }) {
                is TerminalAnswer.Unread -> return StatusCheck.NoAnswer(answer.fault)
                is TerminalAnswer.Read -> answer.body
            }
        if (!matchesOriginal(status, serviceId, category)) return StatusCheck.NoAnswer(Fault.UnreadableReply())
        val response = status.response
        return when {
            response?.result == ResultType.SUCCESS -> {
                val details = repeatedDetails(status, category)
                details?.let { StatusCheck.Settled(TransactionOutcome.Completed(it)) }
                    ?: StatusCheck.NoAnswer(Fault.UnreadableReply())
            }

            Decline.statusNotFound(response) -> {
                StatusCheck.Settled(TransactionOutcome.NotProcessed(Fault.NoRecord))
            }

            Decline.statusInProgress(response) -> {
                StatusCheck.InProgress
            }

            else -> {
                StatusCheck.NoAnswer(Fault.UnreadableReply())
            }
        }
    }

    private fun repeatedDetails(
        status: TransactionStatusResponse,
        category: MessageCategoryType,
    ): TransactionDetails? {
        val body = status.repeatedMessageResponse?.repeatedResponseMessageBody
        return when {
            category == MessageCategoryType.PAYMENT -> body?.paymentResponse?.let { TerminalClient.mapPayment(it, money::minor) }
            category == MessageCategoryType.REVERSAL -> body?.reversalResponse?.let { TerminalClient.mapReversal(it, null, money::minor) }
            else -> body?.storedValueResponse?.let { mapStoredValue(it, null, money::minor) }
        }
    }

    // Some terminals omit echoed identity fields. Any fields supplied must identify the original request.
    private fun matchesOriginal(
        status: TransactionStatusResponse,
        serviceId: String,
        category: MessageCategoryType,
    ): Boolean {
        val reference = status.messageReference
        val referenced =
            reference?.let {
                it.saleID == identity.saleId && it.serviceID == serviceId && it.messageCategory == category
            }
        if (referenced == false) return false
        val header = status.repeatedMessageResponse?.messageHeader ?: return true
        val addressed = listOf(header.saleID, header.serviceID, header.poiid) == listOf(identity.saleId, serviceId, identity.poiId)
        return addressed && header.messageCategory == category && header.messageType == MessageType.RESPONSE
    }

    private fun statusRequest(
        serviceId: String,
        category: MessageCategoryType,
    ) = message(MessageClassType.SERVICE, MessageCategoryType.TRANSACTION_STATUS, newServiceId()).apply {
        transactionStatusRequest =
            TransactionStatusRequest().apply {
                // Ask for the receipts again, so a recovered payment can be printed like any other.
                if (recovery.reprintReceipts) setReceiptReprintFlag(true)
                documentQualifier += listOf(DocumentQualifierType.CASHIER_RECEIPT, DocumentQualifierType.CUSTOMER_RECEIPT)
                messageReference =
                    MessageReference().apply {
                        messageCategory = category
                        saleID = identity.saleId
                        serviceID = serviceId
                    }
            }
    }

    /** A new message of [messageClass] and [category] with ServiceID [serviceId], addressed with [identity]. */
    internal fun message(
        messageClass: MessageClassType,
        category: MessageCategoryType,
        serviceId: String,
    ) = SaleToPOIRequest().apply {
        messageHeader =
            MessageHeader().apply {
                protocolVersion = TerminalClient.PROTOCOL_VERSION
                this.messageClass = messageClass
                messageCategory = category
                messageType = MessageType.REQUEST
                serviceID = serviceId
                saleID = identity.saleId
                poiid = identity.poiId
            }
    }

    /** [request] as the Terminal API envelope the transport sends. */
    internal fun wrap(request: SaleToPOIRequest) = TerminalAPIRequest().apply { saleToPOIRequest = request }

    /** Sends [request] once within [timeout], without recovery; for requests that change nothing on their own. */
    internal suspend fun send(
        request: SaleToPOIRequest,
        timeout: Duration,
    ): Delivery = transport.send(wrap(request), timeout)
}
