package app.minimpos.terminal

import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutModifications
import app.minimpos.terminal.checkout.CheckoutPaymentLinks
import app.minimpos.terminal.checkout.CheckoutSavedCards
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentLinkRequest
import app.minimpos.terminal.checkout.PaymentLinkResult
import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test
import java.time.Instant

/**
 * Successful Checkout and Management answers, damaged in every way [JsonDamage] knows or not JSON at all, are never
 * thrown and never read as something Adyen did not say: a capture, adjustment or link Adyen may have made is never
 * reported as refused or as not sent (which would invite a second one), and a damaged shared key is never read as no key
 * (which would invite replacing it).
 */
class MalformedAdyenReplyTest {
    private val server = MockWebServer().apply { start() }
    private val credentials = CheckoutCredentials("secret-api-key", "HarbourCoffeeCOM", TerminalEnvironment.TEST)
    private val modifications = CheckoutModifications(credentials, baseUrl = server.url("/v72/"))
    private val links = CheckoutPaymentLinks(credentials, baseUrl = server.url("/v72/"))
    private val savedCards = CheckoutSavedCards(credentials, baseUrl = server.url("/v72/"))
    private val management = AdyenTerminalDetails("secret", baseUrl = { server.url("/${it.name.lowercase()}/v3/") })
    private val amount = ModificationAmount("AUD", 1_200)

    @After
    fun tearDown() = server.close()

    /**
     * Answers [call] with each damaged copy of [reply] (HTTP [code]) and returns the changes whose result was thrown or
     * not [allowed] for that body.
     */
    private fun <T : Any> wrongResults(
        reply: String,
        code: Int,
        call: suspend () -> T,
        allowed: (body: String, result: T) -> Boolean,
    ): List<String> {
        val bodies =
            JsonDamage.unreadable.map { "body `$it`" to it } +
                JsonDamage.variants(JsonParser.parseString(reply).asJsonObject).map { (change, body) -> change to body.toString() }
        return bodies.mapNotNull { (change, body) ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(code)
                    .body(body)
                    .build(),
            )
            val result = runCatching { runBlocking { call() } }
            result.fold(
                onSuccess = { if (allowed(body, it)) null else "$change: $it" },
                onFailure = { "$change: threw $it" },
            )
        }
    }

    private fun ModificationResult.mayHaveHappened(): Boolean =
        this !is ModificationResult.Refused && (this !is ModificationResult.Failed || fault.mayHaveTakenEffect)

    @Test
    fun `a damaged capture answer is received or may have taken effect, never refused or not sent`() {
        val wrong =
            wrongResults(
                """{"pspReference":"CAP123","status":"received","paymentPspReference":"PSP1"}""",
                201,
                { modifications.capture("PSP1", amount, "ref", "capture-key") },
            ) { _, result -> result.mayHaveHappened() }
        assertWithMessage("Damaged capture answers").that(wrong).isEmpty()
    }

    @Test
    fun `a damaged adjustment answer is authorised, received or may have taken effect, never refused or not sent`() {
        val wrong =
            wrongResults(
                """{"pspReference":"ADJ1","status":"Authorised","adjustAuthorisationData":"BQABAQnext"}""",
                200,
                { modifications.updateAmount("PSP1", amount, "ref", "BQABAQfirst", "adjust-key") },
            ) { _, result -> result.mayHaveHappened() }
        assertWithMessage("Damaged adjustment answers").that(wrong).isEmpty()
    }

    @Test
    fun `a damaged refund answer is received or may have taken effect, never refused or not sent`() {
        val wrong =
            wrongResults(
                """{"pspReference":"REF1","status":"received","paymentPspReference":"PSP1"}""",
                201,
                { modifications.refund("PSP1", amount, "ref", "refund-key") },
            ) { _, result -> result.mayHaveHappened() }
        assertWithMessage("Damaged refund answers").that(wrong).isEmpty()
    }

    @Test
    fun `a damaged saved-card charge is authorised or may have charged, never refused or not sent`() {
        val request = CardChargeRequest("TOKEN1", "CUST-001", amount, "ref", "UnscheduledCardOnFile")
        val wrong =
            wrongResults(
                """
                {"pspReference":"PSP9","resultCode":"Authorised","merchantReference":"ref",
                "paymentMethod":{"brand":"visa","type":"scheme"},"additionalData":{"cardSummary":"1111","authCode":"123456"}}
                """.trimIndent(),
                200,
                { savedCards.charge(request, "card-key") },
            ) { _, result ->
                result is CardChargeResult.Authorised || (result is CardChargeResult.Failed && result.fault.mayHaveTakenEffect)
            }
        assertWithMessage("Damaged saved-card charges").that(wrong).isEmpty()
    }

    @Test
    fun `a damaged payment link answer is a link or may have made one, never not sent`() {
        val request =
            PaymentLinkRequest(reference = "261002-093000-AB12", amount = amount, expiresAt = Instant.parse("2026-10-03T09:30:00Z"))
        val wrong =
            wrongResults(
                """{"id":"PL123","url":"https://test.adyen.link/PL123","status":"active","expiresAt":"2026-10-03T09:30:00Z"}""",
                201,
                { links.create(request, "link-key") },
            ) { _, result ->
                result is PaymentLinkResult.Answered || (result is PaymentLinkResult.Failed && result.fault.mayHaveTakenEffect)
            }
        assertWithMessage("Damaged payment link answers").that(wrong).isEmpty()
    }

    @Test
    fun `a damaged shared key is never read as no key`() {
        val wrong =
            wrongResults(
                """{"nexo":{"encryptionKey":{"identifier":"key","version":2,"passphrase":"secret passphrase"}}}""",
                200,
                { management.sharedKey("ID", TerminalEnvironment.TEST) },
            ) { body, result ->
                // Without an encryptionKey object the settings really have no key; with one, it must be read or rejected.
                val key = runCatching { JsonParser.parseString(body).asJsonObject["nexo"].asJsonObject["encryptionKey"] }.getOrNull()
                result != SharedKeyLookup.Missing || key == null || !key.isJsonObject
            }
        assertWithMessage("Damaged shared keys").that(wrong).isEmpty()
    }

    @Test
    fun `a damaged terminal list is listed or rejected, never thrown`() {
        val wrong =
            wrongResults(
                """
                {"data":[{"id":"S1F2-123456789","assignment":{"merchantId":"Merchant","storeId":"ST1"},
                "connectivity":{"ethernet":{"ipAddress":"192.168.1.2"},"wifi":{"ipAddress":"192.168.1.3"}}}]}
                """.trimIndent(),
                200,
                { management.terminals(TerminalEnvironment.TEST) },
            ) { _, result -> result is TerminalListing.Listed || result is TerminalListing.Failed }
        assertWithMessage("Damaged terminal lists").that(wrong).isEmpty()
    }
}
