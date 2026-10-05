package io.github.astiskala.minimpos.app.update

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test
import java.net.HttpURLConnection

/** Checks the latest-release decision against canned GitHub answers, and the real HTTP client against a local server. */
class GitHubReleasesTest {
    private val listingUrl = "https://api.github.com/repos/astiskala/minimpos/releases/latest"
    private val metadataUrl = "https://github.com/astiskala/minimpos/releases/download/v0.6.5/update.json"
    private val apkUrl = "https://github.com/astiskala/minimpos/releases/download/v0.6.5/minimpos-0.6.5.apk"

    /** The latest release's answer, with fields the check does not read, as GitHub sends them. */
    private val listing =
        """
        {"tag_name":"v0.6.5","assets":[
            {"name":"minimpos-0.6.5.apk","browser_download_url":"$apkUrl","digest":"sha256:…"},
            {"name":"update.json","browser_download_url":"$metadataUrl"}
        ]}
        """.trimIndent()

    /** The update metadata asset, exactly as the Release workflow writes it. */
    private val metadata = """{"versionName":"0.6.5","versionCode":17,"apk":"minimpos-0.6.5.apk"}"""

    /** The check's two pages ([release] and [update]), recording every address it fetched. */
    private inner class Pages(
        private val release: String?,
        private val update: String?,
    ) {
        val fetched = mutableListOf<String>()

        /** The check's answer for the installed version code. */
        fun latest(installed: Long) = runBlocking { releases().latest(installed) }

        /** The release check reading these pages. */
        fun releases() = GitHubReleases(get = ::page)

        private fun page(url: String): String? {
            fetched += url
            return when (url) {
                listingUrl -> release
                metadataUrl -> update
                else -> null
            }
        }
    }

    private fun pages(
        release: String? = listing,
        update: String? = metadata,
    ) = Pages(release, update)

    @Test
    fun `a release with a higher version code is offered with its version and APK address`() {
        val pages = pages()
        assertThat(pages.latest(16)).isEqualTo(UpdateCheck.Available("0.6.5", apkUrl))
        assertThat(pages.fetched).containsExactly(listingUrl, metadataUrl).inOrder()
    }

    @Test
    fun `the installed version code stays current`() {
        val releases = pages()
        assertThat(releases.latest(17)).isEqualTo(UpdateCheck.Current)
        assertThat(releases.latest(18)).isEqualTo(UpdateCheck.Current)
    }

    @Test
    fun `an unreachable release or update answers unavailable`() {
        val unreachable = pages(release = null)
        assertThat(unreachable.latest(16)).isEqualTo(UpdateCheck.Unavailable)
        assertThat(unreachable.fetched).containsExactly(listingUrl)

        assertThat(pages(update = null).latest(16)).isEqualTo(UpdateCheck.Unavailable)
    }

    @Test
    fun `a release without the update asset answers unavailable`() {
        val without = pages(release = """{"tag_name":"v0.6.5","assets":[]}""")
        assertThat(without.latest(16)).isEqualTo(UpdateCheck.Unavailable)
        assertThat(without.fetched).containsExactly(listingUrl)
    }

    @Test
    fun `malformed release or update answers unavailable`() {
        assertThat(pages(release = "{").latest(16)).isEqualTo(UpdateCheck.Unavailable)
        assertThat(pages(update = "not json").latest(16)).isEqualTo(UpdateCheck.Unavailable)
    }

    @Test
    fun `update metadata naming an APK the release does not carry answers unavailable`() {
        val named = pages(update = """{"versionName":"0.7.0","versionCode":18,"apk":"minimpos-0.7.0.apk"}""")
        assertThat(named.latest(16)).isEqualTo(UpdateCheck.Unavailable)
        assertThat(named.fetched).containsExactly(listingUrl, metadataUrl).inOrder()
    }

    @Test
    fun `only HTTPS asset addresses are fetched`() {
        val metadataAddress =
            pages(release = """{"assets":[{"name":"update.json","browser_download_url":"http://example.com/update.json"}]}""")
        assertThat(metadataAddress.latest(16)).isEqualTo(UpdateCheck.Unavailable)
        assertThat(metadataAddress.fetched).containsExactly(listingUrl)

        val apkAddress =
            pages(
                release =
                    """
                    {"assets":[
                        {"name":"minimpos-0.6.5.apk","browser_download_url":"http://example.com/minimpos-0.6.5.apk"},
                        {"name":"update.json","browser_download_url":"$metadataUrl"}
                    ]}
                    """.trimIndent(),
            )
        assertThat(apkAddress.latest(16)).isEqualTo(UpdateCheck.Unavailable)
        assertThat(apkAddress.fetched).containsExactly(listingUrl, metadataUrl).inOrder()
    }

    @Test
    fun `the real client follows a redirect and returns the body`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(HttpURLConnection.HTTP_MOVED_TEMP)
                    .addHeader("Location", "/moved")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().body("answer").build())
            assertThat(body(server.url("/").toString())).isEqualTo("answer")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/moved")
        }
    }

    @Test
    fun `the real client answers null for anything but HTTP OK`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().code(HttpURLConnection.HTTP_NOT_FOUND).build())
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(HttpURLConnection.HTTP_FORBIDDEN)
                    .body("rate limited")
                    .build(),
            )
            assertThat(body(server.url("/missing").toString())).isNull()
            assertThat(body(server.url("/limited").toString())).isNull()
        }
    }

    @Test
    fun `the real client answers null for a body beyond the limit`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("a".repeat(LIMIT + 1)).build())
            assertThat(body(server.url("/large").toString())).isNull()
        }
    }

    /** The real client's answer for [url]. */
    private fun body(url: String): String? = runBlocking { httpGet(url) }
}
