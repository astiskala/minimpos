package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class TapToPayGuideTest {
    @Test
    fun `all languages explain Tap to Pay remediation and redacted reporting`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website")))
        listOf("", "ja", "zh-CN", "zh-Hant", "pt-BR", "es-MX", "fr-FR").forEach { language ->
            val directory = docs.resolve(language)
            val resources =
                mapOf(
                    "" to "values",
                    "ja" to "values-ja",
                    "zh-CN" to "values-zh-rCN",
                    "zh-Hant" to "values-b+zh+Hant",
                    "pt-BR" to "values-pt-rBR",
                    "es-MX" to "values-es-rMX",
                    "fr-FR" to "values-fr-rFR",
                )
            val strings = Jsoup.parse(docs.parent.resolve("app/src/main/res/${resources.getValue(language)}/strings.xml").readText())
            val page = Jsoup.parse(directory.resolve("troubleshooting.html").readText())
            val help = checkNotNull(page.getElementById("tap-to-pay-check"))
            val connectionTest = strings.select("string[name=settings_test_connection]").text()
            assertThat(connectionTest).isNotEmpty()
            assertThat(help.select("strong").eachText()).contains(connectionTest)
            val diagnostics = strings.select("string[name=diagnostics_details]").text()
            val share = strings.select("string[name=diagnostics_share_report]").text()
            assertThat(diagnostics).isNotEmpty()
            assertThat(share).isNotEmpty()
            val connection = checkNotNull(page.getElementById("connection"))
            val controls = connection.children().filter { it.tagName() == "p" }.flatMap { it.select("strong").eachText() }
            assertThat(controls).containsAtLeast(share, connectionTest)
            assertThat(connection.text()).contains(diagnostics)
            val keySection = strings.select("string[name=settings_shared_key]").text()
            val terminal = strings.select("string[name=settings_terminal]").text()
            assertThat(help.text()).doesNotContain("$terminal › $keySection")
            val quickStart = Jsoup.parse(directory.resolve("quick-start.html").readText())
            val apiTest = strings.select("string[name=settings_test_api]").text()
            val discover = strings.select("string[name=settings_discover]").text()
            listOf("network", "cloud").forEach { id ->
                val heading = checkNotNull(quickStart.getElementById(id))
                val steps = heading.nextElementSibling()!!.nextElementSibling()!!.nextElementSibling()!!
                assertThat(steps.tagName()).isEqualTo("details")
                assertThat(steps.select("strong").eachText()).containsAtLeast(discover, connectionTest)
                assertThat(steps.select("strong").eachText()).doesNotContain(apiTest)
            }
            listOf("UnavailableService", "TEST", "LIVE", "UTC").forEach {
                assertThat(help.text()).contains(it)
            }
            listOf("1029_605", "GET /me", "null").forEach {
                assertThat(help.text()).doesNotContain(it)
            }
            assertThat(help.select("a").map { it.attr("href") }).containsAtLeast(
                "https://docs.adyen.com/point-of-sale/mobile-android/build/payments-app",
                "https://ca-test.adyen.com/",
                "https://ca-live.adyen.com/",
            )
        }
    }
}
