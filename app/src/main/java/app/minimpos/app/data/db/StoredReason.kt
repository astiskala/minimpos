package app.minimpos.app.data.db

import androidx.room.TypeConverter
import app.minimpos.terminal.transport.ExternalText

/**
 * Why a stored transaction (or a sale's latest capture or adjustment) did not succeed, when the app says so rather than
 * the terminal's answer: stored typed (`sales.reason`, `sales.modificationReason`, `refunds.reason`) and worded by the
 * screens in the current language. A terminal's or Adyen's own answer text is stored as it came, in the `message`
 * columns; Adyen's words inside a [Failure] travel in its [ExternalText].
 */
sealed interface StoredReason {
    /**
     * Nothing happened (nothing was charged, refunded or captured), so it is safe to change it and try again.
     *
     * @property failure Why.
     */
    data class NotDone(
        val failure: Failure,
    ) : StoredReason

    /**
     * It could not be established whether what was sent took effect; retry only with the same operation identity, or
     * check its outcome again.
     *
     * @property failure What prevented the confirmation; null when nothing more is known.
     */
    data class Unconfirmed(
        val failure: Failure? = null,
    ) : StoredReason

    /** The app stopped before the answer arrived. */
    data object Interrupted : StoredReason
}

/**
 * How Room stores a [StoredReason]: one text column, read back leniently. Reasons stored before failures were typed
 * (`NOT_SET_UP:…`, `OUTCOME_UNKNOWN`, `INTERRUPTED`) keep their encoding; other failures are stored as
 * `NOT_DONE:{json}` or `UNCONFIRMED:{json}`.
 */
object StoredReasonConverter {
    private const val NOT_SET_UP = "NOT_SET_UP:"
    private const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
    private const val INTERRUPTED = "INTERRUPTED"
    private const val NOT_DONE = "NOT_DONE:"
    private const val UNCONFIRMED = "UNCONFIRMED:"

    /** [reason] as stored, such as `NOT_SET_UP:PASSPHRASE`; null for none. */
    @TypeConverter
    fun encode(reason: StoredReason?): String? =
        when (reason) {
            null -> null
            StoredReason.Interrupted -> INTERRUPTED
            is StoredReason.Unconfirmed -> reason.failure?.let { UNCONFIRMED + FailureJson.encode(it) } ?: OUTCOME_UNKNOWN
            is StoredReason.NotDone -> notDone(reason.failure)
        }

    private fun notDone(failure: Failure): String =
        if (failure is Failure.NotSetUp) NOT_SET_UP + failure.problem.name else NOT_DONE + FailureJson.encode(failure)

    /** The reason stored as [stored]; null for none, and for a value this version does not know. */
    @TypeConverter
    fun decode(stored: String?): StoredReason? =
        when {
            stored == null -> null
            stored == OUTCOME_UNKNOWN -> StoredReason.Unconfirmed()
            stored == INTERRUPTED -> StoredReason.Interrupted
            stored.startsWith(NOT_SET_UP) -> problem(stored.removePrefix(NOT_SET_UP))?.let { StoredReason.NotDone(Failure.NotSetUp(it)) }
            stored.startsWith(NOT_DONE) -> FailureJson.decode(stored.removePrefix(NOT_DONE))?.let(StoredReason::NotDone)
            stored.startsWith(UNCONFIRMED) -> FailureJson.decode(stored.removePrefix(UNCONFIRMED))?.let(StoredReason::Unconfirmed)
            else -> null
        }

    private fun problem(name: String): SetupProblem? = SetupProblem.entries.find { it.name == name }
}
