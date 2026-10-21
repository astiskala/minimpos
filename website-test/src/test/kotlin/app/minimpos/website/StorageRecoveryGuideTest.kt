package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.jsoup.Jsoup
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

class StorageRecoveryGuideTest {
    @Test
    fun `all languages explain explicit storage recovery and keep payment outcomes authoritative`() {
        val docs = Path.of(checkNotNull(System.getProperty("minimpos.website")))
        val languages =
            mapOf(
                "" to ("values" to listOf("Restart", "Clear storage", "uninstall", "does not undo", "recovery records")),
                "zh-CN" to ("values-zh-rCN" to listOf("重启", "清除存储空间", "卸载", "不会撤销", "恢复记录")),
                "zh-Hant" to ("values-b+zh+Hant" to listOf("重新啟動", "清除儲存空間", "解除安裝", "不會撤銷", "復原紀錄")),
                "ja" to ("values-ja" to listOf("再起動", "ストレージを消去", "アンインストール", "取り消されません", "復旧記録")),
            )
        languages.forEach { (language, contract) ->
            val (resourceDirectory, terms) = contract
            val page = Jsoup.parse(docs.resolve(language).resolve("troubleshooting.html").readText())
            val storage = checkNotNull(page.getElementById("storage"))
            terms.forEach { term -> assertWithMessage("$language storage recovery: $term").that(storage.text()).contains(term) }
            assertThat(storage.select("ol.steps > li")).hasSize(4)
            assertThat(storage.select(".callout-warning").text()).contains("PIN")
            assertThat(storage.select(".callout-warning").text()).contains("Adyen")
            assertThat(storage.text()).contains("Customer Area")
            assertThat(storage.select("a").eachAttr("href")).containsAtLeast(
                "#unknown",
                "#help",
                "quick-start.html#connect",
                "https://github.com/astiskala/minimpos/releases/latest",
                "https://docs.adyen.com/point-of-sale/android-terminals/deploy-apps",
            )
            assertThat(page.select("nav.toc a[href=#storage]")).hasSize(2)
            assertThat(page.select("#access a[href=#storage]")).hasSize(1)
            val strings = Jsoup.parse(docs.parent.resolve("app/src/main/res/$resourceDirectory/strings.xml").readText())
            val connection = strings.select("string[name=settings_test_connection]").text()
            assertThat(storage.select("strong").eachText()).contains(connection)
            assertThat(strings.select("string[name=storage_unavailable]").text()).isNotEmpty()
            assertThat(page.select("#help a[href=\"https://github.com/astiskala/minimpos/issues\"]")).hasSize(1)
        }
    }
}
