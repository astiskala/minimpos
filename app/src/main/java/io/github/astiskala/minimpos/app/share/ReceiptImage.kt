package io.github.astiskala.minimpos.app.share

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
import io.github.astiskala.minimpos.app.qr.QrCodes
import io.github.astiskala.minimpos.core.receipt.Align
import io.github.astiskala.minimpos.core.receipt.ReceiptDocument
import io.github.astiskala.minimpos.core.receipt.ReceiptElement
import io.github.astiskala.minimpos.core.receipt.TextStyle
import kotlin.math.roundToInt

/**
 * Draws a receipt as an image to share, the way the on-screen preview shows it: a white slip, monospaced text, rows
 * with the value flush right, dashed dividers and QR codes. Text wraps at the slip's width (CJK glyphs and long links
 * included), so nothing is cut off.
 */
object ReceiptImage {
    /** The default width in pixels: an 80 mm printer's dots, sharp on phones and small enough for messaging apps. */
    const val DEFAULT_WIDTH_PX = 576

    private const val REFERENCE_WIDTH = 576f
    private const val TEXT_SIZE = 22f
    private const val PADDING = 32f
    private const val ROW_GAP = 16f
    private const val QR_SHARE = 0.55f
    private const val DASH = 6f
    private const val LINK_COLOR = 0xFF0A62D0.toInt()

    /** [document] as a bitmap [widthPx] wide and as tall as it needs; the caller recycles it. */
    fun render(
        document: ReceiptDocument,
        widthPx: Int = DEFAULT_WIDTH_PX,
    ): Bitmap {
        val scale = widthPx / REFERENCE_WIDTH
        val slip = Slip(widthPx, scale)
        val blocks = document.elements.map(slip::block)
        val padding = PADDING * scale
        val height = (padding * 2 + blocks.sumOf { it.height.toDouble() }).roundToInt()
        val bitmap = createBitmap(widthPx, height.coerceAtLeast(1))
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        var top = padding
        blocks.forEach { block ->
            canvas.withTranslation(padding, top) { block.draw(this) }
            top += block.height
        }
        return bitmap
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
        private val lineHeight = plain.fontSpacing
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
                is ReceiptElement.Link -> text(element.url, Align.CENTER, TextPaint(plain).apply { color = LINK_COLOR })
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
                ).build()

        private fun text(
            text: String,
            align: Align,
            paint: TextPaint,
        ): Block {
            val layout = layout(text, paint, content, align)
            return Block(layout.height.toFloat()) { layout.draw(it) }
        }

        /** The label wrapped beside the value; a value too wide to share the line goes under the label, flush right. */
        private fun row(row: ReceiptElement.Row): Block {
            val paint = paint(row.style)
            val valueWidth = paint.measureText(row.right).roundToInt()
            val gap = (ROW_GAP * scale).roundToInt()
            if (valueWidth + gap > content / 2) {
                val label = layout(row.left, paint, content)
                val value = layout(row.right, paint, content, Align.RIGHT)
                return Block((label.height + value.height).toFloat()) {
                    label.draw(it)
                    it.translate(0f, label.height.toFloat())
                    value.draw(it)
                }
            }
            val label = layout(row.left, paint, content - valueWidth - gap)
            return Block(maxOf(label.height.toFloat(), lineHeight)) {
                label.draw(it)
                it.drawText(row.right, (content - valueWidth).toFloat(), -paint.fontMetrics.ascent, paint)
            }
        }

        /** The code, centred and about half the slip wide, with its caption under it. */
        private fun qr(qr: ReceiptElement.Qr): Block {
            val size = (content * QR_SHARE).roundToInt()
            val code = QrCodes.bitmap(qr.content, size)
            val caption = qr.caption?.let { layout(it, plain, content, Align.CENTER) }
            val margin = lineHeight / 2
            return Block(margin + code.height + (caption?.height ?: 0)) {
                it.drawBitmap(code, (content - code.width) / 2f, margin, null)
                it.translate(0f, margin + code.height)
                caption?.draw(it)
            }
        }
    }
}
