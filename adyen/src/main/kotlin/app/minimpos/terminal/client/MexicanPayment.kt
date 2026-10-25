package app.minimpos.terminal.client

private const val THREE_MONTHS = 3
private const val SIX_MONTHS = 6
private const val NINE_MONTHS = 9
private const val TWELVE_MONTHS = 12
private const val EIGHTEEN_MONTHS = 18

/**
 * Shopper-selected Mexican card routing. Installments are issuer-managed; no recurring charges are scheduled.
 * The supported common counts are 3, 6, 9, 12 and 18 (Adyen Mexican card and POS guides, October 26, 2026).
 * @property debit Whether TransactionConditions.DebitPreferredFlag is true.
 * @property count Total monthly payments; null for debit or one-off credit.
 */
enum class MexicanPayment(
    val debit: Boolean,
    val count: Int? = null,
) {
    /** One debit payment. */
    DEBIT(true),

    /** One credit payment. */
    CREDIT(false),

    /** Three monthly payments. */
    MONTHS_3(false, THREE_MONTHS),

    /** Six monthly payments. */
    MONTHS_6(false, SIX_MONTHS),

    /** Nine monthly payments. */
    MONTHS_9(false, NINE_MONTHS),

    /** Twelve monthly payments. */
    MONTHS_12(false, TWELVE_MONTHS),

    /** Eighteen monthly payments. */
    MONTHS_18(false, EIGHTEEN_MONTHS),
}
