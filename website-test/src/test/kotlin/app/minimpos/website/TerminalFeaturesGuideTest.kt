package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class TerminalFeaturesGuideTest {
    private val resources =
        mapOf(
            "en" to "values",
            "zh-CN" to "values-zh-rCN",
            "zh-Hant" to "values-b+zh+Hant",
            "ja" to "values-ja",
            "pt-BR" to "values-pt-rBR",
            "es-MX" to "values-es-rMX",
            "fr-FR" to "values-fr-rFR",
        )

    private val workflowLabels =
        mapOf(
            "capabilities" to listOf("settings_capabilities", "capability_status_needs_config", "capability_status_unsupported"),
            "audio-guidance" to listOf("checkout_audio_guidance", "checkout_audio_confirm"),
            "terminal-tips" to listOf("checkout_tip_on_terminal", "settings_dynamic_tip_override", "settings_tip_on_pre_tax"),
            "mexican-installments" to listOf("mexican_title"),
            "cashback" to listOf("cashback_title"),
            "cashout" to listOf("cashout_title", "cashback_handover"),
            "unreferenced-refunds" to
                listOf(
                    "refund_entry_unreferenced",
                    "unreferenced_settings_enable",
                    "unreferenced_settings_limit",
                    "unreferenced_settings_delay",
                    "refund_cancel",
                    "refund_status_approved",
                    "refund_status_requested",
                ),
            "meal-vouchers" to
                listOf("voucher_title", "split_open", "split_pay_meal_voucher", "products_meal_voucher", "voucher_account_title"),
            "partial-payments" to listOf("settings_allow_partial_auth", "status_partially_paid"),
            "shopper-display" to listOf("settings_shopper_display"),
            "giving" to listOf("checkout_ask_giving"),
            "keyed-entry" to listOf("checkout_moto", "checkout_mke"),
            "reconciliation" to listOf("reconciliation_check", "reconciliation_close_period"),
            "offline" to listOf("detail_offline_mode", "detail_unconfirmed_batches"),
        )

    @Test
    fun `every guide links configuration based surcharge requirements and restrictions`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        resources.keys.forEach { language ->
            val directory = docs.resolve(if (language == "en") "" else language)
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            assertThat(using.select("a[href='https://docs.adyen.com/point-of-sale/surcharge/configuration']")).isNotEmpty()
            assertThat(using.select("a[href='https://docs.adyen.com/point-of-sale/surcharge']")).isNotEmpty()
            assertThat(using.text()).contains("DCC")
        }
    }

    @Test
    fun `every guide links the official meal-voucher requirements`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        resources.keys.forEach { language ->
            val directory = docs.resolve(if (language == "en") "" else language)
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            listOf(
                "https://docs.adyen.com/point-of-sale/alternative-payment-methods/brazil-vouchers",
                "https://docs.adyen.com/point-of-sale/cashback-and-cash-out",
                "https://docs.adyen.com/point-of-sale/basic-tapi-integration/refund-payment/unreferenced",
                "https://docs.adyen.com/point-of-sale/basic-tapi-integration/refund-payment/cancel-unreferenced",
                "https://docs.adyen.com/industries/markets/europe-meal-vouchers",
            ).forEach { url -> assertWithMessage("$language $url").that(using.select("a[href='$url']")).isNotEmpty() }
            assertThat(using.text()).contains("EUR 25".takeUnless { language == "fr-FR" } ?: "25 EUR")
        }
    }

    @Test
    fun `terminal workflows use localized app labels and are reachable from setup`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        resources.forEach { (language, folder) ->
            val directory = docs.resolve(if (language == "en") "" else language)
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            val setup = Jsoup.parse(directory.resolve("quick-start.html").readText())
            val strings =
                Jsoup
                    .parse(docs.parent.resolve("app/src/main/res/$folder/strings.xml").readText())
                    .select("string[name]")
                    .associate { it.attr("name") to it.text() }
            setup.select(".manual-setup").take(3).forEach { terminalSetup ->
                assertWithMessage("$language terminal discovery label")
                    .that(terminalSetup.text())
                    .contains(strings.getValue("settings_discover"))
            }
            workflowLabels.forEach { (id, keys) ->
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
                assertWithMessage("$language setup link #$id").that(setup.select("a[href='using#$id']")).isNotEmpty()
            }
            listOf("DCC", "MOTO", "MKE", "Adyen Giving").forEach { term ->
                assertThat(using.select("#capabilities").text()).contains(term)
            }
            assertThat(using.select("#terminal-checkout a[href='troubleshooting#unknown']")).isNotEmpty()
            assertThat(using.select("#offline a[href='troubleshooting#unknown']")).isNotEmpty()
            val trouble = Jsoup.parse(directory.resolve("troubleshooting.html").readText())
            listOf("capabilities", "partial-payments", "reconciliation", "offline").forEach { id ->
                assertWithMessage("$language troubleshooting link #$id")
                    .that(trouble.select("a[href='using#$id']"))
                    .isNotEmpty()
            }
        }
    }

    @Test
    fun `audio guidance documents hardware software limits and provider requirements in every language`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        resources.keys.forEach { language ->
            val directory = docs.resolve(if (language == "en") "" else language)
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            val audio = checkNotNull(using.getElementById("audio-guidance"))
            val audioText =
                generateSequence(audio.nextElementSibling()) { it.nextElementSibling() }
                    .takeWhile { it.tagName() != "h3" }
                    .joinToString(" ") { it.text() }
            listOf("AMS1", "S1F2L", "S1E2L", "S1U2", "M450", "P630", "SFO1", "1.117", "1.124").forEach {
                assertWithMessage("$language audio guidance requirement").that(audioText).contains(it)
            }
            assertThat(
                using.select(
                    "#terminal-checkout a[href='https://docs.adyen.com/point-of-sale/android-terminals/" +
                        "accessibility-mode/accessibility-from-pos']",
                ),
            ).isNotEmpty()
        }
    }
}
