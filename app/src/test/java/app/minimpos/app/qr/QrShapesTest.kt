package app.minimpos.app.qr

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import com.google.common.truth.Truth.assertThat
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QrShapesTest {
    @Test
    fun `coalesced paths preserve every QR module and decode to the original transfer`() {
        val content = "MPC1:ABCD:1/2:" + "ABC123".repeat(80)
        val shape = QrCodes.shape(content)
        val matrix = QrCodes.matrix(content)
        val bitmap = createBitmap(shape.width * 4, shape.width * 4)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(4f, 4f)
        canvas.drawPath(
            shape.path,
            Paint().apply {
                color = Color.BLACK
                isAntiAlias = false
            },
        )
        for (y in 0..<shape.width) {
            for (x in 0..<shape.width) {
                assertThat(bitmap[x * 4 + 2, y * 4 + 2]).isEqualTo(if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        assertThat(QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text).isEqualTo(content)
        bitmap.recycle()
    }

    @Test
    fun `two code cache reuses current and next paths and evicts least recently used`() {
        val cache = QrShapes()
        val first = cache.get("first")
        val second = cache.get("second")
        assertThat(cache.get("first")).isSameInstanceAs(first)
        cache.get("third")
        assertThat(cache.get("first")).isSameInstanceAs(first)
        assertThat(cache.get("second")).isNotSameInstanceAs(second)
    }
}
