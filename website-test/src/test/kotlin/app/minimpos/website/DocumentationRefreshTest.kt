package app.minimpos.website

import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class DocumentationRefreshTest {
    @Test
    fun `screenshot sample data localizes merchant and all catalog labels`() {
        val languages = sampleLanguages()
        assertWithMessage("Screenshot sample languages").that(languages.keys).containsExactly("en", "zh-CN", "zh-Hant", "ja")
        val english = languages.getValue("en").jsonObject
        val originals = english.getValue("names").jsonObject
        languages.forEach { (language, element) ->
            val fixture = element.jsonObject
            val names = fixture.getValue("names").jsonObject
            assertWithMessage("$language sample label parity").that(names.keys).containsExactlyElementsIn(originals.keys)
            names.forEach { (name, translation) ->
                val value = translation.jsonPrimitive.content
                assertWithMessage("$language $name").that(value).isNotEmpty()
                if (language != "en") assertWithMessage("$language $name translation").that(value).isNotEqualTo(name)
            }
            val receipt = fixture.getValue("receipt").jsonObject
            assertWithMessage(
                "$language sample business fields",
            ).that(receipt.keys).containsExactlyElementsIn(english.getValue("receipt").jsonObject.keys)
            if (language != "en") {
                listOf("businessName", "addressLines").forEach { field ->
                    assertWithMessage("$language $field")
                        .that(receipt.getValue(field).jsonPrimitive.content)
                        .isNotEqualTo(
                            english
                                .getValue("receipt")
                                .jsonObject
                                .getValue(field)
                                .jsonPrimitive.content,
                        )
                }
            }
        }
    }

    @Test
    fun `screenshot samples use regional currencies and realistic prices in their minor units`() {
        val languages = sampleLanguages()
        val currencies = mapOf("en" to "AUD", "zh-CN" to "SGD", "zh-Hant" to "HKD", "ja" to "JPY")
        val basketTotals = mapOf("en" to 3_400L, "zh-CN" to 3_350L, "zh-Hant" to 23_200L, "ja" to 3_050L)
        val products =
            languages
                .getValue("en")
                .jsonObject
                .getValue("pricesMinor")
                .jsonObject.keys
        currencies.forEach { (language, currency) ->
            val fixture = languages.getValue(language).jsonObject
            assertWithMessage("$language currency").that(fixture.getValue("currencyCode").jsonPrimitive.content).isEqualTo(currency)
            assertWithMessage("$language currency decimals")
                .that(fixture.getValue("decimals").jsonPrimitive.int)
                .isEqualTo(if (currency == "JPY") 0 else 2)
            val prices = fixture.getValue("pricesMinor").jsonObject.mapValues { it.value.jsonPrimitive.long }
            assertWithMessage("$language sample product prices").that(prices.keys).containsExactlyElementsIn(products)
            prices.forEach { (product, price) -> assertWithMessage("$language $product price").that(price).isGreaterThan(0L) }
            val total = prices.getValue("Flat white") * 2 + prices.getValue("Banana bread") + prices.getValue("Coffee beans 250 g")
            assertWithMessage("$language sample sale total").that(total).isEqualTo(basketTotals.getValue(language))
        }
    }

    private fun sampleLanguages(): JsonObject {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        return Json
            .parseToJsonElement(docs.parent.resolve(".devin/skills/docs-screenshots/sample-data.json").readText())
            .jsonObject
            .getValue("languages")
            .jsonObject
    }

    @Test
    fun `new terminal feature screenshots accompany their workflows in every language`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        val screenshots =
            mapOf(
                "capabilities" to "capabilities.png",
                "terminal-checkout" to "partial-payment.png",
                "offline" to "offline-payment.png",
                "history" to "reconciliation.png",
                "terminal-tips" to "terminal-tipping.png",
            )
        val disclosures =
            mapOf("en" to "English simulator demo", "zh-CN" to "简体中文模拟器", "zh-Hant" to "繁體中文模擬器", "ja" to "日本語のシミュレーター")
        disclosures.forEach { (language, marker) ->
            val directory = docs.resolve(if (language == "en") "" else language)
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            screenshots.forEach { (section, image) ->
                val figure = using.select("#$section figure").single()
                assertWithMessage("$language #$section responsive guide figure").that(figure.hasClass("guide-shot")).isTrue()
                assertWithMessage("$language #$section original terminal frame")
                    .that(figure.select(".terminal.terminal-s1f2 img"))
                    .hasSize(1)
                assertWithMessage("$language #$section screenshot")
                    .that(
                        figure
                            .select("img")
                            .single()
                            .attr("src")
                            .substringAfterLast('/'),
                    ).isEqualTo(image)
                assertWithMessage("$language #$section screenshot description")
                    .that(figure.select("img").single().attr("alt"))
                    .contains(marker)
                assertWithMessage("$language #$section demo caption").that(figure.select("figcaption").text()).contains(marker)
            }
        }
    }

    @Test
    fun `operations explain offline demo links transfer codes and data preserving updates in every language`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        val contracts =
            mapOf(
                "en" to
                    listOf(
                        "Their QR codes cannot take payments",
                        "even when copying only products or settings",
                        "Keep it separate from the QR codes",
                        "Adyen terminals update through the Customer Area",
                        "do not uninstall or clear its data",
                    ),
                "zh-CN" to
                    listOf(
                        "二维码无法收款",
                        "即使只复制商品或设置也需要",
                        "请将传输码与二维码分开保管",
                        "Adyen 终端通过商户后台更新",
                        "不要卸载应用或清除数据",
                    ),
                "zh-Hant" to
                    listOf(
                        "二維碼無法收款",
                        "即使只複製商品或設定也需要",
                        "請將傳輸碼與二維碼分開保管",
                        "Adyen 終端透過商戶後台更新",
                        "不要解除安裝應用程式或清除資料",
                    ),
                "ja" to
                    listOf(
                        "QR コードで支払いはできません",
                        "商品や設定だけをコピーする場合も必要です",
                        "QR コードとは別に保管してください",
                        "Adyen 端末はこの通知ではなく Customer Area から更新します",
                        "アンインストールやデータの消去はしないでください",
                    ),
            )
        contracts.forEach { (language, phrases) ->
            val directory = docs.resolve(if (language == "en") "" else language)
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            assertWithMessage("$language demo QR safety").that(using.select("#payment-links").text()).contains(phrases.first())
            phrases.drop(1).forEach { phrase ->
                assertWithMessage("$language transfer and update safety").that(using.select("#more").text()).contains(phrase)
            }
            assertWithMessage("$language update installation steps")
                .that(using.select("#more a[href='quick-start.html#install']"))
                .isNotEmpty()
        }
    }
}
