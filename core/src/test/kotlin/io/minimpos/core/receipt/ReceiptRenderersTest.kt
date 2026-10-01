package io.minimpos.core.receipt

import com.google.common.truth.Truth.assertThat
import io.minimpos.core.receipt.ReceiptElement.Blank
import io.minimpos.core.receipt.ReceiptElement.Divider
import io.minimpos.core.receipt.ReceiptElement.Qr
import io.minimpos.core.receipt.ReceiptElement.Row
import io.minimpos.core.receipt.ReceiptElement.Text
import org.junit.Assert.assertThrows
import org.junit.Test

class ReceiptRenderersTest {
    private val document =
        ReceiptDocument(
            listOf(
                Text("Shop", Align.CENTER, TextStyle.BOLD),
                Text("Right", Align.RIGHT),
                Row("Total", "$1.00", TextStyle.BOLD),
                Divider,
                Blank,
                Text("<b>&'\"", style = TextStyle.UNDERLINE),
                Qr("MPR1*x", "Scan me"),
                Qr("MPR1*y"),
            ),
        )

    @Test
    fun `plain text aligns within the width`() {
        val text = PlainTextReceiptRenderer(width = 16).render(document)
        assertThat(text)
            .isEqualTo(
                listOf(
                    "      Shop",
                    "           Right",
                    "Total      $1.00",
                    "----------------",
                    "",
                    "<b>&'\"",
                    "  [QR] Scan me",
                ).joinToString("\n") + "\n",
            )
    }

    @Test
    fun `plain text wraps long rows and words`() {
        val renderer = PlainTextReceiptRenderer(width = 16)
        val text = renderer.render(ReceiptDocument(listOf(Row("A very long product name", "$10.00"))))
        assertThat(text.lines()).containsAtLeast("A very long", "product name", "          $10.00").inOrder()
        assertThat(
            renderer.wrap("abcdefghijklmnopqrstuvwxyz end"),
        ).containsExactly("abcdefghijklmnop", "qrstuvwxyz end").inOrder()
        assertThat(renderer.wrap("aa abcdefghijklmnopqrst")).containsExactly("aa", "abcdefghijklmnop", "qrst").inOrder()
        assertThrows(IllegalArgumentException::class.java) { PlainTextReceiptRenderer(width = 5) }
    }

    @Test
    fun `html escapes content and references QR attachments`() {
        val html = HtmlReceiptRenderer().render(document, title = "Receipt & co", intro = "Hi <there>")
        assertThat(html).contains("<title>Receipt &amp; co</title>")
        assertThat(html).contains("Hi &lt;there&gt;")
        assertThat(html).contains("&lt;b&gt;&amp;&#39;&quot;")
        assertThat(html).contains("src=\"cid:qr0\"")
        assertThat(html).contains("src=\"cid:qr1\"")
        assertThat(html).contains("Scan me")
        assertThat(html).contains("text-align:right;")
        assertThat(html).contains("text-decoration:underline;")
        assertThat(HtmlReceiptRenderer().render(ReceiptDocument(emptyList()), "t")).doesNotContain("<p")
    }

    @Test
    fun `segments split text blocks around QR codes`() {
        val segments = document.segments()
        assertThat(segments).hasSize(3)
        assertThat((segments[0] as ReceiptSegment.TextBlock).elements).hasSize(6)
        assertThat((segments[1] as ReceiptSegment.QrCode).qr.content).isEqualTo("MPR1*x")
        assertThat(
            ReceiptDocument(listOf(Blank, Qr("a"), Blank)).segments(),
        ).containsExactly(ReceiptSegment.QrCode(Qr("a")))
    }
}
