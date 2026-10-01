package io.minimpos.core.receipt

/**
 * Fixed-width text rendering, used for the plain-text part of emailed receipts. Lines are at most `width` characters
 * (default [DEFAULT_WIDTH]); the constructor throws [IllegalArgumentException] when the width is below [MIN_WIDTH].
 */
class PlainTextReceiptRenderer(
    private val width: Int = DEFAULT_WIDTH,
) {
    init {
        require(width >= MIN_WIDTH) { "Receipt width must be at least $MIN_WIDTH characters" }
    }

    /**
     * Renders [document] as newline-terminated lines. Long text is word-wrapped, a row that does not fit on one line
     * becomes its wrapped label followed by the right-aligned value, and a QR code is shown only by its caption as
     * `[QR] caption`.
     */
    fun render(document: ReceiptDocument): String =
        buildString {
            for (element in document.elements) {
                when (element) {
                    is ReceiptElement.Text -> wrap(element.text).forEach { appendLine(align(it, element.align)) }
                    is ReceiptElement.Row -> row(element.left, element.right).forEach { appendLine(it) }
                    ReceiptElement.Divider -> appendLine("-".repeat(width))
                    ReceiptElement.Blank -> appendLine()
                    is ReceiptElement.Qr -> element.caption?.let { appendLine(align("[QR] $it", Align.CENTER)) }
                }
            }
        }.trimEnd('\n') + "\n"

    private fun row(
        left: String,
        right: String,
    ): List<String> {
        if (left.length + 1 + right.length <= width) {
            return listOf(left + " ".repeat(width - left.length - right.length) + right)
        }
        return wrap(left) + wrap(right).map { align(it, Align.RIGHT) }
    }

    private fun align(
        text: String,
        align: Align,
    ): String =
        when (align) {
            Align.LEFT -> text
            Align.RIGHT -> text.padStart(width)
            Align.CENTER -> " ".repeat(((width - text.length) / 2).coerceAtLeast(0)) + text
        }

    /**
     * Word-wraps [text] to the width, breaking words longer than a line into line-sized pieces. Internal so tests can
     * check the edge cases directly.
     */
    internal fun wrap(text: String): List<String> {
        if (text.length <= width) return listOf(text)
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            var remaining = word
            while (remaining.length > width) {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                lines += remaining.take(width)
                remaining = remaining.drop(width)
            }
            if (current.isNotEmpty() && current.length + 1 + remaining.length > width) {
                lines += current.toString()
                current = StringBuilder()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(remaining)
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /** The supported line widths. */
    companion object {
        /** Characters per line by default, as on a typical 58 mm receipt printer. */
        const val DEFAULT_WIDTH = 32

        /** The narrowest supported width. */
        const val MIN_WIDTH = 16
    }
}

/**
 * Self-contained HTML (inline styles only) for emailed receipts. QR codes reference inline CID attachments: the n-th
 * QR code (from 0, in [ReceiptDocument.qrCodes] order) is `cid:` plus the renderer's `qrContentId(n)`, "qr0", "qr1",
 * and so on by default, so the email must attach the code images under the same content ids.
 */
class HtmlReceiptRenderer(
    private val qrContentId: (index: Int) -> String = { "qr$it" },
) {
    /**
     * Renders [document] as a complete HTML page with [title] as its title and an optional [intro] paragraph above
     * the receipt. All text is escaped with [escape].
     */
    fun render(
        document: ReceiptDocument,
        title: String,
        intro: String? = null,
    ): String =
        buildString {
            append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
            append("<title>").append(escape(title)).append("</title></head>")
            append(
                "<body style=\"margin:0;padding:24px;background:#f3f6f9;font-family:Helvetica,Arial,sans-serif;color:#00112c;\">",
            )
            append("<div style=\"max-width:420px;margin:0 auto;background:#ffffff;border-radius:12px;padding:24px;\">")
            intro?.let { append("<p style=\"margin:0 0 16px 0;\">").append(escape(it)).append("</p>") }
            var qrIndex = 0
            for (element in document.elements) {
                when (element) {
                    is ReceiptElement.Text -> {
                        append("<div style=\"")
                            .append(textAlign(element.align))
                            .append(fontStyle(element.style))
                            .append("\">")
                            .append(escape(element.text))
                            .append("</div>")
                    }

                    is ReceiptElement.Row -> {
                        append("<table role=\"presentation\" width=\"100%\" style=\"border-collapse:collapse;")
                            .append(fontStyle(element.style))
                            .append("\"><tr><td style=\"padding:2px 0;\">")
                            .append(escape(element.left))
                            .append("</td><td style=\"padding:2px 0;text-align:right;white-space:nowrap;\">")
                            .append(escape(element.right))
                            .append("</td></tr></table>")
                    }

                    ReceiptElement.Divider -> {
                        append(
                            "<hr style=\"border:none;border-top:1px dashed #c7cfd8;margin:12px 0;\">",
                        )
                    }

                    ReceiptElement.Blank -> {
                        append("<div style=\"height:12px;\"></div>")
                    }

                    is ReceiptElement.Qr -> {
                        append("<div style=\"text-align:center;margin-top:16px;\">")
                        append(
                            "<img src=\"cid:",
                        ).append(qrContentId(qrIndex++)).append("\" width=\"180\" height=\"180\" alt=\"QR code\">")
                        element.caption?.let {
                            append("<div style=\"font-size:12px;color:#5c687c;\">").append(escape(it)).append("</div>")
                        }
                        append("</div>")
                    }
                }
            }
            append("</div></body></html>")
        }

    private fun textAlign(align: Align) =
        when (align) {
            Align.LEFT -> "text-align:left;"
            Align.CENTER -> "text-align:center;"
            Align.RIGHT -> "text-align:right;"
        }

    private fun fontStyle(style: TextStyle) =
        when (style) {
            TextStyle.NORMAL -> ""
            TextStyle.BOLD -> "font-weight:bold;"
            TextStyle.UNDERLINE -> "text-decoration:underline;"
        }

    /** HTML escaping, also used for the app's other HTML emails. */
    companion object {
        /** Escapes the HTML special characters `& < > " '` so [text] is safe in element content and attribute values. */
        fun escape(text: String): String =
            buildString(text.length) {
                for (c in text) {
                    when (c) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        '\'' -> append("&#39;")
                        else -> append(c)
                    }
                }
            }
    }
}
