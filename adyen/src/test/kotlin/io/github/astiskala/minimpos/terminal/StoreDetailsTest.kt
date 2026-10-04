package io.github.astiskala.minimpos.terminal

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.terminal.transport.AdyenStoreDetails
import io.github.astiskala.minimpos.terminal.transport.StoreDetails
import io.github.astiskala.minimpos.terminal.transport.StoreListing
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import java.net.UnknownHostException

class StoreDetailsTest {
    private val server = MockWebServer().apply { start() }
    private val api = AdyenStoreDetails("secret-key", TerminalEnvironment.TEST, baseUrl = server.url("/v3/"))
    private val store =
        """
        {"id":"ST1","reference":"coffee","shopperStatement":"Harbour Coffee","description":"Internal label",
        "phoneNumber":"+61212345678","address":{"line1":"1 Main St","line2":"Suite 2","line3":"Building A",
        "city":"Sydney","stateOrProvince":"NSW","postalCode":"2000","country":"AU"}}
        """.trimIndent()

    @After
    fun tearDown() = server.close()

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

    private fun stores() = runBlocking { api.stores("Merchant/One") }

    @Test
    fun `uses the read-only merchant endpoint and the shopper receipt name, not description`() {
        reply("""{"data":[$store]}""")
        val expected =
            StoreDetails(
                "ST1",
                "coffee",
                "Harbour Coffee",
                "1 Main St\nSuite 2\nBuilding A\nSydney\nNSW\n2000\nAU",
                "+61212345678",
            )
        assertThat(stores()).isEqualTo(StoreListing.Listed(listOf(expected)))
        val sent = server.takeRequest()
        assertThat(sent.method).isEqualTo("GET")
        assertThat(sent.url.encodedPath).isEqualTo("/v3/merchants/Merchant%2FOne/stores")
        assertThat(sent.url.queryParameter("pageSize")).isEqualTo("100")
        assertThat(sent.url.queryParameter("pageNumber")).isEqualTo("1")
        assertThat(sent.headers["x-api-key"]).isEqualTo("secret-key")
        assertThat(sent.body).isNull()
    }

    @Test
    fun `reads subsequent pages without following response URLs or leaking credentials`() {
        reply("""{"data":[$store],"_links":{"next":{"href":"https://untrusted.example/steal"}}}""")
        reply("""{"data":[$store,{"id":"ST2"}]}""")
        val result = stores() as StoreListing.Listed
        assertThat(result.stores.map { it.id }).containsExactly("ST1", "ST2").inOrder()
        assertThat(result.stores.last()).isEqualTo(StoreDetails("ST2", "", "", "", ""))
        server.takeRequest()
        val second = server.takeRequest()
        assertThat(second.url.host).isEqualTo(server.url("/").host)
        assertThat(second.url.queryParameter("pageNumber")).isEqualTo("2")
    }

    @Test
    fun `empty lists and blank optional fields are valid`() {
        reply("""{"data":[]}""")
        assertThat(stores()).isEqualTo(StoreListing.Listed(emptyList()))
        reply("""{"data":[{"id":"ST1","shopperStatement":" ","phoneNumber":null,"address":{}}]}""")
        assertThat(stores()).isEqualTo(StoreListing.Listed(listOf(StoreDetails("ST1", "", "", "", ""))))
    }

    @Test
    fun `Management strings are trimmed without coercing non-string fields`() {
        reply(
            """{"data":[{"id":" ST1 ","reference":12,"shopperStatement":" Shop ","phoneNumber":true,""" +
                """"address":{"line1":" Main St ","city":123,"postalCode":[]}}]}""",
        )
        assertThat(stores()).isEqualTo(StoreListing.Listed(listOf(StoreDetails("ST1", "", "Shop", "Main St", ""))))
        reply("""{"data":[{"id":123}]}""")
        assertThat(stores()).isInstanceOf(StoreListing.Failed::class.java)
    }

    @Test
    fun `unreadable successful replies do not offer partial imports`() {
        listOf("not json", "[]", "{}", """{"data":[{"id":""}]}""", """{"data":[1]}""").forEach { body ->
            reply(body)
            assertThat(stores()).isEqualTo(StoreListing.Failed("Adyen sent an unreadable store list"))
        }
        reply("""{"data":[$store],"_links":{"next":{"href":"next"}}}""")
        reply("invalid")
        assertThat(stores()).isInstanceOf(StoreListing.Failed::class.java)
    }

    @Test
    fun `permission and authentication failures give useful non-secret explanations`() {
        reply("{}", 401)
        assertThat((stores() as StoreListing.Failed).message).isEqualTo("Adyen did not accept the API key (HTTP 401)")
        reply("{}", 403)
        assertThat((stores() as StoreListing.Failed).message).contains("Management API—Stores read")
        reply("""{"detail":"Store access denied"}""", 422)
        assertThat((stores() as StoreListing.Failed).message).isEqualTo("Store access denied (HTTP 422)")
        reply("""{"title":"Unavailable"}""", 503)
        assertThat((stores() as StoreListing.Failed).message).isEqualTo("Unavailable (HTTP 503)")
        reply("not json", 500)
        assertThat((stores() as StoreListing.Failed).message).isEqualTo("Adyen returned an error (HTTP 500)")
    }

    @Test
    fun `network failures are returned and endpoint selection follows the detected environment`() {
        val client = OkHttpClient.Builder().dns(Dns { throw UnknownHostException("offline") }).build()
        val offline = AdyenStoreDetails("key", TerminalEnvironment.TEST, baseClient = client)
        assertThat(runBlocking { offline.stores("Merchant") }).isInstanceOf(StoreListing.Failed::class.java)
        assertThat(AdyenStoreDetails.endpoint(TerminalEnvironment.TEST)).isEqualTo("https://management-test.adyen.com/v3")
        assertThat(AdyenStoreDetails.endpoint(TerminalEnvironment.LIVE)).isEqualTo("https://management-live.adyen.com/v3")
    }

    @Test
    fun `unbounded pagination is stopped without returning a partial list`() {
        repeat(100) { reply("""{"data":[],"_links":{"next":{"href":"next"}}}""") }
        assertThat(stores()).isEqualTo(StoreListing.Failed("The store list is too large to import"))
        assertThat(server.requestCount).isEqualTo(100)
    }
}
