package app.minimpos.terminal

import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintOutcome
import app.minimpos.terminal.client.RecoveryPolicy
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.simulator.SimulatorConfig
import app.minimpos.terminal.simulator.TerminalSimulator
import app.minimpos.terminal.transport.AdyenCloudDevices
import app.minimpos.terminal.transport.CloudCredentials
import app.minimpos.terminal.transport.CloudDetection
import app.minimpos.terminal.transport.CloudEndpoint
import app.minimpos.terminal.transport.CloudListing
import app.minimpos.terminal.transport.CloudRegion
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.TerminalEnvironment
import com.adyen.model.nexo.MessageHeader
import com.adyen.model.nexo.SaleToPOIRequest
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.seconds

class CloudDevicesTest {
    private val gson = TerminalAPIGsonBuilder.create()
    private val server = MockWebServer().apply { start() }
    private val credentials = CloudCredentials("cloud-api-key", "HarbourCoffeeCOM")
    private val simulator = TerminalSimulator(config = { SimulatorConfig(delayMillis = 0) })

    /** Every endpoint is the mock server, under a path naming it (`/test`, `/live-au`, …). */
    private val devices =
        AdyenCloudDevices(credentials, baseUrl = { server.url("/" + (it.region?.prefix ?: "test")) })

    /** Answers per endpoint: the HTTP code and body of `connectedDevices`; the terminal for `/sync`. */
    private val listings = mutableMapOf<String, Pair<Int, String>>()
    private var syncReply: ((String) -> MockResponse)? = null

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    if (path.endsWith("/connectedDevices")) {
                        val (code, body) = listings[path.substringBefore("/v1/")] ?: (401 to """{"message":"Invalid key"}""")
                        return MockResponse
                            .Builder()
                            .code(code)
                            .body(body)
                            .build()
                    }
                    val body = request.body!!.utf8()
                    return syncReply?.invoke(body) ?: terminal(body)
                }
            }
    }

    @After
    fun tearDown() = server.close()

    fun terminal(body: String): MockResponse {
        val response = runBlocking { simulator.send(gson.fromJson(body, TerminalAPIRequest::class.java), 10.seconds) }
        return MockResponse.Builder().body((response as Delivery.Answered).response?.let(gson::toJson) ?: "ok").build()
    }

    private fun client(endpoint: CloudEndpoint = CloudEndpoint.TEST) =
        TerminalClient(
            devices.transport(endpoint),
            TerminalIdentity("MiniMPOS", "S1F2-000158213605014"),
            recovery = RecoveryPolicy(attempts = 1, intervalMillis = 1, maxInProgressChecks = 1),
        )

    private val payment = PaymentParams(BigDecimal("12.50"), "AUD", "260930-1")

    @Test
    fun `regions follow Adyen's data centre table and live endpoints need one`() {
        assertThat(CloudRegion.forCountry("au")).isEqualTo(CloudRegion.AU)
        assertThat(CloudRegion.forCountry("NZ")).isEqualTo(CloudRegion.AU)
        assertThat(CloudRegion.forCountry("CA")).isEqualTo(CloudRegion.US)
        assertThat(CloudRegion.forCountry("JP")).isEqualTo(CloudRegion.NEA)
        assertThat(CloudRegion.forCountry("NL")).isEqualTo(CloudRegion.EU)
        assertThat(CloudRegion.forCountry("")).isEqualTo(CloudRegion.EU)
        assertThat(CloudEndpoint.TEST.baseUrl).isEqualTo("https://device-api-test.adyen.com")
        assertThat(CloudEndpoint(TerminalEnvironment.LIVE, CloudRegion.AU).baseUrl).isEqualTo("https://device-api-live-au.adyen.com")
        assertThat(CloudEndpoint(TerminalEnvironment.LIVE, CloudRegion.EU).baseUrl).isEqualTo("https://device-api-live.adyen.com")
        assertThrows(IllegalArgumentException::class.java) { CloudEndpoint(TerminalEnvironment.LIVE) }
        assertThrows(IllegalArgumentException::class.java) { CloudCredentials(" ", "Merchant") }
        assertThat(credentials.toString()).doesNotContain("cloud-api-key")
    }

    @Test
    fun `a payment goes to the terminal's sync endpoint with the API key, unencrypted`() {
        val outcome = runBlocking { client().pay(payment, serviceId = "PAY1") }
        assertThat((outcome as TransactionOutcome.Completed).details.success).isTrue()
        val request = server.takeRequest()
        assertThat(request.url.encodedPath).isEqualTo("/test/v1/merchants/HarbourCoffeeCOM/devices/S1F2-000158213605014/sync")
        assertThat(request.headers["x-api-key"]).isEqualTo("cloud-api-key")
        assertThat(request.body!!.utf8()).contains("\"PaymentRequest\"")
        // Prints and diagnoses go the same way; an abort is acknowledged with a bare "ok".
        assertThat(runBlocking { client().print(listOf(PrintJob.Text(listOf(PrintLine.Text("Hello"))))) }).isEqualTo(PrintOutcome.Printed)
        assertThat(runBlocking { client().diagnose() }.reachable).isTrue()
        syncReply = { MockResponse.Builder().body("ok").build() }
        runBlocking { client().abort("PAY1") }
    }

    @Test
    fun `HTTP errors say whether the request can have reached the terminal`() {
        val transport = devices.transport(CloudEndpoint.TEST)
        val request =
            TerminalAPIRequest().apply {
                saleToPOIRequest =
                    SaleToPOIRequest()
            }

        fun failure(
            code: Int,
            body: String = "",
        ): Delivery.Failed {
            syncReply = {
                MockResponse
                    .Builder()
                    .code(code)
                    .body(body)
                    .build()
            }
            return runBlocking { transport.send(header(request), 5.seconds) } as Delivery.Failed
        }
        assertThat(failure(401)).isInstanceOf(Delivery.NotSent::class.java)
        assertThat(failure(403).reason).contains("Cloud Device API role")
        assertThat(failure(422, """{"message":"Structure of PaymentRequest is invalid"}""").reason)
            .isEqualTo("Structure of PaymentRequest is invalid (HTTP 422)")
        assertThat(failure(404)).isInstanceOf(Delivery.NotSent::class.java)
        assertThat(failure(500)).isInstanceOf(Delivery.MaybeSent::class.java)
        assertThat(failure(408)).isInstanceOf(Delivery.MaybeSent::class.java)
        assertThat(failure(200, """{"unexpected":true}""")).isInstanceOf(Delivery.MaybeSent::class.java)
        // Adyen answers a terminal that is not there, or did not answer, with an event notification.
        assertThat(failure(200, event("Device S1F2-1 is not connected"))).isInstanceOf(Delivery.NotSent::class.java)
        val late = failure(200, event("Did not receive a response from the POI."))
        assertThat(late).isInstanceOf(Delivery.MaybeSent::class.java)
        assertThat(late.reason).contains("Did not receive a response")
        // Without a POIID nothing is sent.
        val anonymous =
            TerminalAPIRequest().apply {
                saleToPOIRequest =
                    SaleToPOIRequest()
            }
        assertThat(runBlocking { transport.send(anonymous, 5.seconds) }).isInstanceOf(Delivery.NotSent::class.java)
    }

    @Test
    fun `a payment whose answer went missing is settled by a status check`() {
        var calls = 0
        syncReply = { body ->
            calls++
            if (calls == 1) MockResponse.Builder().body(event("Did not receive a response from the POI.")).build() else terminal(body)
        }
        val outcome = runBlocking { client().pay(payment, serviceId = "PAY2") }
        // The simulator has no record of a payment it never got, so the status check settles it as not processed.
        assertThat(outcome).isInstanceOf(TransactionOutcome.NotProcessed::class.java)
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun `a TEST key is found on the TEST endpoint`() {
        listings["/test"] = 200 to """{"uniqueDeviceIds":["S1F2-000158213605014","AMS1-000168223606144"]}"""
        val detection = runBlocking { devices.detect(TerminalEnvironment.TEST, "S1F2-000158213605014", "AU") }
        assertThat(detection).isEqualTo(CloudDetection.Found(CloudEndpoint.TEST, listOf("S1F2-000158213605014", "AMS1-000168223606144")))
        assertThat(server.takeRequest().headers["x-api-key"]).isEqualTo("cloud-api-key")
    }

    @Test
    fun `a LIVE key is tried in the country's data centre first, then where the terminal is connected`() {
        listings["/live-au"] = 200 to """{"uniqueDeviceIds":["S1F2-000158213605014"]}"""
        assertThat(runBlocking { devices.detect(TerminalEnvironment.LIVE, "S1F2-000158213605014", "AU") })
            .isEqualTo(CloudDetection.Found(CloudEndpoint(TerminalEnvironment.LIVE, CloudRegion.AU), listOf("S1F2-000158213605014")))
        // In Europe the Australian data centre lists the terminal, so it is chosen over the country's.
        listings["/live"] = 200 to """{"uniqueDeviceIds":[]}"""
        val elsewhere = runBlocking { devices.detect(TerminalEnvironment.LIVE, "S1F2-000158213605014", "NL") }
        assertThat((elsewhere as CloudDetection.Found).endpoint.region).isEqualTo(CloudRegion.AU)
        // Without the terminal anywhere (or without a POIID), the country's data centre is used.
        assertThat(
            (
                runBlocking {
                    devices.detect(TerminalEnvironment.LIVE, "S1U2-1", "NL")
                } as CloudDetection.Found
            ).endpoint.region,
        ).isEqualTo(CloudRegion.EU)
        assertThat(
            (
                runBlocking {
                    devices.detect(TerminalEnvironment.LIVE, null, "NL")
                } as CloudDetection.Found
            ).endpoint.region,
        ).isEqualTo(CloudRegion.EU)
    }

    @Test
    fun `a key that no endpoint accepts, or a refused merchant account, is reported`() {
        val unknown = runBlocking { devices.detect(TerminalEnvironment.TEST, null, "AU") }
        assertThat((unknown as CloudDetection.Failed).message).contains("for TEST")
        assertThat(server.requestCount).isEqualTo(1)
        listings["/test"] = 403 to ""
        assertThat(
            (
                runBlocking {
                    devices.detect(TerminalEnvironment.TEST, null, "AU")
                } as CloudDetection.Failed
            ).message,
        ).contains("HarbourCoffeeCOM")
        listings["/test"] = 200 to "not json"
        assertThat(
            runBlocking { devices.connectedDevices(CloudEndpoint.TEST) },
        ).isEqualTo(CloudListing.Failed("Unexpected response from Adyen"))
    }

    @Test
    fun `typed cloud listings reject null and non-string device IDs`() =
        runBlocking {
            listOf("""{"uniqueDeviceIds":[null]}""", """{"uniqueDeviceIds":[123]}""").forEach { body ->
                listings["/test"] = 200 to body
                assertThat(devices.connectedDevices(CloudEndpoint.TEST)).isEqualTo(CloudListing.Failed("Unexpected response from Adyen"))
            }
            listings["/test"] = 200 to """{"uniqueDeviceIds":["AMS1-1"],"futureField":{}}"""
            assertThat(devices.connectedDevices(CloudEndpoint.TEST)).isEqualTo(CloudListing.Listed(listOf("AMS1-1")))
        }

    @Test
    fun `an unreachable Adyen is reported without sending anything`() {
        val offline = AdyenCloudDevices(credentials, baseUrl = { "http://127.0.0.1:1".toHttpUrl() })
        assertThat(
            (
                runBlocking {
                    offline.detect(TerminalEnvironment.TEST, null, "AU")
                } as CloudDetection.Failed
            ).message,
        ).contains("Cannot connect")
        val transport = offline.transport(CloudEndpoint.TEST)
        val request =
            header(
                TerminalAPIRequest().apply {
                    saleToPOIRequest =
                        SaleToPOIRequest()
                },
            )
        assertThat(runBlocking { transport.send(request, 5.seconds) }).isInstanceOf(Delivery.NotSent::class.java)
    }

    private fun header(request: TerminalAPIRequest) =
        request.apply {
            saleToPOIRequest.messageHeader =
                MessageHeader()
                    .apply { poiid = "S1F2-000158213605014" }
        }

    private fun event(message: String) =
        """{"SaleToPOIRequest":{"EventNotification":{"EventToNotify":"Reject","EventDetails":"message=${message.replace(" ", "+")}"}}}"""
}
