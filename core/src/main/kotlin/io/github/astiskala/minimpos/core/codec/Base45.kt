package io.github.astiskala.minimpos.core.codec

import java.io.ByteArrayOutputStream

/**
 * RFC 9285 Base45. Its alphabet is exactly the QR alphanumeric character set, so encoded data uses the dense
 * alphanumeric QR mode (5.5 bits per character) instead of byte mode.
 *
 * Each pair of bytes, read as a big-endian number n, becomes three characters c, d, e with n = c + d * 45 + e * 45²;
 * a trailing single byte becomes two characters.
 */
object Base45 {
    /** The 45 characters in value order (0 is '0', 44 is ':'), as defined by RFC 9285. */
    const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"
    private const val BASE = 45
    private const val GROUP_BYTES = 2
    private const val GROUP_CHARS = 3

    /** A trailing single byte is encoded in this many characters. */
    private const val TAIL_CHARS = 2
    private const val BYTE_MASK = 0xFF

    /** The largest value three characters may decode to: 45³ - 1 is larger than two bytes can hold. */
    private const val MAX_GROUP_VALUE = 0xFFFF

    /** Every alphabet character is ASCII, so the reverse lookup table only covers ASCII. */
    private const val ASCII_SIZE = 128
    private val REVERSE = IntArray(ASCII_SIZE) { -1 }.also { table -> ALPHABET.forEachIndexed { i, c -> table[c.code] = i } }

    /** Encodes [bytes]; the result is 3 characters per 2 bytes, rounded up, and empty for no bytes. */
    fun encode(bytes: ByteArray): String =
        buildString(bytes.size / GROUP_BYTES * GROUP_CHARS + bytes.size % GROUP_BYTES * TAIL_CHARS) {
            var i = 0
            while (i + 1 < bytes.size) {
                var n = (bytes[i].toInt() and BYTE_MASK shl Byte.SIZE_BITS) or (bytes[i + 1].toInt() and BYTE_MASK)
                val c = n % BASE
                n /= BASE
                append(ALPHABET[c]).append(ALPHABET[n % BASE]).append(ALPHABET[n / BASE])
                i += GROUP_BYTES
            }
            if (i < bytes.size) {
                val n = bytes[i].toInt() and BYTE_MASK
                append(ALPHABET[n % BASE]).append(ALPHABET[n / BASE])
            }
        }

    /**
     * Decodes Base45 [text], which must use only [ALPHABET] (upper case, no surrounding whitespace).
     *
     * @throws IllegalArgumentException if [text] has a length that leaves one character over, contains a character
     *   outside [ALPHABET], or has a group whose value does not fit the bytes it stands for.
     */
    fun decode(text: String): ByteArray {
        require(text.length % GROUP_CHARS != 1) { "Invalid Base45 length" }
        val out = ByteArrayOutputStream(text.length / GROUP_CHARS * GROUP_BYTES + 1)
        var i = 0
        while (i < text.length) {
            val chunk = minOf(GROUP_CHARS, text.length - i)
            var n = 0
            var factor = 1
            for (j in 0 until chunk) {
                n += value(text[i + j]) * factor
                factor *= BASE
            }
            if (chunk == GROUP_CHARS) {
                require(n <= MAX_GROUP_VALUE) { "Invalid Base45 triplet" }
                out.write(n shr Byte.SIZE_BITS)
                out.write(n and BYTE_MASK)
            } else {
                require(n <= BYTE_MASK) { "Invalid Base45 pair" }
                out.write(n)
            }
            i += chunk
        }
        return out.toByteArray()
    }

    private fun value(c: Char): Int {
        val v = if (c.code < ASCII_SIZE) REVERSE[c.code] else -1
        require(v >= 0) { "Invalid Base45 character: '$c'" }
        return v
    }
}
