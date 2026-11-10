package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.data.settings.TerminalSettings
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
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Setup discovery's questions, asked once for the saved setup and an import candidate alike. */
@RunWith(RobolectricTestRunner::class)
class SetupConversationTest {
    private val missing = listOf(AdyenRole.TERMINAL_SETTINGS_ADVANCED, AdyenRole.CLOUD_DEVICE)
    private val lacking = Fault.Permission(ApiKey.ADYEN, AdyenRole.names(missing))
    private var credential: CredentialLookup = CredentialLookup.Allowed(CredentialRoles(AdyenRole.required(), emptyList()))
    private val listed = listOf(TerminalDetails("S1F2-1", "Merchant", "192.0.2.10"), TerminalDetails("S1F2-2", "Other", "192.0.2.20"))
    private var merchants = emptyList<MerchantAccount>()
    private var key: SharedKeyLookup = SharedKeyLookup.Found(DiscoveredKey("listed", 2, "passphrase"))
    private val added = mutableListOf<AdyenRole>()
    private val api =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) = credential

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ) = TerminalListing.Listed(listed.filter { id == null || it.id == id }, environment)

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ) = key

            override suspend fun accountKey(
                merchantAccount: String,
                storeId: String?,
                environment: TerminalEnvironment,
            ) = key

            override suspend fun merchantAccounts(environment: TerminalEnvironment) = MerchantAccountListing.Listed(merchants)

            override suspend fun addRoles(
                environment: TerminalEnvironment,
                roles: List<AdyenRole>,
            ): CredentialRolesUpdate {
                added += roles
                return CredentialRolesUpdate.Added
            }
        }
    private val env = TestEnvironment(terminalDetails = api)
    private val conversation =
        SetupConversation({ api }, { api }, SharedKeySetup(env.container.terminalSetup, { api }, { ApiCheck.Works }, { false }))
    private val entered =
        TerminalSettings(
            mode = TerminalMode.TERMINAL,
            environment = TerminalEnvironment.TEST,
            merchantAccount = "Merchant",
            host = "192.0.2.99",
        )

    private fun setup(
        terminal: TerminalSettings = entered,
        device: DeviceInfo = FakeDevice(),
    ): TerminalSetup =
        TerminalSetup.resolve(
            AppSettings(terminal = terminal),
            setOf(Secret.ADYEN_API_KEY, Secret.TERMINAL_PASSPHRASE),
            device,
            verified = true,
            pendingImport = false,
        )

    @After
    fun tearDown() = env.close()

    @Test
    fun `missing roles are asked about with their failure and added only once that exact offer is accepted`() {
        credential = CredentialLookup.Failed(lacking, CredentialRoles(AdyenRole.required(), missing))
        await {
            val asked = conversation.credential("key", TerminalEnvironment.TEST, accepted = null) as SetupReply.Asks
            assertThat(asked.question).isEqualTo(SetupQuestion.RolesMissing(KeyRolesOffer(missing)))
            assertThat(asked.failure).isEqualTo(Failure.Remote(lacking))
            val other = KeyRolesOffer(listOf(AdyenRole.CLOUD_DEVICE))
            assertThat(conversation.credential("key", TerminalEnvironment.TEST, other)).isInstanceOf(SetupReply.Asks::class.java)
            assertThat(added).isEmpty()
            assertThat(conversation.credential("key", TerminalEnvironment.TEST, KeyRolesOffer(missing))).isEqualTo(SetupReply.Next(Unit))
        }
        assertThat(added).containsExactlyElementsIn(missing)
    }

    @Test
    fun `a key that cannot edit credentials fails instead of asking`() {
        val roles = listOf(AdyenRole.API_CREDENTIALS, AdyenRole.CLOUD_DEVICE)
        val fault = Fault.Permission(ApiKey.ADYEN, AdyenRole.names(roles))
        credential = CredentialLookup.Failed(fault, CredentialRoles(AdyenRole.required(), roles))
        val step = await { conversation.credential("key", TerminalEnvironment.TEST, accepted = null) }
        assertThat(step).isEqualTo(SetupReply.Failed(Failure.Remote(fault)))
        assertThat(added).isEmpty()
    }

    @Test
    fun `Settings offers and adds missing roles through the same question`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.TERMINAL, environment = TerminalEnvironment.TEST)) }
        await { env.container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        credential = CredentialLookup.Failed(lacking, CredentialRoles(AdyenRole.required(), missing))
        val offer = checkNotNull(await { env.container.setupDiscovery.keyRolesOffer() })
        assertThat(offer.roles).isEqualTo(missing)
        assertThat(await { env.container.setupDiscovery.addKeyRoles(offer) }).isTrue()
        assertThat(added).containsExactlyElementsIn(missing)
    }

    @Test
    fun `another account is asked about until the terminal's account is accepted`() {
        val question = SetupQuestion.AccountDiffers("S1F2-2", AccountMismatch("Merchant", "Other"))
        assertThat(conversation.choose(listed, "S1F2-2", "Merchant", accepted = null))
            .isEqualTo(SetupReply.Asks(question, Failure.NotSetUp(SetupProblem.MERCHANT_MISMATCH)))
        assertThat(conversation.choose(listed, "S1F2-2", "Merchant", accepted = "Other")).isEqualTo(SetupReply.Next(listed[1]))
        assertThat(conversation.choose(listed, "S1F2-9", "Merchant", accepted = null))
            .isEqualTo(SetupReply.Failed(Failure.NotSetUp(SetupProblem.TERMINAL_ACCESS)))
        await {
            val asked = conversation.discover(api, TerminalEnvironment.TEST, setup(), "Merchant", SetupAnswers(terminalId = "S1F2-2"))
            assertThat((asked as SetupReply.Asks).question).isEqualTo(question)
            val accepted = SetupAnswers(terminalId = "S1F2-2").accept(question)
            assertThat(
                conversation.discover(api, TerminalEnvironment.TEST, setup(), "Merchant", accepted),
            ).isEqualTo(SetupReply.Next(listed[1]))
        }
    }

    @Test
    fun `discovery asks which terminal when several are eligible and takes only one it offered`() {
        await {
            val asked = conversation.discover(api, TerminalEnvironment.TEST, setup(), "", SetupAnswers()) as SetupReply.Asks
            val question = asked.question as SetupQuestion.Terminals
            assertThat(question.terminals.map { it.id }).containsExactly("S1F2-1", "S1F2-2")
            assertThat(SetupAnswers().choose(question, "S1F2-9")).isNull()
            val chosen = checkNotNull(SetupAnswers().choose(question, "S1F2-1"))
            assertThat(conversation.discover(api, TerminalEnvironment.TEST, setup(), "", chosen)).isEqualTo(SetupReply.Next(listed[0]))
        }
    }

    @Test
    fun `an assignment keeps what a blank listing lacks and takes a listed address only when asked`() {
        val network = setup()
        val kept = conversation.assign(entered, network, TerminalDetails("S1F2-3", "", "", "ST1"), listedAddress = true)
        assertThat(kept.merchantAccount).isEqualTo("Merchant")
        assertThat(kept.host).isEqualTo("192.0.2.99")
        assertThat(kept.storeId).isEqualTo("ST1")
        assertThat(kept.poiIdOverride).isEqualTo("S1F2-3")
        assertThat(conversation.assign(entered, network, listed[0], listedAddress = true).host).isEqualTo("192.0.2.10")
        assertThat(conversation.assign(entered, network, listed[0], listedAddress = false).host).isEqualTo("192.0.2.99")
        val keyed = conversation.assign(entered, network, listed[0], listedAddress = true, DiscoveredKey("listed", 2, "passphrase"))
        assertThat(keyed.keyIdentifier to keyed.keyVersion).isEqualTo("listed" to 2)
        val local = conversation.assign(entered, setup(device = FakeDevice(detectedPoiId = "S1F2-1")), listed[0], listedAddress = true)
        assertThat(local.host).isEqualTo("192.0.2.99")
        assertThat(local.poiIdOverride).isEmpty()
    }

    @Test
    fun `only a terminal reached with a shared key looks one up`() {
        await {
            assertThat(conversation.terminalKey(api, TerminalEnvironment.TEST, setup(), listed[0])).isSameInstanceAs(key)
            val cloud = setup(entered.copy(mode = TerminalMode.CLOUD))
            assertThat(conversation.terminalKey(api, TerminalEnvironment.TEST, cloud, listed[0])).isNull()
        }
    }

    @Test
    fun `Tap to Pay takes the entered or only merchant account and asks among several`() {
        await {
            assertThat(conversation.phoneAccount(api, TerminalEnvironment.TEST, "Entered")).isEqualTo(SetupReply.Next("Entered"))
            merchants = listOf(MerchantAccount("Only", "Only one"))
            assertThat(conversation.phoneAccount(api, TerminalEnvironment.TEST, null)).isEqualTo(SetupReply.Next("Only"))
            merchants = listOf(MerchantAccount("A", "First"), MerchantAccount("B", "Second"))
            val asked = conversation.phoneAccount(api, TerminalEnvironment.TEST, null) as SetupReply.Asks
            val choices = SetupQuestion.Merchants(listOf(SetupMerchantChoice("A", "First"), SetupMerchantChoice("B", "Second")))
            assertThat(asked.question).isEqualTo(choices)
            assertThat(SetupAnswers().choose(choices, "B")?.merchantAccount).isEqualTo("B")
            merchants = emptyList()
            assertThat(conversation.phoneAccount(api, TerminalEnvironment.TEST, null))
                .isEqualTo(SetupReply.Failed(Failure.NotSetUp(SetupProblem.MERCHANT_ACCOUNT)))
        }
    }

    @Test
    fun `a Tap to Pay key Adyen does not hold stays unread`() {
        await {
            val read = conversation.phoneKey(api, TerminalEnvironment.TEST, "Merchant", storeId = null) as SetupReply.Next
            assertThat(read.value.identifier).isEqualTo("listed")
            key = SharedKeyLookup.Missing
            assertThat(conversation.phoneKey(api, TerminalEnvironment.TEST, "Merchant", storeId = null))
                .isEqualTo(SetupReply.Failed(Failure.NotSetUp(SetupProblem.KEY_IDENTIFIER), keyUnread = true))
            key = SharedKeyLookup.Failed(Fault.AdyenUnavailable(503))
            val failed = conversation.phoneKey(api, TerminalEnvironment.TEST, "Merchant", storeId = "ST1") as SetupReply.Failed
            assertThat(failed.keyUnread).isTrue()
        }
    }

    @Test
    fun `answers accept yes-or-no questions and never a choice`() {
        val roles = SetupQuestion.RolesMissing(KeyRolesOffer(missing))
        val account = SetupQuestion.AccountDiffers("S1F2-2", AccountMismatch("Merchant", "Other"))
        val answers = SetupAnswers().accept(roles).accept(account)
        assertThat(answers).isEqualTo(SetupAnswers(acceptedAccount = "Other", acceptedRoles = KeyRolesOffer(missing)))
        assertThat(answers.accept(SetupQuestion.Terminals(emptyList()))).isEqualTo(answers)
        assertThat(answers.choose(roles, "S1F2-2")).isNull()
        val offer = SharedKeyOffer("S1F2-1", TerminalEnvironment.TEST, resume = false, replacesLocalKey = false, entered, "identity")
        assertThat(SetupAnswers().accept(SetupQuestion.KeyConfirmation(offer)).acceptedKey).isSameInstanceAs(offer)
    }
}
