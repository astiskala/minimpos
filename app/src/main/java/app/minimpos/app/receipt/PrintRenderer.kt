package app.minimpos.app.receipt

import app.minimpos.app.data.db.Failure
import app.minimpos.core.receipt.Align
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import app.minimpos.core.receipt.ReceiptSegment
import app.minimpos.core.receipt.TextStyle
import app.minimpos.terminal.client.PrintAlign
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintStyle

/** Maps receipt documents to print jobs: one text job per block ([ReceiptDocument.segments]), one job per QR code. */
object PrintRenderer {
    /** The print jobs for [document], in order; a divider is [dividerWidth] dashes, centred. */
    fun jobs(
        document: ReceiptDocument,
        dividerWidth: Int,
    ): List<PrintJob> =
        document.segments().map { segment ->
            when (segment) {
                is ReceiptSegment.TextBlock -> PrintJob.Text(segment.elements.mapNotNull { line(it, dividerWidth) })
                is ReceiptSegment.QrCode -> PrintJob.QrCode(segment.content)
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
            is ReceiptElement.Link -> PrintLine.Text(element.url, PrintAlign.CENTER)
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

    /** The sale or refund no longer exists, so there was nothing to print or send. */
    data object Missing : ActionResult

    /**
     * It was not printed or sent.
     *
     * @property failure Why: the terminal or email is not set up, the terminal or mail server refused, or no answer.
     */
    data class Failed(
        val failure: Failure,
    ) : ActionResult
}
