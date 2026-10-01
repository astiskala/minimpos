package io.minimpos.core.codec

import java.time.Instant

/**
 * Content of the QR code printed on receipts, carrying what a referenced refund needs:
 * `MPR1*<transactionId>*<epochMillis>*<amountMinor>*<currency>[*<reference>]`.
 *
 * Only `[A-Za-z0-9.*_-]` is used, so URL-encoding (which Adyen print requests may apply) leaves it unchanged.
 *
 * @property transactionId The payment's POITransactionID (`TransactionID`), 1 to 64 letters, digits or dots.
 * @property timestamp The POITransactionID time stamp. It is carried at millisecond precision, so a refund request
 *   re-formats it with `TerminalClient.formatTimestamp` rather than repeating the terminal's original text.
 * @property amountMinor The amount paid, in minor units of [currency]; never negative.
 * @property currency ISO 4217 code of the payment, three upper-case letters.
 * @property reference The payment's merchant reference, shown when the refund is made. It is left out of the encoded
 *   text (and ignored when decoding) unless it is 1 to 40 characters from `[A-Za-z0-9._-]`.
 * @throws IllegalArgumentException if [transactionId], [currency] or [amountMinor] is invalid.
 */
data class RefundQrPayload(
    val transactionId: String,
    val timestamp: Instant,
    val amountMinor: Long,
    val currency: String,
    val reference: String? = null,
) {
    init {
        require(TRANSACTION_ID.matches(transactionId)) { "Unsupported transaction id: $transactionId" }
        require(currency.length == CURRENCY_LENGTH && currency.all { it in 'A'..'Z' }) { "Invalid currency: $currency" }
        require(amountMinor >= 0) { "Amount must not be negative" }
    }

    /** The text to put in the QR code; [reference] is appended only when it is safe to carry. */
    fun encode(): String {
        val fields =
            mutableListOf(PREFIX, transactionId, timestamp.toEpochMilli().toString(), amountMinor.toString(), currency)
        reference?.takeIf { SAFE_REFERENCE.matches(it) }?.let { fields += it }
        return fields.joinToString(SEPARATOR)
    }

    /** Recognising and parsing scanned refund codes. */
    companion object {
        /** The first field of every refund code, which also versions the format. */
        const val PREFIX = "MPR1"
        private const val SEPARATOR = "*"
        private const val CURRENCY_LENGTH = 3

        /** Prefix, transaction ID, time stamp, amount and currency; the reference is the optional sixth field. */
        private const val REQUIRED_FIELDS = 5
        private const val REFERENCE_FIELD = REQUIRED_FIELDS
        private val TRANSACTION_ID = Regex("^[A-Za-z0-9.]{1,64}$")
        private val SAFE_REFERENCE = Regex("^[A-Za-z0-9._-]{1,40}$")

        /**
         * Parses scanned [text] (surrounding whitespace ignored); null when it is not a valid refund code, for example
         * a product barcode or a code from another app. An unsafe reference is dropped rather than rejecting the code.
         */
        fun decode(text: String): RefundQrPayload? {
            val parts = text.trim().split(SEPARATOR)
            if (parts.size !in REQUIRED_FIELDS..REFERENCE_FIELD + 1 || parts[0] != PREFIX) return null
            return runCatching {
                RefundQrPayload(
                    transactionId = parts[1],
                    timestamp = Instant.ofEpochMilli(parts[2].toLong()),
                    amountMinor = parts[3].toLong(),
                    currency = parts[4],
                    reference = parts.getOrNull(REFERENCE_FIELD)?.takeIf { SAFE_REFERENCE.matches(it) },
                )
            }.getOrNull()
        }
    }
}
