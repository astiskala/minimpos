package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.terminal.transport.DiscoveredKey
import app.minimpos.terminal.transport.ManagementFailure
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

@RunWith(RobolectricTestRunner::class)
class SetupDiscoveryTest {
    private var listing: TerminalListing =
        TerminalListing.Listed(
            listOf(TerminalDetails("S1F2-123456789", "Merchant", "192.168.1.42")),
            TerminalEnvironment.TEST,
        )
    private var sharedKey: DiscoveredKey? = DiscoveredKey("key", 2, " secret passphrase ")
    private var keyUnavailable: SharedKeyLookup = SharedKeyLookup.Failed(ManagementFailure.PERMISSION)
    private var reads = 0
    private val environments = mutableListOf<TerminalEnvironment>()
    private val queries = mutableListOf<String?>()
    private val api =
        object : TerminalDetailsApi {
            override suspend fun credential(environment: TerminalEnvironment) = app.minimpos.terminal.transport.CredentialLookup.Allowed

            override suspend fun terminals(
                environment: TerminalEnvironment,
                id: String?,
            ): TerminalListing =
                listing.also {
                    reads++
                    environments += environment
                    queries += id
                }

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): SharedKeyLookup = sharedKey?.let(SharedKeyLookup::Found) ?: keyUnavailable
        }
    private var env = TestEnvironment(terminalDetails = api)
    private val container get() = env.container

    private fun ready(mode: TerminalMode = TerminalMode.TERMINAL) {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = mode, environment = TerminalEnvironment.TEST)) }
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `discovery never guesses an unknown environment and uses the merchant's choice`() {
        ready()
        env.updateSettings { it.copy(terminal = it.terminal.copy(environment = null)) }
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Unavailable)
        assertThat(reads).isEqualTo(0)
        env.updateSettings { it.copy(terminal = it.terminal.copy(environment = TerminalEnvironment.LIVE)) }
        listing = (listing as TerminalListing.Listed).copy(environment = TerminalEnvironment.LIVE)
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Found(listOf("S1F2-123456789")))
        assertThat(environments).containsExactly(TerminalEnvironment.LIVE)
    }

    @Test
    fun `an on-device terminal reads its certificate before discovery without a shared key`() {
        env.close()
        env = TestEnvironment(FakeDevice(detectedPoiId = "S1F2-123456789"), terminalDetails = api)
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Found(listOf("S1F2-123456789")))
        assertThat(await { container.settings.current() }.terminal.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
        assertThat(environments).containsExactly(TerminalEnvironment.TEST)
        assertThat(queries).containsExactly("S1F2-123456789")
    }

    @Test
    fun `saving an API key enables discovery without a merchant account or working terminal connection`() {
        ready()
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Found(listOf("S1F2-123456789")))
        assertThat(container.settingsState.value.terminal.merchantAccount).isEmpty()
        assertThat(await { container.setupDiscovery.choose("S1F2-123456789") }).isEqualTo(SetupDiscoveryChoice.Complete)
        val saved = await { container.settings.current() }.terminal
        assertThat(saved.merchantAccount).isEqualTo("Merchant")
        assertThat(saved.host).isEqualTo("192.168.1.42")
        assertThat(saved.poiIdOverride).isEqualTo("S1F2-123456789")
        assertThat(saved.keyIdentifier).isEqualTo("key")
        assertThat(saved.keyVersion).isEqualTo(2)
        assertThat(saved.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo(" secret passphrase ")
    }

    @Test
    fun `denied settings preserve manual keys and still import assignment and address`() {
        ready()
        sharedKey = null
        env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "manual", keyVersion = 3)) }
        await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "manual secret") }
        await { container.setupDiscovery.find() }
        assertThat(await { container.setupDiscovery.choose("S1F2-123456789") }).isEqualTo(
            SetupDiscoveryChoice.Failed(SetupProblem.MANAGEMENT_PERMISSION),
        )
        val saved = await { container.settings.current() }.terminal
        assertThat(saved.merchantAccount).isEqualTo("Merchant")
        assertThat(saved.keyIdentifier).isEqualTo("manual")
        assertThat(saved.keyVersion).isEqualTo(3)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("manual secret")
    }

    @Test
    fun `missing credentials and denied terminal reads leave settings untouched`() {
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Unavailable)
        assertThat(reads).isEqualTo(0)
        ready()
        listing = TerminalListing.Failed("Forbidden")
        val before = await { container.settings.current() }
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Failed(SetupProblem.MANAGEMENT_UNAVAILABLE))
        assertThat(await { container.setupDiscovery.choose("S1F2-123456789") }).isEqualTo(SetupDiscoveryChoice.Ignored)
        assertThat(await { container.settings.current() }).isEqualTo(before)
    }

    @Test
    fun `changed destination or API key cannot apply a stale selection`() {
        ready()
        await { container.setupDiscovery.find() }
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD)) }
        assertThat(await { container.setupDiscovery.choose("S1F2-123456789") }).isEqualTo(SetupDiscoveryChoice.Ignored)
        ready()
        await { container.setupDiscovery.find() }
        await { container.secrets.set(Secret.ADYEN_API_KEY, "replacement") }
        assertThat(await { container.setupDiscovery.choose("S1F2-123456789") }).isEqualTo(SetupDiscoveryChoice.Ignored)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
    }

    @Test
    fun `the local device is matched automatically and never imports a remote address`() {
        env.close()
        env = TestEnvironment(FakeDevice(detectedPoiId = "S1F2-123456789"), terminalDetails = api)
        ready()
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Found(listOf("S1F2-123456789")))
        await { container.setupDiscovery.choose("S1F2-123456789") }
        assertThat(await { container.settings.current() }.terminal.host).isEmpty()
        assertThat(await { container.settings.current() }.terminal.poiIdOverride).isEmpty()
    }

    @Test
    fun `failed secret storage still permits manual completion of the imported connection`() {
        ready()
        await { container.setupDiscovery.find() }
        env.cipher.failEncrypt = true
        assertThat(await { container.setupDiscovery.choose("S1F2-123456789") }).isEqualTo(SetupDiscoveryChoice.Manual(keyMissing = false))
        assertThat(await { container.settings.current() }.terminal.merchantAccount).isEqualTo("Merchant")
        assertThat(await { container.settings.current() }.terminal.keyIdentifier).isEmpty()
    }

    @Test
    fun `dismissed and unknown selections cannot import fields and Tap to Pay never lists physical terminals`() {
        ready()
        await { container.setupDiscovery.find() }
        assertThat(await { container.setupDiscovery.choose(null) }).isEqualTo(SetupDiscoveryChoice.Ignored)
        assertThat(await { container.setupDiscovery.choose("S1F2-123456789") }).isEqualTo(SetupDiscoveryChoice.Ignored)
        await { container.setupDiscovery.find() }
        assertThat(await { container.setupDiscovery.choose("unknown") }).isEqualTo(SetupDiscoveryChoice.Ignored)
        ready(TerminalMode.PAYMENTS_APP)
        val before = reads
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Unavailable)
        assertThat(reads).isEqualTo(before)
    }

    @Test
    fun `failed search reports its outcome without reading mutable discovery fields`() {
        ready()
        listing = TerminalListing.Failed("Forbidden")
        val before = await { container.settings.current() }
        val failed = await { container.setupDiscovery.find() }
        assertThat(failed).isEqualTo(SetupDiscoverySearch.Failed(SetupProblem.MANAGEMENT_UNAVAILABLE))
        listing = TerminalListing.Listed(emptyList(), TerminalEnvironment.TEST)
        assertThat(await { container.setupDiscovery.find() }).isEqualTo(SetupDiscoverySearch.Failed(SetupProblem.TERMINAL_ACCESS))
        assertThat(failed).isEqualTo(SetupDiscoverySearch.Failed(SetupProblem.MANAGEMENT_UNAVAILABLE))
        assertThat(await { container.settings.current() }).isEqualTo(before)
    }

    @Test
    fun `missing key belongs only to the applied selection outcome`() {
        ready()
        sharedKey = null
        keyUnavailable = SharedKeyLookup.Missing
        await { container.setupDiscovery.find() }
        val applied = await { container.setupDiscovery.choose("S1F2-123456789") }
        assertThat(applied).isEqualTo(SetupDiscoveryChoice.Manual(keyMissing = true))
        assertThat(applied.manualDetails).isTrue()
        await { container.setupDiscovery.find() }
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD)) }
        val stale = await { container.setupDiscovery.choose("S1F2-123456789") }
        assertThat(stale).isEqualTo(SetupDiscoveryChoice.Ignored)
        assertThat(stale.keyMissing).isFalse()
        assertThat(applied.keyMissing).isTrue()
    }

    @Test
    fun `account mismatch is reported without importing fields`() {
        ready()
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Other")) }
        val before = await { container.settings.current() }
        await { container.setupDiscovery.find() }
        val result = await { container.setupDiscovery.choose("S1F2-123456789") }
        assertThat(result).isEqualTo(SetupDiscoveryChoice.Failed(SetupProblem.MERCHANT_MISMATCH))
        assertThat(result.manualDetails).isFalse()
        assertThat(await { container.settings.current() }).isEqualTo(before)
    }

    @Test
    fun `cloud discovery never imports local encryption keys or network addresses`() {
        ready(TerminalMode.CLOUD)
        await { container.setupDiscovery.find() }
        assertThat(await { container.setupDiscovery.choose("S1F2-123456789") }).isEqualTo(SetupDiscoveryChoice.Complete)
        assertThat(await { container.settings.current() }.terminal.host).isEmpty()
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
    }
}
