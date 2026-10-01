package io.minimpos.terminal.client

/**
 * What to do after a failed payment, following Adyen's "Handle responses" guidance: decide on the ErrorCondition, and
 * for Cancel, InvalidCard and Refusal also on the refusal reason. (Adyen advises against coding on `refusalReasonRaw`.)
 */
enum class RetryAdvice {
    /** Try again, e.g. after the shopper cancelled or entered a wrong PIN. */
    RETRY,

    /** The terminal (or its connection to Adyen) is unavailable: wait, then try again. */
    WAIT_AND_RETRY,

    /** Declined for a reason that will not change: ask for a different card or payment method. */
    DIFFERENT_PAYMENT_METHOD,

    /** The terminal is busy with another transaction or its admin menu. */
    TERMINAL_BUSY,

    /** The request was not accepted as sent (message format, unsupported service): the setup must be fixed first. */
    CHECK_SETUP,

    /** Nothing to retry (the payment went through). */
    DO_NOT_RETRY,
    ;

    /** Adyen's decision tables for declined payments. */
    companion object {
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
         * The advice for a payment that was not approved.
         *
         * @param errorCondition The nexo `ErrorCondition` as a string ([TransactionDetails.errorCondition]), e.g.
         *   `Refusal`. Unknown or missing conditions give [RETRY].
         * @param refusalReason The `refusalReason` from the `AdditionalResponse`, which decides between retrying and
         *   asking for another card for `Cancel`, `InvalidCard` and `Refusal`; matched case-insensitively.
         */
        fun forPayment(
            errorCondition: String?,
            refusalReason: String?,
        ): RetryAdvice {
            val reason = refusalReason.orEmpty()
            return when (errorCondition) {
                "Aborted", "WrongPIN", "UnreachableHost", "NotFound" -> RETRY
                "DeviceOut", "UnavailableDevice" -> WAIT_AND_RETRY
                "Busy" -> TERMINAL_BUSY
                "NotAllowed", "PaymentRestriction" -> DIFFERENT_PAYMENT_METHOD
                "MessageFormat", "UnavailableService" -> CHECK_SETUP
                "Cancel" -> if (reason.equals("Approved", ignoreCase = true)) DO_NOT_RETRY else RETRY
                "InvalidCard" -> if (INVALID_CARD_FINAL.any { reason.contains(it, ignoreCase = true) }) DIFFERENT_PAYMENT_METHOD else RETRY
                "Refusal" -> if (REFUSAL_FINAL.any { reason.contains(it, ignoreCase = true) }) DIFFERENT_PAYMENT_METHOD else RETRY
                else -> RETRY
            }
        }
    }
}
