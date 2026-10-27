package app.minimpos.core.logo

import java.io.ByteArrayOutputStream

private const val PACKED_BYTE_BITS = 8
private const val VARINT_BITS = 7
private const val VARINT_MORE = 128
private const val VARINT_PAYLOAD = 127
private const val BYTE_MASK = 255

/** Invalid or unsupported receipt logo bytes; no image contents are included in the message. */
class LogoFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Immutable black/white receipt image. Rows are packed MSB first, one means black, and unused bits are zero.
 *
 * @property width Image width in pixels, 1 through [MAX_WIDTH].
 * @property height Image height in pixels, 1 through [MAX_HEIGHT].
 * @param bits Packed rows, copied on construction. Invalid sizes or padding throw [IllegalArgumentException].
 */
class MonoBitmap(
    val width: Int,
    val height: Int,
    bits: ByteArray,
) {
    /** Bytes in one packed row. */
    val stride: Int = (width + VARINT_BITS) / PACKED_BYTE_BITS
    private val pixels = bits.copyOf()

    init {
        require(width in 1..MAX_WIDTH && height in 1..MAX_HEIGHT)
        require(pixels.size == stride * height)
        val padding = (1 shl ((PACKED_BYTE_BITS - width % PACKED_BYTE_BITS) % PACKED_BYTE_BITS)) - 1
        require((0..<height).all { pixels[it * stride + stride - 1].toInt() and padding == 0 })
    }

    /** Returns the ink at zero-based coordinates; invalid coordinates throw [IllegalArgumentException]. */
    fun isBlack(
        x: Int,
        y: Int,
    ): Boolean {
        require(x in 0..<width && y in 0..<height)
        return pixels[y * stride + x / PACKED_BYTE_BITS].toInt() and (VARINT_MORE ushr (x % PACKED_BYTE_BITS)) != 0
    }

    /** Returns an independent copy of the packed rows. */
    fun bits(): ByteArray = pixels.copyOf()

    /** Encodes unsigned LEB128 width and height followed by the packed rows. */
    fun toBytes(): ByteArray =
        ByteArrayOutputStream()
            .apply {
                for (dimension in listOf(width, height)) {
                    var value = dimension
                    while (value >= VARINT_MORE) {
                        write((value and VARINT_PAYLOAD) or VARINT_MORE)
                        value = value ushr VARINT_BITS
                    }
                    write(value)
                }
                write(pixels)
            }.toByteArray()

    override fun equals(other: Any?): Boolean =
        other is MonoBitmap && width == other.width && height == other.height && pixels.contentEquals(other.pixels)

    override fun hashCode(): Int = 31 * (31 * width + height) + pixels.contentHashCode()

    /** Receipt printer image limits and strict record decoding. */
    companion object {
        /** Maximum receipt image width in pixels. */
        const val MAX_WIDTH: Int = 384

        /** Maximum receipt image height in pixels. */
        const val MAX_HEIGHT: Int = 192

        /** Decodes a complete record; malformed dimensions, lengths or padding throw [LogoFormatException]. */
        fun fromBytes(bytes: ByteArray): MonoBitmap {
            var position = 0

            fun dimension(): Int {
                var value = 0
                var shift = 0
                while (position < bytes.size && shift <= VARINT_BITS) {
                    val next = bytes[position++].toInt() and BYTE_MASK
                    value = value or ((next and VARINT_PAYLOAD) shl shift)
                    if (next and VARINT_MORE == 0) return value
                    shift += VARINT_BITS
                }
                throw LogoFormatException("Invalid logo dimension")
            }
            val width = dimension()
            val height = dimension()
            return try {
                MonoBitmap(width, height, bytes.copyOfRange(position, bytes.size))
            } catch (e: IllegalArgumentException) {
                throw LogoFormatException("Invalid logo record", e)
            }
        }
    }
}
