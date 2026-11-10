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
            listOf("testing.html" to "live", "troubleshooting.html" to "storage").forEach { (file, id) ->
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
            assertThat(using.select("#optional a[href='payment-options#capabilities']")).hasSize(1)
            assertThat(using.select(".task-links a").eachAttr("href"))
                .containsExactly("#sell", "#refund", "#email", "#close-day")
                .inOrder()
        }
    }

    @Test
    fun `setup routes compare destinations and link shared requirements and verification`() {
        languages.forEach { language ->
            val guide = page(language, "quick-start.html")
            val routes = listOf("quick-start-terminal", "quick-start-network", "quick-start-cloud", "quick-start-tap-to-pay")
            assertThat(guide.select(".setup-comparison tbody th a").eachAttr("href")).containsExactlyElementsIn(routes).inOrder()
            assertThat(guide.select(".setup-comparison thead th[scope=col]")).hasSize(4)
            assertThat(guide.select(".setup-comparison td[data-label]")).hasSize(12)
            routes.forEach { route ->
                val destination = page(language, "$route.html")
                assertThat(destination.select("#before a[href='quick-start#credentials']")).hasSize(1)
                assertThat(destination.select("#connect .manual-setup").single().hasAttr("open")).isFalse()
                assertThat(destination.select("#verify a[href=testing]")).hasSize(1)
            }
            val checklist = guide.select(".role-checklist").single()
            assertThat(checklist.hasAttr("open")).isFalse()
            assertThat(checklist.select("ul.roles")).hasSize(2)
            assertThat(
                checklist
                    .select("ul.roles")
                    .first()!!
                    .select("strong")
                    .eachText(),
            ).containsAtLeast(
                "Checkout webservice role",
                "Management API - API credentials read and write",
                "Merchant Recurring role",
            )
            assertThat(
                checklist
                    .select("ul.roles")
                    .last()!!
                    .select("strong")
                    .eachText(),
            ).containsAtLeast("API tokenise payment details", "Cloud Device API role")
        }
    }

    @Test
    fun `every language explains direct Settings navigation and token deletion`() {
        languages.forEach { language ->
            val using = page(language, "using.html")
            assertWithMessage(language).that(using.select("#settings-navigation strong").eachText()).hasSize(2)
            assertThat(page(language, "payment-options.html").select("#token-deletion strong").eachText()).hasSize(2)
            assertThat(page(language, "payment-options.html").select("#token-deletion").text()).contains("Adyen")
            assertThat(page(language, "payment-options.html").select("#token-deletion").text()).contains("Mini mPOS")
            assertThat(page(language, "payment-options.html").select("#token-deletion").text()).contains("PIN")
        }
    }

    @Test
    fun `framed screenshots expose full size originals in every language`() {
        languages.forEach { language ->
            listOf(
                "index.html",
                "quick-start.html",
                "quick-start-terminal.html",
                "quick-start-network.html",
                "quick-start-cloud.html",
                "quick-start-tap-to-pay.html",
                "demo.html",
                "testing.html",
                "using.html",
                "payment-options.html",
                "troubleshooting.html",
                "setup.html",
            ).forEach { file ->
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
                .containsExactly("#unknown", "#install", "#connection", "#receipts", "#access")
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
