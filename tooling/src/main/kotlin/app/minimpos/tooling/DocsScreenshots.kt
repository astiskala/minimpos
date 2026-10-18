package app.minimpos.tooling

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.IndexColorModel
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * Turns raw 720×1280 docs emulator captures into the website's 540×960 screenshots: the status and gesture bars are
 * painted with the background of the app's adjacent row (Adyen terminals show neither), the image is scaled down
 * smoothly and saved as an indexed PNG of at most [COLOURS] colours (median cut, no dithering).
 */
internal object DocsScreenshots {
    /** The capture size of the S1F2-sized docs emulator. */
    const val RAW_WIDTH = 720

    /** See [RAW_WIDTH]. */
    const val RAW_HEIGHT = 1280

    /** The saved size, matching the S1F2 terminal frame's screen. */
    const val WIDTH = 540

    /** See [WIDTH]. */
    const val HEIGHT = 960

    /** The status and gesture bar height in raw pixels. */
    const val BAR = 48

    /** The palette size of a saved screenshot. */
    const val COLOURS = 96

    private val LANGUAGES = setOf("zh-CN", "zh-Hant", "ja")

    /** Red, green and blue, as [channel] numbers them. */
    private val CHANNELS = 0..2

    private const val CHANNEL_MASK = 0xFF

    private const val RGB_MASK = 0xFFFFFF

    /**
     * Processes every `<language>-<name>.png` in [raw] (language `en`, `zh-CN`, `zh-Hant` or `ja`) into
     * `[images]/<name>.png` for English, else `[images]/<language>/<name>.png`, and returns the paths written.
     *
     * @throws IllegalArgumentException for a file of another name or size.
     */
    fun processAll(
        raw: Path,
        images: Path,
    ): List<Path> =
        raw.listDirectoryEntries().filter { it.extension == "png" }.sorted().map { file ->
            val name = file.nameWithoutExtension
            val language = (LANGUAGES + "en").firstOrNull { name.startsWith("$it-") }
            requireNotNull(language) { "Expected <en|zh-CN|zh-Hant|ja>-<name>.png: $name" }
            val target = (if (language == "en") images else images.resolve(language)).resolve(name.removePrefix("$language-") + ".png")
            val image = requireNotNull(ImageIO.read(file.toFile())) { "Not a PNG: $name" }
            Files.createDirectories(target.parent)
            ImageIO.write(process(image), "png", target.toFile())
            target
        }

    /** One raw capture as a finished screenshot; see [DocsScreenshots]. */
    fun process(raw: BufferedImage): BufferedImage {
        require(raw.width == RAW_WIDTH && raw.height == RAW_HEIGHT) { "Expected a ${RAW_WIDTH}x$RAW_HEIGHT capture" }
        val painted = BufferedImage(RAW_WIDTH, RAW_HEIGHT, BufferedImage.TYPE_INT_RGB)
        val top = background(raw, BAR)
        val bottom = background(raw, RAW_HEIGHT - BAR - 1)
        for (x in 0..<RAW_WIDTH) {
            for (y in 0..<RAW_HEIGHT) {
                painted.setRGB(
                    x,
                    y,
                    if (y < BAR) {
                        top
                    } else if (y >= RAW_HEIGHT - BAR) {
                        bottom
                    } else {
                        raw.getRGB(x, y)
                    },
                )
            }
        }
        return quantize(scale(painted, WIDTH, HEIGHT), COLOURS)
    }

    /** The most common colour of row [y]: the screen's background there, so content running under a bar is cut cleanly. */
    private fun background(
        image: BufferedImage,
        y: Int,
    ): Int {
        val counts = HashMap<Int, Int>()
        for (x in 0..<image.width) counts.merge(image.getRGB(x, y), 1, Int::plus)
        return Collections.max(counts.entries, compareBy { it.value }).key
    }

    /** [image] scaled to [width]×[height] with bicubic interpolation, so text stays crisp. */
    fun scale(
        image: BufferedImage,
        width: Int,
        height: Int,
    ): BufferedImage {
        val scaled = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        graphics.drawImage(image, 0, 0, width, height, null)
        graphics.dispose()
        return scaled
    }

    /** [image] with at most [colours] colours chosen by median cut, each pixel mapped to its nearest, undithered. */
    fun quantize(
        image: BufferedImage,
        colours: Int,
    ): BufferedImage {
        val pixels = IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) and RGB_MASK }
        val palette = medianCut(pixels.distinct(), colours)
        val model =
            IndexColorModel(
                8,
                palette.size,
                ByteArray(palette.size) { channel(palette[it], 0).toByte() },
                ByteArray(palette.size) { channel(palette[it], 1).toByte() },
                ByteArray(palette.size) { channel(palette[it], 2).toByte() },
            )
        val indexed = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_INDEXED, model)
        val nearest = HashMap<Int, Int>()
        pixels.forEachIndexed { i, rgb ->
            val index = nearest.getOrPut(rgb) { palette.indices.minBy { distance(palette[it], rgb) } }
            indexed.raster.setSample(i % image.width, i / image.width, 0, index)
        }
        return indexed
    }

    private fun medianCut(
        colours: List<Int>,
        count: Int,
    ): List<Int> {
        val boxes = mutableListOf(colours)
        while (boxes.size < count) {
            val box = boxes.filter { it.size > 1 }.maxByOrNull { range(it).second } ?: break
            val channel = range(box).first
            val sorted = box.sortedBy { channel(it, channel) }
            boxes.remove(box)
            boxes += sorted.subList(0, sorted.size / 2)
            boxes += sorted.subList(sorted.size / 2, sorted.size)
        }
        return boxes.map { box -> CHANNELS.fold(0) { rgb, c -> rgb or (box.sumOf { channel(it, c) } / box.size shl shift(c)) } }
    }

    /** The channel (0 red, 1 green, 2 blue) with the widest spread in [box], and that spread. */
    private fun range(box: List<Int>): Pair<Int, Int> =
        Collections.max(
            CHANNELS.map { c -> box.map { channel(it, c) }.let { values -> c to values.max() - values.min() } },
            compareBy { it.second },
        )

    private fun channel(
        rgb: Int,
        c: Int,
    ) = rgb shr shift(c) and CHANNEL_MASK

    /** How far channel [c] (0 red, 1 green, 2 blue) sits from the low end of a 0xRRGGBB colour. */
    private fun shift(c: Int) = (CHANNELS.last - c) * Byte.SIZE_BITS

    private fun distance(
        a: Int,
        b: Int,
    ) = CHANNELS.sumOf { c -> (channel(a, c) - channel(b, c)).let { it * it } }
}
