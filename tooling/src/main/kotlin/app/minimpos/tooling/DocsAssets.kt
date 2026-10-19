package app.minimpos.tooling

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Color
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** Offline social-page generation, palette conversion and visual-review sheets; never drives a device or browser. */
internal object DocsAssets {
    private val languages = listOf("en", "zh-CN", "zh-Hant", "ja")
    private const val COLUMNS = 7
    private const val CELL_WIDTH = 170
    private const val CELL_HEIGHT = 280
    private const val LABEL_Y = 267
    private const val SOCIAL_WIDTH = 1200
    private const val SOCIAL_HEIGHT = 630

    /** Runs [mode] (`pages`, `social` or `review`) from [input] into [output], returning generated paths; malformed assets fail closed. */
    fun run(
        mode: String,
        input: Path,
        output: Path,
    ): List<Path> {
        require(mode in setOf("pages", "social", "review")) { "Expected docs-assets pages, social or review" }
        Files.createDirectories(output)
        return when (mode) {
            "pages" -> {
                pages(input, output)
            }

            "social" -> {
                languages.map { language ->
                    val raw = read(input.resolve("$language-social.png"))
                    require(raw.width == SOCIAL_WIDTH && raw.height == SOCIAL_HEIGHT) { "Expected 1200x630 social image: $language" }
                    save(DocsScreenshots.quantize(raw, DocsScreenshots.COLOURS), directory(output, language).resolve("social.png"))
                }
            }

            else -> {
                review(input, output)
            }
        }
    }

    private fun pages(
        root: Path,
        output: Path,
    ): List<Path> {
        val source = root.resolve(".devin/skills/docs-screenshots")
        val template = Files.readString(source.resolve("social.html"))
        val copy = Release.readJson(source.resolve("social-copy.json")) as JsonObject
        return languages.map { language ->
            val text = copy.getValue(language) as JsonObject
            val values =
                text.mapValues { it.value.jsonPrimitive.content } +
                    mapOf("docs" to root.resolve("docs").toUri().toASCIIString(), "images" to if (language == "en") "" else "$language/")
            val html = values.entries.fold(template) { page, (key, value) -> page.replace("{{$key}}", value) }
            require("{{" !in html) { "Unresolved social template field: $language" }
            output.resolve("$language.html").also { Files.writeString(it, html) }
        }
    }

    private fun review(
        images: Path,
        output: Path,
    ): List<Path> {
        val expected = images.listDirectoryEntries("*.png").map { it.fileName }.toSet()
        require(expected.map { it.toString() }.containsAll(listOf("home.png", "sale-ams1.png", "social.png"))) {
            "Missing English Home, AMS1 or social image"
        }
        return languages.map { language ->
            val files = directory(images, language).listDirectoryEntries("*.png").sorted()
            require(files.map { it.fileName }.toSet() == expected) { "Incomplete image set: $language" }
            val sheet =
                BufferedImage(COLUMNS * CELL_WIDTH, ((files.size + COLUMNS - 1) / COLUMNS) * CELL_HEIGHT, BufferedImage.TYPE_INT_RGB)
            val graphics = sheet.createGraphics()
            try {
                graphics.color = Color.LIGHT_GRAY
                graphics.fillRect(0, 0, sheet.width, sheet.height)
                files.forEachIndexed { index, path ->
                    val image = read(path)
                    val size =
                        when (path.nameWithoutExtension) {
                            "social" -> SOCIAL_WIDTH to SOCIAL_HEIGHT
                            "sale-ams1" -> DocsScreenshots.AMS1_WIDTH to DocsScreenshots.AMS1_HEIGHT
                            else -> DocsScreenshots.WIDTH to DocsScreenshots.HEIGHT
                        }
                    val palette = image.colorModel
                    // ImageIO pads decoded palette tables; count used colours, not those padded entries.
                    val colours =
                        image
                            .getRGB(0, 0, image.width, image.height, null, 0, image.width)
                            .asSequence()
                            .distinct()
                            .take(DocsScreenshots.COLOURS + 1)
                            .count()
                    require(
                        image.width == size.first && image.height == size.second &&
                            palette is IndexColorModel && colours <= DocsScreenshots.COLOURS,
                    ) { "Wrong frame dimensions or palette: $path (${image.width}x${image.height}, $colours used colours)" }
                    val ratio = minOf(150.0 / image.width, 250.0 / image.height)
                    val thumbnail = DocsScreenshots.scale(image, (image.width * ratio).toInt(), (image.height * ratio).toInt())
                    val x = (index % COLUMNS) * CELL_WIDTH
                    val y = (index / COLUMNS) * CELL_HEIGHT
                    graphics.drawImage(thumbnail, x, y, null)
                    graphics.color = Color.BLACK
                    graphics.drawString(path.nameWithoutExtension, x, y + LABEL_Y)
                }
            } finally {
                graphics.dispose()
            }
            save(sheet, output.resolve("$language-contact.png"))
        }
    }

    private fun directory(
        images: Path,
        language: String,
    ): Path = if (language == "en") images else images.resolve(language)

    private fun read(path: Path): BufferedImage = requireNotNull(ImageIO.read(path.toFile())) { "Not an image: $path" }

    private fun save(
        image: BufferedImage,
        path: Path,
    ): Path {
        Files.createDirectories(path.parent)
        ImageIO.write(image, "png", path.toFile())
        return path
    }
}
