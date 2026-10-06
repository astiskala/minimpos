package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakeManagement
import io.github.astiskala.minimpos.app.FakePaymentsApp
import io.github.astiskala.minimpos.app.FakeTerminal
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.db.SaleEntity
import io.github.astiskala.minimpos.app.data.db.SaleStatus
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.repo.ImportMode
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.security.TransferSeal
import io.github.astiskala.minimpos.app.data.transfer.ImportOutcome
import io.github.astiskala.minimpos.app.data.transfer.ReceivedTransfer
import io.github.astiskala.minimpos.app.terminal.ApiAccess
import io.github.astiskala.minimpos.app.terminal.SetupImportOutcome
import io.github.astiskala.minimpos.app.terminal.TerminalConnection
import io.github.astiskala.minimpos.core.codec.SealedSecrets
import io.github.astiskala.minimpos.core.codec.Transfer
import io.github.astiskala.minimpos.core.codec.TransferCodec
import io.github.astiskala.minimpos.core.money.PaymentContext
import io.github.astiskala.minimpos.terminal.paymentsapp.ManagementResult
import io.github.astiskala.minimpos.terminal.transport.CredentialLookup
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.ManagementFailure
import io.github.astiskala.minimpos.terminal.transport.StoreDetails
import io.github.astiskala.minimpos.terminal.transport.StoreDetailsApi
import io.github.astiskala.minimpos.terminal.transport.StoreListing
import io.github.astiskala.minimpos.terminal.transport.TerminalDetails
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AutomaticSetupTransferTest {
    private val poiId = "S1F2-000158213605014"
    private var credential: CredentialLookup = CredentialLookup.Allowed
    private var available = listOf(TerminalDetails(poiId, "Merchant", "192.168.1.42", "ST1"))
    private var key: DiscoveredKey? = DiscoveredKey("store-key", 2, "correct horse battery staple")
    private var stores: StoreListing =
        StoreListing.Listed(
            listOf(StoreDetails("ST1", "cafe", "Cafe", "1 Main St", "+61212345678")),
        )
    private var credentialReads = 0
    private val details =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment): CredentialLookup {
                credentialReads++
                return credential
            }

            override suspend fun terminals(environment: TerminalEnvironment): TerminalListing =
                TerminalListing.Listed(available, environment)

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): DiscoveredKey? = key
        }
    private val management = FakeManagement()
    private val phone = FakePaymentsApp()
    private var env =
        TestEnvironment(
            FakeDevice(detectedPoiId = poiId),
            terminal = FakeTerminal(),
            terminalDetails = details,
            stores = StoreDetailsApi { stores },
            verifiedSetup = false,
        )
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
    fun `authenticated import requires history loss confirmation before replacing environment`() {
        await {
            env.container.sales.createPending(
                SaleEntity(
                    id = "unknown",
                    createdAt = 1,
                    currency = "AUD",
                    taxMode = "INCLUSIVE",
                    netMinor = 1000,
                    taxMinor = 0,
                    totalMinor = 1000,
                    status = SaleStatus.UNKNOWN,
                    merchantReference = "OLD-LIVE",
                    context = PaymentContext("TERMINAL", poiId, "POS", "Merchant", "LIVE"),
                ),
                emptyList(),
            )
        }
        val before = await { env.container.settings.current() }
        val warning = import() as SetupImportOutcome.HistoryConfirmation
        assertThat(warning.plan.unfinished).isTrue()
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        assertThat(
            await {
                env.container.history
                    .items()
                    .first()
            },
        ).hasSize(1)
        val imported =
            await {
                env.container.setupImport.import(received(), ImportMode.MERGE, code, confirmedHistorySwitch = warning.plan)
            } as SetupImportOutcome.Committed
        assertThat(imported.outcome).isInstanceOf(ImportOutcome.Imported::class.java)
        assertThat(
            await {
                env.container.history
                    .items()
                    .first()
            },
        ).isEmpty()
        assertThat(await { env.container.settings.current() }.terminal.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(
            await {
                env.container.history.pendingSwitch
                    .first()
            },
        ).isNull()
    }

    @Test
    fun `wrong company terminal access blocks the whole import without writes`() {
        available = listOf(TerminalDetails("AMS1-000168223606144", "OtherCompanyMerchant", "192.168.1.7"))
        val before = await { env.container.settings.current() }
        assertThat(import()).isEqualTo(SetupImportOutcome.Failed(SetupProblem.TERMINAL_ACCESS))
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        assertThat(
            await {
                env.container.secrets.configured
                    .first()
            },
        ).isEmpty()
        assertThat(
            await {
                env.container.setupTransfer.pending
                    .first()
            },
        ).isFalse()
    }

    @Test
    fun `permission failures do not become manual fallback or replace saved fields`() {
        credential = CredentialLookup.Failed(ManagementFailure.PERMISSION)
        val before = await { env.container.settings.current() }
        assertThat(import()).isEqualTo(SetupImportOutcome.Failed(SetupProblem.MANAGEMENT_PERMISSION))
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        credential = CredentialLookup.Failed(ManagementFailure.AUTHENTICATION)
        assertThat(import()).isEqualTo(SetupImportOutcome.Failed(SetupProblem.MANAGEMENT_AUTHENTICATION))
        credential = CredentialLookup.Failed(ManagementFailure.UNAVAILABLE)
        assertThat(import()).isEqualTo(SetupImportOutcome.Failed(SetupProblem.MANAGEMENT_UNAVAILABLE))
    }

    @Test
    fun `explicit account mismatch is rejected without correcting the imported account`() {
        assertThat(import("""{"destination":"thisTerminal","automatic":true,"merchantAccount":"OtherMerchant"}"""))
            .isEqualTo(SetupImportOutcome.Failed(SetupProblem.MERCHANT_MISMATCH))
        assertThat(await { env.container.settings.current() }.terminal.merchantAccount).isEmpty()
    }

    @Test
    fun `verified automatic setup imports assigned store details and survives repeated checks without Management`() {
        val outcome = import() as SetupImportOutcome.Committed
        assertThat(outcome.outcome).isInstanceOf(ImportOutcome.Imported::class.java)
        val saved = await { env.container.settings.current() }
        assertThat(saved.terminal.merchantAccount).isEqualTo("Merchant")
        assertThat(saved.terminal.storeId).isEqualTo("ST1")
        assertThat(saved.terminal.keyIdentifier).isEqualTo("store-key")
        assertThat(saved.receipt.businessName).isEqualTo("Cafe")
        assertThat(saved.receipt.addressLines).isEqualTo("1 Main St")
        assertThat(saved.receipt.phone).isEqualTo("+61212345678")
        assertThat(saved.verifiedSetup).isNotNull()
        val reads = credentialReads
        assertThat(await { env.container.terminalStatus.check() }).isInstanceOf(TerminalConnection.Connected::class.java)
        assertThat(credentialReads).isEqualTo(reads)
        await { env.container.secrets.set(Secret.ADYEN_API_KEY, "replacement-key") }
        val access = await { env.container.api.target() }.links(null)
        assertThat((access as ApiAccess.Unavailable).problem).isEqualTo(SetupProblem.SETUP_NOT_VERIFIED)
    }

    @Test
    fun `wrong shared key fails connection before any active configuration changes`() {
        key = DiscoveredKey("store-key", 2, "wrong passphrase")
        val before = await { env.container.settings.current() }
        val result = import() as SetupImportOutcome.Failed
        assertThat(result.message).contains("Crypto error")
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        assertThat(
            await {
                env.container.secrets.configured
                    .first()
            },
        ).isEmpty()
    }

    @Test
    fun `unresolved automatic fields reject rather than offering an entry wizard`() {
        key = null
        assertThat(import()).isEqualTo(SetupImportOutcome.Failed(SetupProblem.KEY_IDENTIFIER, incomplete = true))
        assertThat(
            await {
                env.container.secrets.configured
                    .first()
            },
        ).isEmpty()
    }

    @Test
    fun `receipt lookup failures do not block verified setup or overwrite existing business text`() {
        env.updateSettings { it.copy(receipt = it.receipt.copy(businessName = "Saved shop", phone = "Saved phone")) }
        stores = StoreListing.Failed("Access denied")
        val result = (import() as SetupImportOutcome.Committed).outcome as ImportOutcome.Imported
        assertThat(result.result.businessWarning).isTrue()
        assertThat(await { env.container.settings.current() }.receipt.businessName).isEqualTo("Saved shop")
        assertThat(await { env.container.settings.current() }.receipt.phone).isEqualTo("Saved phone")
    }

    @Test
    fun `terminal and store selectors are only offered when ambiguous and selection writes nothing`() {
        env.close()
        env =
            TestEnvironment(
                FakeDevice(),
                terminal = FakeTerminal(),
                terminalDetails = details,
                stores = StoreDetailsApi { stores },
                verifiedSetup = false,
            )
        available =
            listOf(
                TerminalDetails(poiId, "Merchant", "192.168.1.42"),
                TerminalDetails("AMS1-000168223606144", "Merchant", "192.168.1.7"),
            )
        stores = StoreListing.Listed(listOf(StoreDetails("ST1", "one", "One", "", ""), StoreDetails("ST2", "two", "Two", "", "")))
        val received = received("""{"destination":"network","environment":"TEST","automatic":true}""")
        val before = await { env.container.settings.current() }
        val terminals = await { env.container.setupImport.import(received, ImportMode.MERGE, code) }
        assertThat(terminals).isEqualTo(SetupImportOutcome.Terminals(available.map { it.id }))
        val choice = await { env.container.setupImport.import(received, ImportMode.MERGE, code, terminalId = poiId) }
        assertThat(choice).isInstanceOf(SetupImportOutcome.Businesses::class.java)
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        val complete = await { env.container.setupImport.import(received, ImportMode.MERGE, code, terminalId = poiId, businessId = "ST2") }
        assertThat(complete).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(await { env.container.settings.current() }.receipt.businessName).isEqualTo("Two")
    }

    @Test
    fun `completed phone registration is reused after verification fails without activating setup`() {
        env.close()
        env =
            TestEnvironment(
                FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST)),
                paymentsApp = phone,
                management = management,
                terminalDetails = details,
                verifiedSetup = false,
            )
        val received =
            received(
                """{"destination":"tapToPay","merchantAccount":"Merchant","keyIdentifier":"shared","keyVersion":1}""",
                """{"ADYEN_API_KEY":"key","PAYMENTS_APP_API_KEY":"boarding-key","TERMINAL_PASSPHRASE":"shared passphrase"}""",
            )
        val before = await { env.container.settings.current() }
        management.registrationResult = ManagementResult.Failed("Try again")
        val failed = await { env.container.setupImport.import(received, ImportMode.MERGE, code, board = true) }
        assertThat(failed).isEqualTo(SetupImportOutcome.Failed(message = "Try again"))
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        assertThat(
            await {
                env.container.secrets.configured
                    .first()
            },
        ).isEmpty()
        assertThat(management.requests).hasSize(1)
        val opened = phone.opened.size
        management.registrationResult = ManagementResult.Done()
        val resumed = await { env.container.setupImport.import(received, ImportMode.MERGE, code) }
        assertThat(resumed).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(management.requests).hasSize(1)
        assertThat(phone.opened).hasSize(opened)
    }

    @Test
    fun `Tap to Pay registration is explicit and supported verification does not claim diagnosis`() {
        env.close()
        env =
            TestEnvironment(
                FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST)),
                paymentsApp = phone,
                management = management,
                terminalDetails = details,
                verifiedSetup = false,
            )
        val received =
            received(
                """{"destination":"tapToPay","merchantAccount":"Merchant","keyIdentifier":"shared","keyVersion":1}""",
                """{"ADYEN_API_KEY":"key","PAYMENTS_APP_API_KEY":"boarding-key","TERMINAL_PASSPHRASE":"shared passphrase"}""",
            )
        val proposed = await { env.container.setupImport.import(received, ImportMode.MERGE, code) }
        assertThat(proposed).isEqualTo(SetupImportOutcome.BoardingRequired)
        assertThat(management.requests).isEmpty()
        val registered = await { env.container.setupImport.import(received, ImportMode.MERGE, code, board = true) }
        val outcome = registered as SetupImportOutcome.Committed
        assertThat((outcome.outcome as ImportOutcome.Imported).result.paymentsAppChecked).isTrue()
        assertThat(management.requests).hasSize(1)
        assertThat(await { env.container.settings.current() }.terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
    }
}
