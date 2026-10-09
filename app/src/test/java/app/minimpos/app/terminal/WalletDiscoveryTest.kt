package app.minimpos.app.terminal

import app.minimpos.app.FakeDevice
import app.minimpos.app.FakeTerminal
import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.security.Secret
import app.minimpos.core.payment.ScanWallet
import app.minimpos.terminal.transport.ApiKey
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.WalletMethod
import app.minimpos.terminal.transport.WalletMethodListing
import app.minimpos.terminal.transport.WalletMethodsApi
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletDiscoveryTest {
    private var result: WalletMethodListing =
        WalletMethodListing.Listed(
            listOf(WalletMethod("wechatpay_pos", true, true, "pos", setOf("AUD"))),
        )
    private var calls = 0

    @get:Rule
    val env =
        TestEnvironment(
            FakeDevice(detectedPoiId = "AMS1-000168223606144"),
            FakeTerminal(),
            walletMethods =
                WalletMethodsApi { _, _ ->
                    calls++
                    result
                },
        )

    @Test
    fun `refresh retains verified wallets only for transient errors without blocking ordinary API access`() =
        await {
            env.useCheckoutApi()
            env.updateSettings { it.copy(terminal = it.terminal.copy(keyIdentifier = "mini-key")) }
            env.container.secrets.set(Secret.TERMINAL_PASSPHRASE, "correct horse battery staple")
            env.container.terminalStatus.check()
            val discovery = env.container.walletDiscovery
            discovery.refresh()
            assertThat(discovery.state.value.offered("AUD")).containsExactly(ScanWallet.WECHAT_PAY)
            result = WalletMethodListing.Failed(Fault.AdyenUnavailable(503))
            discovery.refresh()
            assertThat(discovery.state.value.offered("AUD")).containsExactly(ScanWallet.WECHAT_PAY)
            assertThat(discovery.state.value.failure).isEqualTo(Failure.Remote(Fault.AdyenUnavailable(503)))
            result = WalletMethodListing.Failed(Fault.Permission(ApiKey.ADYEN))
            discovery.refresh()
            assertThat(discovery.state.value.offered("AUD")).isEmpty()
            val api = env.container.api.target()
            assertThat(api.modifications(api.context)).isInstanceOf(ApiAccess.Ready::class.java)
            assertThat(calls).isEqualTo(3)
        }
}
