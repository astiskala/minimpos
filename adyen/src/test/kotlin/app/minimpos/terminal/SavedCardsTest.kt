package app.minimpos.terminal

import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutModifications
import app.minimpos.terminal.checkout.CheckoutSavedCards
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.StoredCard
import app.minimpos.terminal.checkout.StoredCardListing
import app.minimpos.terminal.simulator.SimulatedOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test
import kotlin.random.Random

class SavedCardsTest {
    private val server = MockWebServer().apply { start() }
    private val credentials = CheckoutCredentials("secret-api-key", "HarbourCoffeeCOM", TerminalEnvironment.TEST)
    private val api = CheckoutSavedCards(credentials, baseUrl = server.url("/v72/"))
    private val amount = ModificationAmount("AUD", 3_900)
    private val request = CardChargeRequest("TOKEN1", "CUST-001", amount, "261017-101500-AB12", "UnscheduledCardOnFile", "ann@example.com")

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
    fun `listing asks for the shopper's cards of the merchant account and keeps only usable summaries`() =
        runBlocking {
            reply(
                """
                {"merchantAccount":"HarbourCoffeeCOM","shopperReference":"CUST-001","storedPaymentMethods":[
                {"id":"TOKEN1","brand":"visa","lastFour":"1111","expiryMonth":"03","expiryYear":"2030","holderName":"Ann Lee",
                "supportedRecurringProcessingModels":["UnscheduledCardOnFile","CardOnFile"],"type":"scheme"},
                {"brand":"mc","lastFour":"4444"},
                {"id":"TOKEN2","brand":"","lastFour":""}]}
                """.trimIndent(),
            )
            val listed = api.list("CUST-001")
            assertThat(listed)
                .isEqualTo(
                    StoredCardListing.Listed(
                        listOf(
                            StoredCard("TOKEN1", "visa", "1111", "03", "2030", "Ann Lee", setOf("UnscheduledCardOnFile", "CardOnFile")),
                            StoredCard("TOKEN2"),
                        ),
                    ),
                )
            val sent = server.takeRequest()
            assertThat(sent.method).isEqualTo("GET")
            assertThat(sent.url.encodedPath).isEqualTo("/v72/storedPaymentMethods")
            assertThat(sent.url.queryParameter("merchantAccount")).isEqualTo("HarbourCoffeeCOM")
            assertThat(sent.url.queryParameter("shopperReference")).isEqualTo("CUST-001")
            assertThat(sent.headers["x-api-key"]).isEqualTo("secret-api-key")
            reply("""{"shopperReference":"NEW"}""")
            assertThat(api.list("NEW")).isEqualTo(StoredCardListing.Listed(emptyList()))
            reply("""{"status":403,"errorCode":"010","message":"Not allowed"}""", 403)
            assertThat(api.list("CUST-001")).isInstanceOf(StoredCardListing.Failed::class.java)
            reply("not json")
            assertThat(api.list("CUST-001")).isEqualTo(StoredCardListing.Failed(Fault.UnreadableReply()))
            val card = StoredCard("T", recurringModels = setOf("CardOnFile"))
            assertThat(card.supports("CardOnFile")).isTrue()
            assertThat(card.supports("Subscription")).isFalse()
            assertThat(StoredCard("T").supports("Subscription")).isTrue()
        }

    @Test
    fun `a charge sends the token as a shopper-not-present payment with its idempotency key`() =
        runBlocking {
            reply(
                """
                {"pspReference":"PSP9","resultCode":"Authorised","paymentMethod":{"brand":"visa","type":"scheme"},
                "additionalData":{"cardSummary":"1111","authCode":"123456"}}
                """.trimIndent(),
            )
            assertThat(api.charge(request, "card-sale-1")).isEqualTo(CardChargeResult.Authorised("PSP9", "visa", "1111", "123456"))
            val sent = server.takeRequest()
            assertThat(sent.url.encodedPath).isEqualTo("/v72/payments")
            assertThat(sent.headers["Idempotency-Key"]).isEqualTo("card-sale-1")
            val body = JsonParser.parseString(sent.body!!.utf8()).asJsonObject
            assertThat(body["merchantAccount"].asString).isEqualTo("HarbourCoffeeCOM")
            assertThat(body["reference"].asString).isEqualTo("261017-101500-AB12")
            assertThat(body["amount"].asJsonObject["value"].asLong).isEqualTo(3_900)
            assertThat(body["amount"].asJsonObject["currency"].asString).isEqualTo("AUD")
            assertThat(body["paymentMethod"].asJsonObject["storedPaymentMethodId"].asString).isEqualTo("TOKEN1")
            assertThat(body["shopperReference"].asString).isEqualTo("CUST-001")
            assertThat(body["shopperInteraction"].asString).isEqualTo("ContAuth")
            assertThat(body["recurringProcessingModel"].asString).isEqualTo("UnscheduledCardOnFile")
            assertThat(body["shopperEmail"].asString).isEqualTo("ann@example.com")
        }

