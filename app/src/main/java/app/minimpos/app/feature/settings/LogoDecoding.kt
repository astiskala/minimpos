package app.minimpos.app.feature.settings

import android.content.ContentResolver
import android.graphics.ColorSpace
import android.graphics.ColorSpace.Named
import android.graphics.ImageDecoder
import android.net.Uri
import app.minimpos.core.logo.ArgbImage
import java.io.IOException

/**
 * Decodes a local image as non-premultiplied sRGB pixels, bounded to 4096 pixels on either side.
 * Oversized sources use the platform's premultiplied scaler, then [android.graphics.Bitmap.getPixels] returns straight
 * ARGB. Smaller sources decode unpremultiplied to preserve exact channel values. Call on a worker dispatcher.
 * Unreadable or unsupported sources return null; the source is never changed.
 */
internal fun decodeLogo(
    resolver: ContentResolver,
    uri: Uri,
): ArgbImage? =
    try {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetColorSpace(ColorSpace.get(Named.SRGB))
                val longest = maxOf(info.size.width, info.size.height)
                // Android cannot scale an unpremultiplied image. Exact parity excludes oversized sources.
                decoder.isUnpremultipliedRequired = longest <= MAX_SOURCE_SIDE
                if (longest > MAX_SOURCE_SIDE) {
                    decoder.setTargetSize(
                        maxOf(1, (info.size.width.toLong() * MAX_SOURCE_SIDE / longest).toInt()),
                        maxOf(1, (info.size.height.toLong() * MAX_SOURCE_SIDE / longest).toInt()),
                    )
                }
            }
        try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArgbImage(bitmap.width, bitmap.height, pixels)
        } finally {
            bitmap.recycle()
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } catch (_: SecurityException) {
        null
    }

private const val MAX_SOURCE_SIDE = 4096
