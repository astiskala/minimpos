package app.minimpos.app.feature.settings

import android.os.Looper
import androidx.lifecycle.ViewModel
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.closeViewModels
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.ActionOutcome
import app.minimpos.app.feature.ActionState
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
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
class TerminalSetupViewModelTest {
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
                        liveUrlPrefix = "abc-Merchant",
                    ),
            )
        }
        val vm = settingsViewModel()
        val setup = setupViewModel()
        setup.findTerminals()
        val missing = await { setup.actions.first { it.terminals.done } }
        assertThat(missing.manualDetails).isTrue()
        assertThat(missing.connectedTerminals).isNull()
        setup.dismissResult(SetupResult.TERMINALS)
        assertThat(setup.actions.value).isEqualTo(missing)

        vm.saveAndTest(Secret.ADYEN_API_KEY, " cloud-key ", SettingsTest.CONNECTION)
        val tested = await { vm.actions.first { it.connection.isError } }
        assertThat(tested.apiKeyStored).isTrue()
        assertThat(tested.connection.outcome).isEqualTo(ActionOutcome.Failed(Failure.NotSetUp(SetupProblem.POI_ID)))
        assertThat(await { container.secrets.get(Secret.ADYEN_API_KEY) }).isEqualTo("cloud-key")
        vm.dismissTestResult(SettingsResult.CONNECTION)

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
    fun `cloud discovery saves the key and final verification rejects incomplete API setup`() {
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
        val tested = await { vm.actions.first { it.connection.isError } }
        assertThat(tested.connection.outcome).isEqualTo(ActionOutcome.Failed(Failure.NotSetUp(SetupProblem.LIVE_PREFIX)))
        assertThat(tested.api.outcome).isNull()
        vm.dismissTestResult(SettingsResult.CLOUD)
        assertThat(vm.actions.value.connection).isEqualTo(ActionState())
        assertThat(vm.actions.value.api).isEqualTo(ActionState())

        env.updateSettings { it.copy(terminal = it.terminal.copy(liveUrlPrefix = "abc-Merchant")) }
        vm.saveAndTest(Secret.ADYEN_API_KEY, test = SettingsTest.CLOUD)
        val connected = await { vm.actions.first { it.connection.done && it.api.done } }
        assertThat(connected.api.outcome).isEqualTo(ActionOutcome.ApiWorks)
        vm.dismissTestResult(SettingsResult.CLOUD)

        env.updateSettings { it.copy(terminal = it.terminal.copy(poiIdOverride = "")) }
        vm.saveAndTest(Secret.ADYEN_API_KEY, test = SettingsTest.CLOUD)
        val failed = await { vm.actions.first { it.connection.isError } }
        assertThat(failed.connection.outcome).isEqualTo(ActionOutcome.Failed(Failure.NotSetUp(SetupProblem.POI_ID)))
        assertThat(failed.api.outcome).isNull()
    }

    @Test
    fun `Tap to Pay provisions its credential automatically and the phone can be removed again`() {
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP, keyIdentifier = "key", merchantAccount = "Merchant"))
        }
        val vm = setupViewModel()
        vm.setUpTapToPay()
        val ready = await { vm.actions.first { it.tapToPay.done } }
        assertThat(ready.tapToPay.outcome).isEqualTo(ActionOutcome.TapToPayReady(FakePaymentsApp.INSTALLATION_ID))
        vm.dismissResult(SetupResult.TAP_TO_PAY)
        vm.dismissResult(SetupResult.SHARED_KEY)
        assertThat(vm.actions.value.tapToPay).isEqualTo(ActionState(done = true))

        vm.setUpTapToPay(again = true)
        await { vm.actions.first { it.tapToPay.done } }
        vm.removeTapToPay()
        assertThat(await { vm.actions.first { it.tapToPay.done } }.tapToPay.outcome).isEqualTo(ActionOutcome.TapToPayRemoved)

        // A key that cannot be stored is reported.
        env.cipher.failEncrypt = true
        vm.setUpTapToPay()
        assertThat(
            await { vm.actions.first { it.tapToPay.isError } }.tapToPay.outcome,
        ).isEqualTo(ActionOutcome.Failed(Failure.Device(DeviceFault.SECURE_STORAGE)))
    }
}
