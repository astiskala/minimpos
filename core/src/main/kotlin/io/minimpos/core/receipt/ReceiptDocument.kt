package io.minimpos.core.receipt

/** Horizontal alignment of a receipt text line. */
enum class Align {
    /** Flush left, the default for body text. */
    LEFT,

    /** Centred, used for the header, titles and footer. */
    CENTER,

    /** Flush right. */
    RIGHT,
}

/** Emphasis of a receipt line; each renderer maps it to what its output supports. */
enum class TextStyle {
    /** Plain text. */
    NORMAL,

    /** Bold, e.g. the business name and totals. */
    BOLD,

    /** Underlined. */
    UNDERLINE,
}

/** Output-neutral receipt content, rendered to Terminal API print requests, HTML email, plain text or Compose. */
sealed interface ReceiptElement {
    /**
     * A line of text; renderers wrap it when it is wider than the receipt.
     *
     * @property text the text, normally one line ([ReceiptBuilder] splits multi-line settings such as the footer).
     * @property align where the text sits on the line.
     * @property style the text's emphasis.
     */
    data class Text(
        val text: String,
        val align: Align = Align.LEFT,
        val style: TextStyle = TextStyle.NORMAL,
    ) : ReceiptElement

    /**
     * A label on the left and a value flush right on the same line.
     *
     * @property left the label, e.g. an item name or "Subtotal".
     * @property right the value, usually a formatted amount.
     * @property style the emphasis of both parts.
     */
    data class Row(
        val left: String,
        val right: String,
        val style: TextStyle = TextStyle.NORMAL,
    ) : ReceiptElement

    /** A horizontal rule separating sections (a line of dashes across the width in plain text). */
    data object Divider : ReceiptElement

    /** An empty line. */
    data object Blank : ReceiptElement

    /**
     * A QR code, such as the refund code (`RefundQrPayload`) printed at the end of a sale receipt.
     *
     * @property content the text encoded in the QR code.
     * @property caption a line printed under the code, or null for none.
     */
    data class Qr(
        val content: String,
        val caption: String? = null,
    ) : ReceiptElement

    /**
     * A web address, such as a payment link: printed and shown as the address itself on a line of its own (never
     * wrapped at spaces, which it has none of), and a button labelled [label] in HTML email when it is an `https` or
     * `http` address.
     *
     * @property url the address.
     * @property label the text of the email's button, or null to show [url] there too.
     */
    data class Link(
        val url: String,
        val label: String? = null,
    ) : ReceiptElement
}

/** A part of a [ReceiptDocument] that is printed with one Terminal API print request; see [ReceiptDocument.segments]. */
sealed interface ReceiptSegment {
    /**
     * Consecutive text elements, printed as one text request.
     *
     * @property elements the elements in order, ending with the caption of a QR code that follows; never a
     *   [ReceiptElement.Qr], and never only blank lines.
     */
    data class TextBlock(
        val elements: List<ReceiptElement>,
    ) : ReceiptSegment

    /**
     * A QR code, printed as a request of its own; its caption is in the text block before it.
     *
     * @property content the text encoded in the QR code.
     */
    data class QrCode(
        val content: String,
    ) : ReceiptSegment
}

/**
 * A complete receipt, built by [ReceiptBuilder] and rendered by the printer, [HtmlReceiptRenderer],
 * [PlainTextReceiptRenderer] or the on-screen preview.
 *
 * @property elements the receipt's content from top to bottom.
 */
data class ReceiptDocument(
    val elements: List<ReceiptElement>,
) {
    /**
     * Splits the document into consecutive text blocks and QR codes. Adyen terminals print text and QR codes in
     * separate print requests, which come out on one slip when sent back to back. A QR code's caption ends the text
     * block before it, after a blank line and centred, so it prints right above the code. Blocks consisting only of
     * blank lines are dropped.
     */
    fun segments(): List<ReceiptSegment> {
        val segments = mutableListOf<ReceiptSegment>()
        val pending = mutableListOf<ReceiptElement>()

        fun flush() {
            if (pending.any { it !is ReceiptElement.Blank }) segments += ReceiptSegment.TextBlock(pending.toList())
            pending.clear()
        }
        for (element in elements) {
            if (element is ReceiptElement.Qr) {
                element.caption?.let { pending += listOf(ReceiptElement.Blank, ReceiptElement.Text(it, Align.CENTER)) }
                flush()
                segments += ReceiptSegment.QrCode(element.content)
            } else {
                pending += element
            }
        }
        flush()
        return segments
    }

    /** The document's QR codes in order; emailed receipts attach them as images, numbered in this order. */
    val qrCodes: List<ReceiptElement.Qr> get() = elements.filterIsInstance<ReceiptElement.Qr>()
}
