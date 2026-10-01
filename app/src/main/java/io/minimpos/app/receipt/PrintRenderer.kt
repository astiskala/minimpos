package io.minimpos.app.receipt

import io.minimpos.core.receipt.Align
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.receipt.ReceiptElement
import io.minimpos.core.receipt.ReceiptSegment
import io.minimpos.core.receipt.TextStyle
import io.minimpos.terminal.client.PrintAlign
import io.minimpos.terminal.client.PrintJob
import io.minimpos.terminal.client.PrintLine
import io.minimpos.terminal.client.PrintStyle

/** Maps receipt documents to print jobs: one text job per block, one job per QR code. */
object PrintRenderer {
    /** The print jobs for [document], in order; a divider is [dividerWidth] dashes, centred. */
    fun jobs(
        document: ReceiptDocument,
        dividerWidth: Int,
    ): List<PrintJob> {
        // Captions go above their QR code, in the same text request as the preceding content.
        val flattened =
            document.elements.flatMap { element ->
                val caption = (element as? ReceiptElement.Qr)?.caption
                if (element is ReceiptElement.Qr && caption != null) {
                    listOf(ReceiptElement.Blank, ReceiptElement.Text(caption, Align.CENTER), element.copy(caption = null))
                } else {
                    listOf(element)
                }
            }
        return ReceiptDocument(flattened).segments().map { segment ->
            when (segment) {
                is ReceiptSegment.TextBlock -> PrintJob.Text(segment.elements.mapNotNull { line(it, dividerWidth) })
                is ReceiptSegment.QrCode -> PrintJob.QrCode(segment.qr.content)
            }
        }
    }

    private fun line(
        element: ReceiptElement,
        dividerWidth: Int,
    ): PrintLine? =
        when (element) {
            is ReceiptElement.Text -> PrintLine.Text(element.text, align(element.align), style(element.style))
            is ReceiptElement.Row -> PrintLine.Columns(element.left, element.right, style(element.style))
            ReceiptElement.Divider -> PrintLine.Text("-".repeat(dividerWidth), PrintAlign.CENTER)
            ReceiptElement.Blank -> PrintLine.Text("")
            is ReceiptElement.Qr -> null
        }

    private fun style(style: TextStyle): PrintStyle =
        when (style) {
            TextStyle.NORMAL -> PrintStyle.NORMAL
            TextStyle.BOLD -> PrintStyle.BOLD
            TextStyle.UNDERLINE -> PrintStyle.UNDERLINE
        }

    private fun align(align: Align): PrintAlign =
        when (align) {
            Align.LEFT -> PrintAlign.LEFT
            Align.CENTER -> PrintAlign.CENTER
            Align.RIGHT -> PrintAlign.RIGHT
        }
}

/** The outcome of printing or emailing a receipt. */
sealed interface ActionResult {
    /** The receipt was printed or sent. */
    data object Success : ActionResult

    /**
     * It was not.
     *
     * @property message Why, for display.
     */
    data class Failure(
        val message: String,
    ) : ActionResult
}
