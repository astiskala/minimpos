package io.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.junit.Test
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * The standalone localized website in docs/: its six pages (landing page and guide in English, Simplified Chinese and
 * Japanese), their links, language switches and metadata, the app labels the guides quote, and the screenshots.
 */
class WebsiteTest {
    @Test
    fun `pages declare their language and metadata`() {
        val locales = mapOf("en" to "en_US", "zh-CN" to "zh_CN", "ja" to "ja_JP")
        pages.forEach { (key, page) ->
            val (language, guide) = key
            assertThat(page.single("html").attr("lang")).isEqualTo(language)
            assertThat(page.single("link", "rel" to "canonical").attr("href")).isEqualTo(publicUrl(language, guide))
            assertThat(page.single("meta", "property" to "og:url").attr("content")).isEqualTo(publicUrl(language, guide))
            assertThat(page.single("meta", "property" to "og:locale").attr("content")).isEqualTo(locales[language])
            assertWithMessage(page.name)
                .that(page.matching("meta", "property" to "og:locale:alternate").map { it.attr("content") }.toSet())
                .isEqualTo(locales.filterKeys { it != language }.values.toSet())
            listOf(
                "name" to "description",
                "property" to "og:title",
                "property" to "og:description",
                "property" to "og:image:alt",
                "name" to "twitter:title",
                "name" to "twitter:description",
                "name" to "twitter:image:alt",
            ).forEach { assertWithMessage("${page.name} $it").that(page.single("meta", it).attr("content")).isNotEmpty() }
            assertThat(page.single("meta", "property" to "og:image").attr("content")).isEqualTo(BASE + "images/social.png")
            assertThat(page.single("meta", "name" to "twitter:image").attr("content")).isEqualTo(BASE + "images/social.png")
        }
    }

    @Test
    fun `alternate links are reciprocal`() {
        pages.forEach { (key, page) ->
            val guide = key.second
            val alternates = page.matching("link", "rel" to "alternate").associate { it.attr("hreflang") to it.attr("href") }
            val expected = LANGUAGES.associateWith { publicUrl(it, guide) } + ("x-default" to publicUrl("en", guide))
            assertWithMessage(page.name).that(alternates).isEqualTo(expected)
        }
    }

    @Test
    fun `language switches keep the page type`() {
        pages.forEach { (key, page) ->
            val (language, guide) = key
            val links = page.document.select("a[hreflang]")
            assertWithMessage(page.name).that(links).hasSize(LANGUAGES.size)
            links.forEach { link ->
                val target = link.attr("hreflang")
                assertThat(link.attr("lang")).isEqualTo(target)
                assertThat(resolveLocal(page, link.attr("href"))).isEqualTo(docs.resolve(relativePage(target, guide)))
                assertWithMessage("${page.name} → $target")
                    .that(if (link.hasAttr("aria-current")) link.attr("aria-current") else null)
                    .isEqualTo(if (target == language) "page" else null)
            }
        }
    }

    @Test
    fun `local links, fragments and assets exist`() {
        pages.values.forEach { page ->
            assertWithMessage("${page.name} has duplicate IDs").that(page.ids).containsNoDuplicates()
            page.document.allElements.forEach { element ->
                listOf("src", "href").filter(element::hasAttr).forEach { resolveLocal(page, element.attr(it)) }
                if (element.tagName() in setOf("img", "script") && element.hasAttr("src")) {
                    assertWithMessage(page.name).that(hasScheme(element.attr("src"))).isFalse()
                }
                if (element.tagName() == "img") {
                    listOf(
                        "alt",
                        "width",
                        "height",
                    ).forEach { assertWithMessage("${page.name} img $it").that(element.hasAttr(it)).isTrue() }
                }
            }
            assertWithMessage("${page.name} has scripts").that(page.matching("script")).isEmpty()
        }
        Regex("""url\(['"]?([^)'"]+)""").findAll(styles).map { it.groupValues[1] }.forEach { href ->
            assertWithMessage(href).that(hasScheme(href)).isFalse()
            assertWithMessage(href).that(docs.resolve(href).isRegularFile()).isTrue()
        }
    }

