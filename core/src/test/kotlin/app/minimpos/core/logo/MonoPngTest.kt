package app.minimpos.core.logo

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater
import javax.imageio.ImageIO

class MonoPngTest {
    @Test
    fun `png is one bit grayscale with black zero and white padding`() {
        val bitmap = MonoBitmap(9, 1, byteArrayOf(-1, 0x80.toByte()))
        val png = MonoPng.encode(bitmap)
        assertThat(png.copyOfRange(24, 29)).isEqualTo(byteArrayOf(1, 0, 0, 0, 0))
        val input = ByteBuffer.wrap(png).apply { position(33) }
        val compressed = ByteArray(input.int)
        input.position(input.position() + 4)
        input.get(compressed)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed)
            val raw = ByteArray(3)
            assertThat(inflater.inflate(raw)).isEqualTo(3)
            assertThat(raw).isEqualTo(byteArrayOf(0, 0, 0x7F))
        } finally {
            inflater.end()
        }
        assertThat(MonoPng.decode(png)).isEqualTo(bitmap)
        val decoded = ImageIO.read(png.inputStream())
        assertThat(decoded.getRGB(0, 0)).isEqualTo(0xFF000000.toInt())
    }

    @Test
    fun `round trips largest checkerboard and incompressible image`() {
        val checker = MonoBitmap(384, 192, ByteArray(48 * 192) { if (it / 48 % 2 == 0) 0x55 else 0xAA.toByte() })
        assertThat(MonoPng.decode(MonoPng.encode(checker))).isEqualTo(checker)
        val random = Random(123)
        val bitmap = MonoBitmap(384, 192, ByteArray(48 * 192).also(random::nextBytes))
        assertThat(MonoPng.decode(MonoPng.encode(bitmap))).isEqualTo(bitmap)
    }

    @Test
    fun `rejects unsupported and malformed PNG`() {
        val rgb =
            ByteArrayOutputStream()
                .apply {
                    ImageIO.write(
                        BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB),
                        "png",
                        this,
                    )
                }.toByteArray()
        val png = MonoPng.encode(MonoBitmap(3, 1, byteArrayOf(0xA0.toByte())))
        val invalid =
            listOf(
                byteArrayOf(),
                ByteArray(256_001),
                png.copyOf(7),
                png.copyOf().apply { this[0] = 0 },
                png.copyOf(34),
                png.copyOf().apply {
                    this[33] = (-1).toByte()
                },
                png.copyOf().apply { this[37] = 0 },
                png.copyOf().apply { this[29] = 0 },
                png + 0,
                rgb,
            )
        invalid.forEach { assertThrows(LogoFormatException::class.java) { MonoPng.decode(it) } }
        val badWidth = png.copyOf().apply { ByteBuffer.wrap(this).putInt(16, 385) }
        repairCrc(badWidth, 8)
        assertThrows(LogoFormatException::class.java) { MonoPng.decode(badWidth) }
        val badHeader = png.copyOf().apply { this[24] = 8 }
        repairCrc(badHeader, 8)
        assertThrows(LogoFormatException::class.java) { MonoPng.decode(badHeader) }
    }

    private fun repairCrc(
        png: ByteArray,
        offset: Int,
    ) {
        val length = ByteBuffer.wrap(png).getInt(offset)
        val crc = CRC32().apply { update(png, offset + 4, length + 4) }.value.toInt()
        ByteBuffer.wrap(png).putInt(offset + length + 8, crc)
    }
}
