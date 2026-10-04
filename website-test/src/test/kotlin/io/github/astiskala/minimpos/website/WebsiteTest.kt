package io.github.astiskala.minimpos.website

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
 * The standalone localized website in docs/: its landing page, three guides and setup helper in English,
 * Simplified Chinese and Japanese, their links, language switches and metadata, the app labels the guides quote, the
 * screenshots, and that only the setup helper runs scripts, its own.
 */
class WebsiteTest {
    @Test
    fun `pages declare their language and metadata`() {
        val locales = mapOf("en" to "en_US", "zh-CN" to "zh_CN", "ja" to "ja_JP")
        pages.forEach { (key, page) ->
            val (language, kind) = key
            assertThat(page.single("html").attr("lang")).isEqualTo(language)
            assertThat(page.single("link", "rel" to "canonical").attr("href")).isEqualTo(publicUrl(language, kind))
            assertThat(page.single("meta", "property" to "og:url").attr("content")).isEqualTo(publicUrl(language, kind))
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
            val kind = key.second
            val alternates = page.matching("link", "rel" to "alternate").associate { it.attr("hreflang") to it.attr("href") }
            val expected = LANGUAGES.associateWith { publicUrl(it, kind) } + ("x-default" to publicUrl("en", kind))
            assertWithMessage(page.name).that(alternates).isEqualTo(expected)
        }
    }

    @Test
    fun `language switches keep the page type`() {
        pages.forEach { (key, page) ->
            val (language, kind) = key
            val links = page.document.select("a[hreflang]")
            assertWithMessage(page.name).that(links).hasSize(LANGUAGES.size)
            links.forEach { link ->
                val target = link.attr("hreflang")
                assertThat(link.attr("lang")).isEqualTo(target)
                assertThat(resolveLocal(page, link.attr("href"))).isEqualTo(docs.resolve(relativePage(target, kind)))
                assertWithMessage("${page.name} → $target")
                    .that(if (link.hasAttr("aria-current")) link.attr("aria-current") else null)
                    .isEqualTo(if (target == language) "page" else null)
            }
        }
    }

    @Test
    fun `every page exposes the guides and helper in desktop mobile and footer navigation`() {
        val destinations = listOf(Kind.GUIDE, Kind.USING, Kind.TROUBLE, Kind.SETUP).map { it.file }
        pages.forEach { (key, page) ->
            val (language, kind) = key
            val labels =
                pages
                    .getValue(language to Kind.LANDING)
                    .document
                    .select(".desktop-nav a")
                    .map { it.text() }
            listOf(".desktop-nav", ".mobile-menu nav", ".site-footer nav").forEach { selector ->
                val links = page.document.select("$selector a")
                assertWithMessage("${page.name} $selector").that(links.map { it.attr("href") }).isEqualTo(destinations)
                assertWithMessage("${page.name} $selector labels").that(links.map { it.text() }).isEqualTo(labels)
                links.forEach { link ->
                    assertWithMessage("${page.name} $selector ${link.attr("href")}")
                        .that(link.attr("aria-current"))
                        .isEqualTo(if (link.attr("href") == kind.file) "page" else "")
                }
            }
            val menu = page.document.select("details.mobile-menu").single()
            assertWithMessage(page.name).that(menu.hasAttr("open")).isFalse()
            assertWithMessage(page.name).that(menu.selectFirst("summary")!!.text()).isNotEmpty()
        }
    }

    @Test
    fun `localized pages keep all navigation destinations in the same order`() {
        pages.forEach { (key, page) ->
            val english = pages.getValue("en" to key.second)

            fun Page.destinations() = document.select("a[href]:not([hreflang])").map { it.attr("href") }
            assertWithMessage(page.name).that(page.destinations()).isEqualTo(english.destinations())
        }
    }

