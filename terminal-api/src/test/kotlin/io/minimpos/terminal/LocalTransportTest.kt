package io.minimpos.terminal

import com.adyen.Config
import com.adyen.constants.ApiConstants
import com.adyen.httpclient.ClientInterface
import com.adyen.model.RequestOptions
import com.adyen.model.nexo.DiagnosisRequest
import com.adyen.model.nexo.DiagnosisResponse
import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.model.terminal.TerminalAPISecuredRequest
import com.adyen.model.terminal.TerminalAPISecuredResponse
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.security.TerminalCommonNameValidator
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import io.minimpos.terminal.transport.AdyenLocalTransport
import io.minimpos.terminal.transport.Delivery
import io.minimpos.terminal.transport.TerminalEnvironment
import io.minimpos.terminal.transport.TerminalHttpClient
import io.minimpos.terminal.transport.TerminalKey
import io.minimpos.terminal.transport.TerminalProtocolException
import io.minimpos.terminal.transport.TerminalRejectedException
import io.minimpos.terminal.transport.TerminalTls
import io.minimpos.terminal.transport.TerminalUnreachableException
import io.minimpos.terminal.transport.TerminalUntrustedException
import io.minimpos.terminal.transport.toDelivery
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

class LocalTransportTest {
    private val gson = TerminalAPIGsonBuilder.create()
    private val root =
        HeldCertificate
            .Builder()
            .certificateAuthority(0)
            .commonName("Fake Adyen Root")
            .build()
    private val key = TerminalKey("key-id", "correct horse battery staple", 3)
    private val terminalCrypto = NexoCrypto(key.toSecurityKey())
    private val servers = mutableListOf<MockWebServer>()

    @After
    fun tearDown() = servers.forEach { it.close() }

    private fun header(type: MessageType = MessageType.REQUEST) =
        MessageHeader().apply {
            protocolVersion = "3.0"
            messageClass = MessageClassType.SERVICE
            messageCategory = MessageCategoryType.DIAGNOSIS
            messageType = type
            serviceID = "SVC1"
            saleID = "POS1"
            poiid = "S1F2-000158213605014"
        }

    private val request =
        TerminalAPIRequest().apply {
            saleToPOIRequest =
                SaleToPOIRequest().apply {
                    messageHeader = header()
                    diagnosisRequest = DiagnosisRequest().apply { setHostDiagnosisFlag(false) }
                }
        }

    private fun diagnosisOk() =
        TerminalAPIResponse().apply {
            saleToPOIResponse =
                SaleToPOIResponse().apply {
                    messageHeader = header(MessageType.RESPONSE)
                    diagnosisResponse = DiagnosisResponse().apply { response = Response().apply { result = ResultType.SUCCESS } }
                }
        }

    private fun securedResponse(response: TerminalAPIResponse): String =
        gson.toJson(
            TerminalAPISecuredResponse().apply {
                saleToPOIResponse = terminalCrypto.encrypt(gson.toJson(response), response.saleToPOIResponse.messageHeader)
            },
        )

    private fun server(
        commonName: String = "S1F2-000158213605014.test.terminal.adyen.com",
        issuer: HeldCertificate = root,
    ): MockWebServer {
        val leaf =
            HeldCertificate
                .Builder()
                .commonName(commonName)
                .signedBy(issuer)
                .build()
        return MockWebServer().also {
            it.useHttps(
                HandshakeCertificates
                    .Builder()
                    .heldCertificate(leaf, issuer.certificate)
                    .build()
                    .sslSocketFactory(),
            )
            it.start()
            servers += it
        }
    }

    private fun MockWebServer.reply(
        body: String,
        code: Int = 200,
    ) = enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    private fun tls(
        environment: TerminalEnvironment = TerminalEnvironment.TEST,
        trusted: HeldCertificate = root,
    ) = TerminalTls(mapOf(environment to trusted.certificate))

    private fun http(
        environment: TerminalEnvironment = TerminalEnvironment.TEST,
        trusted: HeldCertificate = root,
    ) = TerminalHttpClient(tls(environment, trusted), terminalCrypto)

    private val config =
        Config().apply {
            connectionTimeoutMillis = 5_000
            readTimeoutMillis = 5_000
        }

    private fun TerminalHttpClient.post(url: HttpUrl) = request(url.toString(), "{}", config)

