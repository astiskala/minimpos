package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

class LatinLanguageWebsiteTest {
    private val docs = Path.of(System.getProperty("minimpos.website"))
    private val languages = listOf("en", "zh-CN", "zh-Hant", "ja", "pt-BR", "es-MX", "fr-FR")
    private val translated = listOf("pt-BR", "es-MX", "fr-FR")
    private val files = listOf("index.html", "quick-start.html", "using.html", "troubleshooting.html", "setup.html")

    @Test
    fun `new translations preserve guide structure links and reciprocal language choices`() {
        translated.forEach { language ->
            files.forEach { file ->
                val path = docs.resolve("$language/$file")
                val source = path.readText()
                val page = Jsoup.parse(source)
                val english = Jsoup.parse(docs.resolve(file).readText())
                assertWithMessage("$language/$file IDs")
                    .that(page.select("[id]").map { it.id() })
                    .containsExactlyElementsIn(english.select("[id]").map { it.id() })
                    .inOrder()
                assertThat(page.select("html").attr("lang")).isEqualTo(language)
                val url = "https://minimpos.app/$language/" + file.takeUnless { it == "index.html" }.orEmpty().removeSuffix(".html")
                assertThat(page.select("link[rel=canonical]").attr("href")).isEqualTo(url)
                assertThat(page.select("meta[property=og:url]").attr("content")).isEqualTo(url)
                assertThat(page.select("link[rel=alternate]").map { it.attr("hreflang") })
                    .containsExactlyElementsIn(languages + "x-default")
                listOf(".language-menu", ".mobile-languages").forEach { selector ->
                    val choices = page.select("$selector a[hreflang]")
                    assertThat(choices.map { it.attr("hreflang") }).containsExactlyElementsIn(languages)
                    assertThat(choices.single { it.attr("aria-current") == "page" }.attr("hreflang")).isEqualTo(language)
                    choices.forEach { link ->
                        val target = link.attr("hreflang")
                        val expected = docs.resolve((if (target == "en") "" else "$target/") + file)
                        val actual = path.parent.resolve(link.attr("href")).normalize()
                        assertThat(actual.sourcePage()).isEqualTo(expected)
                    }
                }
                assertThat(page.select("code").map { it.text() }).isEqualTo(english.select("code").map { it.text() })
                assertThat(source).doesNotContain("ZXQ")
                assertThat(source).doesNotContain("ZXSEP")
                page.select("a[href^='#']").forEach { link ->
                    assertWithMessage("$language/$file ${link.attr("href")}")
                        .that(page.getElementById(link.attr("href").removePrefix("#")))
                        .isNotNull()
                }
            }
        }
    }

    @Test
    fun `translated helpers preserve executable attributes and the hosting-only network policy`() {
        val english = Jsoup.parse(docs.resolve("setup.html").readText())
        val selector = "#setup-form, #setup-form *"
        val technical = setOf("id", "name", "type", "value", "required", "hidden", "disabled", "min", "max", "pattern")

        fun attributes(element: Element) =
            element
                .attributes()
                .filter { it.key in technical || (it.key.startsWith("data-") && !it.key.startsWith("data-msg-")) }
                .associate { it.key to it.value }
        translated.forEach { language ->
            val page = Jsoup.parse(docs.resolve("$language/setup.html").readText())
            assertWithMessage(language).that(page.select(selector).map(::attributes)).isEqualTo(english.select(selector).map(::attributes))
            assertThat(page.select("meta[http-equiv=Content-Security-Policy]").attr("content"))
                .isEqualTo(english.select("meta[http-equiv=Content-Security-Policy]").attr("content"))
            assertThat(page.select("script").map { it.attr("src") }).containsExactly("../js/qrcodegen.js", "../js/setup.js").inOrder()
            val messages = page.selectFirst("#setup-form")!!.attributes().filter { it.key.startsWith("data-msg-") }
            val originals = english.selectFirst("#setup-form")!!.attributes().associate { it.key to it.value }
            messages.forEach { message ->
                val arguments = Regex("\\{\\w+}")
                assertThat(arguments.findAll(message.value).map { it.value }.toList())
                    .containsExactlyElementsIn(arguments.findAll(originals.getValue(message.key)).map { it.value }.toList())
            }
        }
    }

    private fun Path.sourcePage(): Path =
        when {
            toFile().isDirectory -> resolve("index.html")
            extension.isEmpty() -> resolveSibling("$fileName.html")
            else -> this
        }
}
