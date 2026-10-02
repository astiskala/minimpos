package io.minimpos.core.receipt

/**
 * Fixed-width text rendering, used for the plain-text part of emailed receipts. Lines are at most `width` columns
 * (default [DEFAULT_WIDTH]). East Asian wide characters occupy two columns, combining marks zero and other characters
 * one. The constructor throws [IllegalArgumentException] when the width is below [MIN_WIDTH].
 */
class PlainTextReceiptRenderer(
    private val width: Int = DEFAULT_WIDTH,
) {
    init {
        require(width >= MIN_WIDTH) { "Receipt width must be at least $MIN_WIDTH characters" }
    }

    /**
     * Renders [document] as newline-terminated lines. Long text is word-wrapped, a row that does not fit on one line
     * becomes its wrapped label followed by the right-aligned value, a QR code is shown only by its caption as
     * `[QR] caption`, and a link is its address on a line of its own, however long, so mail clients can open it.
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
                    is ReceiptElement.Link -> appendLine(element.url)
                }
            }
        }.trimEnd('\n') + "\n"

    private fun row(
        left: String,
        right: String,
    ): List<String> {
        val occupied = ReceiptTextWidth.columns(left) + ReceiptTextWidth.columns(right)
        if (occupied + 1 <= width) {
            return listOf(left + " ".repeat(width - occupied) + right)
        }
        return wrap(left) + wrap(right).map { align(it, Align.RIGHT) }
    }

    private fun align(
        text: String,
        align: Align,
    ): String =
        when (align) {
            Align.LEFT -> text
            Align.RIGHT -> " ".repeat((width - ReceiptTextWidth.columns(text)).coerceAtLeast(0)) + text
            Align.CENTER -> " ".repeat(((width - ReceiptTextWidth.columns(text)) / 2).coerceAtLeast(0)) + text
        }

    /**
     * Word-wraps [text] to the width, breaking words longer than a line into line-sized pieces. Internal so tests can
     * check the edge cases directly.
     */
    internal fun wrap(text: String): List<String> {
        if (ReceiptTextWidth.columns(text) <= width) return listOf(text)
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in text.split(' ')) {
            var remaining = word
            while (ReceiptTextWidth.columns(remaining) > width) {
                if (current.isNotEmpty()) {
                    lines += current.toString()
                    current = StringBuilder()
                }
                val end = ReceiptTextWidth.lineEnd(remaining, width)
                lines += remaining.take(end)
                remaining = remaining.drop(end)
            }
            if (current.isNotEmpty() && ReceiptTextWidth.columns(current.toString()) + 1 + ReceiptTextWidth.columns(remaining) > width) {
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
        /** Columns per line by default, as on a typical 58 mm receipt printer. */
        const val DEFAULT_WIDTH = 32

        /** The narrowest supported width. */
        const val MIN_WIDTH = 16
    }
}

/** Monospaced receipt widths, counting full-width CJK glyphs rather than UTF-16 code units. */
internal object ReceiptTextWidth {
    private val COMBINING_TYPES =
        setOf(Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt())
    private val WIDE_RANGES =
        listOf(
            0x1100..0x115F,
            0x2329..0x232A,
            0x2E80..0xA4CF,
            0xAC00..0xD7A3,
            0xF900..0xFAFF,
            0xFE10..0xFE19,
            0xFE30..0xFE6F,
            0xFF01..0xFF60,
            0xFFE0..0xFFE6,
            0x1F300..0x1FAFF,
            0x20000..0x3FFFD,
        )

    /** The display columns in [text]; surrogate pairs and combining marks are kept intact. */
    fun columns(text: String): Int = text.codePoints().toArray().sumOf(::codePointWidth)

    /** UTF-16 index after the longest prefix fitting [columns], without splitting a code point. */
    fun lineEnd(
        text: String,
        columns: Int,
    ): Int {
        var end = 0
        var used = 0
        while (end < text.length) {
            val point = text.codePointAt(end)
            val size = codePointWidth(point)
            if (used + size > columns) break
            used += size
            end += Character.charCount(point)
        }
        return end
    }

    private fun codePointWidth(point: Int): Int =
        when {
            Character.getType(point) in COMBINING_TYPES -> 0
            WIDE_RANGES.any { point in it } -> 2
            else -> 1
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
                        ).append(qrContentId(qrIndex++))
                            .append("\" width=\"180\" height=\"180\" alt=\"")
                            .append(escape(element.caption.orEmpty()))
                            .append("\">")
                        element.caption?.let {
                            append("<div style=\"font-size:12px;color:#5c687c;\">").append(escape(it)).append("</div>")
                        }
                        append("</div>")
                    }

                    is ReceiptElement.Link -> {
                        link(element)
                    }
                }
            }
            append("</div></body></html>")
        }

    /** A web link as a button with its address under it; anything but an `https`/`http` address is only text. */
    private fun StringBuilder.link(element: ReceiptElement.Link) {
        val url = escape(element.url)
        append("<div style=\"text-align:center;margin:12px 0;\">")
        if (WEB_ADDRESS.matches(element.url)) {
            append("<a href=\"").append(url).append("\" style=\"display:inline-block;padding:10px 20px;background:#0abf53;")
            append("color:#ffffff;border-radius:8px;text-decoration:none;font-weight:bold;\">")
            append(escape(element.label ?: element.url)).append("</a>")
        }
        append("<div style=\"font-size:12px;color:#5c687c;margin-top:6px;word-break:break-all;\">").append(url).append("</div>")
        append("</div>")
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
        /** The addresses a link button may open: web pages only, never `javascript:` or other schemes. */
        private val WEB_ADDRESS = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

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
