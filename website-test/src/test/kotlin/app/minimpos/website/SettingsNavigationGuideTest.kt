package app.minimpos.website

import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class SettingsNavigationGuideTest {
    @Test
    fun `settings breadcrumbs match current localized sections across guides and helper`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        val resources =
            mapOf("en" to "values", "zh-CN" to "values-zh-rCN", "zh-Hant" to "values-b+zh+Hant", "ja" to "values-ja")
        resources.forEach { (language, folder) ->
            val directory = docs.resolve(if (language == "en") "" else language)
            val strings =
                Jsoup
                    .parse(docs.parent.resolve("app/src/main/res/$folder/strings.xml").readText())
                    .select("string[name]")
                    .associate { it.attr("name") to it.text() }
            val root = strings.getValue("settings_title")

            fun path(key: String) = "$root \u203a ${strings.getValue(key)}"
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            val labels = using.select("strong").eachText()
            assertWithMessage("$language admin PIN setup").that(labels).contains(strings.getValue("settings_set_pin"))
            assertWithMessage("$language simulator link action")
                .that(using.select("#payment-links strong").eachText())
                .contains(strings.getValue("link_simulate"))
            assertWithMessage("$language browser update action")
                .that(using.select("#more strong").eachText())
                .contains(strings.getValue("update_download"))
            listOf("settings_tips_donations", "settings_shopper_display", "settings_email", "settings_data").forEach { key ->
                assertWithMessage("$language current $key path").that(labels).contains(path(key))
            }
            listOf("settings_tipping", "settings_shopper_display", "settings_email_receipts").forEach { key ->
                val obsolete = "${path("settings_payments")} \u203a ${strings.getValue(key)}"
                assertWithMessage("$language obsolete $key path").that(using.text()).doesNotContain(obsolete)
            }
            listOf("setup.html", "troubleshooting.html").forEach { page ->
                val text = Jsoup.parse(directory.resolve(page).readText()).text()
                assertWithMessage("$language $page email path").that(text).contains(path("settings_email"))
            }
            val quickStart = Jsoup.parse(directory.resolve("quick-start.html").readText()).text()
            assertWithMessage("$language sample management path").that(quickStart).contains(path("settings_data"))
            listOf("email_not_configured", "email_authentication").forEach { key ->
                assertWithMessage("$language $key path").that(strings.getValue(key)).contains(path("settings_email"))
            }
            assertWithMessage("$language transfer hint path").that(strings.getValue("transfer_export_hint")).contains(path("settings_data"))
        }
    }
}
