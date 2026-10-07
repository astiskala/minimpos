package io.github.astiskala.minimpos.terminal

import com.adyen.model.nexo.MessageCategoryType
import com.adyen.model.nexo.MessageClassType
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.MessageType
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.model.terminal.TerminalAPISecuredRequest
import com.adyen.model.terminal.TerminalAPISecuredResponse
import com.adyen.terminal.security.NexoCrypto
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import io.github.astiskala.minimpos.terminal.client.PaymentParams
import io.github.astiskala.minimpos.terminal.client.PrintJob
import io.github.astiskala.minimpos.terminal.client.PrintLine
import io.github.astiskala.minimpos.terminal.client.PrintOutcome
import io.github.astiskala.minimpos.terminal.client.RecoveryPolicy
import io.github.astiskala.minimpos.terminal.client.RefundParams
import io.github.astiskala.minimpos.terminal.client.TerminalClient
import io.github.astiskala.minimpos.terminal.client.TerminalIdentity
import io.github.astiskala.minimpos.terminal.client.TransactionOutcome
import io.github.astiskala.minimpos.terminal.parse.FormEncoding
import io.github.astiskala.minimpos.terminal.paymentsapp.AdyenPaymentsAppManagement
import io.github.astiskala.minimpos.terminal.paymentsapp.AppLinkExchange
import io.github.astiskala.minimpos.terminal.paymentsapp.BoardingTarget
import io.github.astiskala.minimpos.terminal.paymentsapp.ManagementResult
import io.github.astiskala.minimpos.terminal.paymentsapp.Onboarding
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppLinks
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppManagement
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppOnboarding
import io.github.astiskala.minimpos.terminal.paymentsapp.PaymentsAppTransport
import io.github.astiskala.minimpos.terminal.simulator.SimulatorConfig
import io.github.astiskala.minimpos.terminal.simulator.TerminalSimulator
import io.github.astiskala.minimpos.terminal.transport.Delivery
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalKey
import io.github.astiskala.minimpos.terminal.transport.TerminalUnreachableException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.net.URLDecoder
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class PaymentsAppTest {
    private val key = TerminalKey("key-id", "correct horse battery staple", 1)
    private val app = FakePaymentsApp(key)
    private val transport = PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL)
    private val client =
        TerminalClient(
            transport,
            TerminalIdentity("MiniMPOS", "INSTALLATION-1"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1, maxInProgressChecks = 1),
        )
    private val payment = PaymentParams(BigDecimal("12.50"), "EUR", "260930-1")

    @Test
    fun `links follow Adyen's formats for each environment`() {
        val test = PaymentsAppLinks(TerminalEnvironment.TEST)
        val live = PaymentsAppLinks(TerminalEnvironment.LIVE)
        assertThat(test.packageName).isEqualTo("com.adyen.ipp.mobile.companion.test")
        assertThat(live.packageName).isEqualTo("com.adyen.ipp.mobile.companion.live")
        // As in Adyen's example code, the return URL is URL-encoded before it is added as a parameter.
        assertThat(test.boardedCheck("minimpos://paymentsapp/boarded"))
            .isEqualTo("https://www.adyen.com/test/boarded?returnUrl=minimpos%253A%252F%252Fpaymentsapp%252Fboarded")
        assertThat(
            live.boardedCheck("app://x", reboard = true),
        ).isEqualTo("https://www.adyen.com/boarded?returnUrl=app%253A%252F%252Fx&reboard=true")
        val board = live.board("token-1", "app://x")
        assertThat(board).startsWith("https://www.adyen.com/board?boardingToken=")
        val token = FormEncoding.decode(board.substringAfter('?')).getValue("boardingToken")
        assertThat(String(Base64.getUrlDecoder().decode(token))).isEqualTo("token-1")
        assertThat(test.nexo("abc=", "app://x")).isEqualTo("https://www.adyen.com/test/nexo?request=abc%3D&returnUrl=app%253A%252F%252Fx")
        assertThat(
            PaymentsAppLinks.answer("app://x?boarded=true&installationId=ID%201"),
        ).containsExactly("boarded", "true", "installationId", "ID 1")
        assertThat(PaymentsAppLinks.answer("app://x")).isEmpty()
    }

    @Test
    fun `a payment and a refund travel encrypted in links and come back through the return URL`() {
        val paid = runBlocking { client.pay(payment, serviceId = "PAY1") } as TransactionOutcome.Completed
        assertThat(paid.details.success).isTrue()
        val opened = app.opened.single()
        assertThat(opened.packageName).isEqualTo("com.adyen.ipp.mobile.companion.test")
        assertThat(opened.link).startsWith("https://www.adyen.com/test/nexo?request=")
        assertThat(opened.returnUrl).isEqualTo("$RETURN_URL/nexo")
        // The request in the link is encrypted, not readable.
        assertThat(opened.link).doesNotContain("PaymentRequest")
        assertThat(String(Base64.getUrlDecoder().decode(opened.request))).contains("NexoBlob")

        val refund =
            RefundParams(paid.details.poiTransactionId!!, paid.details.poiTimestamp!!, "R-260930-1")
        val refunded = runBlocking { client.refund(refund, serviceId = "REF1") } as TransactionOutcome.Completed
        assertThat(refunded.details.success).isTrue()
    }

    @Test
    fun `only payments and refunds go to the Payments app`() {
        assertThat(runBlocking { client.diagnose() }.reachable).isFalse()
        assertThat(runBlocking { client.print(listOf(PrintJob.Text(listOf(PrintLine.Text("x"))))) })
            .isInstanceOf(PrintOutcome.Failed::class.java)
        runBlocking { client.abort("PAY1") }
        // Without a late answer, a status check cannot be made, so the outcome stays unknown.
        assertThat(runBlocking { client.status("PAY1") }).isInstanceOf(TransactionOutcome.Unknown::class.java)
        assertThat(app.opened).isEmpty()
    }

    @Test
    fun `an answer that arrived after a restart settles the payment's status check`() {
        app.keepAnswers = true
        // The payment's own status check already finds the answer.
        val paid = runBlocking { client.pay(payment, serviceId = "PAY2") } as TransactionOutcome.Completed
        assertThat(paid.recovered).isTrue()
        val recovered = runBlocking { client.status("PAY2") } as TransactionOutcome.Completed
        assertThat(recovered.recovered).isTrue()
        assertThat(recovered.details.success).isTrue()
        // Answers to other requests do not count.
        assertThat(runBlocking { client.status("OTHER") }).isInstanceOf(TransactionOutcome.Unknown::class.java)
    }

    @Test
    fun `errors, foreign answers and wrong keys are reported as such`() {
        app.reply = { "$RETURN_URL/nexo?error=Cancelled+by+user" }
        assertThat((runBlocking { client.pay(payment) } as TransactionOutcome.NotProcessed).reason).contains("Cancelled by user")
        app.reply = { "$RETURN_URL/nexo?response=bm90IGpzb24" }
        assertThat(runBlocking { client.pay(payment) }).isInstanceOf(TransactionOutcome.Unknown::class.java)
        // A Payments app with another shared key answers in a way that cannot be verified.
        val stranger = FakePaymentsApp(TerminalKey("key-id", "another passphrase", 1))
        val mismatched = PaymentsAppTransport(key, TerminalEnvironment.TEST, stranger, RETURN_URL)
        val request =
            TerminalAPIRequest().apply {
                saleToPOIRequest =
                    SaleToPOIRequest().apply {
                        messageHeader =
                            MessageHeader()
                                .apply { serviceID = "X" }
                        paymentRequest =
                            PaymentRequest()
                    }
            }
        stranger.reply = { stranger.encryptedAnswer(it, serviceId = "X") }
        val failure = runBlocking { mismatched.send(request, 5.seconds) } as Delivery.MaybeSent
        assertThat(failure.reason).contains("shared key")
        // An answer to another request is not taken for this one.
        app.reply = { app.encryptedAnswer(it, serviceId = "SOMETHING-ELSE") }
        assertThat(
            runBlocking { PaymentsAppTransport(key, TerminalEnvironment.TEST, app, RETURN_URL).send(request, 5.seconds) },
        ).isInstanceOf(Delivery.MaybeSent::class.java)
        // A Payments app that cannot be started means nothing was sent.
        app.reply = { throw TerminalUnreachableException("not installed") }
        assertThat(runBlocking { client.pay(payment) }).isInstanceOf(TransactionOutcome.NotProcessed::class.java)
    }

    @Test
    fun `boarding asks the Payments app, gets a token from Adyen and hands it over`() {
        val management = RecordingManagement()
        val onboarding = PaymentsAppOnboarding(TerminalEnvironment.TEST, app, management, RETURN_URL)
        app.boarded = false
        val boarded = runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM", "ST1")) }
        assertThat(boarded).isEqualTo(Onboarding.Boarded("INSTALLATION-1"))
        assertThat(management.requests).containsExactly(BoardingTarget("HarbourCoffeeCOM", "ST1") to "BRT-1")
        assertThat(app.opened.map { it.returnUrl }).containsExactly("$RETURN_URL/boarded", "$RETURN_URL/board").inOrder()
        assertThat(app.boardingTokens).containsExactly("BT-1")

        // Once boarded, the check alone answers; reboarding goes through the steps again.
        assertThat(runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM")) }).isEqualTo(Onboarding.Boarded("INSTALLATION-1"))
        assertThat(management.requests).hasSize(1)
        assertThat(runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM"), reboard = true) })
            .isEqualTo(Onboarding.Boarded("INSTALLATION-1"))
        assertThat(app.opened.count { it.link.contains("reboard=true") }).isEqualTo(1)
        assertThat(management.requests).hasSize(2)

        // Failures from Adyen and from the Payments app are reported.
        app.boarded = false
        management.result = ManagementResult.Failed("Forbidden (HTTP 403)")
        assertThat(
            runBlocking { onboarding.board(BoardingTarget("HarbourCoffeeCOM")) },
        ).isEqualTo(Onboarding.Failed("Forbidden (HTTP 403)"))
        management.result = ManagementResult.Done(boardingToken = null)
        assertThat(
            (
                runBlocking {
                    onboarding.board(BoardingTarget("HarbourCoffeeCOM"))
                } as Onboarding.Failed
            ).message,
        ).contains("no boarding token")
        app.reply = { "$RETURN_URL/boarded?boarded=false&error=Device+not+supported" }
        assertThat(
            (
                runBlocking {
                    onboarding.board(BoardingTarget("HarbourCoffeeCOM"))
                } as Onboarding.Failed
            ).message,
        ).contains("Device not supported")
        app.reply = { "$RETURN_URL/boarded?boarded=true" }
        assertThat(
            (
                runBlocking {
                    onboarding.board(BoardingTarget("HarbourCoffeeCOM"))
                } as Onboarding.Failed
            ).message,
        ).contains("no installation ID")
        assertThrows(IllegalArgumentException::class.java) { BoardingTarget(" ") }
    }

    @Test
    fun `the Management API is called with the Payments app key, per merchant account or store`() {
        MockWebServer().use { server ->
            server.start()
            val api = AdyenPaymentsAppManagement("pa-key", TerminalEnvironment.TEST, baseUrl = server.url("/v1"))
            server.enqueue(MockResponse.Builder().body("""{"installationId":"I1","boardingToken":"BT"}""").build())
            assertThat(runBlocking { api.boardingToken(BoardingTarget("Merchant"), "BRT") }).isEqualTo(ManagementResult.Done("BT"))
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/v1/merchants/Merchant/generatePaymentsAppBoardingToken")
            assertThat(request.headers["x-api-key"]).isEqualTo("pa-key")
            assertThat(JsonParser.parseString(request.body!!.utf8()).asJsonObject["boardingRequestToken"].asString).isEqualTo("BRT")

            server.enqueue(MockResponse.Builder().body("{}").build())
            runBlocking { api.boardingToken(BoardingTarget("Merchant", "ST1"), "BRT") }
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/merchants/Merchant/stores/ST1/generatePaymentsAppBoardingToken")

            server.enqueue(MockResponse.Builder().code(200).build())
            assertThat(runBlocking { api.revoke("Merchant", "I1") }).isEqualTo(ManagementResult.Done())
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/v1/merchants/Merchant/paymentsApps/I1/revoke")

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(403)
                    .body("""{"detail":"Not allowed"}""")
                    .build(),
            )
            assertThat(runBlocking { api.revoke("Merchant", "I1") }).isEqualTo(ManagementResult.Failed("Not allowed (HTTP 403)"))
            server.enqueue(MockResponse.Builder().code(401).build())
            assertThat((runBlocking { api.revoke("Merchant", "I1") } as ManagementResult.Failed).message).contains("did not accept")
            server.enqueue(MockResponse.Builder().code(403).build())
            assertThat(
                (runBlocking { api.revoke("Merchant", "I1") } as ManagementResult.Failed).message,
            ).contains("Adyen Payments app role")
            server.enqueue(MockResponse.Builder().code(500).build())
            assertThat((runBlocking { api.revoke("Merchant", "I1") } as ManagementResult.Failed).message).contains("HTTP 500")
        }
        val offline =
            AdyenPaymentsAppManagement(
                "pa-key",
                TerminalEnvironment.LIVE,
                baseUrl =
                    HttpUrl
                        .Builder()
                        .scheme("http")
                        .host("127.0.0.1")
                        .port(1)
                        .build(),
            )
        val failed = runBlocking { offline.revoke("Merchant", "I1") } as ManagementResult.Failed
        assertThat(failed.message).contains("Cannot connect to Adyen")
        assertThat(AdyenPaymentsAppManagement.endpoint(TerminalEnvironment.LIVE)).isEqualTo("https://management-live.adyen.com/v1")
    }

    @Test
    fun `registration checks the intended merchant and store without boarding mutations`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenPaymentsAppManagement("dummy-key", TerminalEnvironment.TEST, baseUrl = server.url("/v1/"))
                val target = BoardingTarget("Merchant", "ST1")
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(
                            """{"paymentsApps":[{"installationId":"I1","merchantAccountCode":"Merchant","status":"BOARDED"}]}""",
                        ).build(),
                )
                assertThat(api.registration(target, "I1")).isEqualTo(ManagementResult.Done())
                val request = server.takeRequest()
                assertThat(request.method).isEqualTo("GET")
                assertThat(request.url.encodedPath).isEqualTo("/v1/merchants/Merchant/stores/ST1/paymentsApps")
                assertThat(request.url.queryParameter("statuses")).isEqualTo("BOARDED")
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body(
                            """{"paymentsApps":[{"installationId":"I1","merchantAccountCode":"Other","status":"BOARDED"}]}""",
                        ).build(),
                )
                assertThat(api.registration(target, "I1")).isInstanceOf(ManagementResult.Failed::class.java)
                listOf(
                    "{}",
                    "not json",
                    """{"paymentsApps":[{"installationId":{}}]}""",
                    """{"paymentsApps":[null]}""",
                ).forEach { body ->
                    server.enqueue(MockResponse.Builder().body(body).build())
                    assertThat(api.registration(target, "I1")).isInstanceOf(ManagementResult.Failed::class.java)
                }
                server.enqueue(MockResponse.Builder().code(403).build())
                assertThat(api.registration(target, "I1")).isInstanceOf(ManagementResult.Failed::class.java)
            }
        }

    @Test
    fun `registration pagination is bounded and never follows server-provided links`() =
        runBlocking {
            MockWebServer().use { server ->
                server.start()
                val api = AdyenPaymentsAppManagement("dummy-key", TerminalEnvironment.TEST, baseUrl = server.url("/v1/"))
                val full = """{"paymentsApps":[${List(
                    100,
                ) { """{"installationId":"Other","merchantAccountCode":"Merchant","status":"BOARDED"}""" }.joinToString(",")}]}"""
                repeat(100) { server.enqueue(MockResponse.Builder().body(full).build()) }
                assertThat(api.registration(BoardingTarget("Merchant"), "I1")).isInstanceOf(ManagementResult.Failed::class.java)
                assertThat(server.requestCount).isEqualTo(100)
                assertThat(server.takeRequest().url.queryParameter("offset")).isEqualTo("0")
                assertThat(server.takeRequest().url.queryParameter("offset")).isEqualTo("100")
            }
        }

    /** One link the fake Payments app was asked to open. */
    private data class Opened(
        val link: String,
        val packageName: String,
    ) {
        val parameters = FormEncoding.decode(link.substringAfter('?'))
        val returnUrl: String = URLDecoder.decode(parameters.getValue("returnUrl"), Charsets.UTF_8)
        val request: String? = parameters["request"]
    }

    /** Plays the Adyen Payments app: boards, and answers payments and refunds with the simulator. */
    private class FakePaymentsApp(
        key: TerminalKey,
    ) : AppLinkExchange {
        private val gson = TerminalAPIGsonBuilder.create()
        private val crypto = NexoCrypto(key.toSecurityKey())
        private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })
        val opened = mutableListOf<Opened>()
        val boardingTokens = mutableListOf<String>()
        var boarded = true

        /** Keeps answers as if the app had been restarted, so the exchange times out. */
        var keepAnswers = false
        private val late = mutableListOf<String>()

        /** Replaces the answer to the next links. */
        var reply: ((Opened) -> String)? = null

        override suspend fun exchange(
            link: String,
            packageName: String,
            timeout: Duration,
        ): String {
            val opened = Opened(link, packageName).also { this.opened += it }
            val answer = reply?.invoke(opened) ?: answer(opened)
            if (keepAnswers) {
                late += answer
                throw IOException("The Payments app did not call back")
            }
            return answer
        }

        override fun lateReplies(): List<String> = late

        private suspend fun answer(opened: Opened): String =
            when {
                opened.link.contains("/boarded?") -> {
                    if (boarded && !opened.link.contains("reboard=true")) {
                        "${opened.returnUrl}?boarded=true&installationId=INSTALLATION-1"
                    } else {
                        "${opened.returnUrl}?boarded=false&installationId=INSTALLATION-1&boardingRequestToken=BRT-1"
                    }
                }

                opened.link.contains("/board?") -> {
                    boardingTokens += String(Base64.getUrlDecoder().decode(opened.parameters.getValue("boardingToken")))
                    boarded = true
                    "${opened.returnUrl}?boarded=true&installationId=INSTALLATION-1"
                }

                else -> {
                    val secured =
                        gson.fromJson(
                            String(Base64.getUrlDecoder().decode(opened.request)),
                            TerminalAPISecuredRequest::class.java,
                        )
                    val request = gson.fromJson(crypto.decrypt(secured.saleToPOIRequest), TerminalAPIRequest::class.java)
                    val response = checkNotNull((simulator.send(request, 10.seconds) as Delivery.Answered).response)
                    "${opened.returnUrl}?response=${encrypt(gson.toJson(response), response.saleToPOIResponse.messageHeader)}"
                }
            }

        /** An answer that says it is for [serviceId], encrypted with this app's key. */
        fun encryptedAnswer(
            opened: Opened,
            serviceId: String,
        ): String {
            val header =
                MessageHeader().apply {
                    this.serviceID = serviceId
                    messageCategory = MessageCategoryType.PAYMENT
                    messageClass = MessageClassType.SERVICE
                    messageType = MessageType.RESPONSE
                }
            val response =
                TerminalAPIResponse().apply {
                    saleToPOIResponse =
                        SaleToPOIResponse()
                            .apply { messageHeader = header }
                }
            return "${opened.returnUrl}?response=${encrypt(gson.toJson(response), header)}"
        }

        private fun encrypt(
            json: String,
            header: MessageHeader,
        ): String {
            val secured = TerminalAPISecuredResponse().apply { saleToPOIResponse = crypto.encrypt(json, header) }
            return Base64.getUrlEncoder().encodeToString(gson.toJson(secured).toByteArray())
        }
    }

    /** Records boarding token requests and answers with [result]. */
    private class RecordingManagement : PaymentsAppManagement {
        val requests = mutableListOf<Pair<BoardingTarget, String>>()
        var result: ManagementResult = ManagementResult.Done("BT-1")

        override suspend fun boardingToken(
            target: BoardingTarget,
            boardingRequestToken: String,
        ): ManagementResult {
            requests += target to boardingRequestToken
            return result
        }

        override suspend fun revoke(
            merchantAccount: String,
            installationId: String,
        ): ManagementResult = result
    }

    private companion object {
        const val RETURN_URL = "minimpos://paymentsapp"
    }
}
