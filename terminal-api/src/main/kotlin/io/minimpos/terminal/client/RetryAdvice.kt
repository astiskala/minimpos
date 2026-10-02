package io.minimpos.terminal.client

/**
 * What to do after a failed payment, following Adyen's "Handle responses" guidance; [Decline.advice] decides it from
 * the ErrorCondition and refusal reason.
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
}
