package app.minimpos.app.terminal

import app.minimpos.app.FakeCloud
import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeManagement
import app.minimpos.app.FakePaymentsApp
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.security.Secret
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.made
import app.minimpos.app.payment.PaymentStart
import app.minimpos.app.payment.Recheck
import app.minimpos.app.payment.SaleBook
import app.minimpos.app.payment.TransactionLifecycle
import app.minimpos.app.payment.TransactionState
import app.minimpos.core.cart.AppliedTax
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    private val payment = PaymentParams(100, "AUD", "MP-1")

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
            it.copy(
                terminal =
                    it.terminal.copy(
                        mode = TerminalMode.PAYMENTS_APP,
                        environment = TerminalEnvironment.TEST,
                        keyIdentifier = "key",
                        merchantAccount = "Merchant",
                    ),
            )
        }
        await { container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple") }
    }

    @Test
    fun `support inspection checks cloud API and diagnosis independently without payment or setup changes`() {
        useCloud()
        val before = await { container.settings.current() }
        val reading = checkNotNull(await { container.terminalStatus.inspect() })
        assertThat(reading.api).isEqualTo(ApiCheck.Works)
        assertThat(reading.connection).isInstanceOf(TerminalConnection.Connected::class.java)
        assertThat(reading.facts["poiId"]).isEqualTo("S1F2-000158213605014")
        assertThat(reading.facts["merchantAccount"]).isEqualTo(" Merchant ")
        assertThat(reading.facts["integration"]).isEqualTo("CLOUD")
        assertThat(reading.facts.values.joinToString()).doesNotContain("cloud-key")
        assertThat(management.requests).isEmpty()
        assertThat(paymentsApp.opened).isEmpty()
        assertThat(await { container.settings.current() }).isEqualTo(before)
    }

    @Test
    fun `support inspection discards results when its API credential changes`() {
        useCloud()
        cloud.beforeDetection = { container.secrets.set(Secret.ADYEN_API_KEY, "replacement-key") }
        assertThat(await { container.terminalStatus.inspect() }).isNull()
    }

    @Test
    fun `support diagnosis remains independent when API setup is incomplete but final verification fails`() {
        useCloud()
        env.updateSettings { it.copy(terminal = it.terminal.copy(liveUrlPrefix = "")) }
        val reading = checkNotNull(await { container.terminalStatus.inspect() })
        assertThat(reading.api).isEqualTo(ApiCheck.NotSetUp(SetupProblem.LIVE_PREFIX))
        assertThat(reading.connection).isInstanceOf(TerminalConnection.Connected::class.java)
        assertThat(await { container.terminalStatus.check(includeDiagnostics = true) })
            .isEqualTo(TerminalConnection.NotSetUp(SetupProblem.LIVE_PREFIX))
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
        await { gateway.diagnose() }
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
        // A key saved but no longer readable is reported as such.
        env.cipher.fail = true
        assertThat((await { gateway.diagnose() } as TerminalConnection.NotSetUp).problem).isEqualTo(SetupProblem.UNREADABLE_API_KEY)
    }

    @Test
    fun `both installed apps route boarding and payments only to the selected environment`() {
        phone.paymentsApps = TerminalEnvironment.entries.toSet()
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        TerminalEnvironment.entries.forEach { environment ->
            env.updateSettings {
                it.copy(terminal = it.terminal.selectEnvironment(environment).copy(liveUrlPrefix = "live-prefix"))
            }
            paymentsApp.boarded = false
            val first = paymentsApp.packages.size
            assertThat(await { container.tapToPay.board() }).isInstanceOf(TapToPayOutcome.Boarded::class.java)
            assertThat(await { container.terminalStatus.check() }).isInstanceOf(TerminalConnection.Connected::class.java)
            val result = await { gateway.pay(payment, "PAY${environment.name}") }.made() as TransactionOutcome.Completed
            assertThat(result.details.success).isTrue()
            assertThat(paymentsApp.packages.drop(first))
                .containsExactlyElementsIn(List(3) { "com.adyen.ipp.mobile.companion.${environment.name.lowercase()}" })
                .inOrder()
            val prefix = if (environment == TerminalEnvironment.TEST) "https://www.adyen.com/test/" else "https://www.adyen.com/"
            assertThat(paymentsApp.opened.drop(first).all { it.startsWith(prefix) }).isTrue()
            assertThat(management.connections.last().second).isEqualTo(environment)
        }
    }

    @Test
    fun `Tap to Pay recovery uses stored facts after runner recreation and refuses changed accounts`() =
        runTest(StandardTestDispatcher(dispatcher.scheduler)) {
            useTapToPay()
            container.secrets.set(Secret.ADYEN_API_KEY, "key")
            container.tapToPay.board()
            container.terminalStatus.check()
            val originalSetup = container.settings.current()
            paymentsApp.dropFinancialReplies = true
            paymentsApp.dropStatusReplies = true
            val request =
                PaymentStart(
                    totals = Cart().addCustom("Tea", 100, AppliedTax("Zero", 0), "item").totals(TaxMode.INCLUSIVE),
                    currency = CurrencySpec.of("AUD"),
                    merchantReference = "RECOVERY",
                    customerReference = "",
                    shopperEmail = null,
                    tokenization = null,
                )
            val id = checkNotNull(container.payments.start(request))
            container.payments.state.first { it == TransactionState.Finished(id) }
            val unknown = container.sales.get(id)!!.sale
            assertThat(unknown.status).isEqualTo(SaleStatus.UNKNOWN)
            assertThat(unknown.context?.poiId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
            val restarted = TransactionLifecycle(env.scope, gateway, SaleBook(container.sales))
            env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "Other")) }
            val checks = paymentsApp.statusRequests
            assertThat(restarted.recheck(id)).isEqualTo(Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.PAYMENT_CONTEXT)))
            assertThat(paymentsApp.statusRequests).isEqualTo(checks)
            env.updateSettings { originalSetup }
            paymentsApp.dropStatusReplies = false
            assertThat(restarted.recheck(id)).isEqualTo(Recheck.Settled)
            val recovered = container.sales.get(id)!!.sale
            assertThat(recovered.status).isEqualTo(SaleStatus.APPROVED)
            assertThat(recovered.serviceId).isEqualTo(unknown.serviceId)
            assertThat(recovered.context).isEqualTo(unknown.context)
            assertThat(recovered.pspReference).isNotNull()
            assertThat(paymentsApp.financialRequests).isEqualTo(1)
            assertThat(restarted.recheck(id)).isEqualTo(Recheck.Settled)
            assertThat(paymentsApp.statusRequests).isEqualTo(checks + 1)
        }

    @Test
    fun `Tap to Pay is set up by boarding the Payments app, then takes payments through it`() {
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        assertThat(await { gateway.diagnose() }).isEqualTo(TerminalConnection.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED))
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
        // It has no printer; status repeats the original transaction through the encrypted App Link.
        assertThat(await { container.terminalStatus.state.first { it.loaded } }.printerAvailable).isFalse()
        assertThat(
            await { gateway.print(listOf(PrintJob.Text(listOf(PrintLine.Text("x"))))) }.made(),
        ).isInstanceOf(PrintOutcome.Failed::class.java)
        assertThat(
            await {
                gateway.status("PAY1", TransactionKind.PAYMENT)
            }.made(),
        ).isInstanceOf(TransactionOutcome.Completed::class.java)
        assertThat(await { gateway.abort("PAY1") }).isFalse()
    }

    @Test
    fun `a boarding token issued for this installation confirms setup while Adyen's list lags behind`() {
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        management.result = ManagementResult.Done("BT-1", FakePaymentsApp.INSTALLATION_ID)
        management.registrationResult = ManagementResult.NotBoarded
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        assertThat(await { container.tapToPay.board(reboard = true) }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        assertThat(management.requests).hasSize(2)
        assertThat(await { container.settings.current() }.terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)

        // The connection check that verifies the new installation trusts the token too, so payments can start.
        assertThat(await { container.terminalStatus.check() }).isInstanceOf(TerminalConnection.Connected::class.java)
        assertThat(await { gateway.pay(payment, "PAY1") }.made()).isInstanceOf(TransactionOutcome.Completed::class.java)
    }

    @Test
    fun `a boarding the token confirmed vouches only for its installation, until Adyen lists it`() {
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        management.result = ManagementResult.Done("BT-1", FakePaymentsApp.INSTALLATION_ID)
        management.registrationResult = ManagementResult.NotBoarded
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        val notBoarded = TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED)

        env.updateSettings { it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = "INSTALLATION-2")) }
        assertThat(await { container.tapToPay.checkSaved(container.terminalSetup.current()) }).isEqualTo(notBoarded)
        env.updateSettings { it.copy(terminal = it.terminal.copy(paymentsAppInstallationId = FakePaymentsApp.INSTALLATION_ID)) }
        assertThat(await { container.tapToPay.checkSaved(container.terminalSetup.current()) })
            .isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))

        // Once listed, the list decides again, so a later revocation at Adyen counts.
        management.registrationResult = ManagementResult.Done()
        assertThat(await { container.tapToPay.checkSaved(container.terminalSetup.current()) })
            .isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        management.registrationResult = ManagementResult.NotBoarded
        assertThat(await { container.tapToPay.checkSaved(container.terminalSetup.current()) }).isEqualTo(notBoarded)
    }

    @Test
    fun `a Payments app boarded elsewhere needs explicit reboarding, and a failed reboarding forgets the installation`() {
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        paymentsApp.boarded = true
        management.result = ManagementResult.Done("BT-1", FakePaymentsApp.INSTALLATION_ID)
        management.registrationResult = ManagementResult.NotBoarded
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED))
        assertThat(management.requests).isEmpty()
        assertThat(paymentsApp.opened).hasSize(1)
        assertThat(await { container.tapToPay.board(reboard = true) }).isEqualTo(TapToPayOutcome.Boarded(FakePaymentsApp.INSTALLATION_ID))
        assertThat(paymentsApp.opened.map { it.substringBefore('?') to ("reboard=true" in it) })
            .containsExactly(
                "https://www.adyen.com/test/boarded" to false,
                "https://www.adyen.com/test/boarded" to true,
                "https://www.adyen.com/test/board" to false,
            ).inOrder()
        assertThat(management.requests).containsExactly(BoardingTarget("Merchant"))

        management.result = ManagementResult.Failed(Fault.AdyenUnavailable(503))
        assertThat(await { container.tapToPay.board(reboard = true) })
            .isEqualTo(TapToPayOutcome.Failed(Failure.Remote(Fault.AdyenUnavailable(503))))
        assertThat(await { container.settings.current() }.terminal.paymentsAppInstallationId).isEmpty()
        assertThat(await { gateway.diagnose() }).isEqualTo(TerminalConnection.NotSetUp(SetupProblem.PAYMENTS_APP_NOT_BOARDED))
    }

    @Test
    fun `late TEST revocation never clears a new LIVE registration`() {
        phone.paymentsApps = TerminalEnvironment.entries.toSet()
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
        assertThat(await { container.tapToPay.board() }).isInstanceOf(TapToPayOutcome.Boarded::class.java)
        management.beforeRevoke = {
            env.updateSettings {
                it.copy(
                    terminal =
                        it.terminal
                            .selectEnvironment(TerminalEnvironment.LIVE)
                            .copy(paymentsAppInstallationId = FakePaymentsApp.INSTALLATION_ID),
                )
            }
        }
        assertThat(await { container.tapToPay.unregister() }).isEqualTo(TapToPayOutcome.Unregistered)
        val terminal = await { container.settings.current() }.terminal
        assertThat(terminal.environment).isEqualTo(TerminalEnvironment.LIVE)
        assertThat(terminal.paymentsAppInstallationId).isEqualTo(FakePaymentsApp.INSTALLATION_ID)
    }

    @Test
    fun `this phone can be removed, and Tap to Pay explains what is missing`() {
        useTapToPay()
        await { container.secrets.set(Secret.ADYEN_API_KEY, "key") }
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
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.UNREADABLE_API_KEY))
        env.cipher.fail = false
        env.updateSettings { it.copy(terminal = it.terminal.copy(merchantAccount = "")) }
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))
        phone.paymentsApps = TerminalEnvironment.entries.toSet()
        assertThat(await { container.tapToPay.unregister() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.MERCHANT_ACCOUNT))
        phone.paymentsApps = emptySet()
        assertThat(await { container.tapToPay.board() }).isEqualTo(TapToPayOutcome.NotSetUp(SetupProblem.PAYMENTS_APP_MISSING))
    }
}
