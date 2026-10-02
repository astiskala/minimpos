package io.minimpos.app.feature

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.FakeDevice
import io.minimpos.app.FakePaymentsApp
import io.minimpos.app.TestEnvironment
import io.minimpos.app.await
import io.minimpos.app.data.security.Secret
import io.minimpos.app.data.settings.TerminalMode
import io.minimpos.app.feature.settings.SettingsChecks
import io.minimpos.app.feature.settings.SettingsTest
import io.minimpos.app.feature.settings.SettingsViewModel
import io.minimpos.app.feature.settings.TerminalSetupViewModel
import io.minimpos.app.terminal.SetupProblem
import io.minimpos.terminal.transport.TerminalEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Settings for a terminal in the cloud and for Tap to Pay, through the settings view model. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RemoteSettingsViewModelTest {
    private val env = TestEnvironment(FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST)))
    private val container = env.container

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        env.close()
    }

    private fun settingsViewModel() =
        SettingsViewModel(
            container.settings,
            container.secrets,
            container.pinManager,
            container.sessionLock,
            SettingsChecks(container.terminalStatus, container.receipts, container.api),
            container.history,
            container.catalog,
            container::sampleReceipt,
        )

    private fun setupViewModel() =
        TerminalSetupViewModel(container.settings, container.secrets, container.terminalStatus, container.tapToPay)

    @Test
    fun `the API key is saved and tested on the terminal in the cloud, which can be chosen from those connected`() {
        env.updateSettings { it.copy(terminal = it.terminal.copy(mode = TerminalMode.CLOUD, merchantAccount = "Merchant")) }
        val vm = settingsViewModel()
        val setup = setupViewModel()
        setup.findTerminals()
        val missing = await { setup.actions.first { it.terminals.isError } }
        assertThat(missing.terminals.outcome).isEqualTo(ActionOutcome.NotSetUp(SetupProblem.API_REQUIRED))

        vm.saveAndTest(Secret.CHECKOUT_API_KEY, " cloud-key ", SettingsTest.CONNECTION)
        val tested = await { vm.actions.first { it.connection.isError } }
        assertThat(tested.apiKeyStored).isTrue()
        assertThat(tested.connection.outcome).isEqualTo(ActionOutcome.NotSetUp(SetupProblem.POI_ID))
        assertThat(await { container.secrets.get(Secret.CHECKOUT_API_KEY) }).isEqualTo("cloud-key")
        vm.dismissConnectionResult()

        setup.findTerminals()
        val found = await { setup.actions.first { it.connectedTerminals != null } }
        assertThat(found.connectedTerminals).containsExactly("AMS1-000168223606144", "S1F2-000158213605014").inOrder()
        setup.chooseTerminal("S1F2-000158213605014")
        assertThat(setup.actions.value.connectedTerminals).isNull()
        await { container.settings.settings.first { it.terminal.poiIdOverride == "S1F2-000158213605014" } }

        vm.saveAndTest(Secret.CHECKOUT_API_KEY, test = SettingsTest.CONNECTION)
        val connected = await { vm.actions.first { it.connection.done } }
        assertThat(connected.connection.outcome).isInstanceOf(ActionOutcome.Connected::class.java)
        // Dismissing the list chooses nothing.
        setup.chooseTerminal(null)
        assertThat(container.settingsState.value.terminal.poiIdOverride).isEqualTo("S1F2-000158213605014")
    }

    @Test
    fun `Tap to Pay is set up with the Payments app API key typed, and the phone can be removed again`() {
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
