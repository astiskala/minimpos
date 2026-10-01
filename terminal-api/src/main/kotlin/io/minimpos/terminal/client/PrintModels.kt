package io.minimpos.terminal.client

import com.adyen.model.nexo.AlignmentType
import com.adyen.model.nexo.BarcodeType
import com.adyen.model.nexo.CharacterStyleType
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.OutputBarcode
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.OutputText
import com.adyen.model.nexo.PrintOutput
import com.adyen.model.nexo.ResponseModeType
import java.net.URLDecoder
import java.net.URLEncoder

/** Where a [PrintLine.Text] sits on the paper. */
enum class PrintAlign {
    /** Flush left. */
    LEFT,

    /** Centred. */
    CENTER,

    /** Flush right. */
    RIGHT,
}

/** How the characters of a [PrintLine] are printed. */
enum class PrintStyle {
    /** Plain text. */
    NORMAL,

    /** Bold. */
    BOLD,

    /** Underlined. */
    UNDERLINE,
}

/** One printed line of a [PrintJob.Text]. */
sealed interface PrintLine {
    /** How the line's characters are printed. */
    val style: PrintStyle

    /**
     * A single text on its own line; an empty [text] prints a blank line.
     *
     * @property text What to print.
     * @property align Where it sits on the line.
     * @property style How its characters are printed.
     */
    data class Text(
        val text: String,
        val align: PrintAlign = PrintAlign.LEFT,
        override val style: PrintStyle = PrintStyle.NORMAL,
    ) : PrintLine

    /**
     * Two texts on the same line, [left] flush left and [right] flush right (such as an item and its price).
     *
     * @property left The text at the start of the line.
     * @property right The text at the end of the line.
     * @property style How both texts are printed.
     */
    data class Columns(
        val left: String,
        val right: String,
        override val style: PrintStyle = PrintStyle.NORMAL,
    ) : PrintLine
}

/**
 * One print request for [TerminalClient.print]. The mapping to nexo stays inside this module: text is printed as a
 * `Document`, a QR code as a `CustomerReceipt` bar code.
 */
sealed interface PrintJob {
    /**
     * Lines of text, printed in one request.
     *
     * @property lines The lines, top to bottom.
     */
    data class Text(
        val lines: List<PrintLine>,
    ) : PrintJob

    /**
     * A QR code. Adyen requires QR contents other than a plain URL to be URL-encoded; that is done when the request is
     * built, so [content] is the plain text the code should carry.
     *
     * @property content What the code carries.
     */
    data class QrCode(
        val content: String,
    ) : PrintJob
}

/** [this] as the nexo `PrintOutput` of a print request (response mode `PrintEnd`). */
internal fun PrintJob.toNexo(): PrintOutput =
    PrintOutput().apply {
        responseMode = ResponseModeType.PRINT_END
        when (val job = this@toNexo) {
            is PrintJob.Text -> {
                documentQualifier = DocumentQualifierType.DOCUMENT
                outputContent =
                    OutputContent().apply {
                        outputFormat = OutputFormatType.TEXT
                        outputText = job.lines.flatMap { it.toNexo() }
                    }
            }

            is PrintJob.QrCode -> {
                documentQualifier = DocumentQualifierType.CUSTOMER_RECEIPT
                outputContent =
                    OutputContent().apply {
                        outputFormat = OutputFormatType.BAR_CODE
                        outputBarcode =
                            OutputBarcode().apply {
                                barcodeType = BarcodeType.QRCODE
                                barcodeValue = URLEncoder.encode(job.content, UTF_8)
                            }
                    }
            }
        }
    }

/**
 * The job a nexo `PrintOutput` stands for, as a terminal would print it: bar code values are URL-decoded, and a text
 * that does not end its line is paired with the next one as [PrintLine.Columns]. Null when there is nothing to print.
 */
internal fun PrintOutput.toPrintJob(): PrintJob? {
    val barcode = outputContent?.outputBarcode?.barcodeValue
    val texts = outputContent?.outputText
    return when {
        barcode != null -> PrintJob.QrCode(runCatching { URLDecoder.decode(barcode, UTF_8) }.getOrDefault(barcode))
        texts != null -> PrintJob.Text(lines(texts))
        else -> null
    }
}

private fun lines(texts: List<OutputText>): List<PrintLine> {
    val lines = mutableListOf<PrintLine>()
    var index = 0
    while (index < texts.size) {
        val first = texts[index]
        val second = texts.getOrNull(index + 1)
        if (!first.isEndOfLineFlag && second != null) {
            lines += PrintLine.Columns(first.text.orEmpty(), second.text.orEmpty(), style(first.characterStyle))
            index += 2
        } else {
            lines += PrintLine.Text(first.text.orEmpty(), align(first.alignment), style(first.characterStyle))
            index++
        }
    }
    return lines
}

private fun PrintLine.toNexo(): List<OutputText> =
    when (this) {
        is PrintLine.Text -> {
            listOf(text(text, style, align.toNexo(), endOfLine = true))
        }

        is PrintLine.Columns -> {
            listOf(
                text(left, style, AlignmentType.LEFT, endOfLine = false),
                text(right, style, AlignmentType.RIGHT, endOfLine = true),
            )
        }
    }

private fun text(
    value: String,
    style: PrintStyle,
    alignment: AlignmentType,
    endOfLine: Boolean,
) = OutputText().apply {
    text = value
    characterStyle =
        when (style) {
            PrintStyle.NORMAL -> null
            PrintStyle.BOLD -> CharacterStyleType.BOLD
            PrintStyle.UNDERLINE -> CharacterStyleType.UNDERLINED
        }
    this.alignment = alignment
    setEndOfLineFlag(endOfLine)
}

private fun PrintAlign.toNexo(): AlignmentType =
    when (this) {
        PrintAlign.LEFT -> AlignmentType.LEFT
        PrintAlign.CENTER -> AlignmentType.CENTRED
        PrintAlign.RIGHT -> AlignmentType.RIGHT
    }

private fun style(value: CharacterStyleType?): PrintStyle =
    when (value) {
        CharacterStyleType.BOLD -> PrintStyle.BOLD
        CharacterStyleType.UNDERLINED -> PrintStyle.UNDERLINE
        CharacterStyleType.NORMAL, CharacterStyleType.ITALIC, null -> PrintStyle.NORMAL
    }

private fun align(value: AlignmentType?): PrintAlign =
    when (value) {
        AlignmentType.CENTRED -> PrintAlign.CENTER
        AlignmentType.RIGHT -> PrintAlign.RIGHT
        AlignmentType.LEFT, AlignmentType.JUSTIFIED, null -> PrintAlign.LEFT
    }

private const val UTF_8 = "UTF-8"
