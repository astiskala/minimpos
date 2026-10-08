package io.github.astiskala.minimpos.app.terminal

import com.google.common.truth.Truth.assertThat
import io.github.astiskala.minimpos.app.FakeDevice
import io.github.astiskala.minimpos.app.FakeTerminal
import io.github.astiskala.minimpos.app.TestEnvironment
import io.github.astiskala.minimpos.app.await
import io.github.astiskala.minimpos.app.data.security.Secret
import io.github.astiskala.minimpos.core.payment.ScanWallet
import io.github.astiskala.minimpos.terminal.transport.ManagementFailure
import io.github.astiskala.minimpos.terminal.transport.WalletMethod
import io.github.astiskala.minimpos.terminal.transport.WalletMethodListing
import io.github.astiskala.minimpos.terminal.transport.WalletMethodsApi
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
            result = WalletMethodListing.Failed(ManagementFailure.UNAVAILABLE)
            discovery.refresh()
            assertThat(discovery.state.value.offered("AUD")).containsExactly(ScanWallet.WECHAT_PAY)
            assertThat(discovery.state.value.failure).isEqualTo(WalletDiscoveryFailure.UNAVAILABLE)
            result = WalletMethodListing.Failed(ManagementFailure.PERMISSION)
            discovery.refresh()
            assertThat(discovery.state.value.offered("AUD")).isEmpty()
            val api = env.container.api.target()
            assertThat(api.modifications(api.context)).isInstanceOf(ApiAccess.Ready::class.java)
            assertThat(calls).isEqualTo(3)
        }
}