    /** Adyen's TerminalLocalAPI always targets port 8443; point it at the test server instead. */
    private class Redirect(
        private val target: HttpUrl,
        private val delegate: ClientInterface,
    ) : ClientInterface {
        var endpoint: String? = null

        override fun request(
            endpoint: String,
            requestBody: String,
            config: Config,
        ) = forward(endpoint, requestBody, config)

        override fun request(
            endpoint: String,
            requestBody: String,
            config: Config,
            isApiKeyRequired: Boolean,
        ) = forward(endpoint, requestBody, config)

        override fun request(
            endpoint: String,
            requestBody: String,
            config: Config,
            isApiKeyRequired: Boolean,
            requestOptions: RequestOptions?,
        ) = forward(endpoint, requestBody, config)

        override fun request(
            endpoint: String,
            requestBody: String,
            config: Config,
            isApiKeyRequired: Boolean,
            requestOptions: RequestOptions?,
            httpMethod: ApiConstants.HttpMethod?,
        ) = forward(endpoint, requestBody, config)

        override fun request(
            endpoint: String,
            requestBody: String,
            config: Config,
            isApiKeyRequired: Boolean,
            requestOptions: RequestOptions?,
            httpMethod: ApiConstants.HttpMethod?,
            params: Map<String, String>?,
        ) = forward(endpoint, requestBody, config)

        private fun forward(
            endpoint: String,
            body: String,
            config: Config,
        ): String {
            this.endpoint = endpoint
            return delegate.request(target.toString(), body, config)
        }
    }

    @Test
    fun `Adyen TerminalLocalAPI encrypts requests and decrypts replies over OkHttp`() {
        val server = server()
        server.reply(securedResponse(diagnosisOk()))
        val redirect = Redirect(server.url("/nexo/"), http())
        val transport = AdyenLocalTransport("localhost", key, tls(), redirect)
        val response = (runBlocking { transport.send(request, 5.seconds) } as Delivery.Answered).response!!
        assertThat(response.saleToPOIResponse.diagnosisResponse.response.result).isEqualTo(ResultType.SUCCESS)
        assertThat(redirect.endpoint).isEqualTo("https://localhost:8443/nexo/")

        val recorded = server.takeRequest()
        val secured = gson.fromJson(recorded.body!!.utf8(), TerminalAPISecuredRequest::class.java).saleToPOIRequest
        assertThat(secured.messageHeader.serviceID).isEqualTo("SVC1")
        assertThat(secured.securityTrailer.keyIdentifier).isEqualTo("key-id")
        assertThat(secured.securityTrailer.keyVersion).isEqualTo(3)
        val decrypted = gson.fromJson(terminalCrypto.decrypt(secured), TerminalAPIRequest::class.java)
        assertThat(decrypted.saleToPOIRequest.diagnosisRequest).isNotNull()
    }

    @Test
    fun `replies signed with another key are protocol errors that mention the shared key`() {
        val server = server()
        val other = NexoCrypto(TerminalKey("key-id", "wrong passphrase", 3).toSecurityKey())
        server.reply(
            gson.toJson(
                TerminalAPISecuredResponse().apply {
                    saleToPOIResponse = other.encrypt(gson.toJson(diagnosisOk()), header(MessageType.RESPONSE))
                },
            ),
        )
        server.reply("""{"SaleToPOIResponse":{"NexoBlob":"AAAA"}}""")
        val transport = AdyenLocalTransport("localhost", key, tls(), Redirect(server.url("/nexo/"), http()))
        val mismatch = runBlocking { transport.send(request, 5.seconds) } as Delivery.MaybeSent
        assertThat(mismatch.reason).contains("shared key")
        assertThat(runBlocking { transport.send(request, 5.seconds) }).isInstanceOf(Delivery.MaybeSent::class.java)
    }

    @Test
    fun `what the HTTP client throws decides whether the request can have taken effect`() {
        assertThat(TerminalUnreachableException("gone").toDelivery("x")).isEqualTo(Delivery.NotSent("gone"))
        assertThat(TerminalUntrustedException("stranger").toDelivery("x")).isEqualTo(Delivery.NotSent("stranger"))
        assertThat(TerminalRejectedException("wrong key").toDelivery("x")).isEqualTo(Delivery.NotSent("wrong key"))
        assertThat(TerminalProtocolException("garbled").toDelivery("x")).isEqualTo(Delivery.MaybeSent("garbled"))
        assertThat(IOException().toDelivery("no answer")).isEqualTo(Delivery.MaybeSent("no answer"))
        val server = server()
        server.reply("oops", code = 500)
        val transport = AdyenLocalTransport("localhost", key, tls(), Redirect(server.url("/nexo/"), http()))
        assertThat(runBlocking { transport.send(request, 5.seconds) }).isEqualTo(Delivery.MaybeSent("Terminal returned HTTP 500"))
    }

