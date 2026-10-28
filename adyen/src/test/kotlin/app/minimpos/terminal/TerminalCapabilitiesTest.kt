package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart
import app.minimpos.terminal.transport.RemoteTerminalCapabilities
import app.minimpos.terminal.transport.SharedKeyUpdate
import app.minimpos.terminal.transport.TerminalCapabilitiesLookup
import app.minimpos.terminal.transport.TerminalCapabilitiesPatch
import app.minimpos.terminal.transport.TerminalCapabilitiesUpdate
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalGratuity
import app.minimpos.terminal.transport.TerminalListing
import app.minimpos.terminal.transport.TerminalMotoSettings
import app.minimpos.terminal.transport.TerminalOfflineLimits
import com.adyen.model.management.TerminalSettings
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test
import java.util.concurrent.TimeUnit

class TerminalCapabilitiesTest {
    private val server = MockWebServer().apply { start() }
    private val api = AdyenTerminalDetails("secret", baseUrl = { server.url("/${it.name.lowercase()}/v3/") })

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

    @After
    fun tearDown() = server.close()

    @Test
    fun `reads and patches gratuities and dcc without touching nexo or other currencies`() =
        runBlocking {
            val initial =
                """
                {"gratuities":[{"currency":"USD","usePredefinedTipEntries":true,"predefinedTipEntries":["15%","20%"],"allowCustomAmount":true}],
                "dcc":{"enableDcc":false},"surcharge":{"askConfirmation":true},
                "moto":{"enableMoto":false,"maxAmount":250},"offlineProcessing":{"chipFloorLimit":50},
                "storeAndForward":{"maxAmount":[{"currencyCode":"USD","amount":10000}],"maxPayments":20}}
                """.trimIndent()
            reply(initial)
            val found = (api.capabilities("ID", TerminalEnvironment.TEST) as TerminalCapabilitiesLookup.Found).capabilities
            assertThat(found.gratuityFor("USD")).isEqualTo(TerminalGratuity("USD", true, listOf("15%", "20%"), true))
            assertThat(found.dccEnabled).isFalse()
            assertThat(found.surchargeConfigured).isTrue()
            assertThat(found.moto).isEqualTo(TerminalMotoSettings(enabled = false, maxAmount = 250))
            server.takeRequest()

            reply(initial)
            reply("{}")
            reply(
                """
                {"gratuities":[
                  {"currency":"USD","usePredefinedTipEntries":true,"predefinedTipEntries":["15%","20%"],"allowCustomAmount":true},
                  {"currency":"EUR","usePredefinedTipEntries":true,"predefinedTipEntries":["10%","15%","20%"],"allowCustomAmount":true}
                ]}
                """.trimIndent(),
            )
            val patch = TerminalCapabilitiesPatch.Gratuity("EUR", TerminalGratuity("EUR", true, listOf("10%", "15%", "20%"), true))
            val updatedGratuity = api.updateCapabilities("ID", TerminalEnvironment.TEST, patch) as TerminalCapabilitiesUpdate.Ready
            assertThat(updatedGratuity.capabilities.gratuities).hasSize(2)
            assertThat(server.takeRequest().method).isEqualTo("GET")
            val gratuityPatch = checkNotNull(server.takeRequest().body).utf8()
            assertThat(gratuityPatch).doesNotContain("nexo")
            assertThat(gratuityPatch).doesNotContain("moto")
            assertThat(TerminalSettings.fromJson(gratuityPatch).gratuities.map { it.currency }).containsExactly("USD", "EUR").inOrder()
            assertThat(server.takeRequest().method).isEqualTo("GET")

            reply("{}")
            reply("""{"dcc":{"enableDcc":true}}""")
            val updatedDcc =
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Dcc(true),
                ) as TerminalCapabilitiesUpdate.Ready
            assertThat(updatedDcc.capabilities.dccEnabled).isTrue()
            assertThat(checkNotNull(server.takeRequest().body).utf8()).isEqualTo("""{"dcc":{"enableDcc":true}}""")
            assertThat(server.takeRequest().method).isEqualTo("GET")
        }

    @Test
    fun `patches moto and offline limits while preserving other currencies`() =
        runBlocking {
            reply("{}")
            reply("""{"moto":{"enableMoto":true,"maxAmount":500}}""")
            val motoPatch = TerminalCapabilitiesPatch.Moto(TerminalMotoSettings(enabled = true, maxAmount = 500))
            val updatedMoto = api.updateCapabilities("ID", TerminalEnvironment.TEST, motoPatch) as TerminalCapabilitiesUpdate.Ready
            assertThat(updatedMoto.capabilities.moto).isEqualTo(TerminalMotoSettings(enabled = true, maxAmount = 500))
            assertThat(checkNotNull(server.takeRequest().body).utf8()).isEqualTo("""{"moto":{"enableMoto":true,"maxAmount":500}}""")
            server.takeRequest()

            reply("""{"storeAndForward":{"maxAmount":[{"currencyCode":"USD","amount":10000}],"maxPayments":20}}""")
            reply("{}")
            reply(
                """
                {"offlineProcessing":{"chipFloorLimit":75},
                "storeAndForward":{"maxAmount":[{"currencyCode":"USD","amount":10000},{"currencyCode":"EUR","amount":5000}],"maxPayments":15}}
                """.trimIndent(),
            )
            val offlineReq = TerminalCapabilitiesPatch.Offline("EUR", chipFloorLimit = 75, maxStoreAndForwardMinor = 5000, maxPayments = 15)
            val updatedOffline = api.updateCapabilities("ID", TerminalEnvironment.TEST, offlineReq) as TerminalCapabilitiesUpdate.Ready
            assertThat(updatedOffline.capabilities.offline.chipFloorLimit).isEqualTo(75)
            server.takeRequest()
            val offlinePatch = checkNotNull(server.takeRequest().body).utf8()
            assertThat(offlinePatch).doesNotContain("nexo")
            assertThat(
                TerminalSettings
                    .fromJson(offlinePatch)
                    .storeAndForward
                    .maxAmount
                    .map { it.currencyCode },
            ).containsExactly("USD", "EUR").inOrder()
            assertThat(server.takeRequest().method).isEqualTo("GET")

            // Removing gratuity for a currency keeps the remaining currencies.
            reply(
                """
                {"gratuities":[
                  {"currency":"USD","usePredefinedTipEntries":false,"allowCustomAmount":true},
                  {"currency":"EUR","usePredefinedTipEntries":false,"allowCustomAmount":true}
                ]}
                """.trimIndent(),
            )
            reply("{}")
            reply("""{"gratuities":[{"currency":"USD","usePredefinedTipEntries":false,"allowCustomAmount":true}]}""")
            assertThat(
                api.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Gratuity("EUR", null)),
            ).isInstanceOf(TerminalCapabilitiesUpdate.Ready::class.java)
        }

    @Test
    fun `handles capabilities parsing edge cases and read-back or HTTP failures`() =
        runBlocking {
            reply(
                """
                {
                  "surcharge": {"askConfirmation": false, "configurations": [{"currencyCode": "EUR"}]},
                  "gratuities": [
                    {"currency": " ", "allowCustomAmount": true},
                    {"currency": "EUR", "allowCustomAmount": false, "usePredefinedTipEntries": true, "predefinedTipEntries": ["10%", " "]}
                  ],
                  "offlineProcessing": {"chipFloorLimit": -1},
                  "storeAndForward": {
                    "maxPayments": -1,
                    "maxAmount": [
                      {"currencyCode": " ", "amount": 100},
                      {"currencyCode": "EUR", "amount": -5},
                      {"currencyCode": "GBP", "amount": 0}
                    ]
                  }
                }
                """.trimIndent(),
            )
            val edge = (api.capabilities("ID", TerminalEnvironment.TEST) as TerminalCapabilitiesLookup.Found).capabilities
            assertThat(edge.surchargeConfigured).isTrue()
            assertThat(edge.gratuityFor("eur")).isEqualTo(TerminalGratuity("EUR", true, listOf("10%"), false))
            assertThat(edge.gratuityFor("gbp")).isNull()
            assertThat(edge.offline).isEqualTo(TerminalOfflineLimits(-1, mapOf("GBP" to 0L), -1))

            reply("not json")
            assertThat(api.capabilities("ID", TerminalEnvironment.TEST))
                .isEqualTo(TerminalCapabilitiesLookup.Failed(Fault.Malformed(MalformedPart.SETTINGS)))
            reply("{}", 503)
            assertThat(api.capabilities("ID", TerminalEnvironment.TEST))
                .isEqualTo(TerminalCapabilitiesLookup.Failed(Fault.AdyenUnavailable(503)))

            reply("{}")
            reply("""{"dcc":{"enableDcc":false}}""")
            assertThat(api.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Dcc(true)))
                .isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.UnreadableReply(), uncertain = true))
            reply("{}", 503)
            assertThat(api.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Gratuity("EUR", null)))
                .isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.AdyenUnavailable(503), uncertain = false))
            reply("{}", 401)
            assertThat(api.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Dcc(true)))
                .isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.Credential(ApiKey.ADYEN), uncertain = false))
            reply("{}", 503)
            assertThat(api.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Dcc(true)))
                .isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.AdyenUnavailable(503), uncertain = true))
            reply("{}")
            reply("{}", 401)
            val capFailed = api.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Dcc(true))
            assertThat(capFailed).isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.Credential(ApiKey.ADYEN), uncertain = true))
            assertThat((capFailed as TerminalCapabilitiesUpdate.Failed).uncertain).isTrue()
            assertThat(TerminalCapabilitiesUpdate.Failed(Fault.Credential(ApiKey.ADYEN), uncertain = false).uncertain).isFalse()
            assertThat(SharedKeyUpdate.Failed(Fault.TimedOut, uncertain = true).uncertain).isTrue()
            assertThat(SharedKeyUpdate.Failed(Fault.Credential(ApiKey.ADYEN), uncertain = false).uncertain).isFalse()
        }

    @Test
    fun `preserves supported card types when patching offline limits`(): Unit =
        runBlocking {
            reply("not json")
            assertThat(api.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Gratuity("EUR", null)))
                .isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.Malformed(MalformedPart.SETTINGS), uncertain = false))
            reply("{}", 503)
            assertThat(api.terminals(TerminalEnvironment.TEST, id = "P400Plus-1"))
                .isEqualTo(TerminalListing.Failed(Fault.AdyenUnavailable(503)))

            while (server.requestCount > 0) {
                if (server.takeRequest(1, TimeUnit.MILLISECONDS) == null) break
            }
            reply("""{"storeAndForward":{"supportedCardTypes":{"credit":true,"debit":false,"prepaid":false}}}""")
            reply("{}")
            reply("""{"offlineProcessing":{},"storeAndForward":{"maxAmount":[{"currencyCode":"EUR","amount":5000}]}}""")
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Offline("EUR", chipFloorLimit = null, maxStoreAndForwardMinor = 5000, maxPayments = null),
                ),
            ).isInstanceOf(TerminalCapabilitiesUpdate.Ready::class.java)
            server.takeRequest()
            val preservedCardsPatch = checkNotNull(server.takeRequest().body).utf8()
            assertThat(
                TerminalSettings
                    .fromJson(preservedCardsPatch)
                    .storeAndForward
                    .supportedCardTypes
                    .debit,
            ).isFalse()
            server.takeRequest()

            reply("""{"storeAndForward":{}}""")
            reply("{}")
            reply("""{"offlineProcessing":{},"storeAndForward":{}}""")
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Offline("EUR", chipFloorLimit = null, maxStoreAndForwardMinor = null, maxPayments = null),
                ),
            ).isInstanceOf(TerminalCapabilitiesUpdate.Ready::class.java)
            server.takeRequest()
            val emptyAmountsPatch = checkNotNull(server.takeRequest().body).utf8()
            assertThat(TerminalSettings.fromJson(emptyAmountsPatch).storeAndForward.supportedCardTypes).isNull()
            server.takeRequest()
        }

    @Test
    fun `cleared limits send explicit nulls and may read back as inherited values`(): Unit =
        runBlocking {
            reply("{}")
            reply("""{"moto":{"enableMoto":true,"maxAmount":300}}""")
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Moto(TerminalMotoSettings(enabled = true)),
                ),
            ).isEqualTo(TerminalCapabilitiesUpdate.Ready(RemoteTerminalCapabilities(moto = TerminalMotoSettings(true, 300))))
            assertThat(checkNotNull(server.takeRequest().body).utf8()).isEqualTo("""{"moto":{"enableMoto":true,"maxAmount":null}}""")
            server.takeRequest()

            reply(
                """
                {"offlineProcessing":{"chipFloorLimit":50},
                "storeAndForward":{"maxAmount":[{"currencyCode":"USD","amount":10000}],"maxPayments":20,"supportedCardTypes":{"credit":true}}}
                """.trimIndent(),
            )
            reply("{}")
            reply("""{"offlineProcessing":{"chipFloorLimit":40},"storeAndForward":{"maxPayments":30}}""")
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Offline("USD", chipFloorLimit = null, maxStoreAndForwardMinor = null, maxPayments = null),
                ),
            ).isEqualTo(TerminalCapabilitiesUpdate.Ready(RemoteTerminalCapabilities(offline = TerminalOfflineLimits(40, emptyMap(), 30))))
            server.takeRequest()
            assertThat(JsonParser.parseString(checkNotNull(server.takeRequest().body).utf8()))
                .isEqualTo(
                    JsonParser.parseString(
                        """
                        {"offlineProcessing":{"chipFloorLimit":null},
                        "storeAndForward":{"maxAmount":[],"maxPayments":null,"supportedCardTypes":{"credit":true}}}
                        """.trimIndent(),
                    ),
                )
            server.takeRequest()
        }

    @Test
    fun `offline settings can be cleared after the server adds supported card defaults`(): Unit =
        runBlocking {
            reply(
                """
                {"offlineProcessing":{"chipFloorLimit":1},
                "storeAndForward":{"maxAmount":[{"amount":100,"currencyCode":"AUD"}],"maxPayments":1,
                "supportedCardTypes":{"credit":true,"debit":true,"deferredDebit":false,"prepaid":true,"unknown":false}}}
                """.trimIndent(),
            )
            reply("{}")
            reply(
                """
                {"offlineProcessing":{"chipFloorLimit":0},
                "storeAndForward":{"maxAmount":[],"supportedCardTypes":
                {"credit":true,"debit":true,"deferredDebit":false,"prepaid":true,"unknown":false}}}
                """.trimIndent(),
            )
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Offline("AUD", chipFloorLimit = null, maxStoreAndForwardMinor = null, maxPayments = null),
                ),
            ).isEqualTo(
                TerminalCapabilitiesUpdate.Ready(
                    RemoteTerminalCapabilities(offline = TerminalOfflineLimits(chipFloorLimit = 0)),
                ),
            )
            server.takeRequest()
            assertThat(JsonParser.parseString(checkNotNull(server.takeRequest().body).utf8()))
                .isEqualTo(
                    JsonParser.parseString(
                        """
                        {"offlineProcessing":{"chipFloorLimit":null},
                        "storeAndForward":{"maxAmount":[],"maxPayments":null,
                        "supportedCardTypes":{"credit":true,"debit":true,"deferredDebit":false,"prepaid":true,"unknown":false}}}
                        """.trimIndent(),
                    ),
                )
            server.takeRequest()
        }

    @Test
    fun `moto eligibility rejection is definite and does not trigger read-back`(): Unit =
        runBlocking {
            val detail =
                "Field 'device.allowMoto' is not valid. Reason: We couldn't verify whether this merchant account " +
                    "is eligible for MOTO. Contact Support.."
            reply(
                """
                {"status":422,"errorCode":"33_008","title":"Invalid parameters",
                "detail":"$detail"}
                """.trimIndent(),
                422,
            )
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Moto(TerminalMotoSettings(enabled = true, maxAmount = 1)),
                ),
            ).isEqualTo(
                TerminalCapabilitiesUpdate.Failed(
                    Fault.AdyenRejected(
                        422,
                        "33_008",
                        ExternalText(detail),
                    ),
                    uncertain = false,
                ),
            )
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `verifies moto and offline read-backs`() =
        runBlocking {
            reply("{}")
            reply("""{"moto":{"enableMoto":true,"maxAmount":100}}""")
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Moto(TerminalMotoSettings(enabled = true, maxAmount = 500)),
                ),
            ).isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.UnreadableReply(), uncertain = true))

            reply("{}")
            reply("""{"moto":{"enableMoto":false,"maxAmount":500}}""")
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Moto(TerminalMotoSettings(enabled = true, maxAmount = null)),
                ),
            ).isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.UnreadableReply(), uncertain = true))

            reply("{}")
            reply("{}")
            reply("""{"offlineProcessing":{"chipFloorLimit":10},"storeAndForward":{"maxPayments":5}}""")
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Offline("EUR", chipFloorLimit = 20, maxStoreAndForwardMinor = null, maxPayments = 5),
                ),
            ).isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.UnreadableReply(), uncertain = true))

            reply("{}")
            reply("{}")
            reply("""{"offlineProcessing":{"chipFloorLimit":20},"storeAndForward":{"maxPayments":10}}""")
            assertThat(
                api.updateCapabilities(
                    "ID",
                    TerminalEnvironment.TEST,
                    TerminalCapabilitiesPatch.Offline("EUR", chipFloorLimit = 20, maxStoreAndForwardMinor = null, maxPayments = 5),
                ),
            ).isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.UnreadableReply(), uncertain = true))
        }
}
