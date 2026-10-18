package app.minimpos.terminal.client

/**
 * The string vocabulary of the nexo `AdditionalResponse`/`AdditionalData` fields and the Base64 tip-operation blob, in
 * one place for everyone that writes or reads it: [TerminalClient]'s request building and answer mapping, [Decline]'s
 * busy-transaction reading, the shopper display and the simulator that stands in for a terminal. The simulator reads
 * what the client writes rather than a copy, so a key renamed on one side no longer compiles on the other.
 */
internal object NexoVocabulary {
    /** The terminal's or acquirer's own text about a refusal or rejection, shown verbatim. */
    const val MESSAGE = "message"

    /** Adyen's structured refusal reasons (`errors` of the flattened `AdditionalResponse`). */
    const val ERRORS = "errors"

    /** Adyen's structured warnings of the flattened `AdditionalResponse`. */
    const val WARNINGS = "warnings"

    /** Adyen's machine-readable refusal reason, e.g. `Not enough balance`. */
    const val REFUSAL_REASON = "refusalReason"

    /** Adyen's PSP reference of the transaction. */
    const val PSP_REFERENCE = "pspReference"

    /** The merchant reference the sale was sent with, echoed by a reversal answer. */
    const val MERCHANT_REFERENCE = "merchantReference"

    /** The acquirer's approval code. */
    const val AUTH_CODE = "authCode"

    /** Approval code of an offline EMV approval; present only when the payment was approved offline. */
    const val OFFLINE_AUTH_CODE = "offlineAuthCode"

    /** Present exactly when the terminal approved the payment offline (`OnlineFlag=false`). */
    const val OFFLINE = "offline"

    /** Stored-and-forwarded offline payments the terminal has not confirmed as forwarded yet. */
    const val UNCONFIRMED_BATCH_COUNT = "unconfirmedBatchCount"

    /** With ErrorCondition `Busy`, the ServiceID of the transaction the terminal is busy with. */
    const val BUSY_SERVICE_ID = "serviceId"

    /** The payment method, e.g. `visa`. */
    const val PAYMENT_METHOD = "paymentMethod"

    /** The payment method variant, e.g. `visa`. */
    const val PAYMENT_METHOD_VARIANT = "paymentMethodVariant"

    /** How the card was entered, e.g. `CLESS_CHIP` or `Scanned`. */
    const val POS_ENTRY_MODE = "posEntryMode"

    /** The card alias returned for a tokenization request. */
    const val ALIAS = "alias"

    /** The stored payment method made by a tokenization request. */
    const val TOKENIZATION_STORED_PAYMENT_METHOD_ID = "tokenization.storedPaymentMethodId"

    /** The shopper reference the tokenized card is stored under. */
    const val TOKENIZATION_SHOPPER_REFERENCE = "tokenization.shopperReference"

    /** What the tokenization did, e.g. `created`. */
    const val TOKENIZATION_OPERATION_TYPE = "tokenization.store.operationType"

    /** The recurring detail reference of a saved card, as Adyen's Checkout API names it. */
    const val RECURRING_DETAIL_REFERENCE = "recurring.recurringDetailReference"

    /** The shopper reference of a saved card, as Adyen's Checkout API names it. */
    const val RECURRING_SHOPPER_REFERENCE = "recurring.shopperReference"

    /** The recurring processing model of a saved card, as Adyen's Checkout API names it. */
    const val RECURRING_PROCESSING_MODEL = "recurringProcessingModel"

    /** The shopper email echoed with a saved card. */
    const val SHOPPER_EMAIL = "shopperEmail"

    /** Summary of the card's masked PAN, e.g. its last four digits. */
    const val CARD_SUMMARY = "cardSummary"

    /** The ServiceID of the two-step Adyen Giving donation, answered by a later status lookup. */
    const val ADYEN_GIVING_SERVICE_ID = "adyen_giving_service_id"

    /** The SaleID (`AdyenGiving`) of the two-step Adyen Giving donation status lookup. */
    const val ADYEN_GIVING_SALE_ID = "adyen_giving_sale_id"

    /** Adyen's `adjustAuthorisationData` blob for adjusting an uncaptured amount. */
    const val ADJUST_AUTHORISATION_DATA = "adjustAuthorisationData"

    /** The decoded scanned value of a native barcode scan answer. */
    const val BARCODE_DATA = "Barcode.Data"

    /** Tip-operation blob: the list of operations the payment request carries. */
    const val OPERATION = "Operation"

    /** Tip-operation blob: the tip options of the payment operation. */
    const val TIP_OPTIONS = "TipOptions"

    /** Tip-operation blob: the configured tip suggestions, fixed amounts or percentages. */
    const val TIP_SUGGESTIONS = "TipSuggestions"

    /** Tip-operation blob: the amount the tip percentages apply to. */
    const val AMOUNT_TO_TIP_ON = "AmountToTipOn"

    /** Tip-operation blob: whether the shopper may enter a custom tip. */
    const val TIP_CUSTOM = "TipCustom"

    /** Tip-operation blob: the key prefix of a fixed tip amount, followed by its 1-based index. */
    const val TIP_AMOUNT = "Amount"

    /** Tip-operation blob: the key prefix of a tip percentage, followed by its 1-based index. */
    const val TIP_PERCENTAGE = "Percentage"

    /** The first fixed tip amount the terminal suggests. */
    const val FIRST_TIP_AMOUNT = "Amount1"

    /** The first tip percentage the terminal suggests. */
    const val FIRST_TIP_PERCENTAGE = "Percentage1"
}
