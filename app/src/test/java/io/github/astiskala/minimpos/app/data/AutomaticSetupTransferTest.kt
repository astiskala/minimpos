package io.github.astiskala.minimpos.app.data

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakeManagement
import io.github.astiskala.minimpos.app.FakePaymentsApp
import io.github.astiskala.minimpos.app.FakeStoreDetails
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
import io.github.astiskala.minimpos.terminal.transport.SharedKeyLookup
import io.github.astiskala.minimpos.terminal.transport.SharedKeyUpdate
import io.github.astiskala.minimpos.terminal.transport.StoreDetails
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
    private val terminal = FakeTerminal()
    private val createdKeys = mutableListOf<DiscoveredKey>()
    private var activateKey = true
    private val storeDetails =
        FakeStoreDetails(
            StoreListing.Listed(listOf(StoreDetails("ST1", "cafe", "Cafe", "1 Main St", "+61212345678"))),
        )
    private var credentialReads = 0
    private val details: TerminalDetailsApi =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment): CredentialLookup {
                credentialReads++
                return credential
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
                this@AutomaticSetupTransferTest.key = key
                if (activateKey) terminal.passphrase = key.passphrase
                return SharedKeyUpdate.Ready(key, created = true)
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
            env.container.secrets.keyCreationExists
                .first(),
        ).isTrue()

    private fun unfinishedLiveSale() =
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
                    merchantReference = "LIVE",
                    context = PaymentContext("TERMINAL", poiId, "POS", "Merchant", "LIVE"),
                ),
                emptyList(),
            )
        }

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
    fun `merchant-assigned terminal imports the merchant legal name instead of unrelated stores`() {
        available = listOf(TerminalDetails(poiId, "Merchant", "192.168.1.42"))
        storeDetails.storeList = StoreListing.Listed(listOf(StoreDetails("ST1", "", "Other store", "", "")))
        storeDetails.merchantDetails = StoreListing.Listed(listOf(StoreDetails("Merchant", "", "Legal Shop", "", "")))
        val result = import() as SetupImportOutcome.Committed
        assertThat((result.outcome as ImportOutcome.Imported).result.businessWarning).isTrue()
        val receipt = await { env.container.settings.current() }.receipt
        assertThat(receipt.businessName).isEqualTo("Legal Shop")
        assertThat(receipt.addressLines).isEmpty()
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
        credential = CredentialLookup.Failed(ManagementFailure.UNREADABLE)
        assertThat(import()).isEqualTo(SetupImportOutcome.Failed(SetupProblem.MANAGEMENT_UNREADABLE))
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
        assertThat(import()).isInstanceOf(SetupImportOutcome.KeyConfirmation::class.java)
        assertThat(
            await {
                env.container.secrets.configured
                    .first()
            },
        ).isEmpty()
    }

    @Test
    fun `missing key is created only after confirmation and verified before import`() {
        key = null
        val received = received()
        val before = await { env.container.settings.current() }
        val offer =
            (
                await {
                    env.container.setupImport.import(
                        received,
                        ImportMode.MERGE,
                        code,
                    )
                } as SetupImportOutcome.KeyConfirmation
            ).offer
        assertThat(createdKeys).isEmpty()
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isFalse()
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        val result =
            await {
                env.container.setupImport.import(received, ImportMode.MERGE, code, confirmedKey = offer)
            } as SetupImportOutcome.Committed
        assertThat(result.outcome).isInstanceOf(ImportOutcome.Imported::class.java)
        val generated = createdKeys.single()
        assertThat(await { env.container.settings.current() }.terminal.keyIdentifier).isEqualTo(generated.identifier)
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo(generated.passphrase)
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isFalse()
    }

    @Test
    fun `delayed terminal activation keeps import inactive and retry never PATCHes again`() {
        key = null
        activateKey = false
        val received = received()
        val before = await { env.container.settings.current() }
        val offer =
            (
                await {
                    env.container.setupImport.import(
                        received,
                        ImportMode.MERGE,
                        code,
                    )
                } as SetupImportOutcome.KeyConfirmation
            ).offer
        val result = await { env.container.setupImport.import(received, ImportMode.MERGE, code, confirmedKey = offer) }
        assertThat(result).isEqualTo(SetupImportOutcome.Failed(SetupProblem.KEY_CONNECTION_PENDING))
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isTrue()
        terminal.passphrase = createdKeys.single().passphrase
        val resumed = await { env.container.setupImport.import(received, ImportMode.MERGE, code) }
        assertThat(resumed).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(createdKeys).hasSize(1)
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isFalse()
    }

    @Test
    fun `history confirmation precedes key creation and remains valid after generated key fields change`() {
        key = null
        activateKey = false
        unfinishedLiveSale()
        val received = received()
        val history =
            (
                await {
                    env.container.setupImport.import(
                        received,
                        ImportMode.MERGE,
                        code,
                    )
                } as SetupImportOutcome.HistoryConfirmation
            ).plan
        assertThat(history.unfinished).isTrue()
        assertThat(createdKeys).isEmpty()
        val offer =
            (
                await { env.container.setupImport.import(received, ImportMode.MERGE, code, confirmedHistorySwitch = history) }
                    as SetupImportOutcome.KeyConfirmation
            ).offer
        val pending =
            await {
                env.container.setupImport.import(
                    received,
                    ImportMode.MERGE,
                    code,
                    confirmedHistorySwitch = history,
                    confirmedKey = offer,
                )
            }
        assertThat(pending).isEqualTo(SetupImportOutcome.Failed(SetupProblem.KEY_CONNECTION_PENDING))
        assertThat(
            await {
                env.container.history
                    .items()
                    .first()
            },
        ).hasSize(1)
        terminal.passphrase = createdKeys.single().passphrase
        val done = await { env.container.setupImport.import(received, ImportMode.MERGE, code, confirmedHistorySwitch = history) }
        assertThat(done).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(
            await {
                env.container.history
                    .items()
                    .first()
            },
        ).isEmpty()
        assertThat(createdKeys).hasSize(1)
    }

    @Test
    fun `key storage failure prevents PATCH and preserves active setup`() {
        key = null
        val received = received()
        val before = await { env.container.settings.current() }
        val offer =
            (
                await {
                    env.container.setupImport.import(
                        received,
                        ImportMode.MERGE,
                        code,
                    )
                } as SetupImportOutcome.KeyConfirmation
            ).offer
        env.cipher.failEncrypt = true
        val result =
            await {
                env.container.setupImport.import(received, ImportMode.MERGE, code, confirmedKey = offer)
            } as SetupImportOutcome.Committed
        assertThat(result.outcome).isInstanceOf(ImportOutcome.StorageFailed::class.java)
        assertThat(createdKeys).isEmpty()
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
    }

    @Test
    fun `a key added by another administrator is reused instead of created`() {
        key = null
        val received = received()
        val offer =
            (
                await {
                    env.container.setupImport.import(
                        received,
                        ImportMode.MERGE,
                        code,
                    )
                } as SetupImportOutcome.KeyConfirmation
            ).offer
        key = DiscoveredKey("other-admin", 7, terminal.passphrase)
        val result = await { env.container.setupImport.import(received, ImportMode.MERGE, code, confirmedKey = offer) }
        assertThat(result).isInstanceOf(SetupImportOutcome.Committed::class.java)
        assertThat(createdKeys).isEmpty()
        assertThat(await { env.container.settings.current() }.terminal.keyIdentifier).isEqualTo("other-admin")
    }

    @Test
    fun `changed imported credential cannot reuse key creation consent`() {
        key = null
        val offer = (import() as SetupImportOutcome.KeyConfirmation).offer
        val changed = received(secrets = """{"ADYEN_API_KEY":"replacement"}""")
        val result = await { env.container.setupImport.import(changed, ImportMode.MERGE, code, confirmedKey = offer) }
        assertThat(result).isEqualTo(SetupImportOutcome.Failed(SetupProblem.SETUP_CHANGED))
        assertThat(createdKeys).isEmpty()
    }

    @Test
    fun `helper receipt choices import only selected Adyen fields alongside manual details`() {
        val result =
            import(
                """{"destination":"thisTerminal","automatic":true,"receiptBusinessName":"My shop",
                |"receiptPhone":"+123","receiptTaxId":"TAX123","receiptTitle":"Sale receipt","receiptFooter":"Thanks",
                |"importReceiptName":false,"importReceiptPhone":false}
                """.trimMargin(),
            ) as SetupImportOutcome.Committed
        assertThat(result.outcome).isInstanceOf(ImportOutcome.Imported::class.java)
        val receipt = await { env.container.settings.current() }.receipt
        assertThat(receipt.businessName).isEqualTo("My shop")
        assertThat(receipt.addressLines).isEqualTo("1 Main St")
        assertThat(receipt.phone).isEqualTo("+123")
        assertThat(receipt.taxId).isEqualTo("TAX123")
        assertThat(receipt.title).isEqualTo("Sale receipt")
        assertThat(receipt.footer).isEqualTo("Thanks")
    }

    @Test
    fun `unavailable selected Adyen field warns without importing unselected fields`() {
        storeDetails.storeList = StoreListing.Listed(listOf(StoreDetails("ST1", "", "Unselected name", "", "")))
        val result =
            import(
                """{"destination":"thisTerminal","automatic":true,"importReceiptName":false,"importReceiptPhone":false}""",
            ) as SetupImportOutcome.Committed
        assertThat((result.outcome as ImportOutcome.Imported).result.businessWarning).isTrue()
        val receipt = await { env.container.settings.current() }.receipt
        assertThat(receipt.businessName).isEmpty()
        assertThat(receipt.addressLines).isEmpty()
        assertThat(receipt.phone).isEmpty()
    }

    @Test
    fun `manual blank receipt fields do not trigger Adyen lookup or erase saved text`() {
        storeDetails.storeList = StoreListing.Failed("Lookup must not run")
        env.updateSettings { it.copy(receipt = it.receipt.copy(businessName = "Saved", addressLines = "Saved address")) }
        val result =
            import(
                """{"destination":"thisTerminal","automatic":true,"receiptBusinessName":" ",
                |"importReceiptName":false,"importReceiptAddress":false,"importReceiptPhone":false}
                """.trimMargin(),
            ) as SetupImportOutcome.Committed
        assertThat((result.outcome as ImportOutcome.Imported).result.businessWarning).isFalse()
        val receipt = await { env.container.settings.current() }.receipt
        assertThat(receipt.businessName).isEqualTo("Saved")
        assertThat(receipt.addressLines).isEqualTo("Saved address")
        assertThat(receipt.phone).isEmpty()
    }

    @Test
    fun `receipt lookup failures do not block verified setup or overwrite existing business text`() {
        env.updateSettings { it.copy(receipt = it.receipt.copy(businessName = "Saved shop", phone = "Saved phone")) }
        storeDetails.storeList = StoreListing.Failed("Access denied")
        val result = (import() as SetupImportOutcome.Committed).outcome as ImportOutcome.Imported
        assertThat(result.result.businessWarning).isTrue()
        assertThat(await { env.container.settings.current() }.receipt.businessName).isEqualTo("Saved shop")
        assertThat(await { env.container.settings.current() }.receipt.phone).isEqualTo("Saved phone")
    }

    @Test
    fun `terminal selector writes nothing until a terminal is chosen`() {
        env.close()
        env =
            TestEnvironment(
                FakeDevice(),
                terminal = FakeTerminal(),
                terminalDetails = details,
                stores = storeDetails,
                verifiedSetup = false,
            )
        available =
            listOf(
                TerminalDetails(poiId, "Merchant", "192.168.1.42"),
                TerminalDetails("AMS1-000168223606144", "Merchant", "192.168.1.7"),
            )
        val received = received("""{"destination":"network","environment":"TEST","automatic":true}""")
        val before = await { env.container.settings.current() }
        val terminals = await { env.container.setupImport.import(received, ImportMode.MERGE, code) }
        assertThat(terminals).isEqualTo(SetupImportOutcome.Terminals(available.map { it.id }))
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        val complete = await { env.container.setupImport.import(received, ImportMode.MERGE, code, terminalId = poiId) }
        assertThat(complete).isInstanceOf(SetupImportOutcome.Committed::class.java)
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
