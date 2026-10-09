package app.minimpos.app.terminal

import app.minimpos.app.FakeCloud
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeManagement
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.made
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.PrintJob
import app.minimpos.terminal.client.PrintLine
import app.minimpos.terminal.client.PrintOutcome
import app.minimpos.terminal.client.RefundParams
import app.minimpos.terminal.client.TransactionKind
import app.minimpos.terminal.client.TransactionOutcome
import app.minimpos.terminal.paymentsapp.BoardingTarget
import app.minimpos.terminal.paymentsapp.ManagementResult
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.CloudCredentials
import app.minimpos.terminal.transport.CloudDetection
import app.minimpos.terminal.transport.CloudRegion
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/** Payments that go to a terminal in the cloud, or to the Adyen Payments app on this phone (Tap to Pay). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RemoteTerminalTest {
    private val cloud = FakeCloud()
    private val paymentsApp = FakePaymentsApp()
    private val management = FakeManagement()
    private val phone = FakeDevice(paymentsApps = setOf(TerminalEnvironment.TEST))
    private val dispatcher = UnconfinedTestDispatcher()
    private val env = TestEnvironment(phone, dispatcher = dispatcher, cloud = cloud, paymentsApp = paymentsApp, management = management)
    private val container = env.container
    private val gateway = container.gateway
    private val payment = PaymentParams(BigDecimal("1.00"), "AUD", "MP-1")

    @After
    fun tearDown() = env.close()

    private fun useCloud(poiId: String = "S1F2-000158213605014") {
        env.updateSettings {
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.CLOUD,
                        merchantAccount = " Merchant ",
                        poiIdOverride = poiId,
                        environment = TerminalEnvironment.LIVE,
                        liveUrlPrefix = "abc-Merchant",
                    ),
            )
        }
        await { container.secrets.set(Secret.ADYEN_API_KEY, "cloud-key") }
        env.updateSettings { it }
    }

    private fun useTapToPay() {
        env.updateSettings {
            it.copy(terminal = it.terminal.copy(mode = TerminalMode.PAYMENTS_APP, keyIdentifier = "key", merchantAccount = "Merchant"))
        }
        await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
    }

    @Test
    fun `changing the selected cloud environment opens a new transport without changing credentials`() {
        useCloud()
        assertThat(await { gateway.diagnose() }).isInstanceOf(TerminalConnection.Connected::class.java)
        assertThat(cloud.detections).isEqualTo(1)
        env.updateSettings { it.copy(terminal = it.terminal.copy(environment = TerminalEnvironment.TEST, cloudRegion = null)) }
        assertThat(await { gateway.diagnose() }).isInstanceOf(TerminalConnection.Connected::class.java)
        assertThat(cloud.detections).isEqualTo(2)
        assertThat(gateway.detectedEnvironment.value?.environment).isEqualTo(TerminalEnvironment.TEST)
        assertThat(await { container.settings.current() }.terminal.environment).isEqualTo(TerminalEnvironment.TEST)
    }

    @Test
    fun `a late cloud endpoint cannot update a different merchant's setup`() {
        useCloud()
        cloud.beforeDetection = {
            cloud.beforeDetection = {}
            container.settings.update { it.copy(terminal = it.terminal.copy(merchantAccount = "Other")) }
        }
        container.terminalStatus.start()
        assertThat(await { gateway.connectedTerminals() }).isInstanceOf(ConnectedTerminals.Listed::class.java)
        val current = await { container.settings.current() }.terminal
        assertThat(current.merchantAccount).isEqualTo("Other")
        assertThat(current.cloudRegion).isNull()
    }

    @Test
    fun `cloud requests wait for an explicit environment even with credentials and a terminal`() {
        useCloud()
        env.updateSettings { it.copy(terminal = it.terminal.copy(environment = null)) }
        assertThat(await { gateway.diagnose() }).isEqualTo(TerminalConnection.NotSetUp(SetupProblem.ENVIRONMENT))
        assertThat(await { gateway.pay(payment, "PAY1") }).isEqualTo(Attempt.NotSetUp(SetupProblem.ENVIRONMENT))
        assertThat(await { gateway.connectedTerminals() }).isEqualTo(ConnectedTerminals.NotSetUp(SetupProblem.ENVIRONMENT))
        assertThat(cloud.detections).isEqualTo(0)
    }

    @Test
    fun `a terminal in the cloud takes payments with the API key, once its endpoint is found`() =
        runTest(dispatcher) {
            useCloud()
            var sending: String? = null
            val paid = gateway.pay(payment, "PAY1") { sending = it }.made() as TransactionOutcome.Completed
            assertThat(paid.details.success).isTrue()
            assertThat(sending).isEqualTo("S1F2-000158213605014")
            assertThat(cloud.credentials).containsExactly(CloudCredentials("cloud-key", "Merchant"))
            val refund = RefundParams(paid.details.poiTransactionId!!, paid.details.poiTimestamp!!, "R-1")
            assertThat((gateway.refund(refund, "REF1").made() as TransactionOutcome.Completed).details.success).isTrue()
            assertThat(gateway.status("PAY1", TransactionKind.PAYMENT).made()).isInstanceOf(TransactionOutcome.Completed::class.java)
            assertThat(gateway.print(listOf(PrintJob.Text(listOf(PrintLine.Text("x"))))).made()).isEqualTo(PrintOutcome.Printed)
            // The endpoint is found once per key and terminal.
            assertThat(cloud.detections).isEqualTo(1)

            // The key's environment and data centre are what the Checkout API and Settings go by.
            container.terminalStatus.start()
            val saved = container.settings.settings.first { it.terminal.cloudRegion != null }
            assertThat(saved.terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
            assertThat(saved.terminal.cloudRegion).isEqualTo(CloudRegion.AU)
            assertThat(container.terminalStatus.check()).isInstanceOf(TerminalConnection.Connected::class.java)
            val status =
                container.terminalStatus.state.first {
                    it.connection is TerminalConnection.Connected && it.environment == TerminalEnvironment.LIVE
                }
            assertThat(status.mode).isEqualTo(TerminalMode.CLOUD)
            assertThat(status.environment).isEqualTo(TerminalEnvironment.LIVE)
            assertThat(status.apiProblem).isNull()
        }

    @Test
    fun `an API key no endpoint takes is a failed connection, and nothing is sent`() {
        useCloud()
        cloud.detection = CloudDetection.Failed(Fault.Credential(ApiKey.ADYEN))
        val credential = Fault.Credential(ApiKey.ADYEN)
        assertThat((await { gateway.diagnose() } as TerminalConnection.Failed).failure).isEqualTo(Failure.Remote(credential))
        assertThat((await { gateway.pay(payment, "PAY1") }.made() as TransactionOutcome.NotProcessed).fault).isEqualTo(credential)
        assertThat((await { container.terminalStatus.connectedTerminals() } as ConnectedTerminals.Failed).fault).isEqualTo(credential)
        // A key saved but no longer readable is reported as such.
        env.cipher.fail = true
        assertThat((await { gateway.diagnose() } as TerminalConnection.NotSetUp).problem).isEqualTo(SetupProblem.UNREADABLE_API_KEY)
    }

    @Test
    fun `the terminals connected in the cloud can be listed before one is chosen`() {
        assertThat(await { gateway.connectedTerminals() }).isEqualTo(ConnectedTerminals.NotSetUp(SetupProblem.ENVIRONMENT))
        useCloud(poiId = "")
        assertThat((await { gateway.diagnose() } as TerminalConnection.NotSetUp).problem).isEqualTo(SetupProblem.POI_ID)
        val listed = await { gateway.connectedTerminals() } as ConnectedTerminals.Listed
        assertThat(listed.poiIds).containsExactly("AMS1-000168223606144", "S1F2-000158213605014").inOrder()
        assertThat(gateway.detectedEnvironment.value?.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(gateway.detectedEnvironment.value?.cloudRegion).isEqualTo(CloudRegion.AU)
    }

    @Test
    fun `Tap to Pay is set up by boarding the Payments app, then takes payments through it`() {
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        assertThat(await { gateway.diagnose() }).isEqualTo(TerminalConnection.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED))
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_API_KEY))
        await { container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "pa-key") }
        env.updateSettings { it.copy(terminal = it.terminal.copy(storeId = " ST1 ")) }

        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        assertThat(management.requests).containsExactly(BoardingTarget("Merchant", "ST1"))
        assertThat(await { container.settings.current() }.terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)

        // A connection check only checks the setup, without opening the Payments app.
        val opened = paymentsApp.opened.size
        assertThat(await { gateway.diagnose() }).isInstanceOf(TerminalConnection.Connected::class.java)
        assertThat(paymentsApp.opened).hasSize(opened)

        // Payments wait for the stored setup's connection verification, not merely entered credentials.
        assertThat(await { gateway.pay(payment, "PAY0") }).isEqualTo(Attempt.NotSetUp(SetupProblem.SETUP_NOT_VERIFIED))
        assertThat(await { container.terminalStatus.check() }).isInstanceOf(TerminalConnection.Connected::class.java)
        var sending: String? = null
        val paid = await { gateway.pay(payment, "PAY1") { sending = it } }.made() as TransactionOutcome.Completed
        assertThat(paid.details.success).isTrue()
        assertThat(sending).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
        assertThat(paymentsApp.opened.last()).startsWith("https://www.adyen.com/test/nexo?request=")
        // It has no printer, and cannot be asked for a transaction's status.
        assertThat(await { container.terminalStatus.state.first { it.loaded } }.printerAvailable).isFalse()
        assertThat(
            await { gateway.print(listOf(PrintJob.Text(listOf(PrintLine.Text("x"))))) }.made(),
        ).isInstanceOf(PrintOutcome.Failed::class.java)
        assertThat(
            await {
                gateway.status("PAY1", TransactionKind.PAYMENT)
            }.made(),
        ).isEqualTo(TransactionOutcome.Unknown("PAY1", Fault.NoLateReply))
        assertThat(await { gateway.abort("PAY1") }).isFalse()
    }

    @Test
    fun `this phone can be removed, and Tap to Pay explains what is missing`() {
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        await { container.secrets.set(Secret.PAYMENTS_APP_API_KEY, "pa-key") }
        paymentsApp.boarded = true
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        // Already boarded, so Adyen was not asked for a token.
        assertThat(management.requests).isEmpty()

        management.result = ManagementResult.Failed(Fault.Permission(ApiKey.PAYMENTS_APP))
        assertThat(await { container.tapToPay.unregister() })
            .isEqualTo(TapToPayOutcome.Failed(Failure.Remote(Fault.Permission(ApiKey.PAYMENTS_APP))))
        management.result = ManagementResult.Done()
        assertThat(await { container.tapToPay.unregister() }).isEqualTo(TapToPayOutcome.Unregistered)
        assertThat(management.revoked).containsExactly(FakePaymentsApp.INSTALLATION_ID, FakePaymentsApp.INSTALLATION_ID)
        assertThat(await { container.settings.current() }.terminal.paymentsAppInstallationId).isEmpty()

        env.cipher.fail = true
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.UNREADABLE_PAYMENTS_APP_KEY))
        env.cipher.fail = false
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "")) }
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))
        phone.paymentsApps = TerminalEnvironment.entries.toSet()
        assertThat(await { container.tapToPay.unregister() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_AMBIGUOUS))
        phone.paymentsApps = emptySet()
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_MISSING))
    }
}
