package app.minimpos.terminal

import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.TerminalTipOptions
import app.minimpos.terminal.client.TerminalTipSuggestions
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.terminal.TerminalAPIRequest
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.math.BigDecimal
import kotlin.io.encoding.Base64

class TerminalTipOptionsTest {
    @Test
    fun `fixed tip suggestions use currency major units on the wire`() =
        runTest {
            val cases =
                listOf(
                    "AUD" to listOf("0.01", "1.25", "9.99"),
                    "JPY" to listOf("1", "125", "999"),
                    "KWD" to listOf("0.001", "0.125", "0.999"),
                    "ISK" to listOf("0.01", "1.25", "9.99"),
                )
            for ((currency, expected) in cases) {
                val digits = BigDecimal(expected.first()).scale()
                lateinit var sent: TerminalAPIRequest
                val client =
                    TerminalClient(
                        currencyDigits = { digits },
                        transport =
                            TerminalTransport { request, _ ->
                                sent = request
                                Delivery.Failed(Fault.Unsupported)
                            },
                        identity = TerminalIdentity("POS1", "S1F2-000158213605014"),
                    )
                client.pay(
                    PaymentParams(
                        1250,
                        currency,
                        "MP-1",
                        terminalTip =
                            TerminalTipOptions(
                                TerminalTipSuggestions.FixedAmounts(listOf(1, 125, 999), allowCustom = false),
                            ),
                    ),
                )
                val saleData = sent.saleToPOIRequest.paymentRequest.saleData
                assertThat(saleData.saleToAcquirerData.tenderOption).contains(TerminalClient.ASK_GRATUITY)
                val options =
                    JsonParser
                        .parseString(String(Base64.decode(saleData.saleToPOIData), Charsets.UTF_8))
                        .asJsonObject
                        .getAsJsonArray("Operation")
                        .single()
                        .asJsonObject
                        .getAsJsonObject("TipOptions")
                        .getAsJsonObject("TipSuggestions")
                for ((index, amount) in expected.withIndex()) {
                    assertThat(options.get("Amount${index + 1}").asBigDecimal).isEqualTo(BigDecimal(amount))
                }
                assertThat(options.get("TipCustom").asBoolean).isFalse()
            }
        }
}
