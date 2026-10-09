package app.minimpos.terminal.client

/**
 * Why a payment or refund was not approved: the one reading of nexo's `ErrorCondition` and Adyen's refusal reason, so
 * no caller compares those strings itself. It comes with a live answer ([TransactionDetails.decline]) and can be read
 * again from the two values stored with a transaction ([of]).
 *
 * @property errorCondition `Response.ErrorCondition` as the terminal sent it, e.g. `Refusal` or `Cancel`; null when it
 *   sent none.
 * @property refusalReason `refusalReason` from the `AdditionalResponse`, e.g. `Not enough balance`; null when absent.
 * @property busyServiceId With ErrorCondition `Busy`, the ServiceID of the transaction the terminal is busy with (from
 *   the `AdditionalResponse`), which can be aborted; else null.
 */
data class Decline(
    val errorCondition: String?,
    val refusalReason: String?,
    val busyServiceId: String? = null,
) {
    /**
     * Whether it was cancelled on the terminal (ErrorCondition `Cancel` or `Aborted`, by the shopper or an
     * AbortRequest) rather than refused.
     */
    val cancelled: Boolean get() = errorCondition == CANCEL || errorCondition == ABORTED

    /**
     * What to do next, from Adyen's decision tables for declined payments: decided on the ErrorCondition, and for
     * `Cancel`, `InvalidCard` and `Refusal` also on the refusal reason (matched case-insensitively; Adyen advises against
     * coding on `refusalReasonRaw`). Null without an ErrorCondition, when there is nothing to base advice on; unknown
     * conditions give [RetryAdvice.RETRY].
     */
    val advice: RetryAdvice?
        get() {
            val reason = refusalReason.orEmpty()
            return when (errorCondition ?: return null) {
                ABORTED, "WrongPIN", "UnreachableHost", "NotFound" -> RetryAdvice.RETRY
                "DeviceOut", "UnavailableDevice" -> RetryAdvice.WAIT_AND_RETRY
                BUSY -> RetryAdvice.TERMINAL_BUSY
                "NotAllowed", "PaymentRestriction" -> RetryAdvice.DIFFERENT_PAYMENT_METHOD
                "MessageFormat", "UnavailableService" -> RetryAdvice.CHECK_SETUP
                CANCEL -> if (reason.equals("Approved", ignoreCase = true)) RetryAdvice.DO_NOT_RETRY else RetryAdvice.RETRY
                "InvalidCard" -> if (INVALID_CARD_FINAL.matches(reason)) RetryAdvice.DIFFERENT_PAYMENT_METHOD else RetryAdvice.RETRY
                "Refusal" -> if (REFUSAL_FINAL.matches(reason)) RetryAdvice.DIFFERENT_PAYMENT_METHOD else RetryAdvice.RETRY
                else -> RetryAdvice.RETRY
            }
        }

    /** Whether [reason] contains one of these refusal reasons, ignoring case. */
    private fun List<String>.matches(reason: String) = any { reason.contains(it, ignoreCase = true) }

    /** Reading a decline, and Adyen's final refusal reasons. */
    companion object {
        private const val CANCEL = "Cancel"
        private const val ABORTED = "Aborted"
        private const val BUSY = "Busy"

        /** The `AdditionalResponse` key that names the transaction a busy terminal is working on. */
        private const val SERVICE_ID = "serviceId"

        private val INVALID_CARD_FINAL = listOf("Card data authentication failed")

        private val REFUSAL_FINAL =
            listOf(
                "AID banned",
                "Always refused",
                "Amount too low to be accepted by Card Network",
                "Card is blocked",
                "Do Not Honor",
                "Issuer Suspected Fraud",
                "Pin validation not possible",
                "Restricted Card",
            )

        /**
         * The decline of a transaction that did not [succeed][TransactionDetails.success], from its [errorCondition],
         * [refusalReason] and flattened `AdditionalResponse` ([additionalData]; a stored transaction keeps none, so it
         * names no busy transaction); null when it succeeded.
         */
        fun of(
            succeeded: Boolean,
            errorCondition: String?,
            refusalReason: String?,
            additionalData: Map<String, String> = emptyMap(),
        ): Decline? =
            if (succeeded) {
                null
            } else {
                Decline(errorCondition, refusalReason, additionalData[SERVICE_ID]?.takeIf { errorCondition == BUSY })
            }
    }
}
