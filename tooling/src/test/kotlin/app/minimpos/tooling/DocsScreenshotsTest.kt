package app.minimpos.tooling

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import javax.imageio.ImageIO

class DocsScreenshotsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun capture(): BufferedImage =
        BufferedImage(DocsScreenshots.RAW_WIDTH, DocsScreenshots.RAW_HEIGHT, BufferedImage.TYPE_INT_RGB).apply {
            for (x in 0..<width) {
                for (y in 0..<height) {
                    val rgb =
                        when {
                            y < DocsScreenshots.BAR || y >= height - DocsScreenshots.BAR -> 0x000000
                            x < width / 2 -> 0x0ABF53
                            else -> (x * 255 / width shl 16) or (y * 255 / height shl 8) or 0x40
                        }
                    setRGB(x, y, rgb)
                }
            }
        }

    @Test
    fun `empty capture folders cannot silently reuse old website images`() {
        val raw = folder.newFolder("empty").toPath()
        val images = folder.newFolder("unchanged").toPath()
        val result = Cli.run("screenshots", raw.toString(), images.toString())
        assertThat(result.code).isEqualTo(1)
        assertThat(result.output).contains("No raw PNG captures")
    }

    @Test
    fun `palette centroids weight pixel population so rare antialiasing colours cannot wash out brand colours`() {
        val image = BufferedImage(100, 1, BufferedImage.TYPE_INT_RGB)
        for (x in 0..<100) image.setRGB(x, 0, 0x00112C)
        image.setRGB(99, 0, 0xFFFFFF)
        assertThat(DocsScreenshots.quantize(image, 1).getRGB(0, 0) and 0xFFFFFF).isEqualTo(0x02132E)
    }

    @Test
    fun `system bars take the adjacent rows' background and the result is a small indexed frame-sized image`() {
        val shot = DocsScreenshots.process(capture())
        assertThat(shot.width).isEqualTo(DocsScreenshots.WIDTH)
        assertThat(shot.height).isEqualTo(DocsScreenshots.HEIGHT)
        val model = shot.colorModel as IndexColorModel
        assertThat(model.mapSize).isAtMost(DocsScreenshots.COLOURS)
        // The black bars are gone: across their whole width they show the adjacent row's background, the app's green.
        listOf(0, DocsScreenshots.HEIGHT - 1).forEach { y ->
            listOf(5, DocsScreenshots.WIDTH - 5).forEach { x ->
                val rgb = shot.getRGB(x, y)
                assertThat(rgb shr 16 and 0xFF).isLessThan(0x20)
                assertThat(rgb shr 8 and 0xFF).isGreaterThan(0xA0)
            }
        }
    }

    @Test
    fun `quantizing keeps few colours exactly`() {
        val image = BufferedImage(4, 1, BufferedImage.TYPE_INT_RGB)
        listOf(0xFFFFFF, 0x00112C, 0x0ABF53, 0xFFFFFF).forEachIndexed { x, rgb -> image.setRGB(x, 0, rgb) }
        val quantized = DocsScreenshots.quantize(image, 96)
        assertThat(
            (0..<4).map { quantized.getRGB(it, 0) and 0xFFFFFF },
        ).containsExactly(0xFFFFFF, 0x00112C, 0x0ABF53, 0xFFFFFF).inOrder()
    }

    @Test
    fun `AMS1 removes 36px status and 72px navigation bars without resizing or losing terminal identity`() {
        val image = BufferedImage(480, 800, BufferedImage.TYPE_INT_RGB)
        for (y in 36..<728) for (x in 0..<480) image.setRGB(x, y, 0x00843F)
        val processed = DocsScreenshots.process(image)
        assertThat(processed.width).isEqualTo(480)
        assertThat(processed.height).isEqualTo(800)
        assertThat(processed.getRGB(0, 0) and 0xFFFFFF).isEqualTo(0x00843F)
        assertThat(processed.getRGB(479, 799) and 0xFFFFFF).isEqualTo(0x00843F)
        val raw = folder.newFolder("ams1").toPath()
        val images = folder.newFolder("ams1-output").toPath()
        ImageIO.write(image, "png", raw.resolve("en-sale-ams1.png").toFile())
        assertThat(DocsScreenshots.processAll(raw, images)).containsExactly(images.resolve("sale-ams1.png"))
        ImageIO.write(image, "png", raw.resolve("en-home.png").toFile())
        assertThrows(IllegalArgumentException::class.java) { DocsScreenshots.processAll(raw, images) }
        assertThrows(IllegalArgumentException::class.java) { DocsScreenshots.process(BufferedImage(480, 10, BufferedImage.TYPE_INT_RGB)) }
    }

    @Test
    fun `captures are saved by language and other names or sizes are refused`() {
        val raw = folder.newFolder("raw").toPath()
        val images = folder.newFolder("images").toPath()
        ImageIO.write(capture(), "png", raw.resolve("en-tax.png").toFile())
        ImageIO.write(capture(), "png", raw.resolve("zh-Hant-tax.png").toFile())
        val written = DocsScreenshots.processAll(raw, images)
        assertThat(written).containsExactly(images.resolve("tax.png"), images.resolve("zh-Hant/tax.png"))
        assertThat(ImageIO.read(images.resolve("zh-Hant/tax.png").toFile()).width).isEqualTo(DocsScreenshots.WIDTH)
        ImageIO.write(capture(), "png", raw.resolve("fr-tax.png").toFile())
        assertThrows(IllegalArgumentException::class.java) { DocsScreenshots.processAll(raw, images) }
        listOf(10 to 10, DocsScreenshots.RAW_WIDTH to 10).forEach { (width, height) ->
            assertThrows(
                IllegalArgumentException::class.java,
            ) { DocsScreenshots.process(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)) }
        }
    }

    @Test
    fun `the tooling command processes a folder, ignores other files and refuses broken captures`() {
        val raw = folder.newFolder("cli-raw").toPath()
        val images = folder.newFolder("cli-images").toPath()
        ImageIO.write(capture(), "png", raw.resolve("ja-home.png").toFile())
        raw.resolve("notes.txt").toFile().writeText("not a capture")
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        assertThat(Tooling.run(listOf("screenshots", raw.toString(), images.toString()), PrintStream(out), PrintStream(err))).isEqualTo(0)
        assertThat(out.toString(Charsets.UTF_8)).contains("ja/home.png")
        assertThat(Tooling.run(listOf("screenshots", raw.toString()), PrintStream(out), PrintStream(err))).isEqualTo(1)
        raw.resolve("en-broken.png").toFile().writeText("not an image")
        assertThat(Tooling.run(listOf("screenshots", raw.toString(), images.toString()), PrintStream(out), PrintStream(err))).isEqualTo(1)
        assertThat(err.toString(Charsets.UTF_8)).contains("Not a PNG: en-broken")
    }
}
