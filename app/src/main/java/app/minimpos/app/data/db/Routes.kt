package app.minimpos.app.data.db

/**
 * How a stored sale was sent, so how it is checked again, refunded and counted: the one reading of its
 * [SaleEntity.paymentLink] and [SaleEntity.savedCard] flags. Seeded sample history ([SaleEntity.sample]) keeps the
 * route it shows. Not a navigation route.
 */
enum class SaleRoute {
    /** Taken on a terminal: Terminal API payments, scanned wallets and locally recorded cash tenders. */
    TERMINAL,

    /** Paid on Adyen's payment page: checked by asking Adyen about the link and refunded in the Customer Area. */
    PAYMENT_LINK,

    /** A saved card charged through the Checkout API: checked again with its idempotency key and refunded there. */
    SAVED_CARD,
}

/**
 * How a stored refund was sent, so how it is checked again and counted: the one reading of its
 * [RefundEntity.checkoutRefund], [RefundEntity.unreferenced] and [RefundEntity.cash] flags.
 *
 * @property onTerminal Whether it was sent to a terminal, so terminal totals include it.
 */
enum class RefundRoute(
    val onTerminal: Boolean,
) {
    /** A referenced refund or cancellation sent to the terminal as a reversal. */
    TERMINAL(true),

    /** An unreferenced card refund sent to the terminal as a payment and recovered as one. */
    UNREFERENCED(true),

    /** A refund of a saved-card charge through the Checkout API. */
    CHECKOUT(false),

    /** Cash handed back for a cash tender, recorded locally without a Terminal API call. */
    CASH(false),
}

/** How this sale was sent, see [SaleRoute]. */
val SaleEntity.route: SaleRoute
    get() =
        when {
            paymentLink -> SaleRoute.PAYMENT_LINK
            savedCard -> SaleRoute.SAVED_CARD
            else -> SaleRoute.TERMINAL
        }

/** How this refund was sent, see [RefundRoute]. */
val RefundEntity.route: RefundRoute
    get() =
        when {
            checkoutRefund -> RefundRoute.CHECKOUT
            unreferenced -> RefundRoute.UNREFERENCED
            cash -> RefundRoute.CASH
            else -> RefundRoute.TERMINAL
        }
