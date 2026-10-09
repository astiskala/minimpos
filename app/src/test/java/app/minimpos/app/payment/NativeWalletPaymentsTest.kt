package app.minimpos.app.payment

import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.app.data.security.Secret
import app.minimpos.core.payment.ScanWallet
import app.minimpos.terminal.transport.CredentialLookup
import app.minimpos.terminal.transport.Delivery
import app.minimpos.terminal.transport.SharedKeyLookup
import app.minimpos.terminal.transport.TerminalDetails
import app.minimpos.terminal.transport.TerminalDetailsApi
import app.minimpos.terminal.transport.TerminalEnvironment
import app.minimpos.terminal.transport.TerminalListing
import app.minimpos.terminal.transport.WalletMethod
import app.minimpos.terminal.transport.WalletMethodListing
import app.minimpos.terminal.transport.WalletMethodsApi
import com.adyen.model.nexo.AdminResponse
import com.adyen.model.nexo.Response
import com.adyen.model.nexo.ResultType
import com.adyen.model.nexo.SaleToPOIResponse
import com.adyen.model.terminal.TerminalAPIResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class NativeWalletPaymentsTest {
    private val started = CompletableDeferred<Unit>()
    private val ended = CompletableDeferred<Unit>()
    private val code = CompletableDeferred<Unit>()
    private var paymentRequests = 0
    private val terminal =
        FakeTerminal(reply = { request ->
            val message = request.saleToPOIRequest
            if (message.paymentRequest != null) paymentRequests++
            message.adminRequest?.let { admin ->
                val command = String(Base64.getDecoder().decode(admin.serviceIdentification), Charsets.UTF_8)
                if (command.contains("\"End\"")) {
                    ended.complete(Unit)
                } else {
                    started.complete(Unit)
                    code.await()
                }
                Delivery.Answered(
                    TerminalAPIResponse().apply {
                        saleToPOIResponse =
                            SaleToPOIResponse().apply {
                                adminResponse =
                                    AdminResponse().apply {
                                        response =
                                            Response().apply {
                                                result = ResultType.SUCCESS
                                                additionalResponse =
                                                    Base64.getEncoder().encodeToString(
                                                        """{"Barcode":{"Data":"133341022926803846","Symbology":"QR_CODE"}}""".toByteArray(),
                                                    )
                                            }
                                    }
                            }
                    },
                )
            }
        })

    @get:Rule
    val env =
        TestEnvironment(
            device = FakeDevice(detectedPoiId = "S1F2L-123456789"),
            terminal = terminal,
            walletMethods =
                WalletMethodsApi { _, _ ->
                    WalletMethodListing.Listed(listOf(WalletMethod("wechatpay_pos", true, true, "pos")))
                },
            terminalDetails =
                object : TerminalDetailsApi {
                    override suspend fun credential(environment: TerminalEnvironment) = CredentialLookup.Allowed

                    override suspend fun terminals(
                        environment: TerminalEnvironment,
                        id: String?,
                    ): TerminalListing =
                        TerminalListing.Listed(
                            listOf(
                                TerminalDetails(
                                    "S1F2L-123456789",
                                    "HarbourCoffeeCOM",
                                    "",
                                    model = "S1F2L",
                                    countryCode = "AU",
                                ),
                            ),
                            environment,
                        )

                    override suspend fun sharedKey(
                        id: String,
                        environment: TerminalEnvironment,
                    ) = SharedKeyLookup.Missing
                },
        )

    private suspend fun prepare(): WalletPayments {
        env.useCheckoutApi()
        env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "mini-key")) }
        env.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple")
        env.container.terminalStatus.check()
        env.container.walletDiscovery.refresh()
        val session = env.container.session(SaleKind.SALE)
        session.addCustom("Native demo", 150, TaxRateEntity(1, "No tax", 0))
        val displayed = session.checkout(env.container.settingsState, flowOf(false), currency = env.container::currency).first()
        val wallets = env.container.walletPayments
        check(
            wallets.begin(
                displayed.copy(
                    wallets =
                        env.container.walletDiscovery.state.value
                            .offered(displayed.currency.code),
                ),
            ),
        )
        withTimeout(15_000) { started.await() }
        return wallets
    }

    @Test
    fun `sole wallet skips selection and native hardware is preferred over the camera`() =
        await {
            val wallets = prepare()
            assertThat(wallets.state.value.selected).isEqualTo(ScanWallet.WECHAT_PAY)
            assertThat(wallets.state.value.source).isEqualTo(WalletScanSource.NATIVE)
            code.complete(Unit)
            withTimeout(15_000) {
                env.container.payments.state
                    .first { it is TransactionState.Finished }
            }
            assertThat(paymentRequests).isEqualTo(1)
        }

    @Test
    fun `switching to camera ends the original native session and invalidates its callback`() =
        await {
            val wallets = prepare()
            val original = wallets.state.value.epoch
            wallets.useSource(WalletScanSource.CAMERA)
            withTimeout(15_000) { ended.await() }
            code.complete(Unit)
            assertThat(wallets.scanned("133341022926803846", original)).isFalse()
            assertThat(wallets.state.value.source).isEqualTo(WalletScanSource.CAMERA)
            assertThat(paymentRequests).isEqualTo(0)
            wallets.background()
            assertThat(wallets.state.value.stage).isEqualTo(WalletScanStage.PAUSED)
        }
}
