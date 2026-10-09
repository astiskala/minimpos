package app.minimpos.app.qr

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
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
        for (y in 0 until size) {
            for (x in 0 until size) {
                bitmap[x, y] = if (matrix[x / scale, y / scale]) Color.BLACK else Color.WHITE
            }
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
