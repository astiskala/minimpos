package app.minimpos.website

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

class QuickStartGuideTest {
    @Test
    fun `credentials list API roles together and separate manual details from user permissions`() {
        val manual = mapOf("en" to "If you want to configure the device manually", "zh-CN" to "如需手动配置设备", "ja" to "デバイスを手動で設定する場合")
        val additional = mapOf("en" to "enable these additional roles", "zh-CN" to "启用以下额外角色", "ja" to "次のロールを追加で有効にします")
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            val credentials = guide.document.getElementById("credentials")!!.untilNextTask()
            val text = credentials.joinToString(" ") { it.text() }
            listOf("Manage API credentials", "Merchant admin").forEach { assertThat(text).contains(it) }
            val steps = credentials.single { it.tagName() == "ol" }
            assertThat(steps.children()[1].ownText()).contains(additional.getValue(language))
            assertThat(steps.select("ul.roles li strong").map { it.text() })
                .containsExactly(
                    "Management API - Terminal actions read",
                    "Management API - Terminal settings read and write",
                    "Management API - Terminal settings Advanced read and write",
                    "Cloud Device API role",
                ).inOrder()
            steps.select("ul.roles li").forEach { role ->
                assertThat(role.text()).isEqualTo(role.select("strong").single().text())
            }
            assertThat(steps.children()[1].select("ul.roles")).hasSize(1)
            val manualStep = steps.children().last()!!
            assertThat(manualStep.text()).contains(manual.getValue(language))
            assertThat(manualStep.select("ul > li")).hasSize(2)
            assertThat(
                manualStep.select("strong").map {
                    it.text()
                },
            ).contains("Devices › Device settings › Integrations › Terminal API › Encryption key")
            assertThat(text).doesNotContain("API tokenise payment details")
            assertThat(text).doesNotContain("Management API - API credentials read and write")
        }
    }

    @Test
    fun `quick start guides name their outcome and offer the simulator before prerequisites`() {
        val titles = mapOf("en" to "Quick start", "zh-CN" to "快速入门", "ja" to "クイックスタート")
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            val title = titles.getValue(language)
            assertThat(
                guide.document
                    .select("h1")
                    .single()
                    .text(),
            ).isEqualTo(title)
            assertThat(guide.document.title()).contains(title)
            listOf("og:title", "twitter:title").forEach { name ->
                assertThat(
                    guide.document
                        .select("meta[property='$name'], meta[name='$name']")
                        .single()
                        .attr("content"),
                ).contains(title)
            }
            assertThat(guide.document.select(".guide-hero .lead").text()).contains("TEST")
            assertThat(guide.document.select(".guide-hero a[href='#try']")).hasSize(1)
            assertThat(
                guide.document
                    .select("#credentials")
                    .single()
                    .nextElementSibling()!!
                    .text(),
            ).contains("TEST")
        }
    }

    @Test
    fun `test and live validation distinguish physical connection checks from Payments app registration`() {
        val connection = mapOf("en" to "Test connection", "zh-CN" to "测试连接", "ja" to "接続をテスト")
        val api = mapOf("en" to "Test API", "zh-CN" to "测试 API", "ja" to "APIをテスト")
        val unavailable = mapOf("en" to "no connection test", "zh-CN" to "没有连接测试", "ja" to "接続テストはありません")
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            listOf("first-payment" to "TEST", "live" to "LIVE").forEach { (id, environment) ->
                val steps = guide.document.select("#$id > ol > li")
                val check = if (id == "first-payment") steps.first()!! else steps.last()!!
                assertWithMessage("${guide.name} #$id API check").that(check.text()).contains(api.getValue(language))
                assertThat(check.text()).contains(environment)
                val destinations = check.select("ul > li")
                assertWithMessage("${guide.name} #$id destination checks").that(destinations).hasSize(2)
                assertThat(destinations.first()!!.text()).contains(connection.getValue(language))
                val tapToPay = destinations.last()!!.text()
                assertThat(tapToPay).contains("Tap to Pay")
                assertThat(tapToPay).contains(if (id == "first-payment") "Adyen Payments Test" else "Adyen Payments")
                assertThat(tapToPay).contains(unavailable.getValue(language))
                assertThat(tapToPay).doesNotContain(connection.getValue(language))
            }
        }
    }

    @Test
    fun `quick start validation verifies Adyen outcomes and links optional workflows to their owners`() {
        val references = mapOf("en" to "PSP reference", "zh-CN" to "PSP 识别号", "ja" to "PSP参照ID")
        val pending = mapOf("en" to "Refund requested", "zh-CN" to "已请求退款", "ja" to "返金要求済み")
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            val validation = guide.document.select("#first-payment")
            assertThat(validation.text()).contains(references.getValue(language))
            assertThat(validation.text()).contains(pending.getValue(language))
            assertThat(validation.select("a[href='https://docs.adyen.com/point-of-sale/testing-pos-payments']")).hasSize(2)
            assertThat(validation.select("a[href='using.html#refund']")).hasSize(1)
            assertThat(validation.select("a[href='troubleshooting.html#declines']")).hasSize(1)
            assertThat(validation.select("a[href='troubleshooting.html#unknown']")).hasSize(1)
            listOf("preauth", "tips", "payment-links", "wallets").forEach { workflow ->
                assertWithMessage("${guide.name} optional $workflow")
                    .that(guide.document.select("#live a[href='using.html#$workflow']"))
                    .hasSize(1)
            }
            assertThat(guide.document.select("#live a[href='using.html#close-day']")).hasSize(1)
        }
    }
}

