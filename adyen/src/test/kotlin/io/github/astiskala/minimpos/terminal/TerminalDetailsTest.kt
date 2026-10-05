package io.github.astiskala.minimpos.terminal

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.terminal.transport.AdyenTerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test

class TerminalDetailsTest {
    private val server = MockWebServer().apply { start() }
    private val api = AdyenTerminalDetails("secret", baseUrl = { server.url("/${it.name.lowercase()}/v3/") })

    private fun reply(
        body: String,
        code: Int = 200,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    @After
    fun tearDown() = server.close()

    @Test
    fun `reads assignments and network addresses across pages without following untrusted URLs`() =
        runBlocking {
            reply(
                """
                {"data":[{"id":"S1F2-123456789","assignment":{"merchantId":"Merchant", "reassignmentTarget":{"merchantId":"Wrong"}},
                "connectivity":{"ethernet":{"ipAddress":"192.168.1.2"},"wifi":{"ipAddress":"192.168.1.3"}}}],
                "_links":{"next":{"href":"https://untrusted.example/"}}}
                """.trimIndent(),
            )
            reply("""{"data":[{"id":"AMS1-123456789","connectivity":{"wifi":{"ipAddress":"192.168.1.4"}}}]}""")
            val listed = api.terminals(TerminalEnvironment.TEST) as TerminalListing.Listed
            assertThat(listed.environment).isEqualTo(TerminalEnvironment.TEST)
            assertThat(listed.terminals.map { it.host }).containsExactly("192.168.1.2", "192.168.1.4").inOrder()
            assertThat(listed.terminals.first().merchantAccount).isEqualTo("Merchant")
            val first = server.takeRequest()
            assertThat(first.headers["x-api-key"]).isEqualTo("secret")
            assertThat(first.method).isEqualTo("GET")
            assertThat(server.takeRequest().url.queryParameter("pageNumber")).isEqualTo("2")
        }

    @Test
    fun `authentication and permission rejection never try the other environment`() =
        runBlocking {
            reply("{}", 401)
            assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
            reply("{}", 403)
            assertThat(api.terminals(TerminalEnvironment.LIVE)).isInstanceOf(TerminalListing.Failed::class.java)
            reply("""{"data":[]}""")
            assertThat((api.terminals(TerminalEnvironment.LIVE) as TerminalListing.Listed).environment).isEqualTo(TerminalEnvironment.LIVE)
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(server.takeRequest().url.encodedPath).startsWith("/test/")
            assertThat(server.takeRequest().url.encodedPath).startsWith("/live/")
            assertThat(server.takeRequest().url.encodedPath).startsWith("/live/")
        }

    @Test
    fun `malformed and incomplete terminal lists cannot be selected`() =
        runBlocking {
            listOf("not json", "{}", "[]", """{"data":[{"id":""}]}""").forEach {
                reply(it)
                assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
            }
        }

    @Test
    fun `pagination is bounded and a failed later page never returns a partial selection`() =
        runBlocking {
            repeat(100) { reply("""{"data":[],"_links":{"next":{"href":"next"}}}""") }
            assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
            assertThat(server.requestCount).isEqualTo(100)
            reply("""{"data":[{"id":"ID"}],"_links":{"next":{"href":"next"}}}""")
            reply("{}", 503)
            assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
        }

    @Test
    fun `optional fields may be blank and unexpected JSON types reject the list`() =
        runBlocking {
            reply("""{"data":[{"id":"ID"},{"id":"ID"}]}""")
            val listed = api.terminals(TerminalEnvironment.TEST) as TerminalListing.Listed
            assertThat(listed.terminals).hasSize(1)
            assertThat(listed.terminals.single().host).isEmpty()
            reply("""{"data":[{"id":123}]}""")
            assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
        }

    @Test
    fun `shared keys are optional and secret whitespace is preserved`() =
        runBlocking {
            reply("""{"nexo":{"encryptionKey":{"identifier":"key","version":2,"passphrase":" secret passphrase "}}}""")
            val key = checkNotNull(api.sharedKey("Terminal/ID", TerminalEnvironment.TEST))
            assertThat(key.identifier).isEqualTo("key")
            assertThat(key.version).isEqualTo(2)
            assertThat(key.passphrase).isEqualTo(" secret passphrase ")
            assertThat(key.toString()).doesNotContain("secret passphrase")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/test/v3/terminals/Terminal%2FID/terminalSettings")
            listOf(
                "{}",
                "[]",
                """{"nexo":{"encryptionKey":{"identifier":"key","version":0,"passphrase":"secret"}}}""",
                """{"nexo":{"encryptionKey":{"identifier":"","version":2,"passphrase":"secret"}}}""",
                """{"nexo":{"encryptionKey":{"identifier":"key","version":2,"passphrase":""}}}""",
                """{"nexo":{"encryptionKey":{"identifier":"key","version":2.5,"passphrase":"secret"}}}""",
            ).forEach {
                reply(it)
                assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isNull()
            }
            reply("{}", 403)
            assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isNull()
        }
}
