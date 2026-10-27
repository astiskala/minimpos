package app.minimpos.app.data

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import app.minimpos.app.data.settings.decodeLogo
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogoDecodingTest {
    @get:Rule
    val directory = TemporaryFolder()

    @Test
    fun `oversize source decodes within 4096`() {
        val source = directory.newFile("large.png")
        val bitmap = createBitmap(8000, 2000)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val decoded = decodeLogo(RuntimeEnvironment.getApplication().contentResolver, Uri.fromFile(source))
        assertThat(decoded?.width).isEqualTo(4096)
        assertThat(decoded?.height).isEqualTo(1024)
    }

    @Test
    fun `bounded source preserves non-premultiplied alpha channels`() {
        val source = directory.newFile("alpha.png")
        val bitmap = createBitmap(2, 1)
        try {
            bitmap[0, 0] = 0x80ffffff.toInt()
            bitmap[1, 0] = 0
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
        val decoded = decodeLogo(RuntimeEnvironment.getApplication().contentResolver, Uri.fromFile(source))
        assertThat(decoded?.argb()).isEqualTo(intArrayOf(0x80ffffff.toInt(), 0))
    }

    @Test
    fun `invalid image file decodes to null`() {
        val source = directory.newFile("invalid.png")
        source.writeText("unreadable image")
        assertThat(decodeLogo(RuntimeEnvironment.getApplication().contentResolver, Uri.fromFile(source))).isNull()
    }
}
