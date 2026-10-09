package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.security.SecretStoreException
import app.minimpos.app.data.security.SharedKeyGenerator
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.MalformedPart
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.SharedKeyUpdate
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.SecureRandom

@RunWith(RobolectricTestRunner::class)
class SharedKeySetupTest {
    @get:Rule
    val env = TestEnvironment(verifiedSetup = false)
    private val requested = mutableListOf<DiscoveredKey>()
    private var found: SharedKeyLookup = SharedKeyLookup.Missing
    private var updateFailure: SharedKeyUpdate.Failed? = null
    private var accepted = true
    private var busy = false
    private var apiCheck: ApiCheck = ApiCheck.Works
    private var beforeRead: suspend () -> Unit = {}
    private var reads = 0
    private val details =
        object : TerminalDetailsApi {
            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing = TerminalListing.Listed(listOf(TerminalDetails("ID", "Merchant", "192.168.1.42")), environment)

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup {
                reads++
                beforeRead()
                return found
            }

            override suspend fun createSharedKey(
                id: String,
                environment: TerminalEnvironment,
                key: DiscoveredKey,
            ): SharedKeyUpdate {
                assertThat(
                    await {
                        env.container.secrets.readKeyCreation()
                    },
                ).isNotNull()
                requested += key
                if (accepted) found = SharedKeyLookup.Found(key)
                return updateFailure ?: SharedKeyUpdate.Ready(key, created = true)
            }
        }
    private val source by lazy {
        TerminalSetupSource(
            env.container.settings,
            env.container.secrets,
            FakeDevice(),
        )
    }

    private fun owner() = SharedKeySetup(source, { details }, { apiCheck }, { busy })

    private fun unlocked() = await { source.unlocked(forValidation = true) }

    private fun offer(owner: SharedKeySetup): SharedKeyOffer =
        (await { owner.resolve(unlocked()) } as SharedKeySetupOutcome.Confirmation).offer

