package io.github.astiskala.minimpos.app.update

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the repository's GitHub Releases for a newer Mini mPOS release. The latest release carries a small [METADATA]
 * asset, which the Release workflow writes with the release's Android version code, version name and APK asset; a
 * version code above the installed one is an [UpdateCheck.Available] with the APK's GitHub address, which the browser
 * downloads. Anything unreachable, or not matching the contract, is [UpdateCheck.Unavailable], never an exception.
 *
 * @param get Fetches a URL's body, or null when unreachable or not HTTP OK; the default follows redirects and runs
 *   off the main thread.
 */
class GitHubReleases(
    private val get: suspend (url: String) -> String? = { url -> httpGet(url) },
) {
    /** The latest release, compared with the installed [installedVersionCode]. */
    suspend fun latest(installedVersionCode: Long): UpdateCheck {
        val release = get(RELEASES)?.parse<Release>() ?: return UpdateCheck.Unavailable
        val metadata = release.assets.firstOrNull { it.name == METADATA } ?: return UpdateCheck.Unavailable
        if (!metadata.url.startsWith("https://")) return UpdateCheck.Unavailable
        val update = get(metadata.url)?.parse<ReleaseUpdate>() ?: return UpdateCheck.Unavailable
        val apk = release.assets.firstOrNull { it.name == update.apk } ?: return UpdateCheck.Unavailable
        // Only HTTPS addresses, for both fetching the metadata and the browser download.
        if (!apk.url.startsWith("https://")) return UpdateCheck.Unavailable
        return if (update.versionCode > installedVersionCode) {
            UpdateCheck.Available(update.versionName, apk.url)
        } else {
            UpdateCheck.Current
        }
    }

    private companion object {
        /** The repository's latest-release endpoint, the only address this reads. */
        const val RELEASES = "https://api.github.com/repos/astiskala/minimpos/releases/latest"

        /** The release asset the Release workflow writes the update metadata to. */
        const val METADATA = "update.json"
    }
}

/** The parts of a release's GitHub answer this reads: its assets' names and download addresses. */
@Serializable
private data class Release(
    val assets: List<Asset> = emptyList(),
)

@Serializable
private data class Asset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)

/** The release's update metadata asset (`:tooling` writes it); one current format, no earlier ones. */
@Serializable
private data class ReleaseUpdate(
    val versionCode: Long,
    val versionName: String,
    val apk: String,
)

/** Answers the release and metadata questions; their senders include fields this does not read. */
private val format = Json { ignoreUnknownKeys = true }

/** Decodes the receiver as [T], or null when it is not that format. */
private inline fun <reified T> String.parse(): T? = runCatching { format.decodeFromString<T>(this) }.getOrNull()

/** GitHub requires a user agent; this is the app's name, as the Terminal API requests also send it. */
private const val USER_AGENT = "Mini mPOS"

/** How long connecting and reading may each take, in milliseconds. */
private const val TIMEOUT_MILLIS = 10_000

/** The most bytes one fetched answer may be; the update metadata is a few hundred bytes, the release listing a few kilobytes. */
internal const val LIMIT = 1_048_576

private const val BUFFER = 8_192

/**
 * Reads [url] over HTTP with short timeouts, following redirects, and returns its body as UTF-8 text (at most [LIMIT]
 * bytes); null when the server is unreachable, answers anything but HTTP OK, or sends more. Never throws.
 */
internal suspend fun httpGet(
    url: String,
    io: CoroutineDispatcher = Dispatchers.IO,
): String? = withContext(io) { runCatching { fetch(url) }.getOrNull() }

/** One HTTPS request, without its error stream; null for any answer but HTTP OK. */
private fun fetch(url: String): String? {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = TIMEOUT_MILLIS
    connection.readTimeout = TIMEOUT_MILLIS
    connection.instanceFollowRedirects = true
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", USER_AGENT)
    try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        return read(connection.inputStream)
    } finally {
        connection.disconnect()
    }
}

/** Reads [stream] to its end as UTF-8 text, or null when it is longer than [LIMIT] bytes. */
private fun read(stream: InputStream): String? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER)
    stream.use { input ->
        while (out.size() <= LIMIT) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray().toString(Charsets.UTF_8)
            out.write(buffer, 0, read)
        }
    }
    return null
}
