package app.minimpos.core.logo

private const val CHANNEL_MAX = 255
private const val MAX_SOURCE_SIDE = 4096
private const val LUMA_RED = 54
private const val LUMA_GREEN = 183
private const val LUMA_BLUE = 19
private const val HALF_CHANNEL_RANGE = 128
private const val CHANNEL_BITS = 8
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val DEFAULT_THRESHOLD = 127
private const val PERCENT = 100
private const val TRANSPARENT_PERCENT = 10
private const val CHANNEL_VALUES = 256
private const val LAST_OTSU_THRESHOLD = 254
private const val PACKING_ROUNDING = 7

/** Quantization style; [AUTO] selects from the scaled grayscale histogram. */
enum class LogoMode {
    /** Classify the image as a mark or photograph. */
    AUTO,

    /** Threshold without error diffusion. */
    MARK,

    /** Atkinson error diffusion. */
    PHOTO,
}

/** Receipt image height bound, in pixels. Width is always at most 384 pixels. */
enum class LogoSize(
    /** Maximum height in pixels. */
    val maxHeight: Int,
) {
    /** Default height bound. */
    LARGE(MonoBitmap.MAX_HEIGHT),

    /** Compact height bound. */
    SMALL(MonoBitmap.MAX_HEIGHT / 2),
}

/**
 * Conversion choices, independent of platform decoding.
 *
 * @property mode Quantization style.
 * @property threshold Inclusive black threshold, 0 through 255; null selects Otsu or 127.
 * @property invert Whether to invert ink; null detects light ink or a dark background.
 * @property trim Remove margins whose grayscale value is at least 240.
 * @property size Output height bound; images are never enlarged.
 */
data class LogoOptions(
    val mode: LogoMode = LogoMode.AUTO,
    val threshold: Int? = null,
    val invert: Boolean? = null,
    val trim: Boolean = true,
    val size: LogoSize = LogoSize.LARGE,
) {
    init {
        require(threshold == null || threshold in 0..CHANNEL_MAX)
    }
}

/**
 * Immutable decoded, non-premultiplied sRGB pixels. Invalid dimensions or pixel count throw [IllegalArgumentException].
 *
 * @property width Width in pixels, 1 through 4096.
 * @property height Height in pixels, 1 through 4096.
 * @param argb Row-major packed 0xAARRGGBB pixels, copied at construction.
 */
class ArgbImage(
    val width: Int,
    val height: Int,
    argb: IntArray,
) {
    private val pixels = argb.copyOf()

    init {
        require(width in 1..MAX_SOURCE_SIDE && height in 1..MAX_SOURCE_SIDE)
        require(pixels.size == width * height)
    }

    /** Returns an independent copy of the row-major pixels. */
    fun argb(): IntArray = pixels.copyOf()
}

/** Conversion result; a source with no visible ink is [Blank]. */
sealed interface LogoResult {
    /**
     * Converted receipt image and resolved controls.
     *
     * @property bitmap Packed black/white image.
     * @property mode Resolved style, always MARK or PHOTO.
     * @property threshold Inclusive black threshold, 0 through 255.
     * @property inverted Resolved ink inversion.
     */
    data class Converted(
        val bitmap: MonoBitmap,
        val mode: LogoMode,
        val threshold: Int,
        val inverted: Boolean,
    ) : LogoResult

    /** No source pixels were darker than the trim cut-off after compositing. */
    data object Blank : LogoResult
}

/** Integer-exact receipt conversion, mirrored by docs/js/logo.js; safe for concurrent calls, CPU intensive. */
object LogoConverter {
    // Auto-invert light opaque ink when the source has substantial transparency.
    private const val INVERT_INK_LUMA = 160

    // Auto-invert an opaque source with a dark outer border.
    private const val INVERT_BORDER_LUMA = 96

    // Pixels at or above this brightness are plain margins.
    private const val TRIM_LUMA = 240