    @Test
    fun `empty replies are returned as they are`() {
        val server = server()
        server.reply("")
        assertThat(http().post(server.url("/nexo/"))).isEmpty()
    }

    @Test
    fun `rejections surface the terminal's explanation`() {
        val server = server()
        server.reply(
            """{"SaleToPOIRequest":{"MessageHeader":{"MessageClass":"Event","MessageCategory":"Event","MessageType":"Notification"},""" +
                """"EventNotification":{"EventToNotify":"Reject","EventDetails":"message=Crypto%20error"}}}""",
        )
        val event =
            gson.toJson(
                TerminalAPISecuredRequest().apply {
                    saleToPOIRequest =
                        terminalCrypto.encrypt(
                            """{"SaleToPOIRequest":{"EventNotification":""" +
                                """{"EventToNotify":"Reject","EventDetails":"message=Unknown%20POIID"}}}""",
                            header(MessageType.NOTIFICATION),
                        )
                },
            )
        server.reply(event)
        server.reply("""["Bad JSON:1: unexpected end"]""")
        server.reply("""{"SaleToPOIResponse":{"MessageHeader":{}}}""")
        val client = http()

        fun rejection() = assertThrows(TerminalRejectedException::class.java) { client.post(server.url("/nexo/")) }.message
        assertThat(rejection()).isEqualTo("Terminal rejected the request: Crypto error. ${TerminalHttpClient.KEY_ADVICE}")
        assertThat(rejection()).isEqualTo("Terminal rejected the request: Unknown POIID")
        assertThat(rejection()).isEqualTo("Terminal rejected the request: Bad JSON:1: unexpected end")
        assertThat(rejection()).isEqualTo("Terminal rejected the request")
    }

    @Test
    fun `malformed replies and HTTP errors are protocol errors`() {
        val server = server()
        server.reply("not json {")
        server.reply("oops", code = 500)
        val client = http()
        assertThrows(TerminalProtocolException::class.java) { client.post(server.url("/nexo/")) }
        assertThat(assertThrows(TerminalProtocolException::class.java) { client.post(server.url("/nexo/")) }.message)
            .isEqualTo("Terminal returned HTTP 500")
    }

    @Test
    fun `every request overload posts the body`() {
        val server = server()
        repeat(5) { server.reply("") }
        val client = http()
        val url = server.url("/nexo/").toString()
        client.request(url, "{}", config)
        client.request(url, "{}", config, false)
        client.request(url, "{}", config, false, null)
        client.request(url, "{}", config, false, null, ApiConstants.HttpMethod.POST)
        client.request(url, "{}", config, false, null, ApiConstants.HttpMethod.POST, emptyMap())
        assertThat(server.requestCount).isEqualTo(5)
    }

    @Test
    fun `slow terminals time out without looking unreachable`() {
        val server = server()
        server.enqueue(
            MockResponse
                .Builder()
                .body("")
                .headersDelay(2, TimeUnit.SECONDS)
                .build(),
        )
        val quick = Config().apply { readTimeoutMillis = 200 }
        val error = assertThrows(IOException::class.java) { http().request(server.url("/nexo/").toString(), "{}", quick) }
        assertThat(error).isNotInstanceOf(TerminalUnreachableException::class.java)
    }

    @Test
    fun `the environment is detected from the root the terminal certificate chains to`() {
        val liveRoot =
            HeldCertificate
                .Builder()
                .certificateAuthority(0)
                .commonName("Fake Adyen Live Root")
                .build()
        val detected = mutableListOf<TerminalEnvironment>()
        val tls =
            TerminalTls(
                mapOf(TerminalEnvironment.TEST to root.certificate, TerminalEnvironment.LIVE to liveRoot.certificate),
                onEnvironment = { detected += it },
            )
        val client = TerminalHttpClient(tls, terminalCrypto)
        val test = server()
        val live = server("S1F2-000158213605014.live.terminal.adyen.com", issuer = liveRoot)
        test.reply("")
        live.reply("")
        client.post(test.url("/nexo/"))
        client.post(live.url("/nexo/"))
        assertThat(detected).containsExactly(TerminalEnvironment.TEST, TerminalEnvironment.LIVE).inOrder()

        // A certificate must be named for the environment of the root that issued it.
        val mismatched = server("S1F2-000158213605014.test.terminal.adyen.com", issuer = liveRoot)
        mismatched.reply("")
        val error = assertThrows(TerminalUntrustedException::class.java) { client.post(mismatched.url("/nexo/")) }
        assertThat(error.message).contains("Adyen terminal certificate")
        assertThat(detected).hasSize(2)
        assertThat(tls.environmentOf(emptyList())).isNull()
        assertThat(
            tls.environmentOf(
                listOf(
                    HeldCertificate
                        .Builder()
                        .commonName("x")
                        .build()
                        .certificate,
                ),
            ),
        ).isNull()
    }

