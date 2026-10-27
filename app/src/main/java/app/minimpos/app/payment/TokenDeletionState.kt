package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.StoredCard

/** Where the last token deletion stands; UNKNOWN may only be reconciled by listing, never silently retried. */
enum class TokenDeletionStatus {
    /** No deletion attempted in this process. */
    IDLE,

    /** A confirmed DELETE is running. */
    DELETING,

    /** A fresh read-only listing is running. */
    CHECKING,

    /** HTTP 204, or a complete fresh listing no longer contains the token. */
    DELETED,

    /** Deletion could have happened; failed listing or continued presence cannot prove its final outcome. */
    UNKNOWN,

    /** Known not deleted by this attempt. */
    FAILED,
}

/**
 * Memory-only token-management evidence. A restart always requires a fresh listing before any token can be selected.
 * Historical sales and refund identities are never changed by token deletion.
 * @property card Token actually confirmed; null before an attempt.
 * @property reference Shopper reference actually confirmed; null before an attempt.
 * @property status The API outcome or reconciliation stage.
 * @property failure Typed refusal or reason the outcome remains uncertain; null when none.
 */
data class TokenDeletionState(
    val card: StoredCard? = null,
    val reference: String? = null,
    val status: TokenDeletionStatus = TokenDeletionStatus.IDLE,
    val failure: Failure? = null,
)

/**
 * The immutable token and account shown for one confirmation. Only a current lookup can make an offer.
 * @property card Specific listed token, including its exact ID.
 * @property reference Shopper reference shown for confirmation.
 * @property context Account and environment shown for confirmation; null in context-free fakes.
 * @property origin Credential-bound target retained for the send-time check.
 * @property revision Listing generation retained through confirmation and Manager approval.
 * @property identity Exact lookup reading retained so a shopper change away and back cannot revive confirmation.
 */
class TokenDeletionOffer internal constructor(
    val card: StoredCard,
    val reference: String,
    val context: PaymentContext?,
    internal val origin: ApiTarget,
    internal val revision: Long,
    internal val identity: Any,
)
