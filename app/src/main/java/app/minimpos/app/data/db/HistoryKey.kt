package app.minimpos.app.data.db

/**
 * Indexed candidate activity time; accounting decides whether it is an actual activity, including missing dates.
 *
 * @property id Original sale or refund ID.
 * @property at Candidate epoch milliseconds.
 * @property refund True for a refund, false for a sale.
 */
data class HistoryKey(
    val id: String,
    val at: Long,
    val refund: Boolean,
)
