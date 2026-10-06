package io.github.astiskala.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class DailyReportGuideTest {
    @Test
    fun `daily reports and destructive environment switching are explained in every operations guide`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website"))).toAbsolutePath().normalize()
        val terms =
            mapOf(
                "en" to listOf("Local daily summary", "ignoring filters", "undated", "seeded samples", "safe retry data"),
                "zh-CN" to listOf("本地每日汇总", "忽略筛选", "无日期", "示例", "安全重试数据"),
                "ja" to listOf("ローカル日次集計", "フィルターを無視", "日付不明", "サンプル", "安全な再試行データ"),
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
