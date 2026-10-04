package io.github.astiskala.minimpos.core.ids

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Generates the identifiers the app sends to Adyen and prints on receipts. Pass a seeded [Random] in tests. */
object Ids {
    private const val ALPHANUMERIC = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val SUFFIX_LENGTH = 4
    private val REFERENCE_TIME = DateTimeFormatter.ofPattern("yyMMdd-HHmmss")

    /**
     * Merchant reference such as "MP-260929-210355-4F2A": [prefix], the local date and time of [now] in [zone], and a
     * random suffix that keeps concurrent terminals apart. Characters other than letters, digits, '-' and '_' are
     * dropped from [prefix]; an empty prefix gives "260929-210355-4F2A".
     */
    fun transactionReference(
        prefix: String,
        now: Instant,
        zone: ZoneId,
        random: Random = Random.Default,
    ): String {
        val cleanPrefix = prefix.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }
        val body = REFERENCE_TIME.format(now.atZone(zone)) + "-" + randomString(SUFFIX_LENGTH, random)
        return if (cleanPrefix.isEmpty()) body else "$cleanPrefix-$body"
    }

    /** [length] characters picked uniformly from [alphabet] (by default digits and upper-case letters). */
    fun randomString(
        length: Int,
        random: Random = Random.Default,
        alphabet: String = ALPHANUMERIC,
    ): String = buildString(length) { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
}
