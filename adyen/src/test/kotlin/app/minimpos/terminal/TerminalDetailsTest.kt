package app.minimpos.terminal

import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.AdyenTerminalDetails
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRoles
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
            val allowed = api.credential(TerminalEnvironment.TEST) as CredentialLookup.Allowed
            assertThat(allowed.roles).isEqualTo(CredentialRoles(AdyenRole.required(), emptyList()))
            assertThat(allowed.roles.present).containsExactlyElementsIn(AdyenRole.required()).inOrder()
            assertThat(allowed.roles.complete).isTrue()
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/test/v3/me")
            reply("""{"roles":["Checkout webservice role"]}""")
            val denied = api.credential(TerminalEnvironment.TEST) as CredentialLookup.Failed
            assertThat(denied.fault).isEqualTo(Fault.Permission(ApiKey.ADYEN, ROLES))
            assertThat(denied.roles).isEqualTo(CredentialRoles(AdyenRole.required(), AdyenRole.required()))
            assertThat(denied.roles?.present).isEmpty()
            assertThat(denied.roles?.complete).isFalse()
            listOf(401, 403, 503).forEach { status ->
                reply("{}", status)
                val failed = api.credential(TerminalEnvironment.TEST) as CredentialLookup.Failed
                assertThat(failed.roles).isNull()
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
            val partial = api.credential(TerminalEnvironment.TEST) as CredentialLookup.Failed
            assertThat(partial.fault).isEqualTo(Fault.Permission(ApiKey.ADYEN, AdyenRole.names(AdyenRole.required().drop(1))))
            assertThat(partial.roles?.present).containsExactly(AdyenRole.TERMINAL_ACTIONS_READ)
            assertThat(partial.roles?.missing).containsExactlyElementsIn(AdyenRole.required().drop(1)).inOrder()
            reply(rolesReply(ALL_ROLES - "Management API - Payment methods read"))
            val missing = api.credential(TerminalEnvironment.TEST) as CredentialLookup.Failed
            assertThat(missing.fault).isEqualTo(Fault.Permission(ApiKey.ADYEN, "Management API - Payment methods read"))
            assertThat(missing.roles?.missing).containsExactly(AdyenRole.PAYMENT_METHODS_READ)
            // Adyen has spelled roles with other dashes, spacing, case and plurals.
            val respelled =
                ALL_ROLES.map { it.replace(" - ", "—").uppercase() }.map {
                    if (it.contains("ACCOUNT READ")) "Management API - Accounts read" else it
                }
            reply(rolesReply(respelled))
            assertThat(
                api.credential(TerminalEnvironment.TEST),
            ).isEqualTo(CredentialLookup.Allowed(CredentialRoles(AdyenRole.required(), emptyList())))
            // The same complete set is required even without cloud or Tap to Pay.
            listOf(AdyenRole.CLOUD_DEVICE, AdyenRole.API_CREDENTIALS).forEach { role ->
                reply(rolesReply(ALL_ROLES - role.displayName))
                val denied = api.credential(TerminalEnvironment.TEST) as CredentialLookup.Failed
                assertThat(denied.fault).isEqualTo(Fault.Permission(ApiKey.ADYEN, role.displayName))
                assertThat(denied.roles?.missing).containsExactly(role)
            }
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
                .isEqualTo(
                    CredentialLookup.Failed(
                        Fault.Permission(ApiKey.ADYEN, ROLES),
                        CredentialRoles(AdyenRole.required(), AdyenRole.required()),
                    ),
                )
        }

    private companion object {
        val ALL_ROLES =
            listOf(
                "Management API - Terminal actions read",
                "Management API - Terminal settings read and write",
                "Management API - Terminal settings Advanced read and write",
                "Management API - API credentials read and write",
                "Management API - Payment methods read",
                "Management API - Stores read",
                "Management API - Account read",
                "Merchant Recurring role",
                "Cloud Device API role",
            )
        val ROLES = ALL_ROLES.joinToString(", ")
    }
}
