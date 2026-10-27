package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class ReceiptLogoGuideTest {
    @Test
    fun `every receipt guide links local logo setup and troubleshooting`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website")))
        val languages = listOf("", "es-MX", "fr-FR", "ja", "pt-BR", "zh-CN", "zh-Hant")
        languages.forEach { language ->
            val directory = docs.resolve(language)
            val using = Jsoup.parse(directory.resolve("using.html").readText())
            val logo = using.select("#receipts #receipt-logo")
            assertWithMessage("$language logo setup task").that(logo).hasSize(1)
            assertThat(logo.select("a[href='setup']")).hasSize(1)
            listOf("PNG", "JPEG", "WebP").forEach { assertThat(logo.text()).contains(it) }
            val trouble = Jsoup.parse(directory.resolve("troubleshooting.html").readText())
            val fix = trouble.select("#receipts #receipt-logo-print")
            assertWithMessage("$language logo print recovery").that(fix).hasSize(1)
            assertThat(fix.select("dt").text()).isNotEmpty()
            assertThat(fix.select("dd a[href='using#receipt-logo']")).hasSize(1)
        }
    }
}
