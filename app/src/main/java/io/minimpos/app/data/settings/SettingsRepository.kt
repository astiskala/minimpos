package io.minimpos.app.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * The JSON format of everything the app stores as text (settings, secrets file, receipt lines). Lenient so that files
 * written by other versions load: unknown keys are ignored, missing ones take their default, and a value that no
 * longer fits (such as a removed enum constant) is replaced by the default.
 */
internal val StorageJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
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
 * The app's [AppSettings], persisted in DataStore. Reads and writes are main-safe; DataStore serialises updates, so
 * concurrent [update]s never lose each other's changes.
 */
class SettingsRepository(
    private val store: DataStore<AppSettings>,
) {
    /** The stored settings, emitted now and after every change. */
    val settings: Flow<AppSettings> = store.data

    /** The settings as stored now; suspends only until the file has been read once. */
    suspend fun current(): AppSettings = store.data.first()

    /** Atomically replaces the settings with [transform] applied to the stored ones; [transform] must not have side effects. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.updateData(transform)
    }
}
