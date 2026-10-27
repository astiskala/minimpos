package app.minimpos.core.logo

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater

class MonoMalformedTest {
    private val signature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

    @Test
    fun `strict PNG rejects unsupported headers with valid checksums`() {
        val header = header()
        val invalidHeaders =
            listOf(
                header.copyOf(12),
                header + 0,
                header.copyOf().apply { ByteBuffer.wrap(this).putInt(0, 0) },
                header.copyOf().apply { ByteBuffer.wrap(this).putInt(0, 385) },
                header.copyOf().apply { ByteBuffer.wrap(this).putInt(4, 0) },
                header.copyOf().apply { ByteBuffer.wrap(this).putInt(4, 193) },
            ) + (8..12).map { index -> header.copyOf().apply { this[index] = 2 } }
        invalidHeaders.forEach { assertInvalid(png(header = it)) }
    }

    @Test
    fun `strict PNG rejects invalid filters padding and decoded lengths`() {
        assertInvalid(png(raw = byteArrayOf(1, 0x7F)))
        assertInvalid(png(raw = byteArrayOf(0, 0)))
        assertInvalid(png(raw = byteArrayOf(0)))
        assertInvalid(png(raw = byteArrayOf(0, 0x7F, 0)))
        assertInvalid(png(raw = ByteArray(20_000)))
        assertInvalid(png(end = byteArrayOf(0)))
        val bitmap = MonoPng.decode(png())
        assertThat(bitmap).isEqualTo(MonoBitmap(1, 1, byteArrayOf(0x80.toByte())))
    }

    @Test
    fun `strict PNG rejects invalid and unfinished zlib streams`() {
        val compressed = compress(byteArrayOf(0, 0x7F))
        assertInvalid(png(compressed = byteArrayOf()))
        assertInvalid(png(compressed = byteArrayOf(1, 2, 3, 4)))
        assertInvalid(png(compressed = compressed.copyOf(compressed.size - 1)))
        assertInvalid(png(compressed = compressed + 0))
        val dictionaryStream = compress(byteArrayOf(0, 0x7F), dictionary = byteArrayOf(0, 1, 2, 3))
        assertInvalid(png(compressed = dictionaryStream))
    }

    @Test
    fun `strict PNG rejects missing misordered and oversized chunks`() {
        val valid = png()
        for (size in 8..19) assertInvalid(valid.copyOf(size))
        assertInvalid(valid.copyOf().apply { ByteBuffer.wrap(this).putInt(8, -1) })
        assertInvalid(valid.copyOf().apply { ByteBuffer.wrap(this).putInt(8, Int.MAX_VALUE) })
        assertInvalid(valid.copyOf().apply { this[12] = 'X'.code.toByte() })
        assertInvalid(valid.copyOf().apply { this[29] = (this[29].toInt() xor 1).toByte() })
        assertInvalid(signature + chunk("IDAT", compress(byteArrayOf(0, 0x7F))) + chunk("IHDR", header()) + chunk("IEND", byteArrayOf()))
        assertInvalid(
            signature + chunk("IHDR", header()) + chunk("IDAT", byteArrayOf()) +
                chunk("IDAT", byteArrayOf()) + chunk("IEND", byteArrayOf()),
        )
    }

    @Test
    fun `packed records reject missing bytes trailing bytes and unterminated dimensions`() {
        val invalid =
            listOf(
                byteArrayOf(0x80.toByte()),
                byteArrayOf(0x80.toByte(), 0x80.toByte()),
                byteArrayOf(1, 0x80.toByte()),
                byteArrayOf(1, 0x80.toByte(), 0x80.toByte()),
                byteArrayOf(1, 1),
                byteArrayOf(1, 1, 0, 0),
                byteArrayOf(1, 1, 1),
            )
        invalid.forEach { bytes -> assertThrows(LogoFormatException::class.java) { MonoBitmap.fromBytes(bytes) } }
    }

    private fun assertInvalid(bytes: ByteArray) {
        assertThrows(LogoFormatException::class.java) { MonoPng.decode(bytes) }
    }

    private fun header(): ByteArray =
        ByteBuffer
            .allocate(13)
            .putInt(1)
            .putInt(1)
            .put(byteArrayOf(1, 0, 0, 0, 0))
            .array()

    private fun png(
        header: ByteArray = header(),
        raw: ByteArray = byteArrayOf(0, 0x7F),
        compressed: ByteArray = compress(raw),
        end: ByteArray = byteArrayOf(),
    ): ByteArray = signature + chunk("IHDR", header) + chunk("IDAT", compressed) + chunk("IEND", end)

    private fun compress(
        raw: ByteArray,
        dictionary: ByteArray? = null,
    ): ByteArray {
        val deflater = Deflater()
        return try {
            dictionary?.let(deflater::setDictionary)
            deflater.setInput(raw)
            deflater.finish()
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (!deflater.finished()) bytes.write(buffer, 0, deflater.deflate(buffer))
            bytes.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun chunk(
        type: String,
        data: ByteArray,
    ): ByteArray {
        val tag = type.toByteArray(Charsets.US_ASCII)
        val crc =
            CRC32()
                .apply {
                    update(tag)
                    update(data)
                }.value
                .toInt()
        return ByteBuffer
            .allocate(data.size + 12)
            .putInt(data.size)
            .put(tag)
            .put(data)
            .putInt(crc)
            .array()
    }
}
