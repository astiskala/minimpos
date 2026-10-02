package io.minimpos.app.share

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.minimpos.app.forgetSharedFileRoots
import io.minimpos.core.receipt.Align
import io.minimpos.core.receipt.ReceiptDocument
import io.minimpos.core.receipt.ReceiptElement
import io.minimpos.core.receipt.TextStyle
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ShareSheetTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = forgetSharedFileRoots()

    private val receipt =
        ReceiptDocument(
            listOf(
                ReceiptElement.Text("Corner Cafe", Align.CENTER, TextStyle.BOLD),
                ReceiptElement.Text("Thanks", Align.RIGHT, TextStyle.UNDERLINE),
                ReceiptElement.Row("Flat white", "$9.00"),
                ReceiptElement.Row("A label beside a value far too wide to share its line", "$1,234,567,890.00 AUD due today"),
                ReceiptElement.Divider,
                ReceiptElement.Blank,
                ReceiptElement.Qr("https://test.adyen.link/PL1", "Scan to pay"),
                ReceiptElement.Qr("MPR1*x"),
                ReceiptElement.Link("https://test.adyen.link/PL1", "Pay now"),
            ),
        )

    @Test
    fun `a receipt is drawn as a slip of the requested width, as tall as its content`() {
        val image = ReceiptImage.render(receipt)
        assertThat(image.width).isEqualTo(ReceiptImage.DEFAULT_WIDTH_PX)
        assertThat(image.height).isGreaterThan(ReceiptImage.render(ReceiptDocument(receipt.elements.take(1))).height)
        assertThat(ReceiptImage.render(ReceiptDocument(emptyList()), widthPx = 288).width).isEqualTo(288)
    }

    @Test
    fun `the image is written to the shared folder alone, and handed to the share sheet with a read grant`() {
        val stale = File(context.cacheDir, "shared/old.png").apply { parentFile?.mkdirs() }
        stale.writeText("old")
        val file = ShareSheet.write(context, ReceiptImage.render(receipt), "MP 1/2")
        assertThat(file.name).isEqualTo("MP_1_2.png")
        assertThat(file.length()).isGreaterThan(0L)
        assertThat(stale.exists()).isFalse()

        ShareSheet.open(context, file, "Payment request MP-1", "Pay $9.00 securely online: https://test.adyen.link/PL1", "Share with")
        val chooser = shadowOf(context as Application).nextStartedActivity
        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        val send = checkNotNull(IntentCompat.getParcelableExtra(chooser, Intent.EXTRA_INTENT, Intent::class.java))
        assertThat(send.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(send.type).isEqualTo("image/png")
        assertThat(send.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("Payment request MP-1")
        assertThat(send.getStringExtra(Intent.EXTRA_TEXT)).contains("https://test.adyen.link/PL1")
        assertThat(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        val uri = checkNotNull(IntentCompat.getParcelableExtra(send, Intent.EXTRA_STREAM, Uri::class.java))
        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo(context.packageName + ".share")
        assertThat(send.clipData!!.getItemAt(0).uri).isEqualTo(uri)
    }
}
