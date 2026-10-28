package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.TerminalCapabilitiesPatch
import app.minimpos.terminal.transport.TerminalCapabilitiesUpdate
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalGratuity
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Test

class TerminalCapabilityRoundTripTest {
    private val server = MockWebServer().apply { start() }
    private val api = AdyenTerminalDetails("fixture-key", baseUrl = { server.url("/v3/") })
    private val environment = TerminalEnvironment.TEST
    private val gratuity = TerminalGratuity("AUD", true, listOf("10%", "20%"), true)
    private val configured =
        """
        {"gratuities":[{"currency":"AUD","usePredefinedTipEntries":true,
        "predefinedTipEntries":["10%","20%"],"allowCustomAmount":true}],
        "standalone":{"enableGratuities":true}}
        """.trimIndent()
    private val cleared = """{"gratuities":[],"standalone":{"enableGratuities":false}}"""

    private fun reply(body: String) = server.enqueue(MockResponse.Builder().body(body).build())

    @After
    fun tearDown() = server.close()

    @Test
    fun `gratuity add and removal verify requested values despite related standalone changes`() =
        runBlocking {
            reply(cleared)
            reply("{}")
            reply(configured)
            val added =
                api.updateCapabilities(
                    "ID",
                    environment,
                    TerminalCapabilitiesPatch.Gratuity("AUD", gratuity),
                ) as TerminalCapabilitiesUpdate.Ready
            assertThat(added.capabilities.gratuities).containsExactly(gratuity)
            assertThat(server.takeRequest().method).isEqualTo("GET")
            val add = server.takeRequest()
            assertThat(add.method).isEqualTo("PATCH")
            assertThat(JsonParser.parseString(checkNotNull(add.body).utf8()))
                .isEqualTo(
                    JsonParser.parseString(
                        """
                        {"gratuities":[{"currency":"AUD","usePredefinedTipEntries":true,
                        "predefinedTipEntries":["10%","20%"],"allowCustomAmount":true}]}
                        """.trimIndent(),
                    ),
                )
            assertThat(server.takeRequest().method).isEqualTo("GET")

            reply(configured)
            reply("{}")
            reply(cleared)
            val removed =
                api.updateCapabilities(
                    "ID",
                    environment,
                    TerminalCapabilitiesPatch.Gratuity("AUD", null),
                ) as TerminalCapabilitiesUpdate.Ready
            assertThat(removed.capabilities.gratuities).isEmpty()
            assertThat(server.takeRequest().method).isEqualTo("GET")
            val remove = server.takeRequest()
            assertThat(remove.method).isEqualTo("PATCH")
            assertThat(checkNotNull(remove.body).utf8()).isEqualTo("""{"gratuities":[]}""")
            assertThat(server.takeRequest().method).isEqualTo("GET")
            assertThat(server.requestCount).isEqualTo(6)
        }

    @Test
    fun `dcc disable and restore send only dcc and return fresh unrelated capabilities`() =
        runBlocking {
            for (enabled in listOf(false, true)) {
                reply("{}")
                reply(
                    """
                    {"dcc":{"enableDcc":$enabled},"moto":{"enableMoto":false,"maxAmount":250},
                    "gratuities":[],"standalone":{"enableGratuities":false}}
                    """.trimIndent(),
                )
                val result =
                    api.updateCapabilities(
                        "ID",
                        environment,
                        TerminalCapabilitiesPatch.Dcc(enabled),
                    ) as TerminalCapabilitiesUpdate.Ready
                assertThat(result.capabilities.dccEnabled).isEqualTo(enabled)
                assertThat(result.capabilities.moto.enabled).isFalse()
                assertThat(result.capabilities.moto.maxAmount).isEqualTo(250)
                assertThat(result.capabilities.gratuities).isEmpty()
                val patch = server.takeRequest()
                assertThat(patch.method).isEqualTo("PATCH")
                assertThat(checkNotNull(patch.body).utf8()).isEqualTo("""{"dcc":{"enableDcc":$enabled}}""")
                assertThat(server.takeRequest().method).isEqualTo("GET")
            }
            assertThat(server.requestCount).isEqualTo(4)
        }
}
