package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenWalletMethods
import app.minimpos.terminal.transport.ManagementFailure
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.WalletMethodListing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test

class WalletMethodsTest {
    @Test
    fun `malformed enablement flags are a discovery failure not permission to offer wallets`() =
        runTest {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(
                            """{"data":[{"type":"wechatpay_pos","allowed":"true","enabled":"true","shopperInteraction":"pos"}]}""",
                        ).build(),
                )
                val api = AdyenWalletMethods("synthetic", TerminalEnvironment.TEST, baseUrl = server.url("/v3/"))
                assertThat(api.methods("Merchant", "")).isEqualTo(WalletMethodListing.Failed(ManagementFailure.UNREADABLE))
            }
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
                                """"currencies":["AUD"],"storeIds":["ST1"]}],"_links":{"next":{"href":"https://untrusted.example/"}}}""",
                        ).build(),
                )
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(
                            """{"data":[{"type":"alipay","allowed":true,"enabled":false,"shopperInteraction":"eCommerce"}]}""",
                        ).build(),
                )
                val api = AdyenWalletMethods("synthetic", TerminalEnvironment.TEST, baseUrl = server.url("/test/v3/"))
                val listed = api.methods("Merchant", "ST1") as WalletMethodListing.Listed
                assertThat(listed.methods.map { it.type }).containsExactly("wechatpay_pos", "alipay").inOrder()
                assertThat(listed.methods.first().currencies).containsExactly("AUD")
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
