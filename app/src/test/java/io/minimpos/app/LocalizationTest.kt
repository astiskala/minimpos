package io.minimpos.app

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.minimpos.app.feature.settings.currencyOptions
import io.minimpos.app.feature.settings.localName
import io.minimpos.app.receipt.PrintRenderer
import io.minimpos.app.terminal.ApiCheck
import io.minimpos.core.money.AdyenCurrencies
import io.minimpos.core.receipt.HtmlReceiptRenderer
import io.minimpos.core.receipt.PlainTextReceiptRenderer
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.receipt.ReceiptElement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ja-rJP")
class LocalizationTest {
    @Test
    fun `translations cover every resource and preserve format arguments`() {
        val base = resourceEntries("values")
        listOf("values-ja", "values-zh-rCN").forEach { folder ->
            val translated = resourceEntries(folder)
            assertThat(translated.keys).containsExactlyElementsIn(base.keys)
            base.forEach { (name, texts) ->
                val localized = translated.getValue(name)
                // Japanese and Chinese both use only the CLDR "other" plural form.
                assertThat(localized.size).isEqualTo(if (name.startsWith("plurals:")) 1 else texts.size)
                localized.zip(if (name.startsWith("plurals:")) listOf(texts.last()) else texts).forEach { (actual, source) ->
                    if (name in setOf("string:receipt_taxable_gross_format", "string:receipt_taxable_net_format")) {
                        assertWithMessage("$folder/$name").that(arguments(actual)).isEqualTo(
                            if (actual.isEmpty()) emptyList() else listOf("%1\$s"),
                        )
                    } else {
                        assertWithMessage("$folder/$name").that(arguments(actual)).isEqualTo(arguments(source))
                    }
                    assertThat(actual).doesNotContain("\uFFFD")
                }
            }
        }
    }

    @Test
    fun `Japanese defaults receipts and setup errors use localized resources`() =
        TestEnvironment().use { env ->
            val settings = await { env.container.settings.current() }
            assertThat(settings.receipt.title).isEqualTo("領収書")
            assertThat(settings.receipt.taxIdLabel).isEqualTo("登録番号")
            assertThat(settings.email.subject).isEqualTo("{business}の領収書")
            val document = env.container.sampleReceipt(settings.copy(payment = settings.payment.copy(currencyCode = "JPY")))
            val text = PlainTextReceiptRenderer().render(document)
            assertThat(text).contains("領収書")
            assertThat(text).contains("消費税 10%対象（税込）")
            assertThat(text).contains("内税（消費税 10%）")
            assertThat(text).contains("合計")
            assertThat(text).doesNotContain(".00")
            assertThat(PrintRenderer.jobs(document, 32)).isNotEmpty()
            receiptPreview("ja", document)
            assertThat(AdyenCurrencies["JPY"]!!.localName(Locale.JAPAN)).isNotEqualTo("Japanese Yen")
        }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `Chinese defaults use a receipt not a tax invoice`() =
        TestEnvironment().use { env ->
            val settings = await { env.container.settings.current() }
            assertThat(settings.receipt.title).isEqualTo("收据")
            assertThat(settings.receipt.footer).isEqualTo("谢谢惠顾！")
            val document = env.container.sampleReceipt(settings.copy(payment = settings.payment.copy(currencyCode = "CNY")))
            val text = PlainTextReceiptRenderer().render(document)
            assertThat(text).contains("收据")
            assertThat(text).contains("交易时间")
            assertThat(text).contains("合计")
            assertThat(text).contains(".00")
            assertThat(text).doesNotContain("发票")
            assertThat(text).doesNotContain("対象")
            receiptPreview("zh-CN", document)
            assertThat(AdyenCurrencies["CNY"]!!.localName(Locale.SIMPLIFIED_CHINESE)).contains("人民币")
        }

    @Test
    fun `receipt labels update with language without rewriting merchant content`() =
        TestEnvironment().use { env ->
            env.updateSettings { it.copy(receipt = it.receipt.copy(title = "My receipt", footer = "My footer")) }
            val settings = await { env.container.settings.current() }
            assertThat(
                env.container
                    .sampleReceipt(settings)
                    .elements
                    .filterIsInstance<ReceiptElement.Row>()
                    .map { it.left },
            ).contains("合計")
            RuntimeEnvironment.setQualifiers("zh-rCN")
            val translated = env.container.sampleReceipt(settings)
            val text = PlainTextReceiptRenderer().render(translated)
            assertThat(text).contains("My receipt")
            assertThat(text).contains("My footer")
            assertThat(text).contains("合计")
            assertThat(text).contains("自定义商品")
            assertThat(await { env.container.settings.current() }.receipt.title).isEqualTo("My receipt")
            assertThat(await { env.container.api.verify() }).isEqualTo(ApiCheck.Works)
        }

    @Test
    fun `currency names retain Adyen names in English and unknown-code fallback`() {
        assertThat(AdyenCurrencies["AUD"]!!.localName(Locale.US)).isEqualTo("Australian Dollar")
        assertThat(AdyenCurrencies["CNH"]!!.localName(Locale.JAPAN)).isEqualTo("Yuan Renminbi (offshore)")
        assertThat(currencyOptions(" 日本 ", Locale.JAPAN)).contains("JPY")
        assertThat(currencyOptions("人民币", Locale.SIMPLIFIED_CHINESE)).contains("CNY")
        assertThat(currencyOptions("Australian Dollar", Locale.JAPAN)).containsExactly("AUD")
        assertThat(currencyOptions(" aUd ", Locale.SIMPLIFIED_CHINESE)).containsExactly("AUD", "SAR").inOrder()
        assertThat(currencyOptions("no such currency", Locale.JAPAN)).isEmpty()
        assertThat(currencyOptions("", Locale.JAPAN).first()).isEmpty()
    }

    private fun resourceEntries(folder: String): Map<String, List<String>> {
        val root =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/$folder/strings.xml"))
                .documentElement
        return (0 until root.childNodes.length)
            .map { root.childNodes.item(it) }
            .filter { it.nodeType == Node.ELEMENT_NODE }
            .associate { node ->
                val name = "${node.nodeName}:${node.attributes.getNamedItem("name").nodeValue}"
                val values =
                    if (node.nodeName == "string") {
                        listOf(node.textContent)
                    } else {
                        (0 until node.childNodes.length)
                            .map { node.childNodes.item(it) }
                            .filter { it.nodeType == Node.ELEMENT_NODE }
                            .map { it.textContent }
                    }
                name to values
            }
    }

    private fun arguments(text: String): List<String> =
        Regex("""%(?:\d+\$)?[ds]""")
            .findAll(text)
            .map { it.value }
            .sorted()
            .toList()

    private fun receiptPreview(
        language: String,
        document: ReceiptDocument,
    ) {
        val directory = File("build/reports/localization").apply { mkdirs() }
        val text = PlainTextReceiptRenderer().render(document)
        File(directory, "receipt-$language.txt").writeText(text)
        File(directory, "receipt-$language.html").writeText(
            """
            <!doctype html><html lang="$language"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            <style>body{margin:24px;background:#f3f6f9}pre{background:white;padding:24px;width:max-content;max-width:100%;
            font:16px/1.7 ui-monospace,monospace;white-space:pre-wrap}</style><pre>${HtmlReceiptRenderer.escape(text)}</pre></html>
            """.trimIndent(),
        )
    }

    private inline fun <T> TestEnvironment.use(block: (TestEnvironment) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }
}
