package app.minimpos.website

import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class ReceiptImportGuideTest {
    @Test
    fun `receipt import explains each choice once in every language`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website")))
        val resources =
            mapOf(
                "" to "values",
                "zh-CN" to "values-zh-rCN",
                "zh-Hant" to "values-b+zh+Hant",
                "ja" to "values-ja",
                "pt-BR" to "values-pt-rBR",
                "es-MX" to "values-es-rMX",
                "fr-FR" to "values-fr-rFR",
            )
        resources.forEach { (language, folder) ->
            val strings =
                Jsoup
                    .parse(docs.parent.resolve("app/src/main/res/$folder/strings.xml").readText())
                    .select("string[name]")
                    .associate { it.attr("name") to it.text() }
            val page = Jsoup.parse(docs.resolve(language).resolve("using.html").readText())
            val tasks = page.select("#receipts li").filter { strings.getValue("settings_business_import") in it.text() }
            assertWithMessage("$language receipt import task").that(tasks).hasSize(1)
            val labels = tasks.single().select("strong").eachText()
            listOf("settings_business_supplement", "settings_business_replace").forEach { key ->
                assertWithMessage("$language $key choice")
                    .that(labels.count { it == strings.getValue(key) })
                    .isEqualTo(1)
            }
        }
    }
}
