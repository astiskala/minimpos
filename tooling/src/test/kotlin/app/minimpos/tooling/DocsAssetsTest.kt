package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

class DocsAssetsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val languages = listOf("en", "zh-CN", "zh-Hant", "ja", "pt-BR", "es-MX", "fr-FR")

    @Test
    fun `social pages preserve checked-in design and localized copy with portable file URLs`() {
        val root = Path.of(System.getProperty("minimpos.repository"))
        val work = temporary.newFolder("pages with spaces").toPath()
        val result = Cli.run("docs-assets", "pages", root.toString(), work.toString())
        assertThat(result.code).isEqualTo(0)
        languages.forEach { language ->
            val page = Files.readString(work.resolve("$language.html"))
            assertThat(page).doesNotContain("{{")
            assertThat(page).contains(root.resolve("docs").toUri().toASCIIString())
            assertThat(page).contains(if (language == "en") "images/receipt.png" else "images/$language/receipt.png")
            assertThat(page).contains(if (language == "zh-Hant") "lang=\"zh-Hant-HK\"" else "lang=\"$language\"")
        }
        val source = temporary.newFolder("bad template").toPath()
        val templates = Files.createDirectories(source.resolve(".agents/skills/docs-screenshots"))
        Files.copy(root.resolve(".agents/skills/docs-screenshots/social-copy.json"), templates.resolve("social-copy.json"))
        Files.writeString(templates.resolve("social.html"), "{{missing}}")
        assertThrows(IllegalArgumentException::class.java) { DocsAssets.run("pages", source, work) }
        assertThat(Cli.run("docs-assets").code).isEqualTo(1)
        assertThat(Cli.run("docs-assets", "unknown", root.toString(), work.toString()).code).isEqualTo(1)
    }

    @Test
    fun `social conversion validates dimensions before quantizing into each language directory`() {
        val raw = temporary.newFolder("raw").toPath()
        val images = temporary.newFolder("social").toPath()
        languages.forEach { save(BufferedImage(1200, 630, BufferedImage.TYPE_INT_RGB), raw.resolve("$it-social.png")) }
        assertThat(DocsAssets.run("social", raw, images)).hasSize(languages.size)
        assertThat(ImageIO.read(images.resolve("ja/social.png").toFile()).width).isEqualTo(1200)
        for ((width, height) in listOf(1 to 630, 1200 to 1)) {
            save(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), raw.resolve("en-social.png"))
            assertThrows(IllegalArgumentException::class.java) { DocsAssets.run("social", raw, images) }
        }
        Files.writeString(raw.resolve("en-social.png"), "not PNG data")
        assertThrows(IllegalArgumentException::class.java) { DocsAssets.run("social", raw, images) }
    }

    @Test
    fun `review validates parity frame dimensions and palettes then creates labeled contact sheets`() {
        val images = images()
        val review = temporary.newFolder("review").toPath()
        assertThat(DocsAssets.run("review", images, review)).hasSize(languages.size)
        assertThat(ImageIO.read(review.resolve("en-contact.png").toFile()).width).isEqualTo(1190)
        Files.delete(images.resolve("ja/home.png"))
        assertThrows(IllegalArgumentException::class.java) { DocsAssets.run("review", images, review) }
        Files.delete(images.resolve("home.png"))
        assertThrows(IllegalArgumentException::class.java) { DocsAssets.run("review", images, review) }
    }

    @Test
    fun `review rejects wrong width height nonindexed images and oversized palettes`() {
        val images = images()
        val review = temporary.newFolder("bad review").toPath()
        val home = images.resolve("home.png")
        for (image in listOf(
            indexed(1, 960),
            indexed(540, 1),
            BufferedImage(540, 960, BufferedImage.TYPE_INT_RGB),
            BufferedImage(540, 960, BufferedImage.TYPE_BYTE_INDEXED).apply {
                for (x in 0..96) raster.setSample(x, 0, 0, x)
            },
        )) {
            save(image, home)
            assertThrows(IllegalArgumentException::class.java) { DocsAssets.run("review", images, review) }
        }
    }

    private fun images(): Path =
        temporary.newFolder("images").toPath().also { images ->
            languages.forEach { language ->
                val directory = if (language == "en") images else images.resolve(language)
                save(indexed(540, 960), directory.resolve("home.png"))
                save(indexed(480, 800), directory.resolve("sale-ams1.png"))
                save(indexed(1200, 630), directory.resolve("social.png"))
            }
        }

    private fun indexed(
        width: Int,
        height: Int,
    ): BufferedImage =
        DocsScreenshots.quantize(
            BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, 0x00843F) },
            96,
        )

    private fun save(
        image: BufferedImage,
        path: Path,
    ) {
        Files.createDirectories(path.parent)
        ImageIO.write(image, "png", path.toFile())
    }
}
