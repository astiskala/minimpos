package app.minimpos.terminal

import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutPaymentLinks
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.PaymentLink
import app.minimpos.terminal.checkout.PaymentLinkLineItem
import app.minimpos.terminal.checkout.PaymentLinkRequest
import app.minimpos.terminal.checkout.PaymentLinkResult
import app.minimpos.terminal.checkout.PaymentLinkStatus
import app.minimpos.terminal.client.PosApplication
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
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

class CheckoutPaymentLinksTest {
    private val server = MockWebServer().apply { start() }
    private val credentials = CheckoutCredentials("secret-api-key", "HarbourCoffeeCOM", TerminalEnvironment.TEST)
    private val api = CheckoutPaymentLinks(credentials, baseUrl = server.url("/v72/"))
    private val expiresAt = Instant.parse("2026-10-03T09:30:00.250Z")
    private val request =
        PaymentLinkRequest(
            reference = "261002-093000-AB12",
            amount = ModificationAmount("AUD", 1_200),
            expiresAt = expiresAt,
        )

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

    private fun link(status: String) =
        """{"id":"PL123","url":"https://test.adyen.link/PL123","status":"$status","expiresAt":"2026-10-03T09:30:00Z"}"""

    @Test
    fun `link creation sends identical sanitized Terminal API application info but expiration does not`() =
        runBlocking {
            val application =
                PosApplication("Mini mPOS", "1.2.0", "Acme POS", "Android", "13", platformName = "Acme", platformVersion = "2.0")
                    .toApplicationInfo()
            val identified = CheckoutPaymentLinks(credentials, baseUrl = server.url("/v72/"), application = application)
            reply(link("active"), code = 201)
            identified.create(request, "link")
            val body = JsonParser.parseString(server.takeRequest().body!!.utf8()).asJsonObject
            assertThat(body["applicationInfo"]).isEqualTo(JsonParser.parseString(TerminalAPIGsonBuilder.create().toJson(application)))
            reply(link("expired"))
            identified.expire("PL123")
            assertThat(JsonParser.parseString(server.takeRequest().body!!.utf8()))
                .isEqualTo(JsonParser.parseString("""{"status":"expired"}"""))
        }

    @Test
    fun `creating a link posts the amount, expiry and idempotency key with the API key`() {
        reply(link("active"), code = 201)
        val result = runBlocking { api.create(request, "link-sale-1") }
        assertThat(result).isEqualTo(
            PaymentLinkResult.Answered(
                PaymentLink("PL123", "https://test.adyen.link/PL123", PaymentLinkStatus.ACTIVE, Instant.parse("2026-10-03T09:30:00Z")),
            ),
        )
        val sent = server.takeRequest()
        assertThat(sent.method).isEqualTo("POST")
        assertThat(sent.url.encodedPath).isEqualTo("/v72/paymentLinks")
        assertThat(sent.headers["x-api-key"]).isEqualTo("secret-api-key")
        assertThat(sent.headers["Idempotency-Key"]).isEqualTo("link-sale-1")
        val body = JsonParser.parseString(sent.body!!.utf8()).asJsonObject
        assertThat(body["merchantAccount"].asString).isEqualTo("HarbourCoffeeCOM")
        assertThat(body["reference"].asString).isEqualTo("261002-093000-AB12")
        assertThat(body["amount"].asJsonObject["currency"].asString).isEqualTo("AUD")
        assertThat(body["amount"].asJsonObject["value"].asLong).isEqualTo(1_200)
        assertThat(body["expiresAt"].asString).isEqualTo("2026-10-03T09:30:00Z")
        listOf("lineItems", "shopperEmail", "shopperReference", "storePaymentMethodMode", "shopperLocale", "countryCode", "metadata")
            .forEach { assertThat(body.has(it)).isFalse() }
    }

    @Test
    fun `the optional details travel when given, and saving the card needs a shopper reference`() {
        reply(link("active"), code = 201)
        reply(link("active"), code = 201)
        val full =
            request.copy(
                lineItems = listOf(PaymentLinkLineItem("1", "Flat white", 2, 450, 409, 41, 1_000)),
                shopperEmail = "sam@example.com",
                shopperReference = "CUST-001",
                recurringProcessingModel = "CardOnFile",
                shopperLocale = "en-AU",
                countryCode = "AU",
                metadata = mapOf("customerReference" to "CUST-001"),
            )
        runBlocking {
            api.create(full, "a")
            api.create(full.copy(shopperReference = null), "b")
        }
        val body = JsonParser.parseString(server.takeRequest().body!!.utf8()).asJsonObject
        val item = body["lineItems"].asJsonArray.single().asJsonObject
        assertThat(item["id"].asString).isEqualTo("1")
        assertThat(item["description"].asString).isEqualTo("Flat white")
        assertThat(item["quantity"].asInt).isEqualTo(2)
        assertThat(item["amountIncludingTax"].asLong).isEqualTo(450)
        assertThat(item["amountExcludingTax"].asLong).isEqualTo(409)
        assertThat(item["taxAmount"].asLong).isEqualTo(41)
        assertThat(item["taxPercentage"].asLong).isEqualTo(1_000)
        assertThat(body["shopperEmail"].asString).isEqualTo("sam@example.com")
        assertThat(body["shopperReference"].asString).isEqualTo("CUST-001")
        assertThat(body["storePaymentMethodMode"].asString).isEqualTo("askForConsent")
        assertThat(body["recurringProcessingModel"].asString).isEqualTo("CardOnFile")
        assertThat(body["shopperLocale"].asString).isEqualTo("en-AU")
        assertThat(body["countryCode"].asString).isEqualTo("AU")
        assertThat(body["metadata"].asJsonObject["customerReference"].asString).isEqualTo("CUST-001")
        val anonymous = JsonParser.parseString(server.takeRequest().body!!.utf8()).asJsonObject
        assertThat(anonymous.has("storePaymentMethodMode")).isFalse()
        assertThat(anonymous.has("recurringProcessingModel")).isFalse()
    }

