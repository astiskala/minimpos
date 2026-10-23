package app.minimpos.website

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.nio.file.Path

class GuideLayoutTest {
    @Test
    fun `every guide loads all images without horizontal overflow on phones and desktops`() {
        val docs = Path.of(System.getProperty("minimpos.website"))
        val reports = Path.of(System.getProperty("minimpos.layoutReports"))
        HelperBrowser().use { browser ->
            for (language in listOf("", "zh-CN/", "zh-Hant/", "ja/", "pt-BR/", "es-MX/", "fr-FR/")) {
                for (page in listOf("index.html", "quick-start.html", "using.html", "troubleshooting.html", "setup.html")) {
                    for (width in listOf(320, 390, 1280)) {
                        browser.viewport(width)
                        browser.openPage(docs.resolve(language + page))
                        val broken =
                            browser.executeAsyncScript(
                                """
                                const done = arguments[arguments.length - 1];
                                const images = [...document.images];
                                images.forEach(image => image.loading = 'eager');
                                Promise.all(images.map(image => image.decode().catch(() => {})))
                                  .then(() => document.fonts.ready)
                                  .then(() => done(images.filter(image => !image.naturalWidth).map(image => image.getAttribute('src'))));
                                """.trimIndent(),
                            )
                        val prefix = "${language.ifEmpty { "en/" }.dropLast(1)}-${page.removeSuffix(".html")}-$width"
                        browser.screenshot(reports.resolve("$prefix.png"))
                        assertWithMessage("$prefix broken images").that(broken).isEqualTo(emptyList<String>())
                        assertWithMessage(
                            "$prefix viewport",
                        ).that(browser.executeScript("return window.innerWidth")).isEqualTo(width.toLong())
                        // clientWidth excludes a classic scrollbar (Linux), which overlay scrollbars (macOS) do not take.
                        assertWithMessage("$prefix horizontal width")
                            .that(
                                browser.executeScript("return Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)"),
                            ).isEqualTo(browser.executeScript("return document.documentElement.clientWidth"))
                    }
                }
            }
        }
    }
}
