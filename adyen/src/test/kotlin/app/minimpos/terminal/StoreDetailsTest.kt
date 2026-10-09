package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenStoreDetails
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MerchantLookup
import app.minimpos.terminal.transport.StoreDetails
import app.minimpos.terminal.transport.StoreLookup
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
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

    private fun store() = runBlocking { api.store("Merchant/One", "ST1") }

    private fun failure() = (store() as StoreLookup.Failed).fault

    @Test
    fun `reads only the assigned store and its shopper receipt name, not description`() {
        reply(store)
        val expected =
            StoreDetails(
                "Harbour Coffee",
                "1 Main St\nSuite 2\nBuilding A\nSydney\nNSW\n2000\nAU",
                "+61212345678",
            )
        assertThat(store()).isEqualTo(StoreLookup.Found(expected))
        val sent = server.takeRequest()
        assertThat(sent.method).isEqualTo("GET")
        assertThat(sent.url.encodedPath).isEqualTo("/v3/merchants/Merchant%2FOne/stores/ST1")
        assertThat(sent.url.query).isNull()
        assertThat(sent.headers["x-api-key"]).isEqualTo("secret-key")
        assertThat(sent.body).isNull()
    }

    @Test
    fun `an unknown store is missing, not a failure, and another store's answer is rejected`() {
        reply("""{"detail":"Store not found"}""", 404)
        assertThat(store()).isEqualTo(StoreLookup.Missing)
        reply("""{"id":"ST2","shopperStatement":"Other store"}""")
        assertThat(failure()).isEqualTo(Fault.UnreadableReply())
    }

    @Test
    fun `merchant receipt lookup reads only legal name without borrowing city or store fields`() {
        reply("""{"id":"Merchant/One","name":" Legal Shop ","merchantCity":"Sydney","description":"Internal"}""")
        assertThat(runBlocking { api.merchant("Merchant/One") }).isEqualTo(MerchantLookup.Found("Legal Shop"))
        val sent = server.takeRequest()
        assertThat(sent.method).isEqualTo("GET")
        assertThat(sent.url.encodedPath).isEqualTo("/v3/merchants/Merchant%2FOne")
        assertThat(sent.headers["x-api-key"]).isEqualTo("secret-key")
        assertThat(sent.body).isNull()
    }

    @Test
    fun `merchant lookup accepts unavailable name but rejects missing or mismatched identity`() {
        reply("""{"id":"Merchant/One"}""")
        assertThat(runBlocking { api.merchant("Merchant/One") }).isEqualTo(MerchantLookup.Found(""))
        for (body in listOf("not json", "{}", """{"id":"Other","name":"Other name"}""")) {
            reply(body)
            assertThat(runBlocking { api.merchant("Merchant/One") }).isInstanceOf(MerchantLookup.Failed::class.java)
        }
        reply("{}", 403)
        assertThat((runBlocking { api.merchant("Merchant/One") } as MerchantLookup.Failed).fault)
            .isEqualTo(Fault.Permission(ApiKey.ADYEN, "Management API—Account read"))
    }

    @Test
    fun `blank optional fields are valid`() {
        reply("""{"id":"ST1","shopperStatement":" ","phoneNumber":null,"address":{}}""")
        assertThat(store()).isEqualTo(StoreLookup.Found(StoreDetails("", "", "")))
    }

    @Test
    fun `Management strings are trimmed without coercing non-string fields`() {
        reply(
            """{"id":" ST1 ","reference":12,"shopperStatement":" Shop ","phoneNumber":true,""" +
                """"address":{"line1":" Main St ","city":123,"postalCode":[]}}""",
        )
        assertThat(store()).isEqualTo(StoreLookup.Found(StoreDetails("Shop", "Main St", "")))
        reply("""{"id":123}""")
        assertThat(store()).isInstanceOf(StoreLookup.Failed::class.java)
    }

    @Test
    fun `unreadable successful replies do not offer partial imports`() {
        listOf("not json", "[]", "{}", """{"id":""}""", "1").forEach { body ->
            reply(body)
            assertThat(failure()).isEqualTo(Fault.UnreadableReply())
        }
    }

    @Test
    fun `permission and authentication failures give useful non-secret explanations`() {
        reply("{}", 401)
        assertThat(failure()).isEqualTo(Fault.Credential(ApiKey.ADYEN))
        reply("{}", 403)
        assertThat(failure()).isEqualTo(Fault.Permission(ApiKey.ADYEN, "Management API—Stores read"))
        reply("""{"detail":"Store access denied","errorCode":"00_422"}""", 422)
        assertThat(failure()).isEqualTo(Fault.AdyenRejected(422, "00_422", ExternalText("Store access denied")))
        reply("""{"title":"Unavailable"}""", 503)
        assertThat(failure()).isEqualTo(Fault.AdyenUnavailable(503, null, ExternalText("Unavailable")))
        reply("not json", 500)
        assertThat(failure()).isEqualTo(Fault.AdyenUnavailable(500))
    }

    @Test
    fun `network failures are returned and endpoint selection follows the detected environment`() {
        val client = OkHttpClient.Builder().dns(Dns { throw UnknownHostException("offline") }).build()
        val offline = AdyenStoreDetails("key", TerminalEnvironment.TEST, baseClient = client)
        assertThat(runBlocking { offline.store("Merchant", "ST1") }).isInstanceOf(StoreLookup.Failed::class.java)
        assertThat(runBlocking { offline.merchant("Merchant") }).isInstanceOf(MerchantLookup.Failed::class.java)
        assertThat(AdyenStoreDetails.endpoint(TerminalEnvironment.TEST)).isEqualTo("https://management-test.adyen.com/v3")
        assertThat(AdyenStoreDetails.endpoint(TerminalEnvironment.LIVE)).isEqualTo("https://management-live.adyen.com/v3")
    }
}
