package app.minimpos.core.logo

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class LogoConverterTest {
    @Test
    fun `black square on white trims to mark with uniform threshold`() {
        val pixels = IntArray(10_000) { index -> if (index % 100 in 25..74 && index / 100 in 25..74) BLACK else WHITE }
        val result = LogoConverter.convert(ArgbImage(100, 100, pixels)) as LogoResult.Converted
        assertThat(result.mode).isEqualTo(LogoMode.MARK)
        assertThat(result.threshold).isEqualTo(127)
        assertThat(result.inverted).isFalse()
        assertThat(result.bitmap).isEqualTo(MonoBitmap(50, 50, ByteArray(7 * 50) { if (it % 7 == 6) 0xC0.toByte() else -1 }))
    }

    @Test
    fun `light transparent and opaque dark background logos invert`() {
        val transparent = IntArray(800) { if (it % 40 in 10..29 && it / 40 in 5..14) WHITE else 0 }
        val result = convert(40, 20, transparent)
        assertThat(result.inverted).isTrue()
        assertThat(result.bitmap.width).isEqualTo(20)
        assertThat(result.bitmap.height).isEqualTo(10)
        assertThat(result.bitmap.isBlack(0, 0)).isTrue()
        val opaque = IntArray(800) { if (it % 40 in 10..29 && it / 40 in 5..14) WHITE else BLACK }
        assertThat(convert(40, 20, opaque).inverted).isTrue()
        assertThat(convert(2, 2, intArrayOf(0, 0, BLACK, 0)).inverted).isFalse()
    }

    @Test
    fun `gradient is photo with dark and light ends`() {
        val result = convert(256, 64, IntArray(256 * 64) { grey(it % 256) }, LogoOptions(trim = false))
        assertThat(result.mode).isEqualTo(LogoMode.PHOTO)
        assertThat(result.threshold).isEqualTo(127)
        val left = (0..<64).sumOf { y -> (0..<64).count { x -> result.bitmap.isBlack(x, y) } }
        val right = (0..<64).sumOf { y -> (192..<256).count { x -> result.bitmap.isBlack(x, y) } }
        assertThat(left).isGreaterThan(3276)
        assertThat(right).isLessThan(820)
    }

    @Test
    fun `never enlarges and respects both height bounds and narrow strips`() {
        listOf(
            Triple(50, 20, 50 to 20),
            Triple(1000, 100, 384 to 38),
            Triple(100, 1000, 19 to 192),
            Triple(4096, 1, 384 to 1),
            Triple(
                1,
                4096,
                1 to 192,
            ),
        ).forEach { (w, h, expected) ->
            val bitmap = convert(w, h, IntArray(w * h) { BLACK }, LogoOptions(invert = false)).bitmap
            assertThat(bitmap.width to bitmap.height).isEqualTo(expected)
        }
        val bitmap = convert(100, 1000, IntArray(100_000) { BLACK }, LogoOptions(invert = false, size = LogoSize.SMALL)).bitmap
        assertThat(bitmap.width to bitmap.height).isEqualTo(10 to 96)
    }

    @Test
    fun `transparent or white sources are blank with trim on or off`() {
        listOf(true, false).forEach { trim ->
            listOf(0, WHITE).forEach { pixel ->
                assertThat(
                    LogoConverter.convert(ArgbImage(2, 2, IntArray(4) { pixel }), LogoOptions(trim = trim)),
                ).isEqualTo(LogoResult.Blank)
            }
        }
    }

    @Test
    fun `explicit options and otsu choose exact thresholds`() {
        val options = LogoOptions(mode = LogoMode.MARK, threshold = 10, invert = false, trim = false)
        val result = convert(2, 1, intArrayOf(BLACK, WHITE), options)
        assertThat(result.mode).isEqualTo(LogoMode.MARK)
        assertThat(result.threshold).isEqualTo(10)
        assertThat(result.inverted).isFalse()
        assertThat(result.bitmap.bits()).isEqualTo(byteArrayOf(0x80.toByte()))
        assertThat(convert(2, 1, intArrayOf(BLACK, WHITE), options.copy(threshold = null)).threshold).isEqualTo(0)
        assertThat(convert(1, 1, intArrayOf(WHITE), options.copy(invert = true)).inverted).isTrue()
        assertThat(convert(1, 1, intArrayOf(BLACK), options.copy(threshold = 0)).bitmap.isBlack(0, 0)).isTrue()
        assertThat(convert(2, 1, intArrayOf(BLACK, WHITE), options.copy(threshold = 255)).bitmap.isBlack(1, 0)).isTrue()
    }

    @Test
    fun `input and options validate dimensions and own their pixels`() {
        val pixels = intArrayOf(BLACK)
        val image = ArgbImage(1, 1, pixels)
        pixels[0] = WHITE
        image.argb()[0] = WHITE
        assertThat(image.argb()).isEqualTo(intArrayOf(BLACK))
        listOf(0 to 1, 4097 to 1, 1 to 0, 1 to 4097, 2 to 2).forEach { (w, h) ->
            assertThrows(IllegalArgumentException::class.java) { ArgbImage(w, h, intArrayOf(0)) }
        }
        assertThrows(IllegalArgumentException::class.java) { LogoOptions(threshold = -1) }
        assertThrows(IllegalArgumentException::class.java) { LogoOptions(threshold = 256) }
    }

    private fun convert(
        w: Int,
        h: Int,
        pixels: IntArray,
        options: LogoOptions = LogoOptions(),
    ): LogoResult.Converted = LogoConverter.convert(ArgbImage(w, h, pixels), options) as LogoResult.Converted

    private fun grey(value: Int): Int = BLACK or (value shl 16) or (value shl 8) or value

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = -1
    }
}
