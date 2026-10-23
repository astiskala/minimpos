package app.minimpos.website

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.junit.Test
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.readText

class SiteManifestTest {
    private val docs = Path.of(System.getProperty("minimpos.website"))
    private val pages =
        Files.walk(docs).use { paths ->
            paths.filter { it.extension == "html" }.toList().associateWith { Jsoup.parse(it.readText()) }
        }

    @Test
    fun `all pages share a stable installation identity and launch in their selected language`() {
        pages.forEach { (path, page) ->
            val file = path.parent.resolve(page.select("link[rel=manifest]").single().attr("href"))
            val manifest = Json.parseToJsonElement(file.readText()).jsonObject
            val home = pages.getValue(path.parent.resolve("index.html"))
            assertWithMessage(path.toString()).that(manifest.text("lang")).isEqualTo(page.select("html").attr("lang"))
            assertThat(manifest.text("id")).isEqualTo("/")
            assertThat(manifest.text("scope")).isEqualTo("/")
            assertThat(BASE.resolve(manifest.text("start_url")).toString()).isEqualTo(home.select("link[rel=canonical]").attr("href"))
            assertThat(manifest.text("display")).isEqualTo("standalone")
            assertThat(manifest.text("name")).isEqualTo(page.select("meta[name=application-name]").single().attr("content"))
            assertThat(manifest.text("description")).isNotEmpty()
            assertThat(manifest.text("short_name")).isEqualTo("Mini mPOS")
        }
    }

    @Test
    fun `installed guide shortcuts match the visible localized navigation and canonical destinations`() {
        pages.keys.filter { it.fileName.toString() == "index.html" }.forEach { path ->
            val page = pages.getValue(path)
            val manifest = Json.parseToJsonElement(path.parent.resolve("site.webmanifest").readText()).jsonObject
            val navigation = page.select(".desktop-nav a")
            val home = URI(page.select("link[rel=canonical]").attr("href"))
            val shortcuts = manifest.getValue("shortcuts") as JsonArray
            assertThat(shortcuts.map { it.jsonObject.text("name") }).containsExactlyElementsIn(navigation.eachText()).inOrder()
            val destinations = shortcuts.map { BASE.resolve(it.jsonObject.text("url")).toString() }
            assertThat(destinations).containsExactlyElementsIn(navigation.map { home.resolve(it.attr("href")).toString() }).inOrder()
            assertThat(pages.values.map { it.select("link[rel=canonical]").attr("href") }).containsAtLeastElementsIn(destinations)
        }
    }

    @Test
    fun `installation and bookmark icons match their declared sizes and maskable artwork fits the safe circle`() {
        val manifest = Json.parseToJsonElement(docs.resolve("site.webmanifest").readText()).jsonObject
        val icons = manifest.getValue("icons") as JsonArray
        assertThat(icons.map { it.jsonObject.text("purpose") }).containsAtLeast("any", "maskable")
        icons.forEach { item ->
            val icon = item.jsonObject
            val image = ImageIO.read(docs.resolve(icon.text("src").removePrefix("/")).toFile())
            assertThat(icon.text("type")).isEqualTo("image/png")
            assertThat(icon.text("sizes")).isEqualTo("${image.width}x${image.height}")
            assertThat(image.width).isEqualTo(image.height)
            if (icon.text("purpose") == "maskable") assertSafeCircle(image)
        }
        pages.forEach { (path, page) ->
            val touch = page.select("link[rel=apple-touch-icon]").single()
            val image = ImageIO.read(path.parent.resolve(touch.attr("href")).toFile())
            assertThat(touch.attr("sizes")).isEqualTo("${image.width}x${image.height}")
            assertThat(image.width).isEqualTo(180)
            assertThat(image.height).isEqualTo(180)
            assertThat(page.select("link[rel=icon][type='image/svg+xml']")).hasSize(1)
            val fallback = page.select("link[rel=icon][type='image/png']").single()
            val png = ImageIO.read(path.parent.resolve(fallback.attr("href")).toFile())
            assertThat(fallback.attr("sizes")).isEqualTo("${png.width}x${png.height}")
        }
    }

    @Test
    fun `agent index discovers every canonical page grouped by the supported language names`() {
        val index = docs.resolve("llms.txt").readText()
        assertThat(index).startsWith("# Mini mPOS\n\n>")
        val links = Regex("""\[[^\]]+]\(([^)]+)\)""").findAll(index).map { it.groupValues[1] }.toList()
        assertThat(links).containsNoDuplicates()
        assertThat(links).containsExactlyElementsIn(pages.values.map { it.select("link[rel=canonical]").attr("href") })
        val languages = pages.getValue(docs.resolve("index.html")).select(".mobile-languages a").eachText()
        assertThat(Regex("(?m)^## (.+)$").findAll(index).map { it.groupValues[1] }.toList()).containsExactlyElementsIn(languages).inOrder()
        pages.forEach { (path, page) ->
            val link = page.select("link[rel=describedby]").single()
            assertWithMessage(path.toString()).that(path.parent.resolve(link.attr("href")).normalize()).isEqualTo(docs.resolve("llms.txt"))
            assertThat(link.attr("type")).isEqualTo("text/plain")
        }
    }

    private fun assertSafeCircle(image: BufferedImage) {
        val center = image.width / 2.0
        val radiusSquared = (image.width * 0.4) * (image.width * 0.4)
        val background = image.getRGB(0, 0)
        for (y in 0..<image.height) {
            for (x in 0..<image.width) {
                if (image.getRGB(x, y) != background) {
                    val dx = x + 0.5 - center
                    val dy = y + 0.5 - center
                    assertWithMessage("maskable artwork at $x,$y").that(dx * dx + dy * dy).isAtMost(radiusSquared)
                }
            }
        }
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content

    private companion object {
        val BASE: URI = URI("https://minimpos.app/")
    }
}
