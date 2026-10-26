package app.minimpos.core.access

/**
 * Independently configurable actions. Stored keys are stable enum names; new actions use their declared defaults.
 *
 * @property defaultRequired Whether the existing Manager PIN policy protects this action by default.
 */
enum class ManagerAction(
    val defaultRequired: Boolean,
) {
    /** Ordinary sales. */
    SALE(false),

    /** Pre-authorizations. */
    PRE_AUTHORISATION(false),

    /** Cash payments. */
    CASH_PAYMENT(false),

    /** Payment-link creation. */
    LINK_CREATE(false),

    /** Referenced refunds. */
    REFERENCED_REFUND(true),

    /** Refunds without receipt. */
    UNREFERENCED_REFUND(true),

    /** Cash refunds. */
    CASH_REFUND(true),

    /** Captures and receipt tips. */
    CAPTURE(true),

    /** Authorization changes and renewals. */
    ADJUST_AUTHORISATION(true),

    /** Authorization cancellation. */
    CANCEL_AUTHORISATION(true),

    /** Cashout and cashback. */
    CASHOUT(true),

    /** Gift-card value changes. */
    GIFT_CARD_CHANGE(true),

    /** Saved-card charges. */
    SAVED_CARD_CHARGE(true),

    /** Token deletion. */
    TOKEN_DELETE(true),

    /** MOTO and manual card entry. */
    MANUAL_ENTRY(true),

    /** Payment-link changes. */
    LINK_CHANGE(true),

    /** Reconciliation period closing. */
    CLOSE_PERIOD(true),

    /** History and transaction details. */
    HISTORY(false),

    /** Receipt reprinting, emailing and sharing. */
    RECEIPT(false),

    /** Reconciliation totals. */
    TOTALS(false),

    /** Saved-card lookup. */
    SAVED_CARD_LOOKUP(false),

    /** Gift-card balance lookup. */
    GIFT_CARD_BALANCE(false),
}
