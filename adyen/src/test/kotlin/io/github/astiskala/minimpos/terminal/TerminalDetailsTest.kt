package io.github.astiskala.minimpos.terminal

import com.adyen.model.management.TerminalSettings
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.terminal.transport.AdyenTerminalDetails
import io.github.astiskala.minimpos.terminal.transport.CredentialLookup
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.ManagementFailure
import io.github.astiskala.minimpos.terminal.transport.SharedKeyLookup
import io.github.astiskala.minimpos.terminal.transport.SharedKeyUpdate
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
    fun `credential role checks are bounded to one environment and return typed failures`() =
        runBlocking {
            reply(
                """
                {"roles":["Management API — Terminal actions read","Management API – Terminal settings read and write",
                "Management API - Terminal settings Advanced read and write"]}
                """.trimIndent(),
            )
            assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Allowed)
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/test/v3/me")
            reply("""{"roles":["Checkout webservice role"]}""")
            assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Failed(ManagementFailure.PERMISSION))
            listOf(401, 403, 503).forEach { status ->
                reply("{}", status)
                assertThat(api.credential(TerminalEnvironment.TEST)).isInstanceOf(CredentialLookup.Failed::class.java)
            }
            listOf("{}", "not json", """{"roles":[null]}""", """{"roles":[123]}""").forEach { body ->
                reply(body)
                assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Failed(ManagementFailure.UNREADABLE))
            }
        }

    @Test
    fun `real setup requires terminal settings write and advanced roles even when unused`() =
        runBlocking {
            reply("""{"roles":["Management API - Terminal actions read"]}""")
            assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Failed(ManagementFailure.PERMISSION))
            reply(
                """
                {"roles":["Management API - Terminal actions read",
                "Management API - Terminal settings read and write","Management API - Terminal settings Advanced read and write"]}
                """.trimIndent(),
            )
            assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Allowed)
        }

    @Test
    fun `reads assignments and network addresses across pages without following untrusted URLs`() =
        runBlocking {
            reply(
                """
                {"data":[{"id":"S1F2-123456789","assignment":{"merchantId":"Merchant","storeId":"ST1", "reassignmentTarget":{"merchantId":"Wrong","storeId":"ST2"}},
                "connectivity":{"ethernet":{"ipAddress":"192.168.1.2"},"wifi":{"ipAddress":"192.168.1.3"}}}],
                "_links":{"next":{"href":"https://untrusted.example/"}}}
                """.trimIndent(),
            )
            reply("""{"data":[{"id":"AMS1-123456789","connectivity":{"wifi":{"ipAddress":"192.168.1.4"}}}]}""")
            val listed = api.terminals(TerminalEnvironment.TEST) as TerminalListing.Listed
            assertThat(listed.environment).isEqualTo(TerminalEnvironment.TEST)
            assertThat(listed.terminals.map { it.host }).containsExactly("192.168.1.2", "192.168.1.4").inOrder()
            assertThat(listed.terminals.first().merchantAccount).isEqualTo("Merchant")
            assertThat(listed.terminals.first().storeId).isEqualTo("ST1")
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
            val key = (api.sharedKey("Terminal/ID", TerminalEnvironment.TEST) as SharedKeyLookup.Found).key
            assertThat(key.identifier).isEqualTo("key")
            assertThat(key.version).isEqualTo(2)
            assertThat(key.passphrase).isEqualTo(" secret passphrase ")
            assertThat(key.toString()).doesNotContain("secret passphrase")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/test/v3/terminals/Terminal%2FID/terminalSettings")
            reply("{}")
            assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isEqualTo(SharedKeyLookup.Missing)
            listOf(
                "[]",
                """{"nexo":{"encryptionKey":{"identifier":"key","version":0,"passphrase":"secret"}}}""",
                """{"nexo":{"encryptionKey":{"identifier":"","version":2,"passphrase":"secret"}}}""",
                """{"nexo":{"encryptionKey":{"identifier":"key","version":2,"passphrase":""}}}""",
                """{"nexo":{"encryptionKey":{"identifier":"key","version":2.5,"passphrase":"secret"}}}""",
            ).forEach {
                reply(it)
                assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isEqualTo(SharedKeyLookup.Failed(ManagementFailure.UNREADABLE))
            }
            reply("{}", 403)
            assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isEqualTo(SharedKeyLookup.Failed(ManagementFailure.PERMISSION))
        }

    @Test
    fun `known POIID uses one search request and never accepts substring neighbours`() =
        runBlocking {
            reply("""{"data":[{"id":"ID-other"},{"id":"ID"}],"_links":{"next":{"href":"next"}}}""")
            val result = api.terminals(TerminalEnvironment.TEST, "ID") as TerminalListing.Listed
            assertThat(result.terminals.map { it.id }).containsExactly("ID")
            val request = server.takeRequest()
            assertThat(request.url.queryParameter("searchQuery")).isEqualTo("ID")
            assertThat(request.url.queryParameter("pageNumber")).isNull()
            assertThat(server.requestCount).isEqualTo(1)
            reply("""{"data":[{"id":"ID-other"}]}""")
            assertThat((api.terminals(TerminalEnvironment.TEST, "ID") as TerminalListing.Listed).terminals).isEmpty()
        }

    @Test
    fun `creation preserves nexo fields and leaves all other settings untouched`() =
        runBlocking {
            reply(
                """
                {"cardholderReceipt":{"headerForAuthorizedReceipt":"header1,header2,filler"},
                "nexo":{"notification":{"category":"","details":"","enabled":false,"showButton":true,"title":""}},
                "opi":{"enablePayAtTable":false}}
                """.trimIndent(),
            )
            reply("{}")
            reply("""{"nexo":{"encryptionKey":{"identifier":"new","version":1,"passphrase":"NewStrongSecret123!"}}}""")
            val result =
                api.createSharedKey(
                    "ID",
                    TerminalEnvironment.TEST,
                    DiscoveredKey("new", 1, "NewStrongSecret123!"),
                ) as SharedKeyUpdate.Ready
            assertThat(result.created).isTrue()
            assertThat(server.takeRequest().method).isEqualTo("GET")
            val patch = server.takeRequest()
            assertThat(patch.method).isEqualTo("PATCH")
            assertThat(patch.url.encodedPath).isEqualTo("/test/v3/terminals/ID/terminalSettings")
            val sent = TerminalSettings.fromJson(checkNotNull(patch.body).utf8())
            assertThat(sent.nexo.notification.showButton).isTrue()
            assertThat(sent.nexo.notification.enabled).isFalse()
            assertThat(sent.nexo.encryptionKey.passphrase).isEqualTo("NewStrongSecret123!")
            assertThat(sent.cardholderReceipt).isNull()
            assertThat(sent.opi).isNull()
            assertThat(server.takeRequest().method).isEqualTo("GET")
        }

    @Test
    fun `fresh existing keys are reused without PATCH`() =
        runBlocking {
            reply("""{"nexo":{"encryptionKey":{"identifier":"existing","version":3,"passphrase":"ExistingSecret"}}}""")
            val result =
                api.createSharedKey(
                    "ID",
                    TerminalEnvironment.TEST,
                    DiscoveredKey("new", 1, "NewStrongSecret123!"),
                ) as SharedKeyUpdate.Ready
            assertThat(result.created).isFalse()
            assertThat(result.key.identifier).isEqualTo("existing")
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `failed or unmodeled settings never permit creation`() =
        runBlocking {
            listOf(
                "not json",
                "[]",
                """{"nexo":{"encryptionKey":{}}}""",
                """{"nexo":{"unknownSetting":true}}""",
                """{"nexo":{"notification":{"title":123}}}""",
                """{"nexo":{"notification":{"enabled":"false"}}}""",
                """{"nexo":{"notification":{"showButton":1}}}""",
            ).forEach { body ->
                reply(body)
                assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                    .isEqualTo(SharedKeyUpdate.Failed(ManagementFailure.UNREADABLE))
            }
            reply("{}", 403)
            assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                .isEqualTo(SharedKeyUpdate.Failed(ManagementFailure.PERMISSION))
            assertThat(server.requestCount).isEqualTo(8)
        }

    @Test
    fun `unverified PATCH outcomes remain uncertain without automatic retries`() =
        runBlocking {
            reply("{}")
            reply("{}", 503)
            assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                .isEqualTo(SharedKeyUpdate.Failed(ManagementFailure.UNAVAILABLE, uncertain = true))
            reply("{}")
            reply("{}")
            reply("{}")
            assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                .isEqualTo(SharedKeyUpdate.Failed(ManagementFailure.UNREADABLE, uncertain = true))
            assertThat(server.requestCount).isEqualTo(5)
        }
}
