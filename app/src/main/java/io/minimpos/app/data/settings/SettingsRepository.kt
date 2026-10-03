package io.minimpos.app.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * The current JSON format for settings, secrets and receipt lines. All default values are written; omitted values
 * take the current constructor baseline. Unknown fields are ignored, while malformed values remain corruption.
 */
internal val StorageJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/** DataStore serializer that stores [T] as JSON; unreadable files are reported as corruption so they get replaced. */
class JsonDataStoreSerializer<T>(
    private val serializer: KSerializer<T>,
    override val defaultValue: T,
) : Serializer<T> {
    override suspend fun readFrom(input: InputStream): T =
        try {
            StorageJson.decodeFromString(serializer, input.readBytes().decodeToString())
        } catch (e: SerializationException) {
            throw CorruptionException("Unreadable ${serializer.descriptor.serialName}", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Unreadable ${serializer.descriptor.serialName}", e)
        }

    override suspend fun writeTo(
        t: T,
        output: OutputStream,
    ) = output.write(StorageJson.encodeToString(serializer, t).toByteArray())
}

/**
 * The app's [AppSettings], persisted in DataStore and always [AppSettings.normalized]: values out of range are brought
 * within their limits when read and when written. Reads and writes are main-safe; DataStore serialises updates, so
 * concurrent [update]s never lose each other's changes.
 */
class SettingsRepository(
    private val store: DataStore<AppSettings>,
) {
    /** The stored settings, emitted now and after every change. */
    val settings: Flow<AppSettings> = store.data.map { it.normalized() }

    /** The settings as stored now; suspends only until the file has been read once. */
    suspend fun current(): AppSettings = settings.first()

    /** Atomically replaces the settings with [transform] applied to the stored ones; [transform] must not have side effects. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData { transform(it.normalized()).normalized() }
    }
}