    // Intermediate grayscale pixels identify photographic content.
    private const val MID_LOW = 32
    private const val MID_HIGH = 224

    // More than this percentage of intermediate pixels selects photographs.
    private const val PHOTO_MID_PERCENT = 20

    /** Converts bounded decoded pixels; callers should run this CPU work off their UI thread. */
    fun convert(
        image: ArgbImage,
        options: LogoOptions = LogoOptions(),
    ): LogoResult {
        val grey = image.argb()
        val inverted = options.invert ?: detectInversion(image, grey)
        grey.indices.forEach { grey[it] = composite(grey[it], inverted) }
        var left = image.width
        var right = -1
        var top = image.height
        var bottom = -1
        grey.forEachIndexed { index, value ->
            if (value < TRIM_LUMA) {
                left = minOf(left, index % image.width)
                right = maxOf(right, index % image.width)
                top = minOf(top, index / image.width)
                bottom = maxOf(bottom, index / image.width)
            }
        }
        if (right < 0) return LogoResult.Blank
        if (!options.trim) {
            left = 0
            top = 0
            right = image.width - 1
            bottom = image.height - 1
        }
        val width = right - left + 1
        val height = bottom - top + 1
        val targetWidth: Int
        val targetHeight: Int
        if (width * options.size.maxHeight >= height * MonoBitmap.MAX_WIDTH) {
            targetWidth = minOf(width, MonoBitmap.MAX_WIDTH)
            targetHeight = maxOf(1, (height * targetWidth + width / 2) / width)
        } else {
            targetHeight = minOf(height, options.size.maxHeight)
            targetWidth = maxOf(1, (width * targetHeight + height / 2) / height)
        }
        val scaled = scale(grey, image.width, Crop(left, top, width, height), targetWidth, targetHeight)
        val mode =
            if (options.mode != LogoMode.AUTO) {
                options.mode
            } else {
                if (scaled.count { it > MID_LOW && it < MID_HIGH } * PERCENT >
                    scaled.size * PHOTO_MID_PERCENT
                ) {
                    LogoMode.PHOTO
                } else {
                    LogoMode.MARK
                }
            }
        val threshold = options.threshold ?: if (mode == LogoMode.MARK) otsu(scaled) else DEFAULT_THRESHOLD
        return LogoResult.Converted(quantize(scaled, targetWidth, targetHeight, mode, threshold), mode, threshold, inverted)
    }

    private fun luma(
        r: Int,
        g: Int,
        b: Int,
    ): Int = (LUMA_RED * r + LUMA_GREEN * g + LUMA_BLUE * b + HALF_CHANNEL_RANGE) ushr CHANNEL_BITS

    private fun composite(
        pixel: Int,
        inverted: Boolean,
    ): Int {
        val alpha = pixel ushr ALPHA_SHIFT
        val background = if (inverted) 0 else CHANNEL_MAX * (CHANNEL_MAX - alpha)
        val r = (((pixel ushr RED_SHIFT) and CHANNEL_MAX) * alpha + background + DEFAULT_THRESHOLD) / CHANNEL_MAX
        val g = (((pixel ushr CHANNEL_BITS) and CHANNEL_MAX) * alpha + background + DEFAULT_THRESHOLD) / CHANNEL_MAX
        val b = ((pixel and CHANNEL_MAX) * alpha + background + DEFAULT_THRESHOLD) / CHANNEL_MAX
        val value = luma(r, g, b)
        return if (inverted) CHANNEL_MAX - value else value
    }

