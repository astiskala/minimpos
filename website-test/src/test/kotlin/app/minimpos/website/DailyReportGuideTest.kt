package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class DailyReportGuideTest {
    @Test
    fun `merchant scan guides preserve wallet limitations and recovery in every language`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        listOf("", "zh-CN", "ja").forEach { language ->
            val directory = docs.resolve(language)
            val using = Jsoup.parse(directory.resolve("using.html").readText()).select("#wallets")
            listOf("Alipay+", "PayPal", "Venmo", "GCash", "DANA", "Kakao Pay", "TrueMoney")
                .forEach { assertWithMessage("$language wallet contract: $it").that(using.text()).contains(it) }
            assertWithMessage(
                "$language wallet verification",
            ).that(using.text()).contains(if (language.isEmpty()) "test behavior" else "TEST")
            assertThat(using.select("a[href$=\"#unknown\"]")).isNotEmpty()
            assertThat(using.select("img")).hasSize(2)
            assertThat(using.select("img[src$='wallet-scan.png']").single().attr("alt")).contains("WeChat Pay")
            val troubleshooting = Jsoup.parse(directory.resolve("troubleshooting.html").readText()).select("#wallets")
            assertThat(troubleshooting.select("a[href=\"#unknown\"]")).isNotEmpty()
        }
    }

    @Test
    fun `daily reports and destructive environment switching are explained in every operations guide`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        val terms =
            mapOf(
                "en" to listOf("Local daily summary", "ignoring filters", "undated", "safe retry data"),
                "zh-CN" to listOf("本地每日汇总", "忽略筛选", "无日期", "安全重试数据"),
                "ja" to listOf("ローカル日次集計", "フィルターを無視", "日付不明", "安全な再試行データ"),
            )
        terms.forEach { (language, required) ->
            val file = docs.resolve(if (language == "en") "using.html" else "$language/using.html")
            val text = Jsoup.parse(file.readText()).select("#history").text()
            required.forEach { assertWithMessage("$language report contract: $it").that(text).contains(it) }
            assertThat(text).contains("LIVE")
            assertThat(text).contains("TEST")
        }
    }
}
