package app.minimpos.core.receipt

import app.minimpos.core.receipt.ReceiptElement.Blank
import app.minimpos.core.receipt.ReceiptElement.Divider
import app.minimpos.core.receipt.ReceiptElement.Qr
import app.minimpos.core.receipt.ReceiptElement.Row
import app.minimpos.core.receipt.ReceiptElement.Text
import com.google.common.truth.Truth.assertThat
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
    fun `CJK receipts align and wrap in display columns`() {
        val renderer = PlainTextReceiptRenderer(width = 16)
        val text =
            renderer.render(
                ReceiptDocument(
                    listOf(
                        Text("領収書", Align.CENTER),
                        Text("谢谢惠顾", Align.RIGHT),
                        Row("合計", "￥1,200"),
                        Row("超长的中文商品名称", "¥12.00"),
                    ),
                ),
            )
        assertThat(text.lines())
            .containsExactly(
                "     領収書",
                "        谢谢惠顾",
                "合計     ￥1,200",
                "超长的中文商品名",
                "称",
                "          ¥12.00",
                "",
            ).inOrder()
        assertThat(renderer.wrap("全角ＡＢＣ１２３商品名")).containsExactly("全角ＡＢＣ１２３", "商品名").inOrder()
        assertThat(renderer.wrap("ラテ とても長い商品名ラテ")).containsExactly("ラテ", "とても長い商品名", "ラテ").inOrder()
        assertThat(renderer.wrap("短い 名前")).containsExactly("短い 名前")
    }

    @Test
    fun `receipt width counts code points and combining marks without splitting them`() {
        assertThat(ReceiptTextWidth.columns("")).isEqualTo(0)
        assertThat(ReceiptTextWidth.columns("Cafe\u0301")).isEqualTo(4)
        assertThat(ReceiptTextWidth.columns("\u0903\u20DD")).isEqualTo(0)
        assertThat(ReceiptTextWidth.columns("ｶﾀｶﾅ")).isEqualTo(4)
        assertThat(ReceiptTextWidth.columns("中文かなＡＢ한글")).isEqualTo(16)
        val astral = "\uD840\uDC00"
        assertThat(ReceiptTextWidth.columns(astral)).isEqualTo(2)
        assertThat(ReceiptTextWidth.lineEnd("a\u0301$astral", 1)).isEqualTo(2)
        assertThat(ReceiptTextWidth.lineEnd(astral, 1)).isEqualTo(0)
        assertThat(ReceiptTextWidth.lineEnd(astral, 2)).isEqualTo(2)
        val renderer = PlainTextReceiptRenderer(16)
        assertThat(renderer.wrap(astral.repeat(9))).containsExactly(astral.repeat(8), astral).inOrder()
        assertThat(renderer.wrap("a\u0301".repeat(17))).containsExactly("a\u0301".repeat(16), "a\u0301").inOrder()
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
    fun `links are a button in html only for web addresses, and a whole line in plain text`() {
        val url = "https://test.adyen.link/PL50C5F751CED39G71?x=1&y=2"
        val links = ReceiptDocument(listOf(ReceiptElement.Link(url, "Pay <now>"), ReceiptElement.Link("javascript:alert(1)")))
        val html = HtmlReceiptRenderer().render(links, "t")
        assertThat(html).contains("<a href=\"https://test.adyen.link/PL50C5F751CED39G71?x=1&amp;y=2\"")
        assertThat(html).contains(">Pay &lt;now&gt;</a>")
        assertThat(html).doesNotContain("href=\"javascript")
        assertThat(html).contains("javascript:alert(1)</div>")
        assertThat(
            HtmlReceiptRenderer().render(ReceiptDocument(listOf(ReceiptElement.Link("http://a.b"))), "t"),
        ).contains(">http://a.b</a>")
        val text = PlainTextReceiptRenderer(width = 16).render(links)
        assertThat(text.lines()).containsAtLeast(url, "javascript:alert(1)").inOrder()
        assertThat(links.segments()).containsExactly(ReceiptSegment.TextBlock(links.elements))
    }

    @Test
    fun `segments split text blocks around QR codes, with each caption printed above its code`() {
        val segments = document.segments()
        assertThat(segments).hasSize(3)
        val text = (segments[0] as ReceiptSegment.TextBlock).elements
        assertThat(text).hasSize(8)
        assertThat(text.takeLast(2)).containsExactly(Blank, Text("Scan me", Align.CENTER)).inOrder()
        assertThat(segments.drop(1)).containsExactly(ReceiptSegment.QrCode("MPR1*x"), ReceiptSegment.QrCode("MPR1*y")).inOrder()
        assertThat(
            ReceiptDocument(listOf(Blank, Qr("a"), Blank)).segments(),
        ).containsExactly(ReceiptSegment.QrCode("a"))
        // A caption on its own is worth a text request.
        assertThat(ReceiptDocument(listOf(Qr("a", "Code"))).segments().first())
            .isEqualTo(ReceiptSegment.TextBlock(listOf(Blank, Text("Code", Align.CENTER))))
    }
}
