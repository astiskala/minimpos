package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenWalletMethods
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.WalletMethod
import app.minimpos.terminal.transport.WalletMethodListing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Test
import java.net.UnknownHostException

class WalletMethodsTest {
    @Test
    fun `malformed enablement flags errors and HTTP failures are discovery failures not permission to offer wallets`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenWalletMethods("synthetic", TerminalEnvironment.TEST, baseUrl = server.url("/v3/"))
                listOf(
                    """{"data":[{"type":"wechatpay_pos","allowed":"true","enabled":"true","shopperInteraction":"pos"}]}""",
                    """{"data":[{"type":"wechatpay_pos"}],"typesWithErrors":["alipay"]}""",
                    """{"data":[{"type":" "}]}""",
                    """{}""",
                    """not json""",
                ).forEach { body ->
                    server.enqueue(MockResponse.Builder().body(body).build())
                    assertThat(api.methods("Merchant", "")).isEqualTo(WalletMethodListing.Failed(Fault.UnreadableReply()))
                }
                server.enqueue(MockResponse.Builder().code(403).build())
                assertThat(api.methods("Merchant", " "))
                    .isEqualTo(WalletMethodListing.Failed(Fault.Permission(ApiKey.ADYEN, "Management API - Payment methods read")))
                assertThat(server.takeRequest().url.queryParameter("storeId")).isNull()
            }
            val offlineClient = OkHttpClient.Builder().dns(Dns { throw UnknownHostException("offline") }).build()
            val offline = AdyenWalletMethods("synthetic", TerminalEnvironment.TEST, baseClient = offlineClient)
            assertThat(offline.methods("Merchant", "")).isInstanceOf(WalletMethodListing.Failed::class.java)
            val liveClient = OkHttpClient.Builder().dns(Dns { throw UnknownHostException("offline") }).build()
            val liveOffline = AdyenWalletMethods("synthetic", TerminalEnvironment.LIVE, baseClient = liveClient)
            assertThat(liveOffline.methods("Merchant", "")).isInstanceOf(WalletMethodListing.Failed::class.java)
        }

    @Test
    fun `wallet discovery reads all pages within the supplied account store and environment`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(
                            """{"data":[{"type":"wechatpay_pos","allowed":true,"enabled":true,"shopperInteraction":"pos",""" +
                                """"currencies":["AUD"],"countries":["AU"],"storeIds":["ST1"]}],""" +
                                """"_links":{"next":{"href":"https://untrusted.example/"}}}""",
                        ).build(),
                )
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(
                            """{"data":[{"type":"alipay","allowed":false,"enabled":false,"shopperInteraction":"eCommerce"}],""" +
                                """"_links":{"next":{"href":" "}}}""",
                        ).build(),
                )
                val api = AdyenWalletMethods("synthetic", TerminalEnvironment.TEST, baseUrl = server.url("/test/v3/"))
                val listed = api.methods("Merchant", "ST1") as WalletMethodListing.Listed
                assertThat(listed.methods)
                    .containsExactly(
                        WalletMethod("wechatpay_pos", true, true, "pos", setOf("AUD"), setOf("AU"), setOf("ST1")),
                        WalletMethod("alipay", false, false, "eCommerce", emptySet(), emptySet(), emptySet()),
                    ).inOrder()
                val first = listed.methods[0]
                assertThat(first.type).isEqualTo("wechatpay_pos")
                assertThat(first.enabled).isTrue()
                assertThat(first.allowed).isTrue()
                assertThat(first.channel).isEqualTo("pos")
                assertThat(first.currencies).containsExactly("AUD")
                assertThat(first.countries).containsExactly("AU")
                assertThat(first.storeIds).containsExactly("ST1")
                val second = listed.methods[1]
                assertThat(second.enabled).isFalse()
                assertThat(second.allowed).isFalse()
                repeat(2) { page ->
                    val request = server.takeRequest()
                    assertThat(request.url.encodedPath).isEqualTo("/test/v3/merchants/Merchant/paymentMethodSettings")
                    assertThat(request.url.queryParameter("storeId")).isEqualTo("ST1")
                    assertThat(request.url.queryParameter("pageNumber")).isEqualTo((page + 1).toString())
                    assertThat(request.method).isEqualTo("GET")
                }
            }
        }
}
