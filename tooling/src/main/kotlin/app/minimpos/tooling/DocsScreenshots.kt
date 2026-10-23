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
 * Turns raw docs emulator captures into frame-sized screenshots: S1F2 720×1280 becomes 540×960; AMS1 stays 480×800.
 * Adyen terminals show no system bars, so the app fills their whole screen. The docs emulator reproduces that with a
 * logical display taller than the terminal's by exactly one status bar and one gesture bar: the app lays out between
 * the bars as it would on the terminal, and this class crops both bars away. The image is then scaled down smoothly
 * and saved as an indexed PNG of at most [COLOURS] colours (median cut, no dithering).
 */
internal object DocsScreenshots {
    /** The raw capture width of the S1F2-sized docs emulator (`wm size 720x1376`). */
    const val RAW_WIDTH = 720

    /** The raw capture height of the S1F2-sized docs emulator: the 1280 px terminal screen plus both [BAR]s. */
    const val RAW_HEIGHT = 1376

    /** The saved size, matching the S1F2 terminal frame's screen. */
    const val WIDTH = 540

    /** See [WIDTH]. */
    const val HEIGHT = 960

    /** AMS1 raw and saved width in pixels; this capture is not resized. */
    const val AMS1_WIDTH = 480

    /** The saved AMS1 height in pixels, matching the AMS1 terminal frame's screen. */
    const val AMS1_HEIGHT = 800

    /** The raw AMS1 capture height (`wm size 480x872`): the saved height plus both [AMS1_BAR]s. */
    const val AMS1_RAW_HEIGHT = 872

    /** The status and gesture bar height in S1F2 raw pixels (24 dp at 320 dpi). */
    const val BAR = 48

    /** The status and gesture bar height in AMS1 raw pixels (24 dp at 240 dpi). */
    const val AMS1_BAR = 36

    /** The palette size of a saved screenshot. */
    const val COLOURS = 96

    private val LANGUAGES = setOf("zh-CN", "zh-Hant", "ja", "pt-BR", "es-MX", "fr-FR")

    /** Red, green and blue, as [channel] numbers them. */
    private val CHANNELS = 0..2

    private const val CHANNEL_MASK = 0xFF

    private const val RGB_MASK = 0xFFFFFF

    /**
     * Processes every `<language>-<name>.png` in [raw] (any supported app language) into
     * `[images]/<name>.png` for English, else `[images]/<language>/<name>.png`, and returns the paths written.
     *
     * @throws IllegalArgumentException for a file of another name or size.
     */
    fun processAll(
        raw: Path,
        images: Path,
    ): List<Path> =
        raw
            .listDirectoryEntries()
            .filter { it.extension == "png" }
            .also { require(it.isNotEmpty()) { "No raw PNG captures to process" } }
            .sorted()
            .map { file ->
                val name = file.nameWithoutExtension
                val language = (LANGUAGES + "en").firstOrNull { name.startsWith("$it-") }
                requireNotNull(language) { "Expected <language>-<name>.png: $name" }
                val target = (if (language == "en") images else images.resolve(language)).resolve(name.removePrefix("$language-") + ".png")
                val image = requireNotNull(ImageIO.read(file.toFile())) { "Not a PNG: $name" }
                val ams1 = name.endsWith("-sale-ams1")
                require(
                    image.width == (if (ams1) AMS1_WIDTH else RAW_WIDTH) && image.height == (if (ams1) AMS1_RAW_HEIGHT else RAW_HEIGHT),
                ) {
                    "Capture dimensions do not match its terminal: $name"
                }
                Files.createDirectories(target.parent)
                ImageIO.write(process(image), "png", target.toFile())
                target
            }

    /** One raw capture as a finished screenshot; see [DocsScreenshots]. */
    fun process(raw: BufferedImage): BufferedImage {
        val ams1 = raw.width == AMS1_WIDTH && raw.height == AMS1_RAW_HEIGHT
        require(ams1 || (raw.width == RAW_WIDTH && raw.height == RAW_HEIGHT)) { "Expected a 720x1376 or 480x872 capture" }
        val bar = if (ams1) AMS1_BAR else BAR
        val screen = BufferedImage(raw.width, raw.height - 2 * bar, BufferedImage.TYPE_INT_RGB)
        val graphics = screen.createGraphics()
        graphics.drawImage(raw, 0, -bar, null)
        graphics.dispose()
        return quantize(if (ams1) screen else scale(screen, WIDTH, HEIGHT), COLOURS)
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

    /** [image] with at most [colours] median-cut colours, population-weighted centroids and nearest, undithered pixels. */
    fun quantize(
        image: BufferedImage,
        colours: Int,
    ): BufferedImage {
        val pixels = IntArray(image.width * image.height) { image.getRGB(it % image.width, it / image.width) and RGB_MASK }
        val palette = medianCut(pixels.asIterable().groupingBy { it }.eachCount(), colours)
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
        colours: Map<Int, Int>,
        count: Int,
    ): List<Int> {
        val boxes = mutableListOf(colours.keys.toList())
        while (boxes.size < count) {
            val box = boxes.filter { it.size > 1 }.maxByOrNull { range(it).second } ?: break
            val channel = range(box).first
            val sorted = box.sortedBy { channel(it, channel) }
            boxes.remove(box)
            boxes += sorted.subList(0, sorted.size / 2)
            boxes += sorted.subList(sorted.size / 2, sorted.size)
        }
        return boxes.map { box ->
            val population = box.sumOf { colours.getValue(it).toLong() }
            CHANNELS.fold(0) { rgb, c ->
                val average = box.sumOf { channel(it, c).toLong() * colours.getValue(it) } / population
                rgb or (average.toInt() shl shift(c))
            }
        }
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
