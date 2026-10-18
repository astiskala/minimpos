package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart
import app.minimpos.terminal.transport.RemoteTerminalCapabilities
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.SharedKeyUpdate
import app.minimpos.terminal.transport.TerminalCapabilitiesLookup
import app.minimpos.terminal.transport.TerminalCapabilitiesPatch
import app.minimpos.terminal.transport.TerminalCapabilitiesUpdate
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalGratuity
import app.minimpos.terminal.transport.TerminalListing
import app.minimpos.terminal.transport.TerminalMotoSettings
import app.minimpos.terminal.transport.TerminalOfflineLimits
import com.adyen.model.management.TerminalSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Test
import java.util.concurrent.TimeUnit

class TerminalDetailsTest {
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
    fun `credential role checks are bounded to one environment and return typed failures`() =
        runBlocking {
            reply(rolesReply(ALL_ROLES.map { it.replace(" - ", " – ") } + "Checkout webservice role"))
            assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Allowed)
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/test/v3/me")
            reply("""{"roles":["Checkout webservice role"]}""")
            assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Failed(Fault.Permission(ApiKey.ADYEN, ROLES)))
            listOf(401, 403, 503).forEach { status ->
                reply("{}", status)
                assertThat(api.credential(TerminalEnvironment.TEST)).isInstanceOf(CredentialLookup.Failed::class.java)
            }
            listOf("{}", "not json", """{"roles":[null]}""", """{"roles":[123]}""").forEach { body ->
                reply(body)
                assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Failed(Fault.UnreadableReply()))
            }
        }

    @Test
    fun `real setup requires every role even when unused and names only the missing ones`() =
        runBlocking {
            reply(rolesReply(listOf("Management API - Terminal actions read")))
            assertThat(api.credential(TerminalEnvironment.TEST))
                .isEqualTo(
                    CredentialLookup.Failed(Fault.Permission(ApiKey.ADYEN, AdyenRole.names(AdyenRole.required(cloud = false).drop(1)))),
                )
            reply(rolesReply(ALL_ROLES - "Management API - Payment methods read"))
            assertThat(api.credential(TerminalEnvironment.TEST))
                .isEqualTo(CredentialLookup.Failed(Fault.Permission(ApiKey.ADYEN, "Management API - Payment methods read")))
            // Adyen has spelled roles with other dashes, spacing, case and plurals.
            val respelled =
                ALL_ROLES.map { it.replace(" - ", "—").uppercase() }.map {
                    if (it.contains("ACCOUNT READ")) "Management API - Accounts read" else it
                }
            reply(rolesReply(respelled))
            assertThat(api.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Allowed)
            // A cloud terminal also needs the Cloud device API role.
            reply(rolesReply(ALL_ROLES))
            assertThat(api.credential(TerminalEnvironment.TEST, cloud = true))
                .isEqualTo(CredentialLookup.Failed(Fault.Permission(ApiKey.ADYEN, "Cloud Device API role")))
            reply(rolesReply(ALL_ROLES + "Cloud Device API role"))
            assertThat(api.credential(TerminalEnvironment.TEST, cloud = true)).isEqualTo(CredentialLookup.Allowed)
        }

    private fun rolesReply(roles: List<String>): String = roles.joinToString(",", """{"roles":[""", "]}") { "\"$it\"" }

    @Test
    fun `reads assignments and network addresses across pages without following untrusted URLs`() =
        runBlocking {
            reply(
                """
                {"data":[{"id":"S1F2-123456789","model":"S1F2","countryCode":"AU","assignment":{"merchantId":"Merchant","storeId":"ST1","reassignmentTarget":{"merchantId":"Wrong","storeId":"ST2"}},
                "connectivity":{"ethernet":{"ipAddress":"192.168.1.2"},"wifi":{"ipAddress":"192.168.1.3"}}}],
                "_links":{"next":{"href":"https://untrusted.example/"}}}
                """.trimIndent(),
            )
            reply(
                """{"data":[{"id":"AMS1-123456789","connectivity":{"ethernet":{"ipAddress":" "},"wifi":{"ipAddress":"192.168.1.4"}}}]}""",
            )
            val listed = api.terminals(TerminalEnvironment.TEST) as TerminalListing.Listed
            assertThat(listed.environment).isEqualTo(TerminalEnvironment.TEST)
            assertThat(listed.terminals.map { it.host }).containsExactly("192.168.1.2", "192.168.1.4").inOrder()
            val firstTerminal = listed.terminals.first()
            assertThat(firstTerminal.id).isEqualTo("S1F2-123456789")
            assertThat(firstTerminal.model).isEqualTo("S1F2")
            assertThat(firstTerminal.merchantAccount).isEqualTo("Merchant")
            assertThat(firstTerminal.storeId).isEqualTo("ST1")
            assertThat(firstTerminal.countryCode).isEqualTo("AU")
            val first = server.takeRequest()
            assertThat(first.headers["x-api-key"]).isEqualTo("secret")
            assertThat(first.method).isEqualTo("GET")
            assertThat(server.takeRequest().url.queryParameter("pageNumber")).isEqualTo("2")
        }

    @Test
    fun `default interface methods report unsupported`() =
        runBlocking {
            val minimal =
                object : TerminalDetailsApi {
                    override suspend fun terminals(
                        environment: TerminalEnvironment,
                        id: String?,
                    ): TerminalListing = TerminalListing.Failed(Fault.Unsupported)

                    override suspend fun sharedKey(
                        id: String,
                        environment: TerminalEnvironment,
                    ): SharedKeyLookup = SharedKeyLookup.Failed(Fault.Unsupported)
                }
            assertThat(minimal.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Failed(Fault.Unsupported))
            assertThat(minimal.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("k", 1, "secret")))
                .isEqualTo(SharedKeyUpdate.Failed(Fault.Unsupported))
            assertThat(minimal.capabilities("ID", TerminalEnvironment.TEST))
                .isEqualTo(TerminalCapabilitiesLookup.Failed(Fault.Unsupported))
            assertThat(minimal.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Dcc(true)))
                .isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.Unsupported, uncertain = false))
        }

    @Test
    fun `authentication and permission rejection never try the other environment`() =
        runBlocking {
            reply("{}", 401)
            assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
            reply("{}", 403)
            assertThat(api.terminals(TerminalEnvironment.LIVE)).isInstanceOf(TerminalListing.Failed::class.java)
            reply("""{"data":[]}""")
            assertThat((api.terminals(TerminalEnvironment.LIVE) as TerminalListing.Listed).environment).isEqualTo(TerminalEnvironment.LIVE)
            assertThat(server.requestCount).isEqualTo(3)
            assertThat(server.takeRequest().url.encodedPath).startsWith("/test/")
            assertThat(server.takeRequest().url.encodedPath).startsWith("/live/")
            assertThat(server.takeRequest().url.encodedPath).startsWith("/live/")
        }

    @Test
    fun `malformed and incomplete terminal lists cannot be selected`() =
        runBlocking {
            listOf("not json", "{}", "[]", """{"data":[{"id":""}]}""").forEach {
                reply(it)
                assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
            }
        }

    @Test
    fun `pagination is bounded and a failed later page never returns a partial selection`() =
        runBlocking {
            repeat(100) { reply("""{"data":[],"_links":{"next":{"href":"next"}}}""") }
            assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
            assertThat(server.requestCount).isEqualTo(100)
            reply("""{"data":[{"id":"ID"}],"_links":{"next":{"href":"next"}}}""")
            reply("{}", 503)
            assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
        }

    @Test
    fun `optional fields may be blank and unexpected JSON types reject the list`() =
        runBlocking {
            reply("""{"data":[{"id":"ID"},{"id":"ID"}]}""")
            val listed = api.terminals(TerminalEnvironment.TEST) as TerminalListing.Listed
            assertThat(listed.terminals).hasSize(1)
            assertThat(listed.terminals.single().host).isEmpty()
            reply("""{"data":[{"id":123}]}""")
            assertThat(api.terminals(TerminalEnvironment.TEST)).isInstanceOf(TerminalListing.Failed::class.java)
        }

    @Test
    fun `shared keys are optional and secret whitespace is preserved`() =
        runBlocking {
            reply("""{"nexo":{"encryptionKey":{"identifier":"key","version":2,"passphrase":" secret passphrase "}}}""")
            val key = (api.sharedKey("Terminal/ID", TerminalEnvironment.TEST) as SharedKeyLookup.Found).key
            assertThat(key.identifier).isEqualTo("key")
            assertThat(key.version).isEqualTo(2)
            assertThat(key.passphrase).isEqualTo(" secret passphrase ")
            assertThat(key.toString()).doesNotContain("secret passphrase")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/test/v3/terminals/Terminal%2FID/terminalSettings")
            listOf("{}", """{"nexo":null}""", """{"nexo":{}}""", """{"nexo":{"encryptionKey":null}}""").forEach { body ->
                reply(body)
                assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isEqualTo(SharedKeyLookup.Missing)
            }
            listOf(
                "[]" to Fault.Malformed(MalformedPart.SETTINGS),
                """{"nexo":{"encryptionKey":{"identifier":"key","version":0,"passphrase":"secret"}}}""" to
                    Fault.Malformed(MalformedPart.KEY_VERSION),
                """{"nexo":{"encryptionKey":{"identifier":"","version":2,"passphrase":"secret"}}}""" to Fault.Malformed(MalformedPart.KEY),
                """{"nexo":{"encryptionKey":{"identifier":"key","version":2,"passphrase":""}}}""" to Fault.Malformed(MalformedPart.KEY),
                """{"nexo":{"encryptionKey":{"identifier":"key","version":2.5,"passphrase":"secret"}}}""" to
                    Fault.Malformed(MalformedPart.SETTINGS),
            ).forEach { (body, reason) ->
                reply(body)
                assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isEqualTo(SharedKeyLookup.Failed(reason))
            }
            reply("{}", 403)
            assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isEqualTo(SharedKeyLookup.Failed(Fault.Permission(ApiKey.ADYEN)))
            reply("""{"nexo":{"encryptionKey":{"identifier":"key","version":1,"passphrase":"secret"}}}""")
            assertThat((api.sharedKey("ID", TerminalEnvironment.TEST) as SharedKeyLookup.Found).key.version).isEqualTo(1)
            reply("""{"nexo":{"encryptionKey":{"identifier":"key","version":9999,"passphrase":"secret"}}}""")
            assertThat((api.sharedKey("ID", TerminalEnvironment.TEST) as SharedKeyLookup.Found).key.version).isEqualTo(9999)
        }

    @Test
    fun `known POIID uses one search request and never accepts substring neighbours`() =
        runBlocking {
            reply("""{"data":[{"id":"ID-other"},{"id":"ID"}],"_links":{"next":{"href":"next"}}}""")
            val result = api.terminals(TerminalEnvironment.TEST, "ID") as TerminalListing.Listed
            assertThat(result.terminals.map { it.id }).containsExactly("ID")
            val request = server.takeRequest()
            assertThat(request.url.queryParameter("searchQuery")).isEqualTo("ID")
            assertThat(request.url.queryParameter("pageNumber")).isNull()
            assertThat(server.requestCount).isEqualTo(1)
            reply("""{"data":[{"id":"ID-other"}]}""")
            assertThat((api.terminals(TerminalEnvironment.TEST, "ID") as TerminalListing.Listed).terminals).isEmpty()
        }

    @Test
    fun `unmodeled unrelated settings do not block lookup or enter the shared key PATCH`() =
        runBlocking {
            val settings =
                """
                {"nexo":{"notification":{"category":"","details":"","enabled":false,"showButton":true,"title":""}},
                "tapToPay":{"enableTapToPayOnIOS":true,"enableTapToPayOnAndroid":true},
                "terminalInstructions":{"adyenAppRestart":false},
                "homeScreen":{"showPaymentsMenu":true,"showSettingsMenu":true},
                "futureSection":{"setting":true}}
                """.trimIndent()
            reply(settings)
            assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isEqualTo(SharedKeyLookup.Missing)
            assertThat(server.takeRequest().method).isEqualTo("GET")
            reply(settings)
            reply("{}")
            reply("""{"nexo":{"encryptionKey":{"identifier":"new","version":1,"passphrase":"NewStrongSecret123!"}}}""")
            val result =
                api.createSharedKey(
                    "ID",
                    TerminalEnvironment.TEST,
                    DiscoveredKey("new", 1, "NewStrongSecret123!"),
                ) as SharedKeyUpdate.Ready
            assertThat(result.key.identifier).isEqualTo("new")
            assertThat(server.takeRequest().method).isEqualTo("GET")
            val patch = server.takeRequest()
            assertThat(patch.method).isEqualTo("PATCH")
            val body = checkNotNull(patch.body).utf8()
            listOf("tapToPay", "terminalInstructions", "homeScreen", "futureSection").forEach { assertThat(body).doesNotContain(it) }
            val sent = TerminalSettings.fromJson(body)
            assertThat(sent.nexo.notification.showButton).isTrue()
            assertThat(sent.nexo.notification.enabled).isFalse()
            assertThat(sent.nexo.encryptionKey.passphrase).isEqualTo("NewStrongSecret123!")
            assertThat(server.takeRequest().method).isEqualTo("GET")
            assertThat(server.requestCount).isEqualTo(4)
        }

    @Test
    fun `creation preserves nexo fields and leaves all other settings untouched`() =
        runBlocking {
            reply(
                """
                {"cardholderReceipt":{"headerForAuthorizedReceipt":"header1,header2,filler"},
                "nexo":{"notification":{"category":"","details":"","enabled":false,"showButton":true,"title":""}},
                "opi":{"enablePayAtTable":false}}
                """.trimIndent(),
            )
            reply("{}")
            reply("""{"nexo":{"encryptionKey":{"identifier":"new","version":1,"passphrase":"NewStrongSecret123!"}}}""")
            val result =
                api.createSharedKey(
                    "ID",
                    TerminalEnvironment.TEST,
                    DiscoveredKey("new", 1, "NewStrongSecret123!"),
                ) as SharedKeyUpdate.Ready
            assertThat(result.key.identifier).isEqualTo("new")
            assertThat(server.takeRequest().method).isEqualTo("GET")
            val patch = server.takeRequest()
            assertThat(patch.method).isEqualTo("PATCH")
            assertThat(patch.url.encodedPath).isEqualTo("/test/v3/terminals/ID/terminalSettings")
            val sent = TerminalSettings.fromJson(checkNotNull(patch.body).utf8())
            assertThat(sent.nexo.notification.showButton).isTrue()
            assertThat(sent.nexo.notification.enabled).isFalse()
            assertThat(sent.nexo.encryptionKey.passphrase).isEqualTo("NewStrongSecret123!")
            assertThat(sent.cardholderReceipt).isNull()
            assertThat(sent.opi).isNull()
            assertThat(server.takeRequest().method).isEqualTo("GET")
        }

    @Test
    fun `fresh existing keys are reused without PATCH`() =
        runBlocking {
            reply("""{"nexo":{"encryptionKey":{"identifier":"existing","version":3,"passphrase":"ExistingSecret"}}}""")
            val result =
                api.createSharedKey(
                    "ID",
                    TerminalEnvironment.TEST,
                    DiscoveredKey("new", 1, "NewStrongSecret123!"),
                ) as SharedKeyUpdate.Ready
            assertThat(result.key.identifier).isEqualTo("existing")
            assertThat(server.requestCount).isEqualTo(1)
        }

    @Test
    fun `failed or unmodeled settings never permit creation`() =
        runBlocking {
            listOf(
                "not json" to Fault.Malformed(MalformedPart.SETTINGS),
                "[]" to Fault.Malformed(MalformedPart.SETTINGS),
                """{"nexo":{"encryptionKey":{}}}""" to Fault.Malformed(MalformedPart.KEY),
                """{"nexo":{"encryptionKey":{"identifier":"existing","version":2}}}""" to Fault.Malformed(MalformedPart.KEY),
                """{"nexo":{"encryptionKey":{"identifier":"existing","passphrase":"secret"}}}""" to Fault.Malformed(MalformedPart.KEY),
                """{"nexo":{"encryptionKey":{"identifier":"existing","version":10000,"passphrase":"secret"}}}""" to
                    Fault.Malformed(MalformedPart.KEY_VERSION),
                """{"nexo":{"unknownSetting":true}}""" to Fault.Malformed(MalformedPart.SETTINGS),
                """{"nexo":{"notification":{"title":123}}}""" to Fault.Malformed(MalformedPart.SETTINGS),
                """{"nexo":{"notification":{"enabled":"false"}}}""" to Fault.Malformed(MalformedPart.SETTINGS),
                """{"nexo":{"notification":{"showButton":1}}}""" to Fault.Malformed(MalformedPart.SETTINGS),
            ).forEach { (body, reason) ->
                reply(body)
                assertThat(api.sharedKey("ID", TerminalEnvironment.TEST)).isEqualTo(SharedKeyLookup.Failed(reason))
                reply(body)
                assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                    .isEqualTo(SharedKeyUpdate.Failed(reason))
            }
            reply("{}", 403)
            assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                .isEqualTo(SharedKeyUpdate.Failed(Fault.Permission(ApiKey.ADYEN)))
            assertThat(server.requestCount).isEqualTo(21)
        }

    @Test
    fun `unverified PATCH outcomes remain uncertain without automatic retries`() =
        runBlocking {
            reply("{}")
            reply("{}", 503)
            assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                .isEqualTo(SharedKeyUpdate.Failed(Fault.AdyenUnavailable(503), uncertain = true))
            reply("{}")
            reply("{}")
            reply("{}")
            assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                .isEqualTo(SharedKeyUpdate.Failed(Fault.UnreadableReply(), uncertain = true))
            assertThat(server.requestCount).isEqualTo(5)
            // Read-back failure or mismatched key fields remain uncertain.
            reply("{}")
            reply("{}")
            reply("{}", 503)
            assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                .isEqualTo(SharedKeyUpdate.Failed(Fault.AdyenUnavailable(503), uncertain = true))
            reply("{}")
            reply("{}")
            reply("""{"nexo":{"encryptionKey":{"identifier":"new","version":1,"passphrase":"WrongPassword"}}}""")
            assertThat(api.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("new", 1, "NewStrongSecret123!")))
                .isEqualTo(SharedKeyUpdate.Failed(Fault.UnreadableReply(), uncertain = true))
        }

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

    @Test
    fun `default interface methods and default baseUrl work as expected`() =
        runBlocking {
            val defaultApi =
                object : TerminalDetailsApi {
                    override suspend fun terminals(
                        environment: TerminalEnvironment,
                        id: String?,
                    ) = TerminalListing.Listed(emptyList(), environment)

                    override suspend fun sharedKey(
                        id: String,
                        environment: TerminalEnvironment,
                    ) = SharedKeyLookup.Missing
                }
            assertThat(defaultApi.credential(TerminalEnvironment.TEST)).isEqualTo(CredentialLookup.Failed(Fault.Unsupported))
            assertThat(defaultApi.createSharedKey("ID", TerminalEnvironment.TEST, DiscoveredKey("k", 1, "p")))
                .isEqualTo(SharedKeyUpdate.Failed(Fault.Unsupported))
            assertThat(defaultApi.capabilities("ID", TerminalEnvironment.TEST))
                .isEqualTo(TerminalCapabilitiesLookup.Failed(Fault.Unsupported))
            assertThat(defaultApi.updateCapabilities("ID", TerminalEnvironment.TEST, TerminalCapabilitiesPatch.Dcc(false)))
                .isEqualTo(TerminalCapabilitiesUpdate.Failed(Fault.Unsupported))
            assertThat(TerminalCapabilitiesPatch.Dcc(false).enabled).isFalse()
            assertThat(TerminalMotoSettings(enabled = false, maxAmount = null).enabled).isFalse()
            assertThat(TerminalGratuity("EUR", false, emptyList(), false).usePredefinedEntries).isFalse()
            assertThat(TerminalGratuity("EUR", false, emptyList(), false).allowCustomAmount).isFalse()
            assertThat(TerminalGratuity("EUR", true, listOf("10%"), true).allowCustomAmount).isTrue()
            assertThat(RemoteTerminalCapabilities().surchargeConfigured).isFalse()
            val defaultUrlDetails =
                AdyenTerminalDetails(
                    apiKey = "mgmt-key",
                    baseClient =
                        OkHttpClient
                            .Builder()
                            .addInterceptor { chain ->
                                Response
                                    .Builder()
                                    .request(chain.request())
                                    .protocol(Protocol.HTTP_1_1)
                                    .code(200)
                                    .message("OK")
                                    .body("""{"roles":[]}""".toResponseBody())
                                    .build()
                            }.build(),
                )
            assertThat(defaultUrlDetails.credential(TerminalEnvironment.TEST))
                .isEqualTo(CredentialLookup.Failed(Fault.Permission(ApiKey.ADYEN, ROLES)))
        }

    private companion object {
        val ALL_ROLES =
            listOf(
                "Management API - Terminal actions read",
                "Management API - Terminal settings read and write",
                "Management API - Terminal settings Advanced read and write",
                "Management API - Payment methods read",
                "Management API - Stores read",
                "Management API - Account read",
                "Merchant Recurring role",
            )
        val ROLES = ALL_ROLES.joinToString(", ")
    }
}
