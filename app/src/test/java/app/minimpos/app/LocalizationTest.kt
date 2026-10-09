package app.minimpos.app

import app.minimpos.app.feature.settings.currencyOptions
import app.minimpos.app.feature.settings.localName
import app.minimpos.app.receipt.PrintRenderer
import app.minimpos.app.terminal.ApiCheck
import app.minimpos.core.money.AdyenCurrencies
import app.minimpos.core.receipt.HtmlReceiptRenderer
import app.minimpos.core.receipt.PlainTextReceiptRenderer
import app.minimpos.core.receipt.ReceiptDocument
import app.minimpos.core.receipt.ReceiptElement
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
    fun `application identity uses the owned domain and keeps the display name`() {
        assertThat(BuildConfig.APPLICATION_ID).isEqualTo("app.minimpos")
        assertThat(RuntimeEnvironment.getApplication().packageName).isEqualTo("app.minimpos")
        listOf("values", "values-ja", "values-zh-rCN", "values-b+zh+Hant").forEach { folder ->
            assertThat(resourceEntries(folder).getValue("string:app_name").single()).isEqualTo("Mini mPOS")
        }
    }

    @Test
    fun `setup hints point to the localized helpers on the owned domain`() {
        mapOf(
            "values" to "",
            "values-ja" to "ja/",
            "values-zh-rCN" to "zh-CN/",
            "values-b+zh+Hant" to "zh-Hant/",
        ).forEach { (folder, path) ->
            assertWithMessage(folder)
                .that(resourceEntries(folder).getValue("string:settings_quick_setup_hint").single())
                .contains("minimpos.app/${path}setup.html")
        }
    }

    @Test
    fun `terminal address examples use an address reserved for documentation in every language`() {
        listOf("values", "values-ja", "values-zh-rCN", "values-b+zh+Hant").forEach { folder ->
            assertWithMessage(folder)
                .that(resourceEntries(folder).getValue("string:settings_host_placeholder").single())
                .contains("192.0.2.1")
        }
    }

    @Test
    fun `translations cover every resource and preserve format arguments`() {
        val base = resourceEntries("values")
        listOf("values-ja", "values-zh-rCN", "values-b+zh+Hant").forEach { folder ->
            val translated = resourceEntries(folder)
            assertThat(translated.keys).containsExactlyElementsIn(base.keys)
            base.forEach { (name, texts) ->
                val localized = translated.getValue(name)
                // Japanese and Chinese both use only the CLDR "other" plural form.
                assertThat(localized.size).isEqualTo(if (name.startsWith("plurals:")) 1 else texts.size)
                localized.zip(if (name.startsWith("plurals:")) listOf(texts.last()) else texts).forEach { (actual, source) ->
                    assertWithMessage("$folder/$name").that(arguments(actual)).isEqualTo(arguments(source))
                    assertThat(actual).doesNotContain("\uFFFD")
                }
            }
        }
    }

    @Test
    fun `Japanese defaults receipts and setup errors use localized resources`() =
        TestEnvironment(device = FakeDevice(country = "JP")).use { env ->
            val settings = await { env.container.settings.current() }
            assertThat(settings.receipt.title).isEqualTo("領収書")
            assertThat(settings.receipt.taxIdLabel).isEqualTo("登録番号")
            assertThat(settings.receipt.showTaxAmounts).isFalse()
            assertThat(settings.receipt.showTaxRateTotals).isTrue()
            assertThat(settings.receipt.markedTaxRateMilliPercent).isEqualTo(8_000)
            assertThat(settings.email.subject).isEqualTo("{business}の領収書")
            val document = env.container.sampleReceipt(settings.copy(payment = settings.payment.copy(currencyCode = "JPY")))
            val text = PlainTextReceiptRenderer().render(document)
            assertThat(text).contains("領収書")
            assertThat(text).contains("10%対象（税込）")
            assertThat(text).contains("8%対象（税込）")
            assertThat(text).contains("※は軽減税率対象商品")
            assertThat(text).doesNotContain("内税（消費税 10%）")
            assertThat(text).contains("合計")
            assertThat(text).doesNotContain(".00")
            assertThat(PrintRenderer.jobs(document, 32)).isNotEmpty()
            receiptPreview("ja", document)
            assertThat(AdyenCurrencies["JPY"]!!.localName(Locale.JAPAN)).isNotEqualTo("Japanese Yen")
        }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `Chinese defaults use a receipt not a tax invoice`() =
        TestEnvironment(device = FakeDevice(country = "CN")).use { env ->
            val settings = await { env.container.settings.current() }
            assertThat(settings.receipt.title).isEqualTo("收据")
            assertThat(settings.receipt.footer).isEqualTo("谢谢惠顾！")
            assertThat(settings.receipt.showTaxAmounts).isTrue()
            assertThat(settings.receipt.showTaxRateTotals).isFalse()
            assertThat(settings.receipt.markedTaxRateMilliPercent).isNull()
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
    fun `Android app language settings advertise both Chinese scripts`() {
        val locales =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File("src/main/res/xml/locales_config.xml"))
                .getElementsByTagName("locale")
        val names =
            (0 until locales.length).map {
                locales
                    .item(it)
                    .attributes
                    .getNamedItem("android:name")
                    .nodeValue
            }
        assertThat(names).containsExactly("en", "zh-CN", "zh-Hant", "ja")
    }

    @Test
    fun `Traditional Chinese locales share Hong Kong wording without replacing Simplified Chinese`() {
        listOf("zh-rHK", "zh-rMO", "zh-rTW", "b+zh+Hant").forEach { qualifiers ->
            RuntimeEnvironment.setQualifiers(qualifiers)
            val context = RuntimeEnvironment.getApplication()
            assertWithMessage(qualifiers).that(context.getString(R.string.home_settings)).isEqualTo("設定")
            assertWithMessage(qualifiers).that(context.getString(R.string.result_print)).isEqualTo("列印收據")
            assertWithMessage(qualifiers).that(context.getString(R.string.home_history)).isEqualTo("交易紀錄")
            assertWithMessage(qualifiers)
                .that(context.getString(R.string.settings_quick_setup_hint))
                .contains("minimpos.app/zh-Hant/setup.html")
        }
        RuntimeEnvironment.setQualifiers("zh-rCN")
        assertThat(RuntimeEnvironment.getApplication().getString(R.string.home_settings)).isEqualTo("设置")
    }

    @Test
    @Config(qualifiers = "zh-rHK")
    fun `Hong Kong defaults keep tax off and render Traditional Chinese receipts in HKD`() =
        TestEnvironment(device = FakeDevice(country = "HK")).use { env ->
            val settings = await { env.container.settings.current() }
            assertThat(settings.payment.chargeTax).isFalse()
            assertThat(settings.receipt.showTaxAmounts).isFalse()
            assertThat(settings.receipt.showTaxRateTotals).isFalse()
            assertThat(settings.receipt.markedTaxRateMilliPercent).isNull()
            assertThat(settings.receipt.title).isEqualTo("收據")
            assertThat(settings.receipt.footer).isEqualTo("謝謝惠顧！")
            assertThat(settings.receipt.taxIdLabel).isEqualTo("稅務編號")
            assertThat(settings.email.subject).isEqualTo("來自 {business} 的收據")
            assertThat(env.container.currency(settings).code).isEqualTo("HKD")
            assertThat(env.container.currency(settings).fractionDigits).isEqualTo(2)
            assertThat(
                env.container
                    .starterTaxRates()
                    .single()
                    .rateMilliPercent,
            ).isEqualTo(0)
            val document = env.container.sampleReceipt(settings)
            val text = PlainTextReceiptRenderer().render(document)
            assertThat(text).contains("收據")
            assertThat(text).contains("交易時間")
            assertThat(text).contains("總計")
            assertThat(text).contains(".00")
            assertThat(text).doesNotContain("稅額")
            assertThat(text).doesNotContain("发票")
            assertThat(PrintRenderer.jobs(document, 32)).isNotEmpty()
            receiptPreview("zh-Hant", document)
            assertThat(AdyenCurrencies["HKD"]!!.localName(Locale.forLanguageTag("zh-Hant-HK"))).contains("港")
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
            assertThat(await { env.container.settings.current() }.receipt).isEqualTo(settings.receipt)
            assertThat(text).contains("8%应税金额（含税）")
            assertThat(text).contains("※は軽減税率対象商品")
            RuntimeEnvironment.setQualifiers("zh-rHK")
            val traditional = PlainTextReceiptRenderer().render(env.container.sampleReceipt(settings))
            assertThat(traditional).contains("My receipt")
            assertThat(traditional).contains("My footer")
            assertThat(traditional).contains("總計")
            assertThat(traditional).contains("自訂商品")
            assertThat(await { env.container.settings.current() }).isEqualTo(settings)
            assertThat(await { env.container.api.verify() }).isEqualTo(ApiCheck.Works)
        }

    @Test
    @Config(qualifiers = "en-rAU")
    fun `regional tax identifiers and rate names are initial merchant-editable labels`() {
        val labels =
            mapOf(
                "AU" to "ABN",
                "NZ" to "GST No.",
                "SG" to "GST Registration No.",
                "MY" to "SST Registration No.",
                "HK" to "Tax ID",
                "US" to "Tax ID",
                "CA" to "GST/HST No.",
                "MX" to "RFC",
                "BR" to "CPF/CNPJ",
                "GB" to "VAT No.",
                "DE" to "VAT No.",
                "RO" to "VAT No.",
                "SE" to "VAT No.",
                "" to "Tax ID",
            )
        labels.forEach { (country, label) ->
            TestEnvironment(device = FakeDevice(country = country)).use { env ->
                val settings = await { env.container.settings.current() }
                assertWithMessage(country).that(settings.receipt.taxIdLabel).isEqualTo(label)
                assertThat(settings.receipt.title).isEqualTo("RECEIPT")
                assertThat(env.container.sampleReceipt(settings).elements).isNotEmpty()
                val rates = env.container.starterTaxRates()
                when (country) {
                    "AU", "NZ", "SG" -> assertThat(rates.first().name).isEqualTo("GST")
                    "MX" -> assertThat(rates.first().name).isEqualTo("IVA")
                    "GB", "DE", "RO", "SE" -> assertThat(rates.first().name).isEqualTo("VAT")
                    else -> assertThat(rates.single().rateMilliPercent).isEqualTo(0)
                }
                if (country == "AU") assertThat(settings.receipt.markedTaxRateNote).isEqualTo("※ No GST charged")
                env.updateSettings { it.copy(receipt = it.receipt.copy(taxIdLabel = "Merchant ID")) }
                assertThat(await { env.container.settings.current() }.receipt.taxIdLabel).isEqualTo("Merchant ID")
            }
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `regional labels use localized resources rather than the language's country`() {
        TestEnvironment(device = FakeDevice(country = "GB")).use { env ->
            assertThat(await { env.container.settings.current() }.receipt.taxIdLabel).isEqualTo("增值税税号")
            assertThat(
                env.container
                    .starterTaxRates()
                    .first()
                    .name,
            ).isEqualTo("增值税")
        }
        TestEnvironment(device = FakeDevice(country = "AU")).use { env ->
            assertThat(await { env.container.settings.current() }.receipt.markedTaxRateNote).isEqualTo("※ 未收取GST的商品")
        }
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
        Regex("""%(?:\d+\$)?[ds]|\{\w+}""")
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
