package app.minimpos.terminal

import app.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import app.minimpos.terminal.paymentsapp.BoardingCredentialCheck
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Test
import java.net.SocketTimeoutException

class BoardingCredentialTest {
    private fun credential(clientKey: String = "\"test_public_key\""): String =
        """{"id":"CRED1","username":"ws@Company.Test","active":true,"roles":["Adyen Payments App role"],""" +
            """"allowedIpAddresses":[],"clientKey":$clientKey}"""

    @Test
    fun `introspection uses the boarding key and exposes only key presence`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenPaymentsAppManagement("boarding-key", TerminalEnvironment.TEST, server.url("/v1/"))
                listOf("\"test_public_key\"" to true, "\"\"" to false, "\" \"" to false).forEach { (key, present) ->
                    server.enqueue(MockResponse.Builder().body(credential(key)).build())
                    val result = api.credential()
                    assertThat(result).isEqualTo(BoardingCredentialCheck.Verified("CRED1", "ws@Company.Test", true, present, true))
                    assertThat(result.toString()).doesNotContain("test_public_key")
                    val request = server.takeRequest()
                    assertThat(request.method).isEqualTo("GET")
                    assertThat(request.url.encodedPath).isEqualTo("/v1/me")
                    assertThat(request.headers["x-api-key"]).isEqualTo("boarding-key")
                }
            }
            assertThat(AdyenPaymentsAppManagement.endpoint(TerminalEnvironment.LIVE)).isEqualTo("https://management-live.adyen.com/v1")
        }

    @Test
    fun `readable roles distinguish absent boarding role from an unchecked credential`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenPaymentsAppManagement("boarding-key", TerminalEnvironment.TEST, server.url("/v1/"))
                server.enqueue(MockResponse.Builder().body(credential().replace("[\"Adyen Payments App role\"]", "[]")).build())
                assertThat(api.credential()).isEqualTo(BoardingCredentialCheck.Verified("CRED1", "ws@Company.Test", true, true, false))
            }
        }

    @Test
    fun `malformed or unsupported schemas never become missing client keys`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenPaymentsAppManagement("boarding-key", TerminalEnvironment.TEST, server.url("/v1/"))
                listOf(
                    "{}",
                    "not-json",
                    credential("null"),
                    credential("{}"),
                    credential("123"),
                    credential().replace(",\"clientKey\":\"test_public_key\"", ""),
                    credential().replace("\"CRED1\"", "\"\""),
                    credential().replace("\"ws@Company.Test\"", "\"\""),
                    credential().replace("\"active\":true", "\"active\":\"true\""),
                    credential().replace("\"active\":true", "\"active\":null"),
                    credential().replace("[\"Adyen Payments App role\"]", "null"),
                    credential().replace("[\"Adyen Payments App role\"]", "[null]"),
                    credential().replace("\"allowedIpAddresses\":[]", "\"allowedIpAddresses\":null"),
                    credential().replace("\"allowedIpAddresses\":[]", "\"allowedIpAddresses\":[null]"),
                ).forEach { body ->
                    server.enqueue(MockResponse.Builder().body(body).build())
                    assertThat(api.credential()).isEqualTo(BoardingCredentialCheck.Failed(Fault.UnreadableReply()))
                }
            }
        }

    @Test
    fun `authentication permission API and timeout failures remain distinct`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenPaymentsAppManagement("boarding-key", TerminalEnvironment.TEST, server.url("/v1/"))
                listOf(
                    401 to Fault.Credential(ApiKey.PAYMENTS_APP),
                    403 to Fault.Permission(ApiKey.PAYMENTS_APP, null),
                    503 to Fault.AdyenUnavailable(503),
                ).forEach { (code, fault) ->
                    server.enqueue(
                        MockResponse
                            .Builder()
                            .code(code)
                            .body("{}")
                            .build(),
                    )
                    assertThat(api.credential()).isEqualTo(BoardingCredentialCheck.Failed(fault))
                }
            }
            val timeout = OkHttpClient.Builder().addInterceptor { throw SocketTimeoutException() }.build()
            val api = AdyenPaymentsAppManagement("boarding-key", TerminalEnvironment.TEST, baseClient = timeout)
            assertThat(api.credential()).isEqualTo(BoardingCredentialCheck.Failed(Fault.TimedOut))
        }
}
