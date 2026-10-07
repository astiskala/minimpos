package io.github.astiskala.minimpos.app.feature

import android.os.Looper
import androidx.lifecycle.ViewModel
import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakePaymentsApp
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.closeViewModels
import io.github.astiskala.minimpos.app.data.db.SetupProblem
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.app.data.settings.TerminalMode
import io.github.astiskala.minimpos.app.feature.settings.SettingsChecks
import io.github.astiskala.minimpos.app.feature.settings.SettingsTest
import io.github.astiskala.minimpos.app.feature.settings.SettingsViewModel
import io.github.astiskala.minimpos.app.feature.settings.TerminalSetupViewModel
import io.github.astiskala.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Settings for a terminal in the cloud and for Tap to Pay, through the settings view model. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RemoteSettingsViewModelTest {
    private val env = TestEnvironment(FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST)))
    private val container = env.container
    private val main = UnconfinedTestDispatcher()
    private val viewModels = mutableListOf<ViewModel>()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        closeViewModels(viewModels)
        env.close()
        Dispatchers.resetMain()
    }

    private fun settingsViewModel() =
        SettingsViewModel(
            container.pricingChanges,
            container.secrets,
            container.pinManager,
            container.sessionLock,
            SettingsChecks(container.terminalStatus, container.receipts, container.api, container.receiptBusinessDetails),
            container.history,
            container.catalog,
            container::sampleReceipt,
        ).also(viewModels::add)

    private fun awaitTerminalSelection(poiId: String) =
        await {
            while (container.settingsState.value.terminal.poiIdOverride != poiId) {
                shadowOf(Looper.getMainLooper()).idle()
                main.scheduler.runCurrent()
                delay(10)
            }
        }

    private fun setupViewModel() =
        TerminalSetupViewModel(
            container.secrets,
            container.terminalStatus,
            container.tapToPay,
            container.setupDiscovery,
            container.settings,
            settingsViewModel().state,
        ).also(viewModels::add)

    @Test
    fun `the API key is saved and tested on the terminal in the cloud, which can be chosen from those connected`() {
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.CLOUD,
                        merchantAccount = "Merchant",
                        environment = TerminalEnvironment.LIVE,
                    ),
            )
        }
        val vm = settingsViewModel()
        val setup = setupViewModel()
        setup.findTerminals()
        val missing = await { setup.actions.first { it.terminals.done } }
        assertThat(missing.manualDetails).isTrue()
        assertThat(missing.connectedTerminals).isNull()

        vm.saveAndTest(Secret.ADYEN_API_KEY, " cloud-key ", SettingsTest.CONNECTION)
        val tested = await { vm.actions.first { it.connection.isError } }
        assertThat(tested.apiKeyStored).isTrue()
        assertThat(tested.connection.outcome).isEqualTo(ActionOutcome.NotSetUp(SetupProblem.POI_ID))
        assertThat(await { container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("cloud-key")
        vm.dismissConnectionResult()

        setup.findTerminals()
        val found = await { setup.actions.first { it.connectedTerminals != null } }
        assertThat(found.connectedTerminals).containsExactly("AMS1-000168223606144", "S1F2-000158213605014").inOrder()
        setup.chooseTerminal("S1F2-000158213605014")
        assertThat(setup.actions.value.connectedTerminals).isNull()
        awaitTerminalSelection("S1F2-000158213605014")

        vm.saveAndTest(Secret.ADYEN_API_KEY, test = SettingsTest.CONNECTION)
        val connected = await { vm.actions.first { it.connection.done } }
        assertThat(connected.connection.outcome).isInstanceOf(ActionOutcome.Connected::class.java)
        // Dismissing the list chooses nothing.
        setup.chooseTerminal(null)
        assertThat(container.settingsState.value.terminal.poiIdOverride).isEqualTo("S1F2-000158213605014")
    }

    @Test
    fun `in the cloud finding terminals saves the key typed, and one test checks the terminal, then the Checkout API`() {
        container.start()
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.CLOUD,
                        merchantAccount = "Merchant",
                        environment = TerminalEnvironment.LIVE,
                    ),
            )
        }
        val vm = settingsViewModel()
        val setup = setupViewModel()
        setup.findTerminals(" cloud-key ")
        val found = await { setup.actions.first { it.connectedTerminals != null } }
        assertThat(found.apiKeyStored).isTrue()
        assertThat(await { container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("cloud-key")
        setup.chooseTerminal("S1F2-000158213605014")
        awaitTerminalSelection("S1F2-000158213605014")

        vm.saveAndTest(Secret.ADYEN_API_KEY, test = SettingsTest.CLOUD)
        val tested = await { vm.actions.first { it.connection.done && it.api.outcome != null } }
        assertThat(tested.connection.outcome).isInstanceOf(ActionOutcome.Connected::class.java)
        // The key reaches a LIVE data center, so the Checkout API asks for the live URL prefix next.
        assertThat(tested.api.outcome).isEqualTo(ActionOutcome.NotSetUp(SetupProblem.LIVE_PREFIX))
        assertThat(tested.api.isError).isTrue()

        // Without a terminal to reach, the Checkout API is not tested.
        env.updateSettings { it.copy(terminal = it.terminal.copy(poiIdOverride = "")) }
        vm.saveAndTest(Secret.ADYEN_API_KEY, test = SettingsTest.CLOUD)
        val failed = await { vm.actions.first { it.connection.isError } }
        assertThat(failed.connection.outcome).isEqualTo(ActionOutcome.NotSetUp(SetupProblem.POI_ID))
        assertThat(failed.api.outcome).isNull()
    }

    @Test
    fun `Tap to Pay is set up with the Payments app API key typed, and the phone can be removed again`() {
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP, keyIdentifier = "key", merchantAccount = "Merchant"))
        }
        val vm = setupViewModel()
        vm.setUpTapToPay()
        val missing = await { vm.actions.first { it.tapToPay.isError } }
        assertThat(missing.tapToPay.outcome).isEqualTo(ActionOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_API_KEY))

        vm.setUpTapToPay(" pa-key ")
        val ready = await { vm.actions.first { it.tapToPay.done } }
        assertThat(ready.tapToPay.outcome).isEqualTo(ActionOutcome.TapToPayReady(FakePaymentsApp.INSTALLATION_ID))
        assertThat(ready.paymentsAppKeyStored).isTrue()
        assertThat(await { container.secrets.get(Secret.PAYMENTS_APP_API_KEY) }).isEqualTo("pa-key")

        vm.setUpTapToPay(again = true)
        await { vm.actions.first { it.tapToPay.done && !it.paymentsAppKeyStored } }
        vm.removeTapToPay()
        assertThat(await { vm.actions.first { it.tapToPay.done } }.tapToPay.outcome).isEqualTo(ActionOutcome.TapToPayRemoved)

        // A key that cannot be stored is reported.
        env.cipher.failEncrypt = true
        vm.setUpTapToPay("another key")
        assertThat(
            await { vm.actions.first { it.tapToPay.isError } }.tapToPay.outcome,
        ).isInstanceOf(ActionOutcome.SecretNotStored::class.java)
    }
}
