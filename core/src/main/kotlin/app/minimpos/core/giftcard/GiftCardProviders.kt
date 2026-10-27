package app.minimpos.core.giftcard

/** One gift-card operation a provider may support. */
enum class GiftCardOperation {
    /** Reads the balance; changes nothing. */
    BALANCE,

    /** Activates a card, with or without an initial load. */
    ACTIVATE,

    /** Loads value onto an active card. */
    LOAD,

    /** Undoes a card's most recent load within 24 hours. */
    UNDO_LOAD,

    /** Pays with the card's value at checkout. */
    REDEEM,

    /** Refunds onto the card, referenced to a redemption or unreferenced. */
    REFUND,

    /** Pays the card's value out to the shopper in cash. */
    CASH_OUT,

    /** Deactivates the card. */
    DEACTIVATE,
}

/** How staff present a gift card. */
enum class GiftCardEntry {
    /** Scanned with the camera; the number and expiry date are sent in the request. */
    SCANNED,

    /** Swiped on the terminal. */
    SWIPED,

    /** Keyed in on the terminal. */
    KEYED,
}

/**
 * Gift-card providers Adyen supports at the point of sale, with the operations Adyen documents for each (checked
 * October 25, 2026). A merchant still configures which providers they completed onboarding with; this matrix never
 * establishes support by itself.
 *
 * @property wireCode The fixed `StoredValueProvider`; null when the merchant configures the exact card type
 *   (Intersolve and local gift cards).
 * @property operations The documented operations.
 */
enum class GiftCardProvider(
    val wireCode: String?,
    val operations: Set<GiftCardOperation>,
) {
    /** Givex: every operation, the only provider documented for deactivation and partial cash-out. */
    GIVEX("givex", GiftCardOperation.entries.toSet()),

    /** SVS. */
    SVS("svs", GiftCardOperation.entries.toSet() - GiftCardOperation.DEACTIVATE),

    /** Fiserv ValueLink. */
    FISERV("valuelink", GiftCardOperation.entries.toSet() - GiftCardOperation.DEACTIVATE),

    /** An Intersolve-supported card type, configured by the merchant. */
    INTERSOLVE(null, GiftCardOperation.entries.toSet() - GiftCardOperation.DEACTIVATE),

    /** A local gift card (Denmark, France, the Netherlands, Norway, Sweden, United Kingdom): payments only. */
    LOCAL(null, setOf(GiftCardOperation.REDEEM)),
    ;

    /** Whether the merchant must configure the card type sent as `StoredValueProvider`. */
    val configuresCode: Boolean get() = wireCode == null

    /** Whether Adyen documents [operation] for this provider. */
    fun supports(operation: GiftCardOperation): Boolean = operation in operations

    /** Whether a cash-out may pay out less than the whole balance; Adyen documents this for some Givex cards only. */
    val partialCashOut: Boolean get() = this == GIVEX

    /** Checks of configured card types. */
    companion object {
        private val CODE = Regex("[a-z0-9_]{1,64}")

        /** Whether [code] is a well-formed configured `StoredValueProvider`. */
        fun validCode(code: String): Boolean = CODE.matches(code)

        /** How long after a load it may still be undone, in milliseconds. */
        const val UNDO_WINDOW_MILLIS: Long = 24L * 60 * 60 * 1000
    }
}