    @Test
    fun `wrong environment, foreign roots and non-terminal certificates are rejected`() {
        val server = server()
        server.reply("")
        assertThrows(TerminalUntrustedException::class.java) { http(TerminalEnvironment.LIVE).post(server.url("/nexo/")) }
        val stranger = HeldCertificate.Builder().certificateAuthority(0).build()
        assertThrows(TerminalUntrustedException::class.java) { http(trusted = stranger).post(server.url("/nexo/")) }
        val website = server("www.example.com")
        website.reply("")
        assertThrows(TerminalUntrustedException::class.java) { http().post(website.url("/nexo/")) }
        // Same checks over an IPv4 literal, where no fast-fallback race is involved.
        val ipv4 =
            server
                .url("/nexo/")
                .newBuilder()
                .host("127.0.0.1")
                .build()
        assertThrows(TerminalUntrustedException::class.java) { http(TerminalEnvironment.LIVE).post(ipv4) }
        assertThrows(TerminalUntrustedException::class.java) { http(trusted = stranger).post(ipv4) }
    }

    @Test
    fun `unreachable terminals are reported as such`() {
        val server = server()
        val port = server.port
        server.close()
        val closed =
            server
                .url("/nexo/")
                .newBuilder()
                .host("127.0.0.1")
                .port(port)
                .build()
        assertThrows(TerminalUnreachableException::class.java) { http().post(closed) }
        // A resolver that knows no hosts, so the test does not depend on (or wait for) the machine's DNS.
        val noDns = OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build()
        assertThrows(TerminalUnreachableException::class.java) {
            TerminalHttpClient(tls(), terminalCrypto, noDns).request("https://terminal.invalid:8443/nexo/", "{}", config)
        }
    }

    @Test
    fun `bundled Adyen roots match the published fingerprints`() {
        fun fingerprint(environment: TerminalEnvironment) =
            MessageDigest.getInstance("SHA-256").digest(TerminalTls.loadRoot(environment).encoded).joinToString("") {
                "%02X".format(Locale.ROOT, it)
            }
        assertThat(fingerprint(TerminalEnvironment.TEST)).isEqualTo("3A33C334C30F6946E9754B6BB1672B546FBAA966FB6A4B58AA4E3ABE80A7ECBE")
        assertThat(fingerprint(TerminalEnvironment.LIVE)).isEqualTo("06D48641954B957D7AF5F5E45A58D861DB0DE3CCEDBB983660BB016CE6142DA1")
        // By default both roots are trusted; the terminal's certificate tells which environment it belongs to.
        assertThat(TerminalTls().trustManager.acceptedIssuers.map { it.subjectX500Principal })
            .containsExactlyElementsIn(TerminalEnvironment.entries.map { TerminalTls.loadRoot(it).subjectX500Principal })
    }

    @Test
    fun `Adyen's validator accepts only terminal certificates of the environment`() {
        fun valid(
            commonName: String,
            environment: TerminalEnvironment = TerminalEnvironment.TEST,
        ) = TerminalCommonNameValidator.validateCertificate(
            HeldCertificate
                .Builder()
                .commonName(commonName)
                .build()
                .certificate,
            environment.adyen,
        )
        assertThat(valid("S1F2-000158213605014.test.terminal.adyen.com")).isTrue()
        assertThat(valid("legacy-terminal-certificate.test.terminal.adyen.com")).isTrue()
        assertThat(valid("S1F2-000158213605014.live.terminal.adyen.com")).isFalse()
        assertThat(valid("S1F2-000158213605014.live.terminal.adyen.com", TerminalEnvironment.LIVE)).isTrue()
        assertThat(valid("S1F2-12.test.terminal.adyen.com")).isFalse()
    }
}
