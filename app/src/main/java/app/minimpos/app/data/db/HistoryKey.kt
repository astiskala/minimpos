package app.minimpos.app.data.db

/**
 * Indexed candidate activity time; accounting decides whether it is an actual activity, including missing dates.
 *
 * @property id Original sale or refund ID, or a stored-value activity's group ID.
 * @property at Candidate epoch milliseconds.
 * @property refund True for a refund, false for a sale or stored-value activity.
 * @property giftCard True for a stored-value activity.
 */
data class HistoryKey(
    val id: String,
    val at: Long,
    val refund: Boolean,
    val giftCard: Boolean = false,
)
