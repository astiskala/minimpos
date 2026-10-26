package app.minimpos.core.logo

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MonoBitmapTest {
    @Test
    fun `round trips and copies packed pixels`() {
        val bits = byteArrayOf(0xA0.toByte(), 0x40)
        val bitmap = MonoBitmap(3, 2, bits)
        bits[0] = 0
        assertThat(bitmap.toBytes()).isEqualTo(byteArrayOf(3, 2, 0xA0.toByte(), 0x40))
        assertThat(MonoBitmap.fromBytes(bitmap.toBytes())).isEqualTo(bitmap)
        assertThat(MonoBitmap.fromBytes(bitmap.toBytes()).hashCode()).isEqualTo(bitmap.hashCode())
        assertThat(bitmap.isBlack(0, 0)).isTrue()
        assertThat(bitmap.isBlack(1, 0)).isFalse()
        bitmap.bits()[0] = 0
        assertThat(bitmap.isBlack(2, 0)).isTrue()
        assertThat(bitmap).isNotEqualTo(null)
        assertThat(bitmap).isNotEqualTo("bitmap")
        assertThat(bitmap).isNotEqualTo(MonoBitmap(2, 2, byteArrayOf(0, 0)))
        assertThat(bitmap).isNotEqualTo(MonoBitmap(3, 1, byteArrayOf(0)))
        assertThat(bitmap).isNotEqualTo(MonoBitmap(3, 2, byteArrayOf(0, 0)))
        assertThrows(IllegalArgumentException::class.java) { bitmap.isBlack(-1, 0) }
        assertThrows(IllegalArgumentException::class.java) { bitmap.isBlack(3, 0) }
        assertThrows(IllegalArgumentException::class.java) { bitmap.isBlack(0, -1) }
        assertThrows(IllegalArgumentException::class.java) { bitmap.isBlack(0, 2) }
    }

    @Test
    fun `rejects malformed records and dimensions`() {
        listOf(
            byteArrayOf(),
            byteArrayOf(3),
            byteArrayOf(3, 1, 0xB0.toByte()),
            byteArrayOf(3, 2, 0),
            byteArrayOf(0, 1),
            byteArrayOf(1, 0),
            byteArrayOf(0x81.toByte(), 3, 1),
            byteArrayOf(1, 0xC1.toByte(), 1),
            byteArrayOf(-1, -1, 0),
        ).forEach {
            assertThrows(LogoFormatException::class.java) { MonoBitmap.fromBytes(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { MonoBitmap(385, 1, byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { MonoBitmap(1, 193, byteArrayOf()) }
        val bitmap = MonoBitmap(384, 192, ByteArray(48 * 192))
        assertThat(MonoBitmap.fromBytes(bitmap.toBytes())).isEqualTo(bitmap)
    }
}