    @Test
    fun `complete guides keep the operational links and code`() {
        val english = pages.getValue("en" to true)
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to true)
            assertWithMessage(page.name).that(page.sections.toSet()).isEqualTo(GUIDE_SECTIONS)
            assertWithMessage(page.name).that(page.code).isEqualTo(english.code)
            assertWithMessage(page.name).that(page.externalLinks).isEqualTo(english.externalLinks)
            assertThat(page.text).contains("Checkout webservice role")
            assertThat(page.text).contains("Return adjust authorisation data")
            GUIDE_SECTIONS.forEach { assertWithMessage("${page.name} #$it").that(page.matching("a", "href" to "#$it")).isNotEmpty() }
        }
    }

    @Test
    fun `copy is translated and screenshots are disclosed`() {
        listOf("en" to "English demo", "zh-CN" to "英文演示", "ja" to "英語のデモ").forEach { (language, marker) ->
            val page = pages.getValue(language to false)
            assertWithMessage(page.name).that(page.text).contains(marker)
            assertWithMessage(page.name).that(page.source).doesNotContain("class=\"phone")
            if (language != "en") {
                assertThat(page.text).doesNotContain("The whole checkout")
                assertThat(page.text).doesNotContain("Everything a small shop needs")
                assertThat(pages.getValue(language to true).text).doesNotContain("Before you start")
            }
        }
        assertThat(styles).contains("object-fit: contain")
        assertThat(styles).doesNotContain("object-fit: cover")
        assertThat(styles).doesNotContain(".phone")
    }

    @Test
    fun `guides explain receipt languages and their limits`() {
        val references =
            listOf("https://www.nta.go.jp/english/taxes/consumption_tax/pdf/2023/general_04.pdf", "https://inv-veri.chinatax.gov.cn/")
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to true)
            assertWithMessage(page.name).that(page.ids).contains("language-receipts")
            listOf("Android 12", "Android 13", "税込", "税抜", "適格請求書").forEach { assertWithMessage(page.name).that(page.text).contains(it) }
            references.forEach { assertWithMessage("${page.name} $it").that(page.matching("a", "href" to it)).isNotEmpty() }
            // Regulatory caveats belong in the guide, not the marketing page.
            assertWithMessage(page.name).that(pages.getValue(language to false).text).doesNotContain("適格請求書")
        }
    }

    @Test
    fun `guides quote the app's labels`() {
        // The actual UI wording from values-zh-rCN/strings.xml and values-ja/strings.xml.
        val labels =
            mapOf(
                "zh-CN" to
                    listOf(
                        "交易记录",
                        "新建销售",
                        "在收据上填写小费",
                        "待填写小费",
                        "已请求扣款",
                        "支付目标",
                        "同一网络中的终端",
                        "共享至另一台终端",
                        "保存并测试 API 密钥",
                        "重新查询结果",
                        "需处理",
                        "设置 › 税",
                    ),
                "ja" to
                    listOf(
                        "取引履歴",
                        "領収書にチップを記入",
                        "チップ入力待ち",
                        "キャプチャ要求済み",
                        "プリオーソリ商品",
                        "プリオーソリをキャンセル",
                        "APIキーを保存してテスト",
                        "別の端末に共有",
                        "決済先",
                        "アプリ情報",
                        "設定 › 税",
                    ),
            )
        val forbidden =
            mapOf(
                "zh-CN" to listOf("历史记录", "新销售", "等待小费", "已申请扣款", "支付去向", "分享给另一台终端"),
                "ja" to listOf("チップ待ち", "キャプチャ申請済み", "別の端末と共有", "アプリについて"),
            )
        labels.forEach { (language, expected) ->
            val guide = pages.getValue(language to true).text
            expected.forEach { assertWithMessage(language).that(guide).contains(it) }
            forbidden.getValue(language).forEach { assertWithMessage(language).that(guide).doesNotContain(it) }
            val landing = pages.getValue(language to false).text
            assertWithMessage(language).that(landing).contains(expected.first())
            assertWithMessage(language).that(landing).contains(if (language == "zh-CN") "在收据上填写小费" else "領収書にチップを記入")
        }
    }

    @Test
    fun `social image is 1200 by 630`() {
        assertThat(pngSize(docs.resolve("images/social.png"))).isEqualTo(1200 to 630)
    }

    @Test
    fun `screenshots match their terminal frames`() {
        val screens =
            listOf("ams1", "s1f2").associateWith { model ->
                val artwork = docs.resolve("images/terminal-$model.svg").readText()
                val screen = Regex("""<rect x="[\d.]+" y="[\d.]+" width="([\d.]+)" height="([\d.]+)"[^>]*fill="white"""").find(artwork)
                checkNotNull(screen) { "terminal-$model.svg has no white screen" }
                screen.groupValues[1].toDouble() / screen.groupValues[2].toDouble()
            }
        val shot = Regex("""class="terminal terminal-(ams1|s1f2)[^"]*">\s*<img ([^>]*)>""")
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to false)
            val found = shot.findAll(page.source).toList()
            assertWithMessage(page.name).that(found).isNotEmpty()
            found.forEach { match ->
                val attributes =
                    Regex("""(\w+)="([^"]*)"""").findAll(match.groupValues[2]).associate {
                        it.groupValues[1] to
                            it.groupValues[2]
                    }
                val src = attributes.getValue("src")
                val (width, height) = pngSize(page.path.parent.resolve(src))
                assertWithMessage(src).that(attributes["width"] to attributes["height"]).isEqualTo("$width" to "$height")
                assertWithMessage(src).that(width.toDouble() / height).isWithin(FRAME_TOLERANCE).of(screens.getValue(match.groupValues[1]))
            }
        }
    }

    @Test
    fun `terminal frames have no model labels`() {
        listOf("ams1", "s1f2").forEach { assertThat(docs.resolve("images/terminal-$it.svg").readText()).doesNotContain("<text") }
        assertThat(docs.resolve("images/terminal-ams1.svg").readText()).doesNotContain("M62 28")
    }
}