    @Test
    fun `landing pages lead directly to guides and link every feature to its task`() {
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to Kind.LANDING)
            val sections = page.document.select("main > section")
            val guides = sections.single { it.hasClass("guide-start") }
            assertWithMessage(page.name).that(sections.indexOf(guides)).isLessThan(sections.indexOfFirst { it.id() == "features" })
            assertWithMessage(page.name)
                .that(guides.select(".guide-card a").map { it.attr("href") })
                .containsExactly(Kind.GUIDE.file, Kind.USING.file, Kind.TROUBLE.file)
                .inOrder()
            assertWithMessage(page.name)
                .that(
                    page.document
                        .select(".hero .actions a")
                        .first()!!
                        .attr("href"),
                ).isEqualTo(Kind.GUIDE.file)
            page.document.select(".feature").forEach { feature ->
                val link = feature.select(".feature-link a").single()
                assertWithMessage(page.name).that(hasScheme(link.attr("href"))).isFalse()
                assertWithMessage(page.name).that(link.text()).isNotEmpty()
            }
        }
    }

    @Test
    fun `guide contents expose every task in both layouts with a return route`() {
        GUIDE_TOPICS.keys.forEach { kind ->
            LANGUAGES.forEach { language ->
                val page = pages.getValue(language to kind)
                val tasks = page.document.select(".guide-body section[id], .guide-body h3[id]").map { "#${it.id()}" }
                listOf(".desktop-contents", ".mobile-contents").forEach { selector ->
                    assertWithMessage("${page.name} $selector")
                        .that(page.document.select("$selector a").map { it.attr("href") })
                        .isEqualTo(tasks)
                }
                val contents = page.document.select("nav.toc").single()
                assertWithMessage(page.name).that(contents.id()).isEqualTo("contents")
                val mobile = contents.select("details.mobile-contents").single()
                assertWithMessage(page.name).that(mobile.hasAttr("open")).isFalse()
                assertWithMessage(page.name).that(mobile.selectFirst("summary")!!.text()).isNotEmpty()
                page.document.select(".guide-body section").forEach { section ->
                    assertWithMessage("${page.name} #${section.id()}")
                        .that(section.select(".back-to-contents a[href='#contents']"))
                        .hasSize(1)
                }
            }
        }
    }

    @Test
    fun `setup shortcuts keep prerequisites and the next payment step reachable`() {
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            listOf("on-terminal", "network", "cloud", "tap-to-pay").forEach { destination ->
                val prerequisites = guide.document.getElementById(destination)!!.nextElementSibling()!!
                assertWithMessage("${guide.name} #$destination")
                    .that(prerequisites.select("a").map { it.attr("href") })
                    .containsExactly("#install", "#credentials")
                    .inOrder()
            }
            val helper = pages.getValue(language to Kind.SETUP)
            assertWithMessage(helper.name)
                .that(helper.document.select(".setup-main > p a").map { it.attr("href") })
                .containsAtLeast("getting-started.html#choose", "getting-started.html#credentials")
            assertWithMessage(helper.name)
                .that(helper.document.select("#setup-codes a").map { it.attr("href") })
                .containsAtLeast("getting-started.html#first-payment", "troubleshooting.html#access", "troubleshooting.html#connection")
            val notice = helper.document.getElementById("customer-area-note")!!
            assertWithMessage(helper.name).that(notice.text()).isNotEmpty()
            helper.document.select("a[href^='https://ca-']").forEach { link ->
                assertWithMessage(helper.name).that(link.attr("target")).isEqualTo("_blank")
                assertWithMessage(helper.name).that(link.attr("rel").split(" ")).containsAtLeast("noopener", "noreferrer")
                assertWithMessage(helper.name).that(link.attr("aria-describedby")).isEqualTo(notice.id())
            }
        }
    }

    @Test
    fun `normal workflows link directly to their recovery instructions`() {
        val routes =
            mapOf(
                "business" to "access",
                "sell" to "unknown",
                "refund" to "unknown",
                "receipts" to "receipts",
                "payment-links" to "links",
                "preauth" to "modifications",
                "tips" to "modifications",
                "more" to "access",
            )
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to Kind.USING)
            routes.forEach { (task, recovery) ->
                assertWithMessage("${page.name} #$task")
                    .that(page.document.select("#$task a[href='troubleshooting.html#$recovery']"))
                    .isNotEmpty()
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
        }
        Regex("""url\(['"]?([^)'"]+)""").findAll(styles).map { it.groupValues[1] }.forEach { href ->
            assertWithMessage(href).that(hasScheme(href)).isFalse()
            assertWithMessage(href).that(docs.resolve(href).isRegularFile()).isTrue()
        }
    }

    @Test
    fun `localized guides cover the workflows and keep the operational links and code`() {
        GUIDE_TOPICS.forEach { (kind, topics) ->
            val english = pages.getValue("en" to kind)
            LANGUAGES.forEach { language ->
                val page = pages.getValue(language to kind)
                assertWithMessage(page.name).that(page.sections).containsAtLeastElementsIn(topics)
                assertWithMessage(page.name).that(page.sections).containsExactlyElementsIn(english.sections).inOrder()
                assertWithMessage(page.name).that(page.code).isEqualTo(english.code)
                assertWithMessage(page.name).that(page.externalLinks).isEqualTo(english.externalLinks)
                page.sections.forEach {
                    assertWithMessage("${page.name} #$it").that(page.matching("a", "href" to "#$it")).isNotEmpty()
                }
                GUIDE_TOPICS.keys.forEach { target ->
                    assertWithMessage("${page.name} → $target")
                        .that(page.document.select("nav.toc a[href='${target.file}']"))
                        .isNotEmpty()
                }
            }
        }
        LANGUAGES.forEach { language ->
            assertThat(pages.getValue(language to Kind.GUIDE).text).contains("Checkout webservice role")
            assertThat(pages.getValue(language to Kind.USING).text).contains("Return adjust authorisation data")
        }
    }

    @Test
    fun `copy is translated and screenshots are disclosed`() {
        listOf("en" to "English demo", "zh-CN" to "英文演示", "ja" to "英語のデモ").forEach { (language, marker) ->
            val page = pages.getValue(language to Kind.LANDING)
            assertWithMessage(page.name).that(page.text).contains(marker)
            assertWithMessage(page.name).that(page.source).doesNotContain("class=\"phone")
            if (language != "en") {
                assertThat(page.text).doesNotContain("The whole checkout")
                assertThat(page.text).doesNotContain("Everything you need to sell in person")
                assertThat(pages.getValue(language to Kind.GUIDE).text).doesNotContain("Before you start")
            }
        }
        assertThat(styles).contains("object-fit: contain")
        assertThat(styles).doesNotContain("object-fit: cover")
        assertThat(styles).doesNotContain(".phone")
    }

    @Test
    fun `guides explain languages and receipt customization`() {
        val labels =
            mapOf(
                "en" to listOf("Show tax amounts", "Show taxable totals by rate", "Marked tax rate (%)"),
                "zh-CN" to listOf("显示税额", "按税率显示应税金额", "标记的税率（%）"),
                "ja" to listOf("税額を表示", "税率別の対象金額を表示", "印を付ける税率（%）"),
            )
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to Kind.USING)
            assertWithMessage(page.name).that(page.ids).contains("language-receipts")
            (listOf("Android 12", "Android 13") + labels.getValue(language)).forEach {
                assertWithMessage(page.name).that(page.text).contains(it)
            }
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
                        "共享至另一台设备",
                        "保存并测试 API 密钥",
                        "重新查询结果",
                        "需处理",
                        "设置 › 收据",
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
                        "別のデバイスに共有",
                        "決済先",
                        "アプリ情報",
                        "設定 › 領収書",
                    ),
            )
        val forbidden =
            mapOf(
                "zh-CN" to listOf("历史记录", "新销售", "等待小费", "已申请扣款", "支付去向", "分享给另一台终端", "共享至另一台终端"),
                "ja" to listOf("チップ待ち", "キャプチャ申請済み", "別の端末と共有", "別の端末に共有", "アプリについて"),
            )
        labels.forEach { (language, expected) ->
            val guide = GUIDE_TOPICS.keys.joinToString(" ") { pages.getValue(language to it).text }
            expected.forEach { assertWithMessage(language).that(guide).contains(it) }
            forbidden.getValue(language).forEach { assertWithMessage(language).that(guide).doesNotContain(it) }
            val landing = pages.getValue(language to Kind.LANDING).text
            assertWithMessage(language).that(landing).contains(expected.first())
            assertWithMessage(language).that(landing).contains(if (language == "zh-CN") "在收据上填写小费" else "領収書にチップを記入")
        }
    }

    @Test
    fun `only the setup helper runs scripts, its own, under a policy that lets it send nothing`() {
        pages.forEach { (key, page) ->
            val scripts = page.matching("script")
            if (key.second != Kind.SETUP) {
                assertWithMessage("${page.name} has scripts").that(scripts).isEmpty()
                return@forEach
            }
            assertWithMessage(page.name)
                .that(
                    scripts.map {
                        it.attr("src").removePrefix("../")
                    },
                ).containsExactly("js/qrcodegen.js", "js/setup.js")
                .inOrder()
            scripts.forEach { assertWithMessage(page.name).that(it.hasAttr("defer") && it.data().isBlank()).isTrue() }
            val policy = page.single("meta", "http-equiv" to "Content-Security-Policy").attr("content")
            listOf(
                "connect-src 'none'",
                "form-action 'none'",
                "base-uri 'none'",
            ).forEach { assertWithMessage(page.name).that(policy).contains(it) }
            assertWithMessage(page.name).that(page.document.select("[style], [onclick], [onsubmit]")).isEmpty()
        }
        assertThat(docs.resolve("js/qrcodegen.js").readText()).contains("Copyright (c) Project Nayuki. (MIT License)")
    }

    @Test
    fun `the setup helpers ask for the same fields, link the same pages and word every message`() {
        val english = pages.getValue("en" to Kind.SETUP)

        fun Page.fields() = document.select("#setup-form input").map { listOf(it.id(), it.attr("name"), it.attr("type"), it.attr("value")) }

        fun Page.groups() = document.select("#setup-form [data-for]").map { it.attr("data-for") + "|" + it.attr("data-env") }

        fun Page.messages() = document.selectFirst("#setup-form")!!.attributes().filter { it.key.startsWith("data-msg-") }

        fun Page.shortcuts() = document.select("a[data-ca]").map { it.attr("data-ca") to it.attr("href") }
        assertThat(
            english.fields().map { it[1] },
        ).containsAtLeast("destination", "passphrase", "apiKey", "paymentsAppApiKey", "liveUrlPrefix")
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to Kind.SETUP)
            assertWithMessage(page.name).that(page.fields()).isEqualTo(english.fields())
            assertWithMessage(page.name).that(page.groups()).isEqualTo(english.groups())
            assertWithMessage(page.name).that(page.shortcuts()).isEqualTo(english.shortcuts())
            assertWithMessage(page.name).that(page.externalLinks).isEqualTo(english.externalLinks)
            assertWithMessage(page.name).that(page.messages().map { it.key }).isEqualTo(english.messages().map { it.key })
            page.messages().forEach { message ->
                val placeholders = Regex("""\{\w+}""").findAll(message.value).map { it.value }.toSet()
                val englishValue = english.messages().single { it.key == message.key }.value
                assertWithMessage("${page.name} ${message.key}")
                    .that(placeholders)
                    .isEqualTo(Regex("""\{\w+}""").findAll(englishValue).map { it.value }.toSet())
            }
            // The Customer Area links of the form open TEST until LIVE is chosen, but the live-only page.
            page.shortcuts().forEach { (path, href) ->
                assertWithMessage(path).that(href).isAnyOf(CUSTOMER_AREA_TEST + path, CUSTOMER_AREA_LIVE + path)
            }
            // Every page of the site leads to the setup helper.
            Kind.entries.forEach { kind ->
                val other = pages.getValue(language to kind)
                assertWithMessage(other.name).that(other.document.select("ul.nav a[href$=setup.html]")).isNotEmpty()
            }
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
            val page = pages.getValue(language to Kind.LANDING)
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
private const val CUSTOMER_AREA_TEST = "https://ca-test.adyen.com/ca/ui/"
private const val CUSTOMER_AREA_LIVE = "https://ca-live.adyen.com/ca/ui/"
private val LANGUAGES = listOf("en", "zh-CN", "ja")
private val GUIDE_TOPICS =
    mapOf(
        Kind.GUIDE to setOf("try", "choose", "install", "connect", "first-payment", "live"),
        Kind.USING to setOf("business", "sell", "refund", "history", "receipts", "shoppers", "payment-links", "preauth", "tips", "more"),
        Kind.TROUBLE to setOf("unknown", "connection", "modifications", "links", "receipts", "access", "install", "help"),
    )

private val docs: Path =
    Path.of(checkNotNull(System.getProperty("minimpos.website")) { "minimpos.website is not set" }).toAbsolutePath().normalize()
private val styles = docs.resolve("styles.css").readText()

/** The kinds of page each language has. */
private enum class Kind(
    val file: String,
) {
    LANDING("index.html"),
    GUIDE("getting-started.html"),
    USING("using.html"),
    TROUBLE("troubleshooting.html"),
    SETUP("setup.html"),
}

private val pages: Map<Pair<String, Kind>, Page> =
    LANGUAGES
        .flatMap { language -> Kind.entries.map { kind -> (language to kind) to Page(docs.resolve(relativePage(language, kind))) } }
        .toMap()

private fun relativePage(
    language: String,
    kind: Kind,
) = (if (language == "en") "" else "$language/") + kind.file

private fun publicUrl(
    language: String,
    kind: Kind,
) = BASE + relativePage(language, kind).removeSuffix(Kind.LANDING.file)

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
