package app.minimpos.app.receipt

import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.history.ReceiptStanding

/** What a receipt is made for: paper gets blank tip lines to fill in, an emailed or shared receipt does not. */
enum class ReceiptSurface {
    /** Printed: a sale awaiting its tip gets lines for the shopper to write it on. */
    PAPER,

    /** Emailed or shared as an image, where nobody can write a tip. */
    ELECTRONIC,
}

/**
 * What kind of receipt a stored transaction gets: the one reading of its meaning that both its document
 * ([ReceiptFactory]) and its email envelope (subject and introduction, [app.minimpos.app.email.ReceiptEmailer]) follow.
 */
enum class ReceiptKind {
    /** A payment's receipt. */
    SALE,

    /** A pre-authorisation, which only holds its amount. */
    PRE_AUTHORISATION,

    /** An unpaid payment link's request to pay, not proof of payment. */
    UNPAID_LINK,

    /** An offline demo link's receipt or request, which moves no money, whether paid, open or ended. */
    DEMO_LINK,

    /** A refund's receipt. */
    REFUND,

    /** A cancelled hold's receipt: the amount was released, nothing refunded. */
    CANCELLATION,
    ;

    /** Reading a stored transaction. */
    companion object {
        /** The kind of a sale's receipt, from where it stands ([ReceiptStanding.of]). */
        fun of(standing: ReceiptStanding): ReceiptKind =
            when {
                standing.simulatedLink -> DEMO_LINK
                standing.unpaidLink != null -> UNPAID_LINK
                standing.preAuthorisation -> PRE_AUTHORISATION
                else -> SALE
            }

        /** The kind of [refund]'s receipt. */
        fun of(refund: RefundEntity): ReceiptKind = if (refund.cancellation) CANCELLATION else REFUND
    }
}
