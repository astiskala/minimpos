package app.minimpos.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.minimpos.core.logo.MonoBitmap

/** Shows [bitmap] centred on a white receipt of 384-pixel logical width, without smoothing the printer's dots. */
@Composable
fun ReceiptLogoPreview(
    bitmap: MonoBitmap,
    modifier: Modifier = Modifier,
) {
    val image =
        remember(bitmap) {
            val pixels =
                IntArray(bitmap.width * bitmap.height) { index ->
                    if (bitmap.isBlack(index % bitmap.width, index / bitmap.width)) {
                        android.graphics.Color.BLACK
                    } else {
                        android.graphics.Color.WHITE
                    }
                }
            Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }
    Canvas(modifier.fillMaxWidth().aspectRatio(384f / bitmap.height).background(Color.White)) {
        val width = (bitmap.width * size.width / 384).toInt().coerceAtLeast(1)
        drawImage(
            image,
            dstOffset = IntOffset(((size.width - width) / 2).toInt(), 0),
            dstSize = IntSize(width, size.height.toInt().coerceAtLeast(1)),
            filterQuality = FilterQuality.None,
        )
    }
}