    @Before
    fun ready() {
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.TERMINAL,
                        environment = TerminalEnvironment.TEST,
                        host = "192.168.1.42",
                        poiIdOverride = "ID",
                        merchantAccount = "Merchant",
                    ),
            )
        }
        await { env.container.secrets.set(Secret.ADYEN_API_KEY, "synthetic-api-key") }
    }

    @Test
    fun `offers are read only and existing effective keys never create recovery or PATCH`() {
        val owner = owner()
        val offered = offer(owner)
        assertThat(offered.poiId).isEqualTo("ID")
        assertThat(offered.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(offered.resume).isFalse()
        assertThat(offered.toString()).doesNotContain("synthetic-api-key")
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isFalse()
        assertThat(requested).isEmpty()
        found = SharedKeyLookup.Found(DiscoveredKey("inherited", 2, "InheritedSecret"))
        val result = await { owner.resolve(unlocked(), offered) } as SharedKeySetupOutcome.Ready
        assertThat(result.key.identifier).isEqualTo("inherited")
        assertThat(result.pending).isFalse()
        assertThat(requested).isEmpty()
    }

    @Test
    fun `confirmed creation persists encrypted generated values before mutation without changing active setup`() {
        val owner = owner()
        val before = await { env.container.settings.current() }
        val result = await { owner.resolve(unlocked(), offer(owner)) } as SharedKeySetupOutcome.Ready
        assertThat(result.pending).isTrue()
        assertThat(requested).hasSize(1)
        assertThat(result.key.version).isEqualTo(1)
        assertThat(result.key.identifier).startsWith("minimpos-")
        assertThat(result.key.passphrase).hasLength(32)
        assertThat(env.dir.resolve("secrets.json").readText()).doesNotContain(result.key.passphrase)
        assertThat(await { env.container.settings.current() }).isEqualTo(before)
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
    }

    @Test
    fun `unknown delivery survives owner reconstruction and explicitly retries exactly the same key`() {
        val first = owner()
        accepted = false
        updateFailure = SharedKeyUpdate.Failed(Fault.AdyenUnavailable(503), uncertain = true)
        assertThat(
            await { first.resolve(unlocked(), offer(first)) },
        ).isEqualTo(SharedKeySetupOutcome.Failed(SetupProblem.KEY_CREATION_UNCONFIRMED))
        val original = requested.single()
        val restarted = owner()
        val resume = await { restarted.pendingOffer(unlocked()) }
        assertThat(resume?.resume).isTrue()
        assertThat(requested).hasSize(1)
        updateFailure = null
        accepted = true
        val retried = await { restarted.resolve(unlocked(), resume) } as SharedKeySetupOutcome.Ready
        assertThat(requested).hasSize(2)
        assertThat(retried.key.identifier).isEqualTo(original.identifier)
        assertThat(retried.key.passphrase).isEqualTo(original.passphrase)
        assertThat(retried.key.version).isEqualTo(original.version)
        await { restarted.complete(unlocked()) }
        assertThat(
            await {
                env.container.secrets.keyCreationExists
                    .first()
            },
        ).isFalse()
    }

    @Test
    fun `accepted but unanswered creation resolves by reading without a second PATCH`() {
        val owner = owner()
        updateFailure = SharedKeyUpdate.Failed(Fault.AdyenUnavailable(503), uncertain = true)
        await { owner.resolve(unlocked(), offer(owner)) }
        val restarted = owner()
        val result = await { restarted.resolve(unlocked(), restarted.pendingOffer(unlocked())) } as SharedKeySetupOutcome.Ready
        assertThat(result.pending).isTrue()
        assertThat(requested).hasSize(1)
    }

    @Test
    fun `busy incomplete and denied setup never offers creation`() {
        val owner = owner()
        busy = true
        assertThat(await { owner.resolve(unlocked()) }).isEqualTo(SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED))
        assertThat(reads).isEqualTo(0)
        busy = false
        apiCheck = ApiCheck.Failed(Failure.Remote(Fault.Permission(ApiKey.ADYEN)))
        assertThat(
            await { owner.resolve(unlocked()) },
        ).isEqualTo(SharedKeySetupOutcome.Failed(Failure.Remote(Fault.Permission(ApiKey.ADYEN))))
        apiCheck = ApiCheck.Failed(Failure.Remote(Fault.AdyenUnavailable(503)))
        assertThat(
            await {
                owner.resolve(unlocked())
            },
        ).isEqualTo(SharedKeySetupOutcome.Failed(failure = Failure.Remote(Fault.AdyenUnavailable(503))))
        apiCheck = ApiCheck.Works
        env.updateSettings { it.copy(terminal = it.terminal.copy(host = "")) }
        assertThat(await { owner.resolve(unlocked()) }).isEqualTo(SharedKeySetupOutcome.Failed(SetupProblem.HOST))
        assertThat(requested).isEmpty()
    }

    @Test
    fun `failed reads and unreadable recovery never become key absence`() {
        val owner = owner()
        listOf(
            Fault.Permission(ApiKey.ADYEN),
            Fault.UnreadableReply(),
            Fault.Malformed(MalformedPart.SETTINGS),
            Fault.Malformed(MalformedPart.KEY),
            Fault.Malformed(MalformedPart.KEY_VERSION),
        ).forEach { reason ->
            found = SharedKeyLookup.Failed(reason)
            assertThat(await { owner.resolve(unlocked()) }).isEqualTo(SharedKeySetupOutcome.Failed(Failure.Remote(reason)))
        }
        found = SharedKeyLookup.Missing
        await {
            env.container.secrets.writeKeyCreation("not json")
        }
        assertThat(await { owner.resolve(unlocked()) }).isEqualTo(SharedKeySetupOutcome.Failed(SetupProblem.KEY_RECOVERY_UNREADABLE))
        assertThat(requested).isEmpty()
    }

    @Test
    fun `changed credential connection or in flight setup invalidates confirmation`() {
        val owner = owner()
        val original = offer(owner)
        env.updateSettings { it.copy(terminal = it.terminal.copy(host = "192.168.1.43")) }
        assertThat(await { owner.resolve(unlocked(), original) }).isEqualTo(SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED))
        val next = offer(owner)
        await { env.container.secrets.set(Secret.ADYEN_API_KEY, "replacement") }
        assertThat(await { owner.resolve(unlocked(), next) }).isEqualTo(SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED))
        val current = offer(owner)
        var unchanged = true
        beforeRead = { unchanged = false }
        assertThat(
            await { owner.resolve(unlocked(), current) { unchanged } },
        ).isEqualTo(SharedKeySetupOutcome.Failed(SetupProblem.SETUP_CHANGED))
        assertThat(requested).isEmpty()
    }

    @Test
    fun `storage failure prevents all remote mutations`() {
        val owner = owner()
        val offered = offer(owner)
        env.cipher.failEncrypt = true
        assertThrows(SecretStoreException::class.java) { await { owner.resolve(unlocked(), offered) } }
        assertThat(requested).isEmpty()
    }

    @Test
    fun `manual key values require replacement disclosure but are never uploaded`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "manual", keyVersion = 5)) }
        await { env.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "ManualSecret") }
        val owner = owner()
        val offered = offer(owner)
        assertThat(offered.replacesLocalKey).isTrue()
        val created = await { owner.resolve(unlocked(), offered) } as SharedKeySetupOutcome.Ready
        assertThat(created.key.identifier).isNotEqualTo("manual")
        assertThat(created.key.version).isEqualTo(1)
        assertThat(created.key.passphrase).isNotEqualTo("ManualSecret")
        assertThat(await { env.container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("ManualSecret")
    }

    @Test
    fun `generated passphrase uses only Adyen allowed special characters`() {
        val random =
            object : SecureRandom() {
                override fun nextInt(bound: Int): Int = minOf(9, bound - 1)
            }
        val key = SharedKeyGenerator(random).generate()
        assertThat(key.passphrase).doesNotContain("-")
        assertThat(key.passphrase.matches(Regex("[A-Za-z0-9!@#$%^&*_+=?]{32}"))).isTrue()
    }

    @Test
    fun `generated passphrases contain each required character class and independent identifiers`() {
        val generator = SharedKeyGenerator()
        val keys = List(20) { generator.generate() }
        assertThat(keys.map { it.identifier }.distinct()).hasSize(20)
        keys.forEach { key ->
            assertThat(key.passphrase.any { it in 'A'..'Z' }).isTrue()
            assertThat(key.passphrase.any { it in 'a'..'z' }).isTrue()
            assertThat(key.passphrase.any { it in '0'..'9' }).isTrue()
            assertThat(key.passphrase.any { it in "!@#$%^&*_-+=?" }).isTrue()
            assertThat(key.toString()).doesNotContain(key.passphrase)
        }
    }
}
