package app.minimpos.core.logo

import java.io.ByteArrayOutputStream
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Strict receipt PNG codec: 1-bit grayscale, no interlace, filter zero, and one zlib IDAT. Thread safe. */
object MonoPng {
    private const val HEADER_BYTES = 13
    private const val WORD_BYTES = 4
    private const val MAX_PNG_BYTES = 256_000
    private const val DIMENSION_BYTES = 8
    private const val CHUNK_TAIL_BYTES = 8
    private const val CHUNK_OVERHEAD = 12
    private const val PACKED_BYTE_BITS = 8
    private const val PACKING_ROUNDING = 7
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val headerFormat = byteArrayOf(1, 0, 0, 0, 0)

    /** Encodes [bitmap] as a PNG, with zero for black and white padding. */
    fun encode(bitmap: MonoBitmap): ByteArray {
        val raw = ByteArray((bitmap.stride + 1) * bitmap.height)
        val bits = bitmap.bits()
        repeat(bitmap.height) { y ->
            repeat(bitmap.stride) { x ->
                raw[y * (bitmap.stride + 1) + x + 1] = bits[y * bitmap.stride + x].toInt().inv().toByte()
            }
        }
        val compressor = Deflater()
        val compressed = ByteArrayOutputStream()
        try {
            compressor.setInput(raw)
            compressor.finish()
            val buffer = ByteArray(1024)
            while (!compressor.finished()) compressed.write(buffer, 0, compressor.deflate(buffer))
        } finally {
            compressor.end()
        }
        val header =
            ByteBuffer
                .allocate(HEADER_BYTES)
                .putInt(bitmap.width)
                .putInt(bitmap.height)
                .put(headerFormat)
                .array()
        return ByteArrayOutputStream()
            .apply {
                write(signature)
                chunk(this, "IHDR", header)
                chunk(this, "IDAT", compressed.toByteArray())
                chunk(this, "IEND", byteArrayOf())
            }.toByteArray()
    }

    private fun chunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val tag = type.toByteArray(Charsets.US_ASCII)
        out.write(ByteBuffer.allocate(WORD_BYTES).putInt(data.size).array())
        out.write(tag)
        out.write(data)
        val crc =
            CRC32().apply {
                update(tag)
                update(data)
            }
        out.write(ByteBuffer.allocate(WORD_BYTES).putInt(crc.value.toInt()).array())
    }

    /** Decodes only the receipt PNG shape; invalid, oversized or unsupported data throws [LogoFormatException]. */
    fun decode(png: ByteArray): MonoBitmap {
        if (png.size > MAX_PNG_BYTES || png.size < signature.size) invalid()
        if (!png.copyOfRange(0, signature.size).contentEquals(signature)) invalid()
        val input = ByteBuffer.wrap(png).apply { (this as Buffer).position(signature.size) }
        val header = read(input, "IHDR")
        if (header.size != HEADER_BYTES) invalid()
        if (!header.copyOfRange(DIMENSION_BYTES, HEADER_BYTES).contentEquals(headerFormat)) invalid()
        val dimensions = ByteBuffer.wrap(header)
        val width = dimensions.int
        val height = dimensions.int
        if (width !in 1..MonoBitmap.MAX_WIDTH || height !in 1..MonoBitmap.MAX_HEIGHT) invalid()
        val compressed = read(input, "IDAT")
        if (read(input, "IEND").isNotEmpty() || input.hasRemaining()) invalid()
        val stride = (width + PACKING_ROUNDING) / PACKED_BYTE_BITS
        return unpack(inflate(compressed, (stride + 1) * height), width, height, stride)
    }

    private fun read(
        input: ByteBuffer,
        type: String,
    ): ByteArray {
        if (input.remaining() < CHUNK_OVERHEAD) invalid()
        val length = input.int
        if (length < 0 || length > input.remaining() - CHUNK_TAIL_BYTES) invalid()
        val tag = ByteArray(WORD_BYTES).also(input::get)
        if (!tag.contentEquals(type.toByteArray(Charsets.US_ASCII))) invalid()
        val data = ByteArray(length).also(input::get)
        val crc =
            CRC32()
                .apply {
                    update(tag)
                    update(data)
                }.value
                .toInt()
        if (input.int != crc) invalid()
        return data
    }

    private fun inflate(
        compressed: ByteArray,
        expected: Int,
    ): ByteArray {
        val raw = ByteArray(expected + 1)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            var count = 0
            while (!inflater.finished() && count < raw.size) {
                val n = inflater.inflate(raw, count, raw.size - count)
                if (n == 0 && !inflater.finished()) invalid()
                count += n
            }
            if (!inflater.finished() || count != expected || inflater.remaining != 0) invalid()
        } catch (e: DataFormatException) {
            throw LogoFormatException("Invalid logo PNG compression", e)
        } finally {
            inflater.end()
        }
        return raw
    }

    private fun unpack(
        raw: ByteArray,
        width: Int,
        height: Int,
        stride: Int,
    ): MonoBitmap {
        val bits = ByteArray(stride * height)
        val padding = (1 shl ((PACKED_BYTE_BITS - width % PACKED_BYTE_BITS) % PACKED_BYTE_BITS)) - 1
        repeat(height) { y ->
            if (raw[y * (stride + 1)].toInt() != 0) invalid()
            repeat(stride) { x -> bits[y * stride + x] = raw[y * (stride + 1) + x + 1].toInt().inv().toByte() }
            if (bits[y * stride + stride - 1].toInt() and padding != 0) invalid()
        }
        return MonoBitmap(width, height, bits)
    }

    private fun invalid(): Nothing = throw LogoFormatException("Invalid logo PNG")
}
