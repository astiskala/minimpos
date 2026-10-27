package app.minimpos.app.data.repo

import app.minimpos.app.data.settings.ReceiptLogoStore
import app.minimpos.core.logo.ArgbImage
import app.minimpos.core.logo.LogoConverter
import app.minimpos.core.logo.LogoResult
import app.minimpos.core.logo.MonoBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The text-free café mark sample data puts on receipts: [image] converted with the logo editor's default options, as
 * choosing it in Settings › Receipts and saving would. It never replaces a merchant's logo: it is added only while
 * none is saved and removed only while the saved logo is still exactly this mark.
 *
 * @param store The receipt logo it adds to and removes from.
 * @param image Decodes the bundled source image; null when unreadable, which adds and removes nothing.
 * @param cpu Dispatcher for decoding and conversion.
 */
class SampleLogo(
    private val store: ReceiptLogoStore,
    private val image: () -> ArgbImage?,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) {
    /** The converted mark; null when the source is unreadable or blank. */
    suspend fun mark(): MonoBitmap? = withContext(cpu) { image()?.let { (LogoConverter.convert(it) as? LogoResult.Converted)?.bitmap } }

    /** Saves the mark unless a logo is already saved. */
    suspend fun add() {
        mark()?.let { store.replaceIf(expected = null, logo = it) }
    }

    /** Removes the saved logo only while it is still the unchanged mark. */
    suspend fun remove() {
        mark()?.let { store.replaceIf(expected = it, logo = null) }
    }
}
