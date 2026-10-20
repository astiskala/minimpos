package app.minimpos.terminal

import app.minimpos.terminal.paymentsapp.AdyenPaymentsAppCredentials
import app.minimpos.terminal.paymentsapp.ProvisionedCredential
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test

class PaymentsAppCredentialsTest {
    private val role = "Management API - API credentials read and write"
    private val reference = "Mini mPOS synthetic-device"

    private fun credential(clientKey: String = "\"test_public\""): String =
        """{"id":"C1","username":"ws_1@Company.Test","active":true,"roles":["Adyen Payments App role"],""" +
            """"description":"$reference","clientKey":$clientKey}"""

    private fun created(clientKey: String = "\"test_public\""): String =
        credential(clientKey).dropLast(1) + ""","apiKey":"boarding-secret"}"""

    private fun api(server: MockWebServer) = AdyenPaymentsAppCredentials("primary-secret", TerminalEnvironment.TEST, server.url("/v3/"))

    @Test
    fun `creation uses primary key with merchant scoped boarding and diagnostic roles`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body(created()).build())
                val result = api(server).provision("Merchant", reference, recover = false) as ProvisionedCredential.Ready
                assertThat(result.apiKey).isEqualTo("boarding-secret")
                assertThat(result.toString()).doesNotContain("boarding-secret")
                val request = server.takeRequest()
                assertThat(request.method).isEqualTo("POST")
                assertThat(request.url.encodedPath).isEqualTo("/v3/merchants/Merchant/apiCredentials")
                assertThat(request.headers["x-api-key"]).isEqualTo("primary-secret")
                assertThat(request.body!!.utf8()).isEqualTo(
                    """{"description":"$reference","roles":["Adyen Payments App role","Management API - Accounts read"]}""",
                )
                assertThat(server.requestCount).isEqualTo(1)
            }
        }

    @Test
    fun `only confirmed empty client key is generated and existing key is never rotated`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body(created("\"\"")).build())
                server.enqueue(MockResponse.Builder().body("""{"clientKey":"test_new"}""").build())
                assertThat(api(server).provision("Merchant", reference, false)).isInstanceOf(ProvisionedCredential.Ready::class.java)
                server.takeRequest()
                val generated = server.takeRequest()
                assertThat(generated.url.encodedPath).isEqualTo("/v3/merchants/Merchant/apiCredentials/C1/generateClientKey")
                assertThat(generated.body!!.utf8()).isEqualTo("{}")
                server.enqueue(MockResponse.Builder().body(created("null")).build())
                assertThat(api(server).provision("Merchant", reference, false)).isEqualTo(
                    ProvisionedCredential.Failed(Fault.UnreadableReply()),
                )
                assertThat(server.requestCount).isEqualTo(3)
            }
        }

    @Test
    fun `known client key failure still retains remote creation for recovery`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body(created("\"\"")).build())
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(403)
                        .body("{}")
                        .build(),
                )
                val result = api(server).provision("Merchant", reference, false) as ProvisionedCredential.Failed
                assertThat(result.fault).isEqualTo(Fault.Permission(ApiKey.ADYEN, role))
                assertThat(result.creationMayExist).isTrue()
            }
        }

    @Test
    fun `unreadable client key generation preserves the creation for recovery`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse.Builder().body(created("\"\"")).build())
                server.enqueue(MockResponse.Builder().body("""{"clientKey":""}""").build())
                assertThat(api(server).provision("Merchant", reference, false)).isEqualTo(
                    ProvisionedCredential.Failed(Fault.UnreadableReply(), creationMayExist = true),
                )
            }
        }

    @Test
    fun `recovery finds exact creation across pages and replaces only its unretrieved API key`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse.Builder().body("""{"data":[],"_links":{"next":{"href":"https://untrusted.example/"}}}""").build(),
                )
                server.enqueue(MockResponse.Builder().body("""{"data":[${credential()}]}""").build())
                server.enqueue(MockResponse.Builder().body("""{"apiKey":"recovered-secret"}""").build())
                val result = api(server).provision("Merchant", reference, recover = true) as ProvisionedCredential.Ready
                assertThat(result.apiKey).isEqualTo("recovered-secret")
                for (page in 1..2) {
                    val request = server.takeRequest()
                    assertThat(request.method).isEqualTo("GET")
                    assertThat(request.url.encodedPath).isEqualTo("/v3/merchants/Merchant/apiCredentials")
                    assertThat(request.url.queryParameter("pageNumber")).isEqualTo("$page")
                    assertThat(request.url.queryParameter("pageSize")).isEqualTo("100")
                }
                val rotate = server.takeRequest()
                assertThat(rotate.url.encodedPath).isEqualTo("/v3/merchants/Merchant/apiCredentials/C1/generateApiKey")
                assertThat(server.requestCount).isEqualTo(3)
            }
        }

    @Test
    fun `recovery creates the same reference again only once the complete listing lacks it`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                listOf(
                    """{"data":[]}""",
                    """{"data":[${credential().replace(reference, "another-device")}]}""",
                ).forEach { body ->
                    server.enqueue(MockResponse.Builder().body(body).build())
                    server.enqueue(MockResponse.Builder().body(created()).build())
                    val result = api(server).provision("Merchant", reference, recover = true) as ProvisionedCredential.Ready
                    assertThat(result.apiKey).isEqualTo("boarding-secret")
                    assertThat(server.takeRequest().method).isEqualTo("GET")
                    val creation = server.takeRequest()
                    assertThat(creation.method).isEqualTo("POST")
                    assertThat(creation.url.encodedPath).isEqualTo("/v3/merchants/Merchant/apiCredentials")
                    assertThat(creation.body!!.utf8()).contains(""""description":"$reference"""")
                }
                assertThat(server.requestCount).isEqualTo(4)
            }
        }

    @Test
    fun `duplicate malformed and unusable recovery matches never create or rotate`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                listOf(
                    """{"data":[${credential()},${credential()}]}""",
                    """{"data":[${credential().replace("\"active\":true", "\"active\":false")}]}""",
                    """{"data":[${credential().replace("Adyen Payments App role", "Merchant Recurring role")}]}""",
                    """{"data":[${credential().replace("\"C1\"", "\"\"")}]}""",
                    """{"data":[null]}""",
                    """{"data":null}""",
                    "{}",
                ).forEach { body ->
                    server.enqueue(MockResponse.Builder().body(body).build())
                    assertThat(api(server).provision("Merchant", reference, true)).isInstanceOf(ProvisionedCredential.Failed::class.java)
                    assertThat(server.takeRequest().method).isEqualTo("GET")
                }
                assertThat(server.requestCount).isEqualTo(7)
            }
        }

    @Test
    fun `creation auth permission and uncertain server failures are distinct without retries`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                listOf(
                    401 to Fault.Credential(ApiKey.ADYEN),
                    403 to Fault.Permission(ApiKey.ADYEN, role),
                    503 to Fault.AdyenUnavailable(503),
                ).forEach { (code, fault) ->
                    server.enqueue(
                        MockResponse
                            .Builder()
                            .code(code)
                            .body("{}")
                            .build(),
                    )
                    val result = api(server).provision("Merchant", reference, false) as ProvisionedCredential.Failed
                    assertThat(result.fault).isEqualTo(fault)
                    assertThat(result.creationMayExist).isEqualTo(fault.mayHaveTakenEffect)
                }
                assertThat(server.requestCount).isEqualTo(3)
            }
        }
}
