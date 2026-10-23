package app.minimpos.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/** QR code generation with ZXing (UTF-8, error correction M by default), for the screen and for emails. */
object QrCodes {
    /** Ignored for PNG, which is lossless, but required by [Bitmap.compress]. */
    private const val PNG_QUALITY = 100

    /**
     * Module matrix without scaling; one entry per QR module (plus [margin] quiet-zone modules).
     *
     * @throws com.google.zxing.WriterException if [content] is too long for a QR code.
     */
    fun matrix(
        content: String,
        margin: Int = 2,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): BitMatrix =
        QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            0,
            0,
            mapOf(
                EncodeHintType.MARGIN to margin,
                EncodeHintType.ERROR_CORRECTION to errorCorrection,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /**
     * Immutable drawing data after publication; callers must not mutate [path].
     * @property width Width in QR modules, including the quiet zone.
     * @property path Coalesced black runs in module coordinates, drawn without antialiasing.
     */
    class Shape(
        val width: Int,
        val path: Path,
    )

    /** Encodes [content] into coalesced drawing runs; CPU work, never called during composition or drawing. */
    fun shape(content: String): Shape {
        val matrix = matrix(content)
        val path = Path()
        for (y in 0..<matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (matrix[x, y]) {
                    val start = x
                    while (x < matrix.width && matrix[x, y]) x++
                    path.addRect(start.toFloat(), y.toFloat(), x.toFloat(), y + 1f, Path.Direction.CW)
                } else {
                    x++
                }
            }
        }
        return Shape(matrix.width, path)
    }

    /**
     * A black-on-white bitmap of the code, scaled by a whole number of pixels per module so it stays sharp; it is at
     * most [sizePx] wide, or one pixel per module when the code has more modules than that.
     */
    fun bitmap(
        content: String,
        sizePx: Int,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
    ): Bitmap {
        val matrix = matrix(content, errorCorrection = errorCorrection)
        val scale = (sizePx / matrix.width).coerceAtLeast(1)
        val size = matrix.width * scale
        val bitmap = createBitmap(size, size)
        val row = IntArray(size)
        for (moduleY in 0..<matrix.height) {
            for (x in 0..<size) row[x] = if (matrix[x / scale, moduleY]) Color.BLACK else Color.WHITE
            for (offset in 0..<scale) bitmap.setPixels(row, 0, size, 0, moduleY * scale + offset, size, 1)
        }
        return bitmap
    }

    /** The code as PNG bytes of about [sizePx] pixels square (see [bitmap]), for inline email images. */
    fun png(
        content: String,
        sizePx: Int = 360,
    ): ByteArray {
        val bitmap = bitmap(content, sizePx)
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
            bitmap.recycle()
            out.toByteArray()
        }
    }
}

/** Per-composable two-code cache; CPU callers only, safe across cancelled prefetch and replacement effects. */
internal class QrShapes {
    private val shapes = LinkedHashMap<String, QrCodes.Shape>(2, 0.75f, true)

    /** The code for [content] if it is already encoded, without encoding it; null otherwise. */
    @Synchronized
    fun cached(content: String): QrCodes.Shape? = shapes[content]

    /** Current or next code, encoding it only when absent; never retains a whole animated export. */
    @Synchronized
    fun get(content: String): QrCodes.Shape =
        shapes.getOrPut(content) { QrCodes.shape(content) }.also {
            if (shapes.size > 2) shapes.remove(shapes.keys.first())
        }
}
