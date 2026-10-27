package app.minimpos.terminal.transport

import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalResponse
import com.adyen.model.nexo.StoredValueResponse
import com.adyen.model.nexo.TransactionStatusRequest
import com.adyen.model.nexo.TransactionStatusResponse
import java.util.concurrent.ConcurrentHashMap

/**
 * Finished payments and reversals by the ServiceID of their request, so a transaction status request can be answered
 * the way a terminal answers it: by repeating the transaction's response (the shape `TerminalClient` reads back). For
 * transports that answer status requests themselves (the simulator, and the Payments app from its late answers). Kept
 * in memory only; thread-safe.
 */
internal class CompletedTransactions {
    private val bodies = ConcurrentHashMap<String, Entry>()
    private val byTransaction = mutableMapOf<String, MutableSet<String>>()

    /** Releases all cached status answers after confirmed complete history deletion. */
    @Synchronized
    fun clear() {
        bodies.clear()
        byTransaction.clear()
    }

    /** Releases exact stored identities after durable history deletion, including earlier split-tender requests. */
    @Synchronized
    fun forget(
        serviceIds: Set<String>,
        transactionIds: Set<String>,
    ) {
        val ids = serviceIds + transactionIds.flatMap { byTransaction[it].orEmpty() }
        ids.forEach { id ->
            val transaction = bodies.remove(id)?.transactionId
            transaction?.let {
                byTransaction[it]?.remove(id)
                if (byTransaction[it].isNullOrEmpty()) byTransaction.remove(it)
            }
        }
    }

    /**
     * Remembers the response of the transaction sent with [serviceId]: a [payment], a [reversal] or a gift-card
     * [storedValue] operation; with none, nothing is remembered. A later response for the same ServiceID replaces the
     * earlier one. [ownerTransactionId] ties a related Giving reply to its payment's durable retention identity without
     * altering that reply's protocol fields.
     */
    @Synchronized
    fun remember(
        serviceId: String,
        payment: PaymentResponse? = null,
        reversal: ReversalResponse? = null,
        ownerTransactionId: String? = null,
        storedValue: StoredValueResponse? = null,
    ) {
        if (payment == null && reversal == null && storedValue == null) return
        forget(setOf(serviceId), emptySet())
        val transactionId = ownerTransactionId ?: payment?.poiData?.poiTransactionID?.transactionID
        transactionId?.let {
            byTransaction.getOrPut(it) { mutableSetOf() }.add(serviceId)
        }
        val body =
            RepeatedResponseMessageBody().apply {
                paymentResponse = payment
                reversalResponse = reversal
                storedValueResponse = storedValue
            }
        bodies[serviceId] = Entry(body, transactionId)
    }

    /**
     * The successful status response that repeats the transaction [request] asks about; null when it is not
     * remembered.
     */
    fun repeat(request: TransactionStatusRequest): TransactionStatusResponse? {
        val body =
            request.messageReference
                ?.serviceID
                ?.let(bodies::get)
                ?.body ?: return null
        return TransactionStatusResponse().apply {
            response = Response().apply { result = ResultType.SUCCESS }
            messageReference = request.messageReference
            repeatedMessageResponse = RepeatedMessageResponse().apply { repeatedResponseMessageBody = body }
        }
    }

    private class Entry(
        val body: RepeatedResponseMessageBody,
        val transactionId: String?,
    )

    /** Status responses for transactions that are not remembered. */
    companion object {
        /** A failed status response with [condition], such as `NotFound` or `InProgress`. */
        fun failed(condition: ErrorConditionType): TransactionStatusResponse =
            TransactionStatusResponse().apply {
                response =
                    Response().apply {
                        result = ResultType.FAILURE
                        errorCondition = condition
                    }
            }
    }
}
