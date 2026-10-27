package app.minimpos.terminal

import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintOutcome
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.toPrintJob
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.nexo.DocumentQualifierType
import com.adyen.model.nexo.ErrorConditionType
import com.adyen.model.nexo.OutputContent
import com.adyen.model.nexo.OutputFormatType
import com.adyen.model.nexo.PrintOutput
import com.adyen.model.nexo.PrintResponse
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.model.terminal.TerminalAPIResponse
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.io.encoding.Base64

class TerminalLogoPrintTest {
    private val sent = mutableListOf<TerminalAPIRequest>()
    private var ids = 0
    private val unreachable = Fault.Unreachable("192.168.1.20:8443", terminal = true)

    private fun client(reply: (TerminalAPIRequest) -> Delivery) =
        TerminalClient(
            currencyDigits = testCurrencyDigits,
            transport =
                TerminalTransport { request, _ ->
                    sent += request
                    reply(request)
                },
            identity = TerminalIdentity("POS1", "S1F2-000158213605014"),
            clock = Clock.fixed(Instant.parse("2026-09-30T01:02:03.456Z"), ZoneOffset.UTC),
            newServiceId = { "S${++ids}" },
        )

    private fun printReply(response: Response) =
        Delivery.Answered(
            TerminalAPIResponse().apply {
                saleToPOIResponse =
                    SaleToPOIResponse().apply {
                        printResponse = PrintResponse().apply { this.response = response }
                    }
            },
        )

    private fun result(
        type: ResultType,
        condition: ErrorConditionType? = null,
        additional: String? = null,
    ) = Response().apply {
        result = type
        errorCondition = condition
        additionalResponse = additional
    }

    @Test
    fun `image job serializes raw XHTML bytes as base64`() =
        runTest {
            val image = PrintJob.Image(byteArrayOf(1, 2, 3))
            val client = client { printReply(result(ResultType.SUCCESS)) }
            assertThat(client.print(listOf(image))).isEqualTo(PrintOutcome.Printed)
            val output =
                sent
                    .single()
                    .saleToPOIRequest.printRequest.printOutput
            assertThat(output.documentQualifier).isEqualTo(DocumentQualifierType.DOCUMENT)
            val json = JsonParser.parseString(TerminalAPIGsonBuilder.create().toJson(output)).asJsonObject
            val content = json.getAsJsonObject("OutputContent")
            assertThat(content.get("OutputFormat").asString).isEqualTo("XHTML")
            val xhtml = "<img src=\"data:image/png;base64, AQID\"/>"
            assertThat(content.get("OutputXHTML").asString).isEqualTo(Base64.encode(xhtml.toByteArray()))
            assertThat(output.toPrintJob()).isEqualTo(image)
        }

    @Test
    fun `rejected image still prints text and remembers the first rejection`() =
        runTest {
            val image = PrintJob.Image(byteArrayOf(1))
            val text = PrintJob.Text(listOf(PrintLine.Text("Receipt")))
            val client =
                client { request ->
                    if (request.saleToPOIRequest.printRequest.printOutput.outputContent.outputFormat == OutputFormatType.XHTML) {
                        printReply(result(ResultType.FAILURE, ErrorConditionType.REFUSAL, "message=Unsupported"))
                    } else {
                        printReply(result(ResultType.SUCCESS))
                    }
                }
            assertThat(client.print(listOf(image, image, text)))
                .isEqualTo(PrintOutcome.PrintedWithoutImage(Fault.TerminalRejected(ExternalText("Unsupported"))))
            assertThat(sent).hasSize(3)
        }

    @Test
    fun `printer and paper failures on an image stop before text`() =
        runTest {
            val image = PrintJob.Image(byteArrayOf(1))
            val text = PrintJob.Text(listOf(PrintLine.Text("Receipt")))
            for (condition in listOf(ErrorConditionType.UNAVAILABLE_DEVICE, ErrorConditionType.DEVICE_OUT)) {
                sent.clear()
                val client = client { printReply(result(ResultType.FAILURE, condition)) }
                assertThat(client.print(listOf(image, text)))
                    .isEqualTo(PrintOutcome.Failed(Fault.TerminalRejected(null), condition == ErrorConditionType.UNAVAILABLE_DEVICE))
                assertThat(sent).hasSize(1)
            }
        }

    @Test
    fun `missing image answer stops before text`() =
        runTest {
            val client = client { Delivery.Failed(unreachable) }
            val result = client.print(listOf(PrintJob.Image(byteArrayOf(1)), PrintJob.Text(listOf(PrintLine.Text("Receipt")))))
            assertThat(result).isEqualTo(PrintOutcome.Failed(unreachable, noPrinter = false))
            assertThat(sent).hasSize(1)
        }

    @Test
    fun `image bytes are immutable and malformed image XHTML is ignored`() {
        val original = byteArrayOf(1, 2)
        val image = PrintJob.Image(original)
        original[0] = 3
        image.png[0] = 4
        assertThat(image).isEqualTo(PrintJob.Image(byteArrayOf(1, 2)))
        assertThat(image.hashCode()).isEqualTo(PrintJob.Image(byteArrayOf(1, 2)).hashCode())
        assertThat(image).isNotEqualTo(PrintJob.Image(byteArrayOf(1)))
        assertThrows(IllegalArgumentException::class.java) { PrintJob.Image(ByteArray(256_001)) }
        val invalidImages =
            listOf(
                "<p>not an image</p>",
                "<img src=\"data:image/png;base64, A\"/>",
                "<img src=\"data:image/png;base64, AQID!\"/>",
                "<img src=\"data:image/png;base64, ${"A".repeat(341_340)}\"/>",
            )
        for (xhtml in invalidImages) {
            val output =
                PrintOutput().apply {
                    outputContent = OutputContent().apply { outputXHTML = xhtml.toByteArray() }
                }
            assertThat(output.toPrintJob()).isNull()
        }
    }
}
