package app.minimpos.app.data.repo

import app.minimpos.app.data.settings.StorageJson
import app.minimpos.core.receipt.CardReceiptLine
import app.minimpos.terminal.parse.ReceiptField
import kotlinx.serialization.serializer

/**
 * JSON codec for the lists stored in text columns: card receipt lines (`customerReceiptJson`, `cashierReceiptJson`)
 * and refunded items (`refunds.linesJson`).
 *
 * An empty list is stored as null, and decoding is lenient: a null, unreadable or incompatible value decodes to an
 * empty list rather than failing, so a bad column never stops a receipt from being shown.
 */
object ReceiptLinesJson {
    private val serializer = serializer<List<CardReceiptLine>>()
    private val refundedSerializer = serializer<List<RefundedLine>>()

    /** Encodes card receipt [lines], or returns null when there are none. */
    fun encode(lines: List<CardReceiptLine>): String? = lines.takeIf { it.isNotEmpty() }?.let { StorageJson.encodeToString(serializer, it) }

    /** Encodes the card receipt [fields] the terminal sent, as [encode] does; null when there are none. */
    fun encodeFields(fields: List<ReceiptField>): String? = encode(fields.map { CardReceiptLine(it.key, it.name, it.value, it.bold) })

    /** Decodes card receipt lines stored by [encode]; empty when [json] is null or unreadable. */
    fun decode(json: String?): List<CardReceiptLine> =
        json
            ?.let {
                runCatching { StorageJson.decodeFromString(serializer, it) }.getOrNull()
            }.orEmpty()

    /** Encodes refunded item [lines], or returns null when there are none (an amount or full refund). */
    fun encodeRefunded(lines: List<RefundedLine>): String? =
        lines
            .takeIf {
                it.isNotEmpty()
            }?.let { StorageJson.encodeToString(refundedSerializer, it) }

    /** Decodes refunded items stored by [encodeRefunded]; empty when [json] is null or unreadable. */
    fun decodeRefunded(json: String?): List<RefundedLine> =
        json?.let { runCatching { StorageJson.decodeFromString(refundedSerializer, it) }.getOrNull() }.orEmpty()
}
