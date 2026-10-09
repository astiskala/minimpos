package app.minimpos.app.data.db

import androidx.room.TypeConverter

/**
 * Why a stored transaction (or a sale's latest capture or adjustment) did not succeed, when the app itself says so
 * rather than Adyen or the terminal: stored typed (`sales.reason`, `sales.modificationReason`, `refunds.reason`) and
 * worded by the screens in the current language. Adyen's and the terminal's own texts are stored as they came, in the
 * `message` columns.
 */
sealed interface StoredReason {
    /**
     * Nothing was sent, because something must be entered, installed or fixed first.
     *
     * @property problem What.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : StoredReason

    /** It could not be established whether what was sent took effect; it can be checked again. */
    data object OutcomeUnknown : StoredReason

    /** The app stopped before the answer arrived. */
    data object Interrupted : StoredReason
}

/** How Room stores a [StoredReason]: one text column, read back leniently. */
object StoredReasonConverter {
    private const val NOT_SET_UP = "NOT_SET_UP:"
    private const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    private const val INTERRUPTED = "INTERRUPTED"

    /** [reason] as stored, such as `NOT_SET_UP:PASSPHRASE`; null for none. */
    @TypeConverter
    fun encode(reason: StoredReason?): String? =
        when (reason) {
            is StoredReason.NotSetUp -> NOT_SET_UP + reason.problem.name
            StoredReason.OutcomeUnknown -> OUTCOME_UNKNOWN
            StoredReason.Interrupted -> INTERRUPTED
            null -> null
        }

    /** The reason stored as [stored]; null for none, and for a value this version does not know. */
    @TypeConverter
    fun decode(stored: String?): StoredReason? =
        when {
            stored == null -> {
                null
            }

            stored == OUTCOME_UNKNOWN -> {
                StoredReason.OutcomeUnknown
            }

            stored == INTERRUPTED -> {
                StoredReason.Interrupted
            }

            stored.startsWith(NOT_SET_UP) -> {
                SetupProblem.entries.find { it.name == stored.removePrefix(NOT_SET_UP) }?.let(StoredReason::NotSetUp)
            }

            else -> {
                null
            }
        }
}