    @Test
    fun `refusals keep Adyen's reason while pending, missing and failed answers stay unknown`() =
        runBlocking {
            reply("""{"pspReference":"PSP8","resultCode":"Refused","refusalReason":"Expired Card"}""")
            assertThat(api.charge(request, "k1")).isEqualTo(CardChargeResult.Refused("PSP8", ExternalText("Expired Card")))
            reply("""{"resultCode":"Cancelled"}""")
            assertThat(api.charge(request, "k2")).isEqualTo(CardChargeResult.Refused(null, null))
            reply("""{"pspReference":"PSP7","resultCode":"Pending"}""")
            assertThat(api.charge(request, "k3")).isEqualTo(CardChargeResult.Failed(Fault.StillInProgress))
            reply("""{"resultCode":"Authorised"}""")
            assertThat((api.charge(request, "k4") as CardChargeResult.Failed).fault.mayHaveTakenEffect).isTrue()
            reply("""{"pspReference":"PSP6","resultCode":"RedirectShopper"}""")
            assertThat((api.charge(request, "k5") as CardChargeResult.Failed).fault.mayHaveTakenEffect).isTrue()
            reply("""{"status":422,"errorCode":"800","message":"Contract not found"}""", 422)
            assertThat((api.charge(request, "k6") as CardChargeResult.Failed).fault.mayHaveTakenEffect).isFalse()
            reply("{}", 503)
            assertThat((api.charge(request, "k7") as CardChargeResult.Failed).fault.mayHaveTakenEffect).isTrue()
            val requests = server.requestCount
            assertThat(
                api.charge(request.copy(recurringProcessingModel = "Weekly"), "k8"),
            ).isEqualTo(CardChargeResult.Failed(Fault.NotStarted))
            assertThat(server.requestCount).isEqualTo(requests)
        }

    @Test
    fun `a Checkout refund posts the amount with its idempotency key and is only received`() =
        runBlocking {
            val modifications = CheckoutModifications(credentials, baseUrl = server.url("/v72/"))
            reply("""{"pspReference":"REF1","status":"received","paymentPspReference":"PSP9"}""", 201)
            assertThat(modifications.refund("PSP9", amount, "R-1", "refund-r1")).isEqualTo(ModificationResult.Received("REF1"))
            val sent = server.takeRequest()
            assertThat(sent.url.encodedPath).isEqualTo("/v72/payments/PSP9/refunds")
            assertThat(sent.headers["Idempotency-Key"]).isEqualTo("refund-r1")
            val body = JsonParser.parseString(sent.body!!.utf8()).asJsonObject
            assertThat(body["reference"].asString).isEqualTo("R-1")
            assertThat(body["amount"].asJsonObject["value"].asLong).isEqualTo(3_900)
            reply("""{"status":"weird"}""", 201)
            assertThat((modifications.refund("PSP9", amount, "R-2", "refund-r2") as ModificationResult.Failed).fault.mayHaveTakenEffect)
                .isTrue()
        }

    @Test
    fun `the simulator charges known cards with its outcome, answers repeats alike and ledgers refunds`() =
        runBlocking {
            var outcome = SimulatedOutcome.APPROVE
            val simulator = TerminalSimulator({ SimulatorConfig(outcome = outcome, delayMillis = 0) }, random = Random(1))
            val card = StoredCard("TOKEN1", "visa", "1111")
            val cards = simulator.savedCards { reference -> if (reference == "CUST-001") listOf(card) else emptyList() }
            assertThat(cards.list("CUST-001")).isEqualTo(StoredCardListing.Listed(listOf(card)))
            assertThat(cards.list("OTHER")).isEqualTo(StoredCardListing.Listed(emptyList()))
            val approved = cards.charge(request, "a") as CardChargeResult.Authorised
            assertThat(approved.lastFour).isEqualTo("1111")
            assertThat(cards.charge(request, "a")).isEqualTo(approved)
            assertThat(
                simulator.modifications.refund(approved.pspReference, amount, "R", "r"),
            ).isInstanceOf(ModificationResult.Received::class.java)
            assertThat(simulator.modifications.capture(approved.pspReference, amount, "C", "c"))
                .isInstanceOf(ModificationResult.Failed::class.java)
            outcome = SimulatedOutcome.DECLINE
            assertThat(cards.charge(request, "b")).isInstanceOf(CardChargeResult.Refused::class.java)
            assertThat(cards.charge(request.copy(storedPaymentMethodId = "GONE"), "c")).isInstanceOf(CardChargeResult.Refused::class.java)
            outcome = SimulatedOutcome.TIMEOUT
            assertThat(cards.charge(request, "d")).isEqualTo(CardChargeResult.Failed(Fault.TimedOut))
            assertThat(cards.charge(request, "d")).isInstanceOf(CardChargeResult.Authorised::class.java)
        }
}
