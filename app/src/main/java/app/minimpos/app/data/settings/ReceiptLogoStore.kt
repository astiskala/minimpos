package app.minimpos.app.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import app.minimpos.core.logo.LogoFormatException
import app.minimpos.core.logo.MonoBitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.InputStream
import java.io.OutputStream

/**
 * The persisted receipt image.
 *
 * @property logo Final monochrome image; null when no logo has been chosen.
 */
data class ReceiptLogoFile(
    val logo: MonoBitmap? = null,
)

/** Versioned binary receipt-logo file; corrupt content is preserved by the protected DataStore. */
object ReceiptLogoSerializer : Serializer<ReceiptLogoFile> {
    private const val MAX_RECORD_BYTES = 10 * 1024

    override val defaultValue: ReceiptLogoFile = ReceiptLogoFile()

    override suspend fun readFrom(input: InputStream): ReceiptLogoFile {
        val version = input.read()
        if (version == -1) return defaultValue
        if (version != 1) throw CorruptionException("Unreadable receipt logo version")
        val buffer = ByteArray(MAX_RECORD_BYTES + 1)
        var count = 0
        while (count < buffer.size) {
            val read = input.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        if (count > MAX_RECORD_BYTES) throw CorruptionException("Oversize receipt logo")
        val bytes = buffer.copyOf(count)
        return decodeRecord(bytes)
    }

    private fun decodeRecord(bytes: ByteArray): ReceiptLogoFile =
        try {
            ReceiptLogoFile(MonoBitmap.fromBytes(bytes))
        } catch (failure: LogoFormatException) {
            throw CorruptionException("Unreadable receipt logo", failure)
        }

    override suspend fun writeTo(
        t: ReceiptLogoFile,
        output: OutputStream,
    ) {
        t.logo?.let {
            output.write(1)
            output.write(it.toBytes())
        }
    }
}

/**
 * Final monochrome receipt branding. Reads and writes are main-safe and serialized by [store]; storage errors
 * propagate through its StorageHealth protection without publishing an empty replacement.
 *
 * @param store Single-owner versioned file, protected by [StorageHealth].
 */
class ReceiptLogoStore(
    store: DataStore<ReceiptLogoFile>,
) {
    private val store = ObservedStore(store)

    /** The image now and after each replacement; null means receipts have no logo. */
    val logo: Flow<MonoBitmap?> = this.store.data.map { it.logo }

    /** The current stored image, or null when none has been chosen. */
    suspend fun current(): MonoBitmap? = logo.first()

    /** Replaces the image durably, or removes it when [logo] is null. */
    suspend fun replace(logo: MonoBitmap?) {
        store.update { ReceiptLogoFile(logo) }
    }
}
