package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class GuideExperienceTest {
    private val docs = Path.of(checkNotNull(System.getProperty("minimpos.website")))
    private val languages = listOf("", "es-MX", "fr-FR", "ja", "pt-BR", "zh-CN", "zh-Hant")

    @Test
    fun `data loss warnings precede environment switching and reset instructions in every language`() {
        languages.forEach { language ->
            listOf("quick-start.html" to "live", "troubleshooting.html" to "storage").forEach { (file, id) ->
                val section = checkNotNull(page(language, file).getElementById(id))
                val children = section.children()
                assertWithMessage("$language $id warning before action")
                    .that(children.indexOf(section.selectFirst(".callout-warning")))
                    .isLessThan(children.indexOf(section.selectFirst("ol")))
            }
            val storage = page(language, "troubleshooting.html").select("#storage ol").single()
            assertThat(storage.hasClass("recovery-steps")).isTrue()
            assertThat(storage.hasClass("steps")).isFalse()
        }
    }

    @Test
    fun `daily work precedes optional features and contents follow the reading order`() {
        languages.forEach { language ->
            val using = page(language, "using.html")
            val sections = using.select(".guide-body > section").map { it.id() }
            assertWithMessage(
                language,
            ).that(sections.take(5)).containsExactly("business", "sell", "refund", "receipts", "history").inOrder()
            assertThat(sections.indexOf("capabilities")).isGreaterThan(sections.indexOf("history"))
            assertThat(using.select(".task-links a").eachAttr("href"))
                .containsExactly("#sell", "#refund", "#email", "#close-day")
                .inOrder()
        }
    }

    @Test
    fun `setup routes compare destinations and link the shared requirements and verification`() {
        languages.forEach { language ->
            val guide = page(language, "quick-start.html")
            val destinations = listOf("on-terminal", "network", "cloud", "tap-to-pay")
            assertThat(guide.select(".setup-comparison tbody th a").eachAttr("href"))
                .containsExactlyElementsIn(destinations.map { "#$it" })
                .inOrder()
            assertThat(guide.select(".setup-comparison thead th[scope=col]")).hasSize(4)
            assertThat(guide.select(".setup-comparison td[data-label]")).hasSize(12)
            guide.select(".setup-path").forEachIndexed { index, route ->
                val install = if (index == 0) "#install-terminal" else "#install-mobile"
                assertThat(route.select("a").eachAttr("href"))
                    .containsExactly("#credentials", install, "#helper", "#first-payment", "#live")
                    .inOrder()
            }
            assertThat(guide.select(".destination-route")).hasSize(4)
            assertThat(guide.select(".destination-route .route-next a").eachAttr("href"))
                .containsExactlyElementsIn(List(4) { "#first-payment" })
            assertThat(guide.select(".role-checklist").single().hasAttr("open")).isFalse()
            assertThat(guide.select(".role-checklist strong").eachText()).containsAtLeast(
                "Checkout webservice role",
                "Management API - API credentials read and write",
                "Management API - Payment methods read",
                "Management API - Stores read",
                "Management API - Accounts read",
                "Merchant Recurring role",
            )
        }
    }

    @Test
    fun `framed screenshots expose full size originals in every language`() {
        languages.forEach { language ->
            listOf("index.html", "quick-start.html", "using.html").forEach { file ->
                page(language, file).select(".terminal").forEach { terminal ->
                    val link = checkNotNull(terminal.parent())
                    assertThat(link.tagName()).isEqualTo("a")
                    assertThat(link.hasClass("screenshot-link")).isTrue()
                    assertThat(link.attr("href")).isEqualTo(terminal.select("img").single().attr("src"))
                    assertThat(link.attr("aria-label")).isNotEmpty()
                }
            }
            val troubleshooting = page(language, "troubleshooting.html")
            assertThat(troubleshooting.select(".task-links a").eachAttr("href"))
                .containsExactly("#unknown", "#connection", "#receipts", "#messages")
                .inOrder()
            troubleshooting.select(".fixes dd:has(.cause)").forEach { entry ->
                assertThat(entry.children().last()!!.hasClass("cause")).isTrue()
                assertThat(entry.select(".cause .fix-label")).isEmpty()
            }
        }
    }

    private fun page(
        language: String,
        file: String,
    ) = Jsoup.parse(docs.resolve(language).resolve(file).readText())
}
