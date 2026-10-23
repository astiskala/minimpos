package app.minimpos.app.share

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withTranslation
import app.minimpos.app.qr.QrCodes
import app.minimpos.core.receipt.Align
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.core.receipt.ReceiptSegment
import app.minimpos.core.receipt.TextStyle
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws the fixed-width receipt used by both the on-screen preview and sharing: a white slip, monospaced text, rows
 * with the value flush right, dashed dividers and QR codes. Text wraps at the slip's width (CJK glyphs and long links
 * included), so nothing is cut off.
 */
object ReceiptImage {
    /** The default width in pixels: an 80 mm printer's dots, sharp on phones and small enough for messaging apps. */
    const val DEFAULT_WIDTH_PX = 576

    private const val MAX_HEIGHT = 16_384
    private const val MAX_PIXELS = 4_000_000
    private const val REFERENCE_WIDTH = 576f
    private const val TEXT_SIZE = 22f
    private const val PADDING = 32f
    private const val ROW_GAP = 16f
    private const val LINE_GAP = 6f
    private const val QR_SHARE = 0.55f
    private const val DASH = 6f

    /**
     * [document] as a bitmap up to [widthPx] wide; very long slips scale down to fit 16384 pixels high and four million
     * pixels total without truncation. The caller recycles it. Run off the main thread.
     */
    fun render(
        document: ReceiptDocument,
        widthPx: Int = DEFAULT_WIDTH_PX,
    ): Bitmap {
        val measured = measure(document, widthPx)
        // Bound native allocation and texture dimensions even for very long delivered receipts.
        val scale =
            minOf(
                1.0,
                MAX_HEIGHT.toDouble() / measured.height,
                sqrt(MAX_PIXELS.toDouble() / (widthPx.toDouble() * measured.height)),
            )
        val bitmap = createBitmap((widthPx * scale).toInt().coerceAtLeast(1), (measured.height * scale).toInt().coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        canvas.scale(scale.toFloat(), scale.toFloat())
        measured.draw(canvas)
        return bitmap
    }

    /** Measures [document] off the main thread; [widthPx] is its fixed layout width in pixels. No full-height bitmap is allocated. */
    fun measure(
        document: ReceiptDocument,
        widthPx: Int = DEFAULT_WIDTH_PX,
    ): MeasuredReceipt {
        val scale = widthPx / REFERENCE_WIDTH
        val slip = Slip(widthPx, scale)
        val blocks =
            document.segments().flatMap { segment ->
                when (segment) {
                    is ReceiptSegment.TextBlock -> segment.elements.map(slip::block)
                    is ReceiptSegment.QrCode -> listOf(slip.block(ReceiptElement.Qr(segment.content)))
                }
            }
        val padding = PADDING * scale
        val height = (padding * 2 + blocks.sumOf { it.height.toDouble() }).roundToInt().coerceAtLeast(1)
        var end = padding
        val ends = FloatArray(blocks.size) { index -> (end + blocks[index].height).also { end = it } }
        return MeasuredReceipt(widthPx, height) { canvas ->
            canvas.drawColor(Color.WHITE)
            val clip = canvas.clipBounds
            var index = visibleStart(ends, clip.top.toFloat())
            var top = if (index == 0) padding else ends[index - 1]
            while (index < blocks.size && top < clip.bottom) {
                canvas.withTranslation(padding, top) { blocks[index].draw(this) }
                top = ends[index]
                index++
            }
        }
    }

    /** First block whose bottom lies strictly below the clip's [top]; [ends] must be nondecreasing. */
    internal fun visibleStart(
        ends: FloatArray,
        top: Float,
    ): Int {
        var low = 0
        var high = ends.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (ends[middle] <= top) low = middle + 1 else high = middle
        }
        return low
    }

    /**
     * Fixed-width measured slip, drawable without a bitmap. Draw only on one thread at a time.
     * @property width Layout width in pixels.
     * @property height Full layout height in pixels.
     * @param paint Draws the measured blocks.
     */
    class MeasuredReceipt internal constructor(
        val width: Int,
        val height: Int,
        private val paint: (Canvas) -> Unit,
    ) {
        /** Draws the slip into [canvas], respecting its current transform and clipping bounds. */
        fun draw(canvas: Canvas) = paint(canvas)
    }