    private fun detectInversion(
        image: ArgbImage,
        pixels: IntArray,
    ): Boolean {
        val transparent = pixels.count { it ushr ALPHA_SHIFT < HALF_CHANNEL_RANGE }
        if (transparent.toLong() * PERCENT >= pixels.size.toLong() * TRANSPARENT_PERCENT) {
            var sum = 0L
            var count = 0
            pixels.forEach {
                if (it ushr ALPHA_SHIFT >= HALF_CHANNEL_RANGE) {
                    sum += composite(it, false)
                    count++
                }
            }
            return count > 0 && sum / count >= INVERT_INK_LUMA
        }
        var sum = 0L
        var count = 0
        pixels.forEachIndexed { index, pixel ->
            val x = index % image.width
            val y = index / image.width
            val verticalEdge = x == 0 || x == image.width - 1
            val horizontalEdge = y == 0 || y == image.height - 1
            if (verticalEdge || horizontalEdge) {
                sum += composite(pixel, false)
                count++
            }
        }
        return sum / count < INVERT_BORDER_LUMA
    }

    private class Crop(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    )

    private fun scale(
        source: IntArray,
        stride: Int,
        crop: Crop,
        tw: Int,
        th: Int,
    ): IntArray {
        val left = crop.left
        val top = crop.top
        val width = crop.width
        val height = crop.height
        val horizontal = LongArray(tw * height)
        repeat(height) { y ->
            repeat(tw) { x ->
                val start = x * width
                val end = (x + 1) * width
                var sum = 0L
                for (i in start / tw..<(end + tw - 1) / tw) {
                    val overlap = minOf(end, (i + 1) * tw) - maxOf(start, i * tw)
                    sum += overlap.toLong() * source[(top + y) * stride + left + i]
                }
                horizontal[y * tw + x] = sum
            }
        }
        val area = width.toLong() * height
        return IntArray(tw * th) { index ->
            val x = index % tw
            val y = index / tw
            val start = y * height
            val end = (y + 1) * height
            var sum = 0L
            for (j in start / th..<(end + th - 1) / th) {
                val overlap = minOf(end, (j + 1) * th) - maxOf(start, j * th)
                sum += overlap * horizontal[j * tw + x]
            }
            ((sum + area / 2) / area).toInt()
        }
    }

    private fun otsu(grey: IntArray): Int {
        val histogram = IntArray(CHANNEL_VALUES)
        grey.forEach { histogram[it]++ }
        var totalSum = 0L
        repeat(CHANNEL_VALUES) { totalSum += it.toLong() * histogram[it] }
        var count = 0
        var sum = 0L
        var best = -1.0
        var threshold = DEFAULT_THRESHOLD
        for (t in 0..LAST_OTSU_THRESHOLD) {
            count += histogram[t]
            sum += t.toLong() * histogram[t]
            val foreground = grey.size - count
            if (count > 0 && foreground > 0) {
                val difference = sum.toDouble() / count - (totalSum - sum).toDouble() / foreground
                val between = count.toDouble() * foreground * difference * difference
                if (between > best) {
                    best = between
                    threshold = t
                }
            }
        }
        return threshold
    }

    private fun quantize(
        values: IntArray,
        width: Int,
        height: Int,
        mode: LogoMode,
        threshold: Int,
    ): MonoBitmap {
        val stride = (width + PACKING_ROUNDING) / CHANNEL_BITS
        val bits = ByteArray(stride * height)

        fun spread(
            x: Int,
            y: Int,
            error: Int,
        ) {
            if (x in 0..<width && y in 0..<height) values[y * width + x] += error
        }
        values.indices.forEach { index ->
            val x = index % width
            val y = index / width
            val black = values[index] <= threshold
            if (black) {
                bits[y * stride + x / CHANNEL_BITS] =
                    (bits[y * stride + x / CHANNEL_BITS].toInt() or (HALF_CHANNEL_RANGE ushr (x % CHANNEL_BITS))).toByte()
            }
            if (mode == LogoMode.PHOTO) {
                val error = (values[index] - if (black) 0 else CHANNEL_MAX) / CHANNEL_BITS
                spread(x + 1, y, error)
                spread(x + 2, y, error)
                spread(x - 1, y + 1, error)
                spread(x, y + 1, error)
                spread(x + 1, y + 1, error)
                spread(x, y + 2, error)
            }
        }
        return MonoBitmap(width, height, bits)
    }
}
