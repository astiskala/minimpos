package app.minimpos.terminal

import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutModifications
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.simulator.SimulatedModifications
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class CheckoutModificationsTest {
    private val server = MockWebServer().apply { start() }
    private val credentials = CheckoutCredentials("secret-api-key", "HarbourCoffeeCOM", TerminalEnvironment.TEST)
    private val api = CheckoutModifications(credentials, baseUrl = server.url("/v72/"))
    private val amount = ModificationAmount("AUD", 3_900)

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

    @Test
    fun `captures and both adjustment modes send identical sanitized Terminal API application info`() =
        runBlocking {
            val application = PosApplication(" (Mini) mPOS! ", "1.2.0-beta_1", "!!!", "Android", "13").toApplicationInfo()
            val identified = CheckoutModifications(credentials, baseUrl = server.url("/v72/"), application = application)
            val expected = JsonParser.parseString(TerminalAPIGsonBuilder.create().toJson(application))
            repeat(3) { reply("""{"status":"received"}""") }
            identified.capture("PSP1", amount, "ref", "capture")
            identified.updateAmount("PSP1", amount, "ref", "blob", "sync")
            identified.updateAmount("PSP1", amount, "ref", null, "async")
            repeat(3) {
                val body = JsonParser.parseString(server.takeRequest().body!!.utf8()).asJsonObject
                assertThat(body["applicationInfo"]).isEqualTo(expected)
            }
            reply("""{"paymentMethods":[]}""")
            assertThat(identified.verify()).isNull()
            assertThat(JsonParser.parseString(server.takeRequest().body!!.utf8()).asJsonObject.has("applicationInfo")).isFalse()
        }

    @Test
    fun `a capture posts the amount with the API key and idempotency key, and is received`() {
        reply("""{"pspReference":"CAP123","status":"received","paymentPspReference":"PSP1"}""", code = 201)
        val result = runBlocking { api.capture("PSP1", amount, "260930-1", "capture-sale-3900") }
        assertThat(result).isEqualTo(ModificationResult.Received("CAP123"))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v72/payments/PSP1/captures")
        assertThat(request.headers["x-api-key"]).isEqualTo("secret-api-key")
        assertThat(request.headers["Idempotency-Key"]).isEqualTo("capture-sale-3900")
        val body = JsonParser.parseString(request.body!!.utf8()).asJsonObject
        assertThat(body).isEqualTo(
            JsonParser.parseString(
                """
                {
                    "merchantAccount":"HarbourCoffeeCOM",
                    "amount":{"currency":"AUD","value":3900},
                    "reference":"260930-1"
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an adjustment with the blob is synchronous and returns the next blob`() {
        reply("""{"pspReference":"ADJ1","status":"Authorised","adjustAuthorisationData":"BQABAQnext"}""")
        val result = runBlocking { api.updateAmount("PSP1", amount, "ref", "BQABAQfirst", "adjust-1") }
        assertThat(result).isEqualTo(ModificationResult.Authorised("ADJ1", "BQABAQnext"))
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.url.encodedPath).isEqualTo("/v72/payments/PSP1/amountUpdates")
        assertThat(request.headers["x-api-key"]).isEqualTo("secret-api-key")
        assertThat(request.headers["Idempotency-Key"]).isEqualTo("adjust-1")
        val body = JsonParser.parseString(request.body!!.utf8()).asJsonObject
        assertThat(body).isEqualTo(
            JsonParser.parseString(
                """
                {
                    "merchantAccount":"HarbourCoffeeCOM",
                    "amount":{"currency":"AUD","value":3900},
                    "reference":"ref",
                    "industryUsage":"delayedCharge",
                    "adjustAuthorisationData":"BQABAQfirst"
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `an adjustment without the blob is asynchronous, and a refusal keeps its reason`() {
        reply("""{"pspReference":"ADJ2","status":"received"}""", code = 201)
        reply("""{"status":"Refused","refusalReason":"Not enough balance"}""")
        reply("""{"status":"refused"}""")
        runBlocking {
            assertThat(api.updateAmount("PSP1", amount, "ref", null, "a")).isEqualTo(ModificationResult.Received("ADJ2"))
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/v72/payments/PSP1/amountUpdates")
            assertThat(request.headers["Idempotency-Key"]).isEqualTo("a")
            assertThat(JsonParser.parseString(request.body!!.utf8())).isEqualTo(
                JsonParser.parseString(
                    """
                    {
                        "merchantAccount":"HarbourCoffeeCOM",
                        "amount":{"currency":"AUD","value":3900},
                        "reference":"ref",
                        "industryUsage":"delayedCharge"
                    }
                    """.trimIndent(),
                ),
            )
            assertThat(
                api.updateAmount("PSP1", amount, "ref", "blob", "b"),
            ).isEqualTo(ModificationResult.Refused(ExternalText("Not enough balance")))
            assertThat(api.updateAmount("PSP1", amount, "ref", "blob", "c")).isEqualTo(ModificationResult.Refused(null))
        }
    }

    @Test
    fun `client errors are not processed, while server errors, throttling and odd answers are unknown`() {
        reply("""{"status":422,"errorCode":"167","message":"Original pspReference required","errorType":"validation"}""", code = 422)
        reply("""{"status":401,"message":"HTTP Status Response - Unauthorized"}""", code = 401)
        reply("""{"status":500,"message":"Internal error"}""", code = 500)
        reply("busy", code = 429)
        reply("not json")
        reply("""{"status":"pending"}""")
        runBlocking {
            assertThat(api.capture("PSP1", amount, "r", "k"))
                .isEqualTo(failed(Fault.AdyenRejected(422, "167", ExternalText("Original pspReference required"))))
            assertThat(api.capture("PSP1", amount, "r", "k")).isEqualTo(failed(Fault.Credential(ApiKey.ADYEN)))
            assertThat(
                api.capture("PSP1", amount, "r", "k"),
            ).isEqualTo(failed(Fault.AdyenUnavailable(500, null, ExternalText("Internal error"))))
            assertThat(api.capture("PSP1", amount, "r", "k")).isEqualTo(failed(Fault.AdyenUnavailable(429)))
            assertThat(api.capture("PSP1", amount, "r", "k")).isEqualTo(failed(Fault.UnreadableReply()))
            assertThat(api.capture("PSP1", amount, "r", "k")).isEqualTo(failed(Fault.UnreadableReply(ExternalText("pending"))))
        }
    }

    @Test
    fun `a request that never arrives is not processed, and a timeout is unknown`() {
        val slow =
            CheckoutModifications(credentials, baseUrl = server.url("/v72/"), timeout = 200.milliseconds)
        server.enqueue(
            MockResponse
                .Builder()
                .body("{}")
                .headersDelay(2, TimeUnit.SECONDS)
                .build(),
        )
        assertThat(runBlocking { slow.capture("PSP1", amount, "r", "k") }).isEqualTo(failed(Fault.TimedOut))

        val closed = MockWebServer().apply { start() }
        val url = closed.url("/v72/")
        closed.close()
        val result = runBlocking { CheckoutModifications(credentials, baseUrl = url).capture("PSP1", amount, "r", "k") }
        assertThat(result).isEqualTo(failed(Fault.Unreachable(url.host, terminal = false)))
        assertThat(result.toString()).doesNotContain("secret-api-key")
    }

    @Test
    fun `verify asks for payment methods and explains a refused key or merchant account`() {
        reply("""{"paymentMethods":[]}""")
        reply("{}", code = 401)
        reply("{}", code = 403)
        reply("""{"message":"Invalid merchant account","errorCode":"901"}""", code = 422)
        runBlocking {
            assertThat(api.verify()).isNull()
            val request = server.takeRequest()
            assertThat(request.url.encodedPath).isEqualTo("/v72/paymentMethods")
            assertThat(request.headers["Idempotency-Key"]).isNull()
            assertThat(JsonParser.parseString(request.body!!.utf8())).isEqualTo(
                JsonParser.parseString("""{"merchantAccount":"HarbourCoffeeCOM"}"""),
            )
            assertThat(api.verify()).isEqualTo(Fault.Credential(ApiKey.ADYEN))
            assertThat(api.verify()).isEqualTo(Fault.Permission(ApiKey.ADYEN))
            assertThat(api.verify()).isEqualTo(Fault.AdyenRejected(422, "901", ExternalText("Invalid merchant account")))
        }
    }

    @Test
    fun `typed replies ignore extra fields but never infer success from unknown or malformed statuses`() =
        runBlocking {
            listOf("authorised", "Authorised", "AUTHORISED").forEach { status ->
                reply("""{"status":"$status","pspReference":"ADJ","adjustAuthorisationData":"next","futureField":{}}""")
                assertThat(api.updateAmount("PSP", amount, "r", "blob", "k"))
                    .isEqualTo(ModificationResult.Authorised("ADJ", "next"))
            }
            listOf("""{"status":"future"}""", """{"status":true}""", """{"status":"received"} {}""").forEach { body ->
                reply(body)
                val answer = api.updateAmount("PSP", amount, "r", "blob", "k")
                assertThat((answer as ModificationResult.Failed).fault).isInstanceOf(Fault.UnreadableReply::class.java)
            }
            reply("""{"status":"received","pspReference":"CAP","futureField":{}}""")
            assertThat(api.capture("PSP", amount, "r", "k")).isEqualTo(ModificationResult.Received("CAP"))
        }

    @Test
    fun `credentials pick the endpoint for the environment and keep the key out of their text`() {
        assertThat(credentials.baseUrl).isEqualTo("https://checkout-test.adyen.com/v72")
        val live = CheckoutCredentials("key", "Merchant", TerminalEnvironment.LIVE, " 1797a841fbb37ca7-AdyenDemo ")
        assertThat(live.baseUrl).isEqualTo("https://1797a841fbb37ca7-AdyenDemo-checkout-live.adyenpayments.com/checkout/v72")
        assertThat(credentials.toString()).isEqualTo("CheckoutCredentials(HarbourCoffeeCOM, TEST)")
        assertThrows(IllegalArgumentException::class.java) { CheckoutCredentials("key", "Merchant", TerminalEnvironment.LIVE) }
        assertThrows(IllegalArgumentException::class.java) { CheckoutCredentials(" ", "Merchant", TerminalEnvironment.TEST) }
        assertThrows(IllegalArgumentException::class.java) { CheckoutCredentials("key", "", TerminalEnvironment.TEST) }
    }

    @Test
    fun `the simulated API accepts everything and adjusts synchronously only with a blob`() {
        val simulated = SimulatedModifications(Random(1))
        runBlocking {
            assertThat(simulated.capture("PSP", amount, "r", "k")).isInstanceOf(ModificationResult.Received::class.java)
            assertThat(simulated.updateAmount("PSP", amount, "r", null, "k")).isInstanceOf(ModificationResult.Received::class.java)
            val adjusted = simulated.updateAmount("PSP", amount, "r", "BQABAQold", "k") as ModificationResult.Authorised
            assertThat(adjusted.adjustAuthorisationData).startsWith("BQABAQ")
            assertThat(adjusted.adjustAuthorisationData).isNotEqualTo("BQABAQold")
            assertThat(simulated.verify()).isNull()
        }
    }

    private fun failed(fault: Fault) = ModificationResult.Failed(fault)
}
