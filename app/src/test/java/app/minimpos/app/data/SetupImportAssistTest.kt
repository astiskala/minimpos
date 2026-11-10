package app.minimpos.app.data

import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeManagement
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.FakeStoreDetails
import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.repo.ImportMode
import app.minimpos.app.data.security.RecoveryJournal
import app.minimpos.app.data.security.TransferSeal
import app.minimpos.app.data.transfer.ReceivedTransfer
import app.minimpos.app.terminal.AccountMismatch
import app.minimpos.app.terminal.SetupAnswers
import app.minimpos.app.terminal.SetupImportOutcome
import app.minimpos.app.terminal.SetupQuestion
import app.minimpos.core.codec.SealedSecrets
import app.minimpos.core.codec.Transfer
import app.minimpos.core.codec.TransferCodec
import app.minimpos.terminal.transport.AdyenRole
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.CredentialRoles
import app.minimpos.terminal.transport.CredentialRolesUpdate
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MerchantAccount
import app.minimpos.terminal.transport.MerchantAccountListing
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.SharedKeyUpdate
import app.minimpos.terminal.transport.StoreDetails
import app.minimpos.terminal.transport.StoreLookup
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Import help: an account to accept, a supplied key, roles to add, a stale address and Automatic Tap to Pay. */
@RunWith(RobolectricTestRunner::class)
class SetupImportAssistTest {
    private val poiId = "S1F2-000158213605014"
    private var credential: CredentialLookup = CredentialLookup.Allowed(CredentialRoles(AdyenRole.required(), emptyList()))
    private val available = listOf(TerminalDetails(poiId, "Merchant", "192.168.1.42", "ST1"))
    private var key: DiscoveredKey? = DiscoveredKey("store-key", 2, "correct horse battery staple")
    private val terminal = FakeTerminal()
    private val createdKeys = mutableListOf<DiscoveredKey>()
    private val activateKey = true
    private val storeDetails =
        FakeStoreDetails(StoreLookup.Found(StoreDetails("Cafe", "1 Main St", "+61212345678")))
    private var credentialReads = 0
    private val rolesAdded = mutableListOf<AdyenRole>()
    private var merchants = emptyList<MerchantAccount>()
    private var accountKeyMerchant: String? = null
    private val details: TerminalDetailsApi =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment): CredentialLookup {
                credentialReads++
                return credential
            }

            override suspend fun merchantAccounts(environment: TerminalEnvironment) = MerchantAccountListing.Listed(merchants)

            override suspend fun accountKey(
                merchantAccount: String,
                storeId: String?,
                environment: TerminalEnvironment,
            ): SharedKeyLookup {
                accountKeyMerchant = merchantAccount
                return key?.let(SharedKeyLookup::Found) ?: SharedKeyLookup.Missing
            }

            override suspend fun addRoles(
                environment: TerminalEnvironment,
                roles: List<AdyenRole>,
            ): CredentialRolesUpdate {
                rolesAdded += roles
                return CredentialRolesUpdate.Added
            }

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing = TerminalListing.Listed(available, environment)

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup = key?.let(SharedKeyLookup::Found) ?: SharedKeyLookup.Missing

            override suspend fun createSharedKey(
                id: String,
                environment: TerminalEnvironment,
                key: DiscoveredKey,
            ): SharedKeyUpdate {
                assertCreationPersisted()
                createdKeys += key
                this@SetupImportAssistTest.key = key
                if (activateKey) terminal.passphrase = key.passphrase
                return SharedKeyUpdate.Ready(key)
            }
        }
    private val management = FakeManagement()
    private val phone = FakePaymentsApp()
    private var env: TestEnvironment =
        TestEnvironment(
            FakeDevice(detectedPoiId = poiId),
            terminal = terminal,
            terminalDetails = details,
            stores = storeDetails,
            verifiedSetup = false,
        )

    internal suspend fun assertCreationPersisted() =
        assertThat(
            env.container.secrets
                .hasJournal(RecoveryJournal.KEY_CREATION)
                .first(),
        ).isTrue()

    private val seal = TransferSeal(iterations = 1_000)
    private val code = seal.newCode()

    private fun received(
        connection: String = """{"destination":"thisTerminal","automatic":true}""",
        secrets: String = """{"ADYEN_API_KEY":"imported-key"}""",
    ): ReceivedTransfer {
        val public = Transfer(connection = connection)
        val encrypted = seal.seal(secrets.toByteArray(), code, TransferCodec.authenticationData(public))
        val protected = public.copy(sealedSecrets = SealedSecrets(encrypted))
        return env.container.setupTransfer.receive(protected)
    }

    private fun import(connection: String = """{"destination":"thisTerminal","automatic":true}"""): SetupImportOutcome =
        await { env.container.setupImport.import(received(connection), ImportMode.MERGE, code) }

    @After
    fun tearDown() = env.close()

    @Test
    fun `an explicit account mismatch waits for the operator and then imports the terminal's account`() {
        val mismatched = """{"destination":"thisTerminal","automatic":true,"merchantAccount":"OtherMerchant"}"""
        assertThat(
            import(mismatched),
        ).isEqualTo(SetupImportOutcome.Asks(SetupQuestion.AccountDiffers(poiId, AccountMismatch("OtherMerchant", "Merchant"))))
        assertThat(await { env.container.settings.current() }.terminal.merchantAccount).isEmpty()
        val outcome =
            await {
                env.container.setupImport.import(
                    received(mismatched),
                    ImportMode.MERGE,
                    code,
                    answers = SetupAnswers(acceptedAccount = "Merchant"),
                )
            }
        assertThat(outcome).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(await { env.container.settings.current() }.terminal.merchantAccount).isEqualTo("Merchant")
    }

    @Test
    fun `a shared key supplied with Automatic setup is used without looking one up`() {
        key = null
        terminal.passphrase = "typed passphrase"
        val received =
            received(
                """{"destination":"thisTerminal","automatic":true,"keyIdentifier":"typed","keyVersion":3}""",
                """{"ADYEN_API_KEY":"imported-key","TERMINAL_PASSPHRASE":"typed passphrase"}""",
            )
        val outcome = await { env.container.setupImport.import(received, ImportMode.MERGE, code) }
        assertThat(outcome).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(createdKeys).isEmpty()
        val saved = await { env.container.settings.current() }.terminal
        assertThat(saved.keyIdentifier).isEqualTo("typed")
        assertThat(saved.keyVersion).isEqualTo(3)
        assertThat(saved.merchantAccount).isEqualTo("Merchant")
    }

    @Test
    fun `roles the key lacks are added only after the operator agrees and the import continues`() {
        val missing = listOf(AdyenRole.TERMINAL_SETTINGS_ADVANCED, AdyenRole.CLOUD_DEVICE)
        credential =
            CredentialLookup.Failed(
                Fault.Permission(ApiKey.ADYEN, AdyenRole.names(missing)),
                CredentialRoles(AdyenRole.required(), missing),
            )
        val offered = (import() as SetupImportOutcome.Asks).question as SetupQuestion.RolesMissing
        assertThat(offered.offer.roles).isEqualTo(missing)
        assertThat(rolesAdded).isEmpty()
        val outcome =
            await {
                env.container.setupImport.import(
                    received(),
                    ImportMode.MERGE,
                    code,
                    answers = SetupAnswers(acceptedRoles = offered.offer),
                )
            }
        assertThat(rolesAdded).containsExactlyElementsIn(missing)
        assertThat(outcome).isInstanceOf(SetupImportOutcome.Committed::class.java)
    }

    @Test
    fun `a key that cannot edit credentials gets the named roles rather than an offer`() {
        val missing = listOf(AdyenRole.API_CREDENTIALS, AdyenRole.CLOUD_DEVICE)
        val fault = Fault.Permission(ApiKey.ADYEN, AdyenRole.names(missing))
        credential = CredentialLookup.Failed(fault, CredentialRoles(AdyenRole.required(), missing))
        assertThat(import()).isEqualTo(SetupImportOutcome.Failed(Failure.Remote(fault)))
    }

    @Test
    fun `a stale network address can be corrected without new codes`() {
        env.close()
        env =
            TestEnvironment(
                FakeDevice(),
                terminal = terminal,
                terminalDetails = details,
                stores = storeDetails,
                verifiedSetup = false,
            )
        terminal.unreachableHosts = setOf("192.168.1.42")
        val received = received("""{"destination":"network","environment":"TEST","automatic":true}""")
        val failed = await { env.container.setupImport.import(received, ImportMode.MERGE, code) } as SetupImportOutcome.Failed
        assertThat(failed.terminalHost).isEqualTo("192.168.1.42")
        val outcome = await { env.container.setupImport.import(received, ImportMode.MERGE, code, host = "192.168.1.99") }
        assertThat(outcome).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(await { env.container.settings.current() }.terminal.host).isEqualTo("192.168.1.99")
    }

    @Test
    fun `Automatic Tap to Pay finds the only merchant account and its shared key`() {
        tapToPayEnvironment()
        merchants = listOf(MerchantAccount("OnlyMerchant", "Only"))
        val connection = """{"destination":"tapToPay","environment":"TEST","automatic":true}"""
        val outcome = await { env.container.setupImport.import(received(connection), ImportMode.MERGE, code, board = true) }
        assertThat(outcome).isInstanceOf(SetupImportOutcome.Committed::class.java)
        val saved = await { env.container.settings.current() }.terminal
        assertThat(saved.merchantAccount).isEqualTo("OnlyMerchant")
        assertThat(saved.keyIdentifier).isEqualTo("store-key")
        assertThat(accountKeyMerchant).isEqualTo("OnlyMerchant")
    }

    @Test
    fun `Automatic Tap to Pay asks which merchant account when the key reaches several`() {
        tapToPayEnvironment()
        merchants = listOf(MerchantAccount("A", "First"), MerchantAccount("B", "Second"))
        val connection = """{"destination":"tapToPay","environment":"TEST","automatic":true}"""
        val choices =
            (
                await {
                    env.container.setupImport.import(received(connection), ImportMode.MERGE, code)
                } as SetupImportOutcome.Asks
            ).question as SetupQuestion.Merchants
        assertThat(choices.accounts.map { it.id }).containsExactly("A", "B").inOrder()
        val chosen =
            await {
                env.container.setupImport.import(
                    received(connection),
                    ImportMode.MERGE,
                    code,
                    answers = SetupAnswers(merchantAccount = "B"),
                    board = true,
                )
            }
        assertThat(chosen).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(await { env.container.settings.current() }.terminal.merchantAccount).isEqualTo("B")
    }

    @Test
    fun `Automatic Tap to Pay without a readable key says to enter it in the helper`() {
        tapToPayEnvironment()
        merchants = listOf(MerchantAccount("OnlyMerchant", "Only"))
        key = null
        val outcome = import("""{"destination":"tapToPay","environment":"TEST","automatic":true}""") as SetupImportOutcome.Failed
        assertThat(outcome.keyUnread).isTrue()
    }

    private fun tapToPayEnvironment() {
        env.close()
        env =
            TestEnvironment(
                FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST)),
                paymentsApp = phone,
                management = management,
                terminalDetails = details,
                verifiedSetup = false,
            )
    }
}