/**
 * The standalone localized website in docs/: its landing page, three guides and setup helper in English,
 * Simplified Chinese and Japanese, their links, language switches and metadata, the app labels the guides quote, the
 * screenshots, and that only the setup helper runs scripts, its own.
 */
class WebsiteTest {
    @Test
    fun `every quick start guide explains onboarding and scoped sample removal`() {
        val labels =
            mapOf(
                "en" to
                    listOf(
                        "Try simulator with samples",
                        "Set up from another device",
                        "Set up a terminal",
                        "Add sample data",
                        "Remove sample data",
                    ),
                "zh-CN" to listOf("使用示例数据试用模拟器", "从另一台设备设置", "设置终端", "添加示例数据", "删除示例数据"),
                "ja" to listOf("サンプルでシミュレーターを試す", "別のデバイスから設定", "端末を設定", "サンプルデータを追加", "サンプルデータを削除"),
            )
        labels.forEach { (language, actions) ->
            val text =
                pages
                    .getValue(language to Kind.GUIDE)
                    .document
                    .select("#try")
                    .text()
            actions.forEach { assertWithMessage("$language onboarding: $it").that(text).contains(it) }
        }
    }

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
            listOf(".language-menu", ".mobile-languages").forEach { selector ->
                val links = page.document.select("$selector a[hreflang]")
                assertWithMessage("${page.name} $selector").that(links).hasSize(LANGUAGES.size)
                links.forEach { link ->
                    val target = link.attr("hreflang")
                    assertThat(link.attr("lang")).isEqualTo(target)
                    assertThat(resolveLocal(page, link.attr("href"))).isEqualTo(docs.resolve(relativePage(target, kind)))
                    assertWithMessage("${page.name} $selector → $target")
                        .that(if (link.hasAttr("aria-current")) link.attr("aria-current") else null)
                        .isEqualTo(if (target == language) "page" else null)
                }
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
            listOf(".desktop-nav", ".mobile-menu .nav", ".site-footer nav").forEach { selector ->
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
            assertWithMessage(page.name).that(menu.select(".mobile-panel > nav")).hasSize(2)
            assertWithMessage(page.name).that(menu.select(".mobile-languages").single().attr("aria-label")).isNotEmpty()
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
                assertWithMessage("${guide.name} #$destination prerequisites").that(prerequisites.hasClass("callout-prereq")).isTrue()
                assertWithMessage("${guide.name} #$destination")
                    .that(prerequisites.select("a").map { it.attr("href") })
                    .containsAtLeast("#install", "#credentials")
                    .inOrder()
            }
            val helper = pages.getValue(language to Kind.SETUP)
            assertWithMessage(helper.name)
                .that(helper.document.select(".setup-main > p a").map { it.attr("href") })
                .containsAtLeast("quick-start.html#choose", "quick-start.html#credentials")
            assertWithMessage(helper.name)
                .that(helper.document.select("#setup-codes a").map { it.attr("href") })
                .containsAtLeast("quick-start.html#first-payment", "troubleshooting.html#access", "troubleshooting.html#connection")
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
    fun `destination guides start with their environment source and keep discovery optional`() {
        val keys = mapOf("en" to "Adyen API key", "zh-CN" to "Adyen API 密钥", "ja" to "Adyen APIキー")
        val environments = mapOf("en" to "Environment", "zh-CN" to "环境", "ja" to "環境")
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            listOf("on-terminal", "network", "cloud", "tap-to-pay").forEach { destination ->
                val following = guide.document.getElementById(destination)!!.untilNextTask()
                val steps =
                    if (destination == "tap-to-pay") {
                        following.first { it.tagName() == "ol" }
                    } else {
                        following.single { it.hasClass("manual-setup") }.select("ol").single()
                    }
                val first = steps.select("li").first()!!.text()
                val label =
                    when (destination) {
                        "network", "cloud" -> environments.getValue(language)
                        "tap-to-pay" -> "Adyen Payments"
                        else -> keys.getValue(language)
                    }
                assertWithMessage("${guide.name} #$destination first step").that(first).contains(label)
                if (destination != "tap-to-pay") assertThat(steps.text()).contains(keys.getValue(language))
                if (destination == "network" || destination == "cloud") {
                    assertThat(first).contains("TEST")
                    assertThat(first).contains("LIVE")
                }
            }
            assertThat(guide.text).contains("Management API - Terminal actions read")
            assertThat(guide.text).contains("Management API - Terminal settings Advanced read and write")
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
            assertWithMessage("${page.name} #sell declines")
                .that(page.document.select("#sell a[href='troubleshooting.html#declines']"))
                .isNotEmpty()
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
            assertThat(pages.getValue(language to Kind.GUIDE).text).contains("Management API - Terminal settings read and write")
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
                "en" to
                    listOf(
                        "Show tax amounts",
                        "Show tax breakdown",
                        "Show taxable totals by rate",
                        "Show references",
                        "Characters per line",
                        "Marked tax rate (%)",
                    ),
                "zh-CN" to listOf("显示税额", "显示税额明细", "按税率显示应税金额", "显示识别号", "每行字符宽度", "标记的税率（%）"),
                "ja" to listOf("税額を表示", "税率別の内訳を表示", "税率別の対象金額を表示", "参照IDを表示", "1行の文字幅", "印を付ける税率（%）"),
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
    fun `guides explain automatic links, receipt tipping modes and reviewed business import`() {
        val wording =
            mapOf(
                "en" to
                    listOf(
                        "Import business details from Adyen",
                        "Enabled (default off)",
                        "Enabled (default on)",
                        "Ask for a merchant reference",
                        "Change Admin PIN",
                        "Remove Admin PIN",
                        "Disabled",
                    ),
                "zh-CN" to
                    listOf(
                        "从 Adyen 导入商家信息",
                        "启用（默认关闭）",
                        "启用（默认开启）",
                        "要求输入商家交易识别号",
                        "更改管理员 PIN",
                        "移除管理员 PIN",
                        "禁用",
                    ),
                "ja" to
                    listOf(
                        "Adyenから店舗情報をインポート",
                        "有効（初期値オフ）",
                        "有効（初期値オン）",
                        "加盟店参照IDの入力を求める",
                        "管理者PINを変更",
                        "管理者PINを削除",
                        "無効",
                    ),
            )
        LANGUAGES.forEach { language ->
            val using = pages.getValue(language to Kind.USING)
            wording.getValue(language).forEach { assertThat(using.text).contains(it) }
            val troubleshooting = pages.getValue(language to Kind.TROUBLE)
            listOf("Settings › Payments › Offer payment links", "设置 › 支付 › 提供支付链接", "設定 › 決済 › 支払いリンクを使う").forEach { obsolete ->
                assertThat(using.text).doesNotContain(obsolete)
                assertThat(troubleshooting.text).doesNotContain(obsolete)
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
                        "Adyen API 密钥",
                        "获取设置",
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
                        "Adyen APIキー",
                        "設定を取得",
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

        fun Page.fields() =
            document
                .select("#setup-form input, #setup-form select, #setup-form textarea")
                .map { listOf(it.id(), it.attr("name"), it.attr("type"), it.attr("value")) }

        fun Page.groups() =
            document.select("#setup-form [data-for]").map {
                listOf(it.attr("data-for"), it.attr("data-env"), it.attr("data-mode"), it.attr("data-automatic-for"))
            }

        fun Page.messages() = document.selectFirst("#setup-form")!!.attributes().filter { it.key.startsWith("data-msg-") }

        fun Page.shortcuts() = document.select("a[data-ca]").map { it.attr("data-ca") to it.attr("href") }
        assertThat(
            english.fields().map { it[1] },
        ).containsAtLeast("destination", "setupMode", "passphrase", "apiKey", "paymentsAppApiKey", "liveUrlPrefix")
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to Kind.SETUP)
            assertWithMessage(page.name).that(page.fields()).isEqualTo(english.fields())
            assertWithMessage("${page.name} keeps Customer Area links in context")
                .that(page.document.select(".setup-links, .setup-side a[href*=ca-test], .setup-side a[href*=ca-live]"))
                .isEmpty()
            assertWithMessage("${page.name} keeps inline credential links").that(page.document.select("#setup-form a[data-ca]")).hasSize(4)
            assertWithMessage("${page.name} API key comes first")
                .that(
                    page.document
                        .select("#api-key-fields")
                        .single()
                        .select("input")
                        .map { it.id() },
                ).containsExactly("apiKey")
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
    fun `Automatic needs only the API key and LIVE prefix while Manual holds destination details`() {
        LANGUAGES.forEach { language ->
            val page = pages.getValue(language to Kind.SETUP)
            val modes = page.document.select("input[name=setupMode]")
            assertWithMessage(page.name).that(modes.map { it.attr("value") }).containsExactly("automatic", "manual").inOrder()
            assertThat(modes.first()!!.hasAttr("checked")).isTrue()
            assertThat(
                modes
                    .first()!!
                    .parent()!!
                    .parent()!!
                    .attr("data-for"),
            ).isEqualTo("thisTerminal network cloud")
            listOf("merchantAccount", "host", "poiId", "cloudPoiId", "keyIdentifier", "keyVersion", "passphrase").forEach { id ->
                val field = page.document.getElementById(id)!!
                assertWithMessage("$language: $id").that(field.parent()!!.attr("data-mode")).isEqualTo("manual")
                assertWithMessage("$language: $id").that(field.hasAttr("required")).isTrue()
            }
            val prefix = page.document.getElementById("liveUrlPrefix")!!
            assertThat(prefix.hasAttr("required")).isTrue()
            assertThat(prefix.parent()!!.attr("data-env")).isEqualTo("live")
            assertThat(prefix.parent()!!.hasAttr("data-mode")).isFalse()
            val apiKey = page.document.getElementById("apiKey")!!
            assertThat(apiKey.hasAttr("required")).isTrue()
            assertThat(apiKey.parent()!!.hasAttr("data-mode")).isFalse()
            assertThat(page.document.select("[data-automatic-for]")).isEmpty()
            listOf("keyIdentifier", "passphrase", "keyVersion").forEach { id ->
                assertThat(page.document.getElementById(id)!!.attr("data-required-for")).isEqualTo("thisTerminal network tapToPay")
            }
            assertThat(page.externalLinks).doesNotContain(
                "https://docs.adyen.com/api-explorer/Management/3/get/merchants/(merchantId)/terminalSettings",
            )
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
        pages.forEach { (key, page) ->
            val found = shot.findAll(page.source).toList()
            if (key.second == Kind.LANDING || key.second == Kind.GUIDE) assertWithMessage(page.name).that(found).isNotEmpty()
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

class PartnerStyleGuideTest {
    @Test
    fun `Adyen documentation links name Adyen and use stable page URLs`() {
        pages.values.forEach { page ->
            page.document.select("a[href^='https://docs.adyen.com/']").forEach { link ->
                val href = link.attr("href")
                assertWithMessage("${page.name}: $href").that(link.parent()!!.text()).contains("Adyen")
                assertWithMessage("${page.name}: $href").that(link.text()).isNotEmpty()
                if ('#' in href) assertWithMessage("${page.name}: $href").that(href.substringAfter('#')).matches("[a-z0-9-]+")
                assertWithMessage("${page.name}: $href").that(href).doesNotContain("+")
            }
        }
    }

    @Test
    fun `setup examples use reserved addresses and distinguish merchant accounts`() {
        LANGUAGES.forEach { language ->
            val helper = pages.getValue(language to Kind.SETUP)
            assertWithMessage(helper.name).that(helper.document.getElementById("host")!!.attr("placeholder")).isEqualTo("192.0.2.1")
            assertWithMessage(helper.name)
                .that(
                    helper.document
                        .getElementById("merchantAccount")!!
                        .parent()!!
                        .select("legend")
                        .text(),
                ).isEqualTo(helper.document.select("label[for=merchantAccount]").text())
        }
    }

    @Test
    fun `all manual connection procedures use numbered steps`() {
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            guide.document.select(".manual-setup").forEach { manual ->
                assertWithMessage(guide.name).that(manual.select("ol > li").size).isAtLeast(2)
            }
        }
    }

    @Test
    fun `guides count sample activity in demo totals and require every transfer code`() {
        val sampleTotals =
            mapOf(
                "en" to "Samples count in demo day totals and reports",
                "zh-CN" to "示例计入演示每日合计和报告",
                "ja" to "サンプルはデモの日次合計とレポートに含まれます",
            )
        val transferCodes =
            mapOf(
                "en" to "A wrong or blank code imports nothing",
                "zh-CN" to "错误的码或留空都不会导入任何内容",
                "ja" to "誤ったコードでも空欄でも何も取り込みません",
            )
        LANGUAGES.forEach { language ->
            assertWithMessage(language)
                .that(
                    pages
                        .getValue(language to Kind.GUIDE)
                        .document
                        .select("#try")
                        .text(),
                ).contains(sampleTotals.getValue(language))
            assertWithMessage(language)
                .that(
                    pages
                        .getValue(language to Kind.TROUBLE)
                        .document
                        .select("#access")
                        .text(),
                ).contains(transferCodes.getValue(language))
        }
    }
}

/** Destination setup instructions and optional email fields share the website's localized page fixtures. */
class SetupGuideTest {
    @Test
    fun `guides describe the customer reference default`() {
        val defaults = mapOf("en" to "a customer reference typed at checkout (the default)", "zh-CN" to "客户识别号（默认）", "ja" to "顧客参照ID（初期設定）")
        LANGUAGES.forEach { language ->
            val using = pages.getValue(language to Kind.USING)
            assertThat(using.document.select("#shoppers").text()).contains(defaults.getValue(language))
            assertThat(using.document.select("#sell a[href='#shoppers']")).isNotEmpty()
        }
    }

    @Test
    fun `prerequisites name the network ports and Adyen domains in every language`() {
        LANGUAGES.forEach { language ->
            val heading = pages.getValue(language to Kind.GUIDE).document.getElementById("network-requirements")!!
            val text = heading.untilNextTask().joinToString(" ") { it.text() }
            listOf("443", "8443", "*.adyen.com", "*.adyenpayments.com").forEach {
                assertWithMessage("$language network prerequisites: $it").that(text).contains(it)
            }
            val allowlisting =
                heading.untilNextTask().flatMap {
                    it.select(
                        "a[href='https://docs.adyen.com/development-resources/security/integration-security/" +
                            "allowlisting#dns-resolution-and-domain-base-allowlist']",
                    )
                }
            assertThat(allowlisting).hasSize(1)
            assertThat(allowlisting.single().text()).isNotEmpty()
        }
    }

    @Test
    fun `every language describes confirmed key creation and links delayed activation recovery`() {
        val create = mapOf("en" to "Create encryption key", "zh-CN" to "创建加密密钥", "ja" to "暗号化キーを作成")
        val resume = mapOf("en" to "Resume key setup", "zh-CN" to "继续密钥设置", "ja" to "キー設定を再開")
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            assertThat(guide.document.getElementById("shared-key-creation")).isNotNull()
            assertThat(guide.text).contains(create.getValue(language))
            assertThat(guide.text).contains(resume.getValue(language))
            assertThat(guide.document.select("a[href='troubleshooting.html#key-setup']")).isNotEmpty()
            val recovery = pages.getValue(language to Kind.TROUBLE).document.getElementById("key-setup")!!
            assertThat(recovery.text()).contains("Admin menu › Config › Update")
            assertThat(recovery.text()).contains(resume.getValue(language))
            assertThat(recovery.select("a[href='quick-start.html#shared-key-creation']")).isNotEmpty()
        }
    }

    @Test
    fun `setup guides combine API credentials in every language`() {
        val tests = mapOf("en" to "Test API", "zh-CN" to "测试 API", "ja" to "APIをテスト")
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            assertWithMessage(guide.name)
                .that(guide.document.select("#connect strong").map { it.text() })
                .contains("Adyen API")
            assertWithMessage(guide.name).that(guide.text).contains(tests.getValue(language))
            val manual = guide.document.select(".manual-setup").map { it.text() }
            assertThat(manual).hasSize(4)
            manual.forEach { assertWithMessage(guide.name).that(it).contains("Adyen API") }
        }
    }

    @Test
    fun `Tap to Pay separates activation installation credential options and boarding`() {
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            val heading = guide.document.getElementById("tap-to-pay")!!
            val notice = heading.nextElementSibling()!!
            assertWithMessage(guide.name).that(notice.hasClass("callout-prereq")).isTrue()
            assertThat(notice.text()).contains("Adyen Payments app role")
            assertThat(notice.select("a").map { it.attr("href") }).contains("https://help.adyen.com/contact")
            val following = heading.nextElementSiblings()
            val steps = following.first { it.tagName() == "ol" }.select("li")
            assertThat(steps).hasSize(2)
            assertThat(steps.first()!!.select("a").map { it.attr("href") })
                .containsExactly(
                    "https://play.google.com/store/apps/details?id=com.adyen.ipp.mobile.companion.test",
                    "https://play.google.com/store/apps/details?id=com.adyen.ipp.mobile.companion.live",
                ).inOrder()
            val options = following.first { it.tagName() == "dl" }
            assertThat(options.select("dt")).hasSize(1)
            assertThat(options.select("dd")).hasSize(1)
            assertThat(options.select("a").map { it.attr("href") }).containsExactly("setup.html", "#helper").inOrder()
            assertThat(following.single { it.hasClass("manual-setup") }.text()).contains("Adyen Payments app role")
        }
        val english =
            pages
                .getValue("en" to Kind.GUIDE)
                .document
                .getElementById("tap-to-pay")!!
                .untilNextTask()
        assertThat(english.single { it.hasClass("manual-setup") }.text()).contains("shared key")
    }

    @Test
    fun `every integration shows helper guidance and keeps Manual collapsed without scripts`() {
        val helperLabels = mapOf("en" to "Setup helper (recommended)", "zh-CN" to "设置助手（推荐）", "ja" to "セットアップヘルパー（推奨）")
        val manualLabels = mapOf("en" to "Manual", "zh-CN" to "手动", "ja" to "手動")
        val automaticLabels = mapOf("en" to "Automatic", "zh-CN" to "自动", "ja" to "自動")
        val registrationLabels = mapOf("en" to "register the phone", "zh-CN" to "注册手机", "ja" to "スマートフォンを登録")
        LANGUAGES.forEach { language ->
            val guide = pages.getValue(language to Kind.GUIDE)
            assertThat(guide.document.select("#connect details.manual-setup")).hasSize(4)
            assertThat(guide.document.select("script")).isEmpty()
            listOf("on-terminal", "network", "cloud", "tap-to-pay").forEach { destination ->
                val following = guide.document.getElementById(destination)!!.untilNextTask()
                val helper = following.single { it.hasClass("settings-list") }
                assertWithMessage("${guide.name} #$destination helper")
                    .that(helper.select("dt").single().text())
                    .isEqualTo(helperLabels.getValue(language))
                assertThat(helper.select("dd")).hasSize(1)
                assertThat(helper.parents().any { it.tagName() == "details" }).isFalse()
                assertThat(helper.select("a").map { it.attr("href") }).containsExactly("setup.html", "#helper").inOrder()
                val flowLabels =
                    if (destination == "tap-to-pay") {
                        listOf(registrationLabels.getValue(language))
                    } else {
                        listOf(automaticLabels.getValue(language))
                    }
                (listOf("TEST", "LIVE") + flowLabels).forEach {
                    assertThat(helper.text()).contains(it)
                }
                if (destination == "cloud") assertThat(helper.text()).contains("Cloud Device API role")
                val manual = following.single { it.hasClass("manual-setup") }
                assertThat(helper.nextElementSibling()).isEqualTo(manual)
                assertThat(manual.tagName()).isEqualTo("details")
                assertWithMessage("${guide.name} #$destination Manual default").that(manual.hasAttr("open")).isFalse()
                assertThat(manual.hasAttr("hidden")).isFalse()
                assertThat(manual.children().first()!!.tagName()).isEqualTo("summary")
                assertThat(manual.select("summary").single().text()).isEqualTo(manualLabels.getValue(language))
                val steps =
                    when (destination) {
                        "on-terminal" -> 2
                        "cloud" -> 3
                        else -> 4
                    }
                assertThat(manual.select("ol > li")).hasSize(steps)
            }
        }
    }

    @Test
    fun `SMTP setup is optional secured and links to email testing in every language`() {
        LANGUAGES.forEach { language ->
            val helper = pages.getValue(language to Kind.SETUP)
            val choices = helper.document.select("input[name=includeSmtp]")
            assertThat(choices.map { it.attr("value") }).containsExactly("no", "yes").inOrder()
            assertThat(choices.first()!!.hasAttr("checked")).isTrue()
            val fields = helper.document.getElementById("smtp-fields")!!
            assertThat(fields.hasAttr("hidden")).isTrue()
            assertThat(fields.hasAttr("disabled")).isTrue()
            assertThat(fields.hasAttr("data-for")).isFalse()
            assertThat(fields.hasAttr("data-mode")).isFalse()
            assertThat(fields.attr("data-email")).isEqualTo("true")
            listOf("smtpHost", "smtpPort", "smtpFromAddress").forEach { id ->
                assertWithMessage("$language: $id").that(fields.select("#$id").single().hasAttr("required")).isTrue()
            }
            listOf("smtpUsername", "smtpPassword", "smtpFromName").forEach { id ->
                assertWithMessage("$language: $id").that(fields.select("#$id").single().hasAttr("required")).isFalse()
            }
            assertThat(fields.select("#smtpPassword").single().attr("type")).isEqualTo("password")
            val security = fields.select("input[name=smtpSecurity]")
            assertThat(security.map { it.attr("value") }).containsExactly("STARTTLS", "SSL", "NONE").inOrder()
            assertThat(security.first()!!.hasAttr("checked")).isTrue()
            assertThat(fields.select("#smtpPort").single().attr("value")).isEqualTo("587")
            assertThat(helper.document.select("#setup-form a[href='using.html#email']")).isNotEmpty()
            val using = pages.getValue(language to Kind.USING)
            assertThat(
                using.document
                    .getElementById("email")!!
                    .untilNextTask()
                    .first { it.tagName() == "ol" }
                    .select("li"),
            ).hasSize(3)
        }
    }

    @Test
    fun `setup helper steps are numbered by layout and optional details stay inside their choice`() {
        LANGUAGES.forEach { language ->
            val helper = pages.getValue(language to Kind.SETUP)
            // The stylesheet numbers visible groups, so numbers in the text would duplicate or skip when groups hide.
            helper.document.select("#setup-form legend").forEach { legend ->
                assertWithMessage("${helper.name}: ${legend.text()}").that(legend.text()).doesNotContainMatch("^\\d")
            }
            mapOf("receipt-fields" to "includeReceipt", "smtp-fields" to "includeSmtp").forEach { (details, choice) ->
                val group = helper.document.getElementById(details)!!.parent()!!
                assertWithMessage("${helper.name} #$details").that(group.select("> label > input[name=$choice]")).hasSize(2)
            }
        }
    }
}

private const val BASE = "https://minimpos.app/"
private const val FRAME_TOLERANCE = 0.005
private const val CUSTOMER_AREA_TEST = "https://ca-test.adyen.com/ca/ui/"
private const val CUSTOMER_AREA_LIVE = "https://ca-live.adyen.com/ca/ui/"
private val LANGUAGES = listOf("en", "zh-CN", "ja")
private val GUIDE_TOPICS =
    mapOf(
        Kind.GUIDE to setOf("try", "choose", "install", "connect", "first-payment", "live"),
        Kind.USING to setOf("business", "sell", "refund", "history", "receipts", "shoppers", "payment-links", "preauth", "tips", "more"),
        Kind.TROUBLE to
            setOf(
                "unknown",
                "declines",
                "connection",
                "network-dropouts",
                "modifications",
                "links",
                "receipts",
                "access",
                "install",
                "help",
            ),
    )

private val docs: Path =
    Path.of(checkNotNull(System.getProperty("minimpos.website")) { "minimpos.website is not set" }).toAbsolutePath().normalize()
private val styles = docs.resolve("styles.css").readText()

/** The kinds of page each language has. */
private enum class Kind(
    val file: String,
) {
    LANDING("index.html"),
    GUIDE("quick-start.html"),
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

/** The elements after this task heading, up to the next one. */
private fun Element.untilNextTask(): List<Element> = nextElementSiblings().takeWhile { it.tagName() != "h3" }

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
