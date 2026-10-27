package app.minimpos.terminal

import app.minimpos.terminal.client.GiftCardAccount
import app.minimpos.terminal.client.GiftCardEntryMode
import app.minimpos.terminal.client.PosApplication
import app.minimpos.terminal.client.StoredValueOperation
import app.minimpos.terminal.client.StoredValueParams
import app.minimpos.terminal.client.TerminalClient
import app.minimpos.terminal.client.TerminalIdentity
import app.minimpos.terminal.client.storedValue
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalTransport
import com.adyen.model.terminal.TerminalAPIRequest
import com.adyen.terminal.serialization.TerminalAPIGsonBuilder
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.io.encoding.Base64

class GiftCardApplicationInfoTest {
    @Test
    fun `every stored value operation sends the same sanitized application info on the wire`() =
        runTest {
            val application = PosApplication(" (Mini) mPOS! ", "1.2.0-beta_1", "!!!", "Android", "13")
            val sent = mutableListOf<TerminalAPIRequest>()
            val client =
                TerminalClient(
                    transport =
                        TerminalTransport { request, _ ->
                            sent += request
                            Delivery.Failed(Fault.NotStarted)
                        },
                    identity = TerminalIdentity("POS1", "AMS1-123"),
                    application = application,
                    currencyDigits = testCurrencyDigits,
                )
            StoredValueOperation.entries.forEach { operation ->
                val undo = operation == StoredValueOperation.UNDO_LOAD
                client.storedValue(
                    StoredValueParams(
                        operation = operation,
                        account = if (undo) null else GiftCardAccount.onTerminal("svs", GiftCardEntryMode.MAG_STRIPE),
                        amountMinor = if (operation == StoredValueOperation.DEACTIVATE) 0 else 100,
                        currency = "EUR",
                        merchantReference = "GC-${operation.name}",
                        originalTransactionId = if (undo) ".PSP1" else null,
                        originalTimestamp = if (undo) "2026-10-25T10:00:00Z" else null,
                    ),
                )
            }
            val gson = TerminalAPIGsonBuilder.create()
            val expected = JsonParser.parseString(gson.toJson(application.toApplicationInfo()))
            assertThat(sent).hasSize(StoredValueOperation.entries.size)
            sent.forEach { request ->
                val body =
                    JsonParser
                        .parseString(gson.toJson(request))
                        .asJsonObject["SaleToPOIRequest"]
                        .asJsonObject["StoredValueRequest"]
                        .asJsonObject["SaleData"]
                        .asJsonObject["SaleToAcquirerData"]
                        .asString
                val acquirer = JsonParser.parseString(Base64.decode(body).decodeToString()).asJsonObject
                assertThat(acquirer["applicationInfo"]).isEqualTo(expected)
            }
        }
}
