package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class TerminalFeaturesGuideTest {
    @Test
    fun `terminal workflows use localized app labels and are reachable from setup`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        val labels =
            mapOf(
                "capabilities" to listOf("settings_capabilities", "capability_status_needs_config", "capability_status_unsupported"),
                "terminal-tips" to listOf("checkout_tip_on_terminal", "settings_dynamic_tip_override", "settings_tip_on_pre_tax"),
                "partial-payments" to listOf("settings_allow_partial_auth", "status_partially_paid"),
                "shopper-display" to listOf("settings_shopper_display"),
                "giving" to listOf("checkout_ask_giving"),
                "keyed-entry" to listOf("checkout_moto", "checkout_mke"),
                "reconciliation" to listOf("history_reconcile", "reconciliation_close_period"),
                "offline" to listOf("detail_offline_mode", "detail_unconfirmed_batches"),
            )
        val resources =
            mapOf("en" to "values", "zh-CN" to "values-zh-rCN", "zh-Hant" to "values-b+zh+Hant", "ja" to "values-ja")
        resources.forEach { (language, folder) ->
            val directory = docs.resolve(if (language == "en") "" else language)
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            val setup = Jsoup.parse(directory.resolve("quick-start.html").readText())
            val strings =
                Jsoup
                    .parse(docs.parent.resolve("app/src/main/res/$folder/strings.xml").readText())
                    .select("string[name]")
                    .associate { it.attr("name") to it.text() }
            labels.forEach { (id, keys) ->
                val task = checkNotNull(using.getElementById(id)) { "$language missing #$id" }
                val text =
                    if (task.tagName() == "h3") {
                        generateSequence(task.nextElementSibling()) { it.nextElementSibling() }
                            .takeWhile { it.tagName() !in setOf("h2", "h3") }
                            .joinToString(" ") { it.text() }
                    } else {
                        task.text()
                    }
                keys.forEach { key ->
                    assertWithMessage("$language #$id label $key").that(text).contains(strings.getValue(key))
                }
                assertWithMessage("$language #$id navigation").that(using.select("nav.toc a[href='#$id']")).isNotEmpty()
            }
            listOf("capabilities", "terminal-tips", "terminal-checkout", "offline", "reconciliation").forEach { id ->
                assertWithMessage("$language setup link #$id").that(setup.select("a[href='using.html#$id']")).isNotEmpty()
            }
            listOf("DCC", "MOTO", "MKE", "Adyen Giving").forEach { term ->
                assertThat(using.select("#capabilities").text()).contains(term)
            }
            assertThat(using.select("#terminal-checkout a[href='troubleshooting.html#unknown']")).isNotEmpty()
            assertThat(using.select("#offline a[href='troubleshooting.html#unknown']")).isNotEmpty()
            val trouble = Jsoup.parse(directory.resolve("troubleshooting.html").readText())
            listOf("capabilities", "partial-payments", "reconciliation", "offline").forEach { id ->
                assertWithMessage("$language troubleshooting link #$id")
                    .that(trouble.select("a[href='using.html#$id']"))
                    .isNotEmpty()
            }
        }
    }
}
