package io.minimpos.app.share

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import io.minimpos.app.R
import io.minimpos.app.payment.SharedReceipt
import io.minimpos.app.ui.components.rememberMoneyFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Hands receipts and payment links to Android's share sheet: the receipt as a PNG image (through the app's
 * `FileProvider`, which grants the receiving app read access to that one file) with a subject and a message. Only
 * offered on phones and tablets; an Adyen terminal emails instead.
 */
object ShareSheet {
    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    /** The `FileProvider` authority after the application ID, as the manifest declares it. */
    private const val AUTHORITY_SUFFIX = ".share"

    /** The cache folder shared images are written to (`res/xml/shared_files.xml`); emptied before each share. */
    private const val DIRECTORY = "shared"

    private const val PNG_QUALITY = 100

    /**
     * Writes [image] as `<name>.png` (unsafe characters replaced) to the shared cache folder and returns the file. Blocks:
     * call it off the main thread.
     */
    fun write(
        context: Context,
        image: Bitmap,
        name: String,
    ): File {
        val folder = File(context.cacheDir, DIRECTORY).apply { mkdirs() }
        folder.listFiles()?.forEach { it.delete() }
        val file = File(folder, name.replace(UNSAFE, "_") + ".png")
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
        return file
    }

    /** Opens the share sheet, titled [chooserTitle], for [file] (a PNG [write] made) with [subject] and [text]. */
    fun open(
        context: Context,
        file: File,
        subject: String,
        text: String,
        chooserTitle: String,
    ) {
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val send =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, text)
                // The clip carries the read grant to the chosen app, as Intent.EXTRA_STREAM alone does not.
                clipData = ClipData.newRawUri(subject, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        val chooser = Intent.createChooser(send, chooserTitle)
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

/**
 * Shares [request], when there is one, through the share sheet: the receipt as an image (drawn and written on [io]),
 * with the payment link in the message when the sale still awaits its payment through it, then calls [onDone] (also
 * when it could not be shared), so it is shared once.
 */
@Composable
fun ShareEffect(
    request: SharedReceipt?,
    onDone: () -> Unit,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val done by rememberUpdatedState(onDone)
    request ?: return
    val context = LocalContext.current
    val amount = rememberMoneyFormatter(request.currency).format(request.amountMinor)
    val link = request.paymentLink
    val subject =
        if (link != null) {
            stringResource(R.string.share_link_subject, request.reference)
        } else {
            stringResource(R.string.share_receipt_subject, request.reference)
        }
    val text = if (link != null) stringResource(R.string.share_link_text, amount, link) else subject
    val chooser = stringResource(R.string.share_chooser)
    LaunchedEffect(request) {
        try {
            val file =
                withContext(io) {
                    val image = ReceiptImage.render(request.document)
                    ShareSheet.write(context, image, request.reference).also { image.recycle() }
                }
            ShareSheet.open(context, file, subject, text, chooser)
        } catch (ignored: IOException) {
            // The cache is full or gone: the share sheet does not open, and the button can be tapped again.
        } finally {
            done()
        }
    }
}
