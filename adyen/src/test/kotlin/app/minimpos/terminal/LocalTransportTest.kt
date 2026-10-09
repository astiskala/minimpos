package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenLocalTransport
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.FaultException
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalHttpClient
import app.minimpos.terminal.transport.TerminalKey
import app.minimpos.terminal.transport.TerminalTls
import app.minimpos.terminal.transport.fault
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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
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
    fun `the certificate environment can be read before any encrypted API request`() =
        runBlocking {
            val server = server()
            val observed = mutableListOf<TerminalEnvironment>()
            val tls = TerminalTls(mapOf(TerminalEnvironment.TEST to root.certificate), onEnvironment = { observed += it })
            assertThat(tls.readEnvironment("localhost", server.port)).isEqualTo(TerminalEnvironment.TEST)
            assertThat(observed).containsExactly(TerminalEnvironment.TEST)
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(tls.readEnvironment("127.0.0.1", 1)).isNull()
            assertThat(tls.readEnvironment("localhost", server(commonName = "not-a-terminal").port)).isNull()
            assertThat(TerminalTls().readEnvironment("localhost", server.port)).isNull()
        }

    @Test
    fun `a network terminal certificate must match the selected environment before any request is sent`() =
        runBlocking {
            val server = server()
            val observed = mutableListOf<TerminalEnvironment>()
            val tls =
                TerminalTls(
                    mapOf(TerminalEnvironment.TEST to root.certificate),
                    onEnvironment = { observed += it },
                    expectedEnvironment = TerminalEnvironment.LIVE,
                )
            assertThat(tls.readEnvironment("localhost", server.port)).isNull()
            assertThat(observed).isEmpty()
            assertThat(server.requestCount).isEqualTo(0)
            assertThat(fault { TerminalHttpClient(tls, terminalCrypto).post(server.url("/nexo/")) }).isEqualTo(Fault.Untrusted("localhost"))
            assertThat(server.requestCount).isEqualTo(0)
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
    fun `replies signed with another key may have taken effect and point to the shared key`() {
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
        assertThat(runBlocking { transport.send(request, 5.seconds) }).isEqualTo(Delivery.Failed(Fault.ReplyUnverified))
        val garbled = runBlocking { transport.send(request, 5.seconds) } as Delivery.Failed
        assertThat(garbled.fault.mayHaveTakenEffect).isTrue()
    }

    @Test
    fun `what the HTTP client throws decides whether the request can have taken effect`() {
        assertThat(FaultException(Fault.Untrusted("stranger")).fault()).isEqualTo(Fault.Untrusted("stranger"))
        assertThat(SocketTimeoutException().fault()).isEqualTo(Fault.TimedOut)
        assertThat(IOException().fault()).isEqualTo(Fault.ConnectionLost)
        val server = server()
        server.reply("oops", code = 500)
        val transport = AdyenLocalTransport("localhost", key, tls(), Redirect(server.url("/nexo/"), http()))
        assertThat(runBlocking { transport.send(request, 5.seconds) }).isEqualTo(Delivery.Failed(Fault.TerminalHttp(500)))
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

        fun rejection() = fault { client.post(server.url("/nexo/")) }
        assertThat(rejection()).isEqualTo(Fault.KeyRejected(ExternalText("Crypto error")))
        assertThat(rejection()).isEqualTo(Fault.TerminalRejected(ExternalText("Unknown POIID")))
        assertThat(rejection()).isEqualTo(Fault.TerminalRejected(ExternalText("Bad JSON:1: unexpected end")))
        assertThat(rejection()).isEqualTo(Fault.TerminalRejected(null))
    }

    @Test
    fun `malformed replies and HTTP errors are protocol errors`() {
        val server = server()
        server.reply("not json {")
        server.reply("oops", code = 500)
        val client = http()
        assertThat(fault { client.post(server.url("/nexo/")) }).isEqualTo(Fault.UnreadableReply())
        assertThat(fault { client.post(server.url("/nexo/")) }).isEqualTo(Fault.TerminalHttp(500))
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
        assertThat(error.fault()).isEqualTo(Fault.TimedOut)
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
        assertThat(fault { client.post(mismatched.url("/nexo/")) }).isInstanceOf(Fault.Untrusted::class.java)
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
        assertThat(fault { http(TerminalEnvironment.LIVE).post(server.url("/nexo/")) }).isInstanceOf(Fault.Untrusted::class.java)
        val stranger = HeldCertificate.Builder().certificateAuthority(0).build()
        assertThat(fault { http(trusted = stranger).post(server.url("/nexo/")) }).isInstanceOf(Fault.Untrusted::class.java)
        val website = server("www.example.com")
        website.reply("")
        assertThat(fault { http().post(website.url("/nexo/")) }).isInstanceOf(Fault.Untrusted::class.java)
        // Same checks over an IPv4 literal, where no fast-fallback race is involved.
        val ipv4 =
            server
                .url("/nexo/")
                .newBuilder()
                .host("127.0.0.1")
                .build()
        assertThat(fault { http(TerminalEnvironment.LIVE).post(ipv4) }).isEqualTo(Fault.Untrusted("127.0.0.1"))
        assertThat(fault { http(trusted = stranger).post(ipv4) }).isEqualTo(Fault.Untrusted("127.0.0.1"))
    }

    @Test(timeout = 5_000)
    fun `TLS failures hidden in causes or suppressed exceptions are untrusted, without following cyclic causes`() {
        fun thrown(error: IOException): Fault {
            val failing = OkHttpClient.Builder().addInterceptor { throw error }.build()
            return fault { TerminalHttpClient(tls(), terminalCrypto, failing).request("https://terminal.invalid:8443/nexo/", "{}", config) }
        }
        val cycle = ConnectException("refused")
        val nested = IOException("nested")
        cycle.initCause(nested)
        nested.initCause(cycle)
        assertThat(thrown(cycle)).isEqualTo(Fault.Unreachable("terminal.invalid:8443", terminal = true))

        val untrusted = Fault.Untrusted("terminal.invalid")
        assertThat(
            thrown(ConnectException("refused").apply { addSuppressed(SSLPeerUnverifiedException("suppressed")) }),
        ).isEqualTo(untrusted)
        assertThat(thrown(ConnectException("refused").apply { initCause(SSLHandshakeException("cause")) })).isEqualTo(untrusted)
        val outerTls = SSLHandshakeException("outer").apply { addSuppressed(SSLPeerUnverifiedException("suppressed")) }
        assertThat(thrown(ConnectException("refused").apply { initCause(outerTls) })).isEqualTo(untrusted)
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
        assertThat(fault { http().post(closed) }).isEqualTo(Fault.Unreachable("127.0.0.1:$port", terminal = true))
        // A resolver that knows no hosts, so the test does not depend on (or wait for) the machine's DNS.
        val noDns = OkHttpClient.Builder().dns { throw UnknownHostException(it) }.build()
        assertThat(fault { TerminalHttpClient(tls(), terminalCrypto, noDns).request("https://terminal.invalid:8443/nexo/", "{}", config) })
            .isEqualTo(Fault.UnknownHost("terminal.invalid", terminal = true))
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

    /** The fault [block] throws, as the transport turns it into a delivery. */
    private fun fault(block: () -> Unit): Fault = assertThrows(FaultException::class.java) { block() }.fault
}