    @Test
    fun `asking reads every status, and expiring patches the link`() {
        reply(link("paymentPending"))
        reply(link("completed"))
        reply(link("paid"))
        reply("""{"id":"PL123","url":"https://test.adyen.link/PL123","status":"expired"}""")
        runBlocking {
            assertThat((api.status("PL123") as PaymentLinkResult.Answered).link.status).isEqualTo(PaymentLinkStatus.PAYMENT_PENDING)
            assertThat(server.takeRequest().run { method to url.encodedPath }).isEqualTo("GET" to "/v72/paymentLinks/PL123")
            assertThat((api.status("PL123") as PaymentLinkResult.Answered).link.status).isEqualTo(PaymentLinkStatus.COMPLETED)
            assertThat((api.status("PL123") as PaymentLinkResult.Answered).link.status).isEqualTo(PaymentLinkStatus.COMPLETED)
            server.takeRequest()
            server.takeRequest()
            val expired = (api.expire("PL123") as PaymentLinkResult.Answered).link
            assertThat(expired.status).isEqualTo(PaymentLinkStatus.EXPIRED)
            assertThat(expired.expiresAt).isNull()
        }
        val patch = server.takeRequest()
        assertThat(patch.method).isEqualTo("PATCH")
        assertThat(patch.url.encodedPath).isEqualTo("/v72/paymentLinks/PL123")
        assertThat(JsonParser.parseString(patch.body!!.utf8()).asJsonObject["status"].asString).isEqualTo("expired")
    }

    @Test
    fun `client errors are not processed, while server errors, throttling and odd answers are unknown`() {
        reply("""{"status":403,"errorCode":"010","message":"Not allowed","errorType":"security"}""", code = 403)
        reply("""{"status":500,"message":"Internal error"}""", code = 500)
        reply("busy", code = 429)
        reply("not json")
        reply("""{"id":"PL1","status":"active"}""")
        reply("""{"id":"PL1","url":"https://test.adyen.link/PL1","status":"strange"}""")
        reply("""{"id":"PL1","url":"https://test.adyen.link/PL1","status":"active","expiresAt":"tomorrow"}""")
        runBlocking {
            assertThat(api.status("PL1")).isEqualTo(PaymentLinkResult.Failed(Fault.Permission(ApiKey.ADYEN)))
            assertThat(
                api.status("PL1"),
            ).isEqualTo(PaymentLinkResult.Failed(Fault.AdyenUnavailable(500, null, ExternalText("Internal error"))))
            assertThat(api.status("PL1")).isEqualTo(PaymentLinkResult.Failed(Fault.AdyenUnavailable(429)))
            assertThat(api.status("PL1")).isEqualTo(PaymentLinkResult.Failed(Fault.UnreadableReply()))
            assertThat(api.status("PL1")).isEqualTo(PaymentLinkResult.Failed(Fault.UnreadableReply()))
            assertThat(api.status("PL1")).isEqualTo(PaymentLinkResult.Failed(Fault.UnreadableReply(ExternalText("strange"))))
            assertThat((api.status("PL1") as PaymentLinkResult.Answered).link.expiresAt).isNull()
        }
    }

    @Test
    fun `typed links reject malformed required fields while ignoring extra fields`() =
        runBlocking {
            reply(link("active").dropLast(1) + """, "futureField":{}}""")
            assertThat(api.status("PL123")).isInstanceOf(PaymentLinkResult.Answered::class.java)
            listOf(
                """{"id":123,"url":"https://test.adyen.link/PL123","status":"active"}""",
                """{"id":"PL123","url":false,"status":"active"}""",
                """{"id":"PL123","url":"https://test.adyen.link/PL123","status":{}}""",
                link("active") + " {}",
            ).forEach { body ->
                reply(body)
                assertThat(api.status("PL123")).isEqualTo(PaymentLinkResult.Failed(Fault.UnreadableReply()))
            }
        }

    @Test
    fun `a request that never arrives is not processed, and a timeout is unknown`() {
        val slow = CheckoutPaymentLinks(credentials, baseUrl = server.url("/v72/"), timeout = 200.milliseconds)
        server.enqueue(
            MockResponse
                .Builder()
                .body(link("active"))
                .headersDelay(2, TimeUnit.SECONDS)
                .build(),
        )
        assertThat(runBlocking { slow.create(request, "k") }).isEqualTo(PaymentLinkResult.Failed(Fault.TimedOut))

        val closed = MockWebServer().apply { start() }
        val url = closed.url("/v72/")
        closed.close()
        val result = runBlocking { CheckoutPaymentLinks(credentials, baseUrl = url).create(request, "k") }
        assertThat(result).isEqualTo(PaymentLinkResult.Failed(Fault.Unreachable(url.host, terminal = false)))
        assertThat(result.toString()).doesNotContain("secret-api-key")
    }
}