private const val BASE = "https://astiskala.github.io/minimpos/"
private const val FRAME_TOLERANCE = 0.005
private val LANGUAGES = listOf("en", "zh-CN", "ja")
private val GUIDE_SECTIONS =
    setOf("before", "try", "key", "install", "connect", "business", "products", "sell", "api", "tips", "preauth", "more", "live", "trouble")

private val docs: Path =
    Path.of(checkNotNull(System.getProperty("minimpos.website")) { "minimpos.website is not set" }).toAbsolutePath().normalize()
private val styles = docs.resolve("styles.css").readText()
private val pages: Map<Pair<String, Boolean>, Page> =
    LANGUAGES
        .flatMap { language ->
            listOf(false, true).map { guide ->
                (language to guide) to
                    Page(docs.resolve(relativePage(language, guide)))
            }
        }.toMap()

private fun relativePage(
    language: String,
    guide: Boolean,
) = (if (language == "en") "" else "$language/") + if (guide) "getting-started.html" else "index.html"

private fun publicUrl(
    language: String,
    guide: Boolean,
) = BASE + relativePage(language, guide).let { if (guide) it else it.removeSuffix("index.html") }

private fun hasScheme(href: String) = Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(href) || href.startsWith("//")

private fun decode(part: String) = URLDecoder.decode(part.replace("+", "%2B"), Charsets.UTF_8)

/** The file a local link on [page] points at (checking it exists, as does its fragment), or null for external links. */
private fun resolveLocal(
    page: Page,
    href: String,
): Path? {
    if (hasScheme(href)) return null
    val fragment = href.substringAfter('#', "")
    val path = href.substringBefore('#').substringBefore('?')
    val target =
        (
            if (path.isEmpty()) {
                page.path
            } else {
                page.path.parent
                    .resolve(decode(path))
                    .normalize()
            }
        ).let { if (it.isDirectory()) it.resolve("index.html") else it }
    assertWithMessage("${page.name}: $href leaves the site").that(target.startsWith(docs) && target != docs).isTrue()
    assertWithMessage("${page.name}: $href does not exist").that(target.isRegularFile()).isTrue()
    if (fragment.isNotEmpty()) {
        assertWithMessage("${page.name}: $href").that(target.extension).isEqualTo("html")
        assertWithMessage("${page.name}: $href").that(Page(target).ids).contains(decode(fragment))
    }
    return target
}

private fun pngSize(file: Path): Pair<Int, Int> {
    val data = file.readBytes()
    assertWithMessage("$file is not a PNG").that(data.copyOf(PNG_SIGNATURE.size)).isEqualTo(PNG_SIGNATURE)
    val header = ByteBuffer.wrap(data, PNG_SIZE_OFFSET, Int.SIZE_BYTES * 2)
    return header.int to header.int
}

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
private const val PNG_SIZE_OFFSET = 16

/** One page of the site, parsed. */
private class Page(
    val path: Path,
) {
    val name: String = docs.relativize(path).toString()
    val source: String = path.readText()
    val document: Document = Jsoup.parse(source)
    val ids: List<String> = document.select("[id]").map { it.id() }
    val sections: List<String> = document.select("section[id]").map { it.id() }
    val code: List<String> = document.select("code").map { it.wholeText() }

    /** All the page's text, whitespace collapsed. */
    val text: String = document.text()
    val externalLinks: Set<String> = document.select("a[href^=https:]").map { it.attr("href") }.toSet()

    fun matching(
        tag: String,
        vararg attributes: Pair<String, String>,
    ): List<Element> = document.getElementsByTag(tag).filter { element -> attributes.all { (key, value) -> element.attr(key) == value } }

    fun single(
        tag: String,
        vararg attributes: Pair<String, String>,
    ): Element {
        val found = matching(tag, *attributes)
        assertWithMessage("$name: $tag ${attributes.toList()}").that(found).hasSize(1)
        return found.single()
    }
}
