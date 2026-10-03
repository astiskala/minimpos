package io.minimpos.terminal.transport

import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.PaymentResponse
import com.adyen.model.nexo.RepeatedMessageResponse
import com.adyen.model.nexo.RepeatedResponseMessageBody
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.ReversalResponse
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
    private val bodies = ConcurrentHashMap<String, RepeatedResponseMessageBody>()

    /**
     * Remembers the response of the transaction sent with [serviceId]: a [payment] or a [reversal]; with neither, nothing
     * is remembered. A later response for the same ServiceID replaces the earlier one.
     */
    fun remember(
        serviceId: String,
        payment: PaymentResponse? = null,
        reversal: ReversalResponse? = null,
    ) {
        if (payment == null && reversal == null) return
        bodies[serviceId] =
            RepeatedResponseMessageBody().apply {
                paymentResponse = payment
                reversalResponse = reversal
            }
    }

    /**
     * The successful status response that repeats the transaction [request] asks about; null when it is not
     * remembered.
     */
    fun repeat(request: TransactionStatusRequest): TransactionStatusResponse? {
        val body = request.messageReference?.serviceID?.let(bodies::get) ?: return null
        return TransactionStatusResponse().apply {
            response = Response().apply { result = ResultType.SUCCESS }
            messageReference = request.messageReference
            repeatedMessageResponse = RepeatedMessageResponse().apply { repeatedResponseMessageBody = body }
        }
    }

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