    /** One receipt element, measured: its [height] and how to [draw] it at the top left of the content area. */
    private class Block(
        val height: Float,
        val draw: (Canvas) -> Unit,
    )

    /** The paints and measures of one slip [widthPx] wide, drawn at [scale] times the reference size. */
    private class Slip(
        widthPx: Int,
        private val scale: Float,
    ) {
        private val content = (widthPx - PADDING * 2 * scale).roundToInt()
        private val plain = paint(Typeface.NORMAL)
        private val bold = paint(Typeface.BOLD)
        private val lineHeight = plain.fontSpacing + LINE_GAP * scale
        private val divider =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                strokeWidth = scale
                pathEffect = DashPathEffect(floatArrayOf(DASH * scale, DASH * scale), 0f)
            }

        private fun paint(style: Int) =
            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = TEXT_SIZE * scale
                typeface = Typeface.create(Typeface.MONOSPACE, style)
            }

        private fun paint(style: TextStyle): TextPaint =
            TextPaint(if (style == TextStyle.BOLD) bold else plain).apply { isUnderlineText = style == TextStyle.UNDERLINE }

        fun block(element: ReceiptElement): Block =
            when (element) {
                is ReceiptElement.Text -> text(element.text, element.align, paint(element.style))
                is ReceiptElement.Row -> row(element)
                ReceiptElement.Divider -> Block(lineHeight) { it.drawLine(0f, lineHeight / 2, content.toFloat(), lineHeight / 2, divider) }
                ReceiptElement.Blank -> Block(lineHeight / 2) {}
                is ReceiptElement.Qr -> qr(element)
                is ReceiptElement.Link -> text(element.url, Align.CENTER, plain)
            }

        private fun layout(
            text: String,
            paint: TextPaint,
            width: Int,
            align: Align = Align.LEFT,
        ): StaticLayout =
            StaticLayout.Builder
                .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
                .setAlignment(
                    when (align) {
                        Align.LEFT -> Layout.Alignment.ALIGN_NORMAL
                        Align.CENTER -> Layout.Alignment.ALIGN_CENTER
                        Align.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    },
                ).setLineSpacing(LINE_GAP * scale, 1f)
                .build()

        private fun text(
            text: String,
            align: Align,
            paint: TextPaint,
        ): Block {
            val layout = layout(text, paint, content, align)
            return Block(layout.height + LINE_GAP * scale) { layout.draw(it) }
        }

        /** The label wrapped beside the value; a value too wide to share the line goes under the label, flush right. */
        private fun row(row: ReceiptElement.Row): Block {
            val paint = paint(row.style)
            val valueWidth = paint.measureText(row.right).roundToInt()
            val gap = (ROW_GAP * scale).roundToInt()
            if (paint.measureText(row.left) + valueWidth + gap > content) {
                val label = layout(row.left, paint, content)
                val value = layout(row.right, paint, content, Align.RIGHT)
                return Block(label.height + value.height + LINE_GAP * 2 * scale) {
                    label.draw(it)
                    it.translate(0f, label.height + LINE_GAP * scale)
                    value.draw(it)
                }
            }
            val label = layout(row.left, paint, content - valueWidth - gap)
            return Block(maxOf(label.height.toFloat(), lineHeight) + LINE_GAP * scale) {
                label.draw(it)
                it.drawText(row.right, (content - valueWidth).toFloat(), -paint.fontMetrics.ascent, paint)
            }
        }

        /** The code, centred and about half the slip wide; its caption is already in the preceding text segment. */
        private fun qr(qr: ReceiptElement.Qr): Block {
            val size = (content * QR_SHARE).roundToInt()
            val code = QrCodes.bitmap(qr.content, size)
            val margin = lineHeight / 2
            return Block(margin * 2 + code.height) {
                it.drawBitmap(code, (content - code.width) / 2f, margin, null)
            }
        }
    }
}
