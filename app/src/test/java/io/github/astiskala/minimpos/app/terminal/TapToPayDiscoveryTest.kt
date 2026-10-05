package io.github.astiskala.minimpos.app.terminal

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakePaymentsApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.terminal.transport.DiscoveredKey
import io.github.astiskala.minimpos.terminal.transport.TerminalDetailsApi
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import io.github.astiskala.minimpos.terminal.transport.TerminalListing
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class TapToPayDiscoveryTest {
    private val device = FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST))
    private val paymentsApp = FakePaymentsApp()
    private val requests = mutableListOf<Triple<String, String?, TerminalEnvironment>>()
    private var key: DiscoveredKey? = DiscoveredKey("discovered-key", 2, " secret passphrase ")
    private var beforeRead: suspend () -> Unit = {}
    private val api =
        object : TerminalDetailsApi {
            override suspend fun terminals(environment: TerminalEnvironment): TerminalListing = error("No physical-terminal discovery")

            override suspend fun sharedKey(
                id: String,
                environment: TerminalEnvironment,
            ): DiscoveredKey? = error("No physical-terminal settings")

            override suspend fun accountSharedKey(
                merchantAccount: String,
                storeId: String?,
                environment: TerminalEnvironment,
            ): DiscoveredKey? {
                requests += Triple(merchantAccount, storeId, environment)
                beforeRead()
                return key
            }
        }
    private val env = TestEnvironment(device, paymentsApp = paymentsApp, terminalDetails = api)
    private val container get() = env.container

    private fun ready(store: String = "") {
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP, merchantAccount = " Merchant ", storeId = store))
        }
        await {
            container.secrets.set(Secret.ADYEN_API_KEY, "checkout-key")
            container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "boarding-key")
        }
    }

    @After
    fun tearDown() = env.close()

    @Test
    fun `registration discovers the merchant key and encrypted storage preserves passphrase whitespace`() {
        ready()
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID, true))
        assertThat(requests).containsExactly(Triple("Merchant", null, TerminalEnvironment.TEST))
        val terminal = await { container.settings.current() }.terminal
        assertThat(terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
        assertThat(terminal.keyIdentifier).isEqualTo("discovered-key")
        assertThat(terminal.keyVersion).isEqualTo(2)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo(" secret passphrase ")
    }

    @Test
    fun `store lookup uses the boarded store ID and installed LIVE environment`() {
        ready(" ST1 ")
        device.paymentsApps = setOf(TerminalEnvironment.LIVE)
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID, true))
        assertThat(requests).containsExactly(Triple("Merchant", "ST1", TerminalEnvironment.LIVE))
    }

    @Test
    fun `denied lookup preserves manual fields and can be retried without reboarding`() {
        ready()
        key = null
        env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "manual", keyVersion = 3)) }
        await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "manual secret") }
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        assertThat(await { container.settings.current() }.terminal.keyIdentifier).isEqualTo("manual")
        assertThat(await { container.settings.current() }.terminal.keyVersion).isEqualTo(3)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isEqualTo("manual secret")
        val opened = paymentsApp.opened.size
        key = DiscoveredKey("new-key", 4, "new secret")
        assertThat(await { container.setupDiscovery.findPaymentsAppKey() }).isTrue()
        assertThat(paymentsApp.opened).hasSize(opened)
        assertThat(await { container.settings.current() }.terminal.keyIdentifier).isEqualTo("new-key")
    }

    @Test
    fun `lookup I-O failures are nonfatal after registration`() {
        ready()
        beforeRead = { throw IOException("Unavailable lookup") }
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        assertThat(await { container.settings.current() }.terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
        assertThat(await { container.setupDiscovery.findPaymentsAppKey() }).isFalse()
    }

    @Test
    fun `missing lookup credentials or failed secret storage never undo registration`() {
        ready()
        await { container.secrets.set(Secret.ADYEN_API_KEY, null) }
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        assertThat(requests).isEmpty()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "checkout-key") }
        env.cipher.failEncrypt = true
        assertThat(await { container.setupDiscovery.findPaymentsAppKey() }).isFalse()
        assertThat(await { container.settings.current() }.terminal.keyIdentifier).isEmpty()
        assertThat(await { container.settings.current() }.terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
        assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNull()
    }

    @Test
    fun `unboarded missing ambiguous and non-Payments destinations never request shared keys`() {
        ready()
        assertThat(await { container.setupDiscovery.findPaymentsAppKey() }).isNull()
        env.updateSettings { it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = FakePaymentsApp.INSTALLATION_ID)) }
        listOf(emptySet(), TerminalEnvironment.entries.toSet()).forEach {
            device.paymentsApps = it
            assertThat(await { container.setupDiscovery.findPaymentsAppKey() }).isNull()
        }
        device.paymentsApps = setOf(TerminalEnvironment.TEST)
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "")) }
        assertThat(await { container.setupDiscovery.findPaymentsAppKey() }).isNull()
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD, merchantAccount = "Merchant")) }
        assertThat(await { container.setupDiscovery.findPaymentsAppKey() }).isNull()
        assertThat(requests).isEmpty()
    }

    @Test
    fun `changed setup credentials installation or manual passphrase reject a stale key`() {
        val changes: List<suspend () -> Unit> =
            listOf(
                { container.settings.update { it.copy(terminal = it.terminal.copy(merchantAccount = "Other")) } },
                { container.settings.update { it.copy(terminal = it.terminal.copy(storeId = "OtherStore")) } },
                { container.settings.update { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD)) } },
                { container.settings.update { it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = "OtherInstallation")) } },
                { container.secrets.set(Secret.ADYEN_API_KEY, "other-key") },
                { device.paymentsApps = setOf(TerminalEnvironment.LIVE) },
                { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "edited secret") },
            )
        changes.forEach { change ->
            device.paymentsApps = setOf(TerminalEnvironment.TEST)
            ready()
            env.updateSettings {
                it.copy(
                    terminal =
                        it.terminal.copy(
                            paymentsAppInstallationId = FakePaymentsApp.INSTALLATION_ID,
                            keyIdentifier = "manual",
                            keyVersion = 1,
                        ),
                )
            }
            await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "manual secret") }
            beforeRead = change
            assertThat(await { container.setupDiscovery.findPaymentsAppKey() }).isNull()
            assertThat(await { container.settings.current() }.terminal.keyIdentifier).isEqualTo("manual")
            assertThat(await { container.secrets.get(Secret.TERMINAL_PASSPHRASE) }).isNotEqualTo(" secret passphrase ")
        }
    }
}
