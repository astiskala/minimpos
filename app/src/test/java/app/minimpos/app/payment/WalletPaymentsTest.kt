package app.minimpos.app.payment

import app.minimpos.app.TestEnvironment
import app.minimpos.app.await
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.TaxRateEntity
import app.minimpos.core.payment.ScanWallet
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WalletPaymentsTest {
    @get:Rule
    val env = TestEnvironment()

    private suspend fun prepare(): Pair<SaleSession, WalletPayments> {
        env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
        env.container.walletDiscovery.refresh()
        val session = env.container.session(SaleKind.SALE)
        session.addCustom("Demo", 150, TaxRateEntity(1, "No tax", 0))
        val displayed = session.checkout(env.container.settingsState, flowOf(true), currency = env.container::currency).first()
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
        wallets.choose(ScanWallet.WECHAT_PAY)
        return session to wallets
    }

    @Test
    fun `background and closed attempts reject late scanner results without changing checkout`() =
        await {
            val (session, wallets) = prepare()
            val original = wallets.state.value.epoch
            wallets.background()
            assertThat(wallets.scanned("133341022926803846", original)).isFalse()
            assertThat(wallets.state.value.stage).isEqualTo(WalletScanStage.PAUSED)
            wallets.scanAgain()
            val resumed = wallets.state.value.epoch
            assertThat(resumed).isNotEqualTo(original)
            wallets.close()
            assertThat(wallets.scanned("133341022926803846", resumed)).isFalse()
            assertThat(env.container.payments.state.value).isEqualTo(TransactionState.Idle)
            assertThat(session.cart.value.lines).hasSize(1)
        }

    @Test
    fun `a changed checkout cannot be paid by a previously opened scanner`() =
        await {
            val (session, wallets) = prepare()
            session.updateForm { it.copy(customerReference = "NEW-REFERENCE") }
            assertThat(wallets.scanned("133341022926803846", wallets.state.value.epoch)).isTrue()
            withTimeout(15_000) { wallets.state.first { it.stage == WalletScanStage.PAUSED } }
            assertThat(wallets.state.value.problem).isEqualTo(WalletScanProblem.CHECKOUT_CHANGED)
            assertThat(env.container.payments.state.value).isEqualTo(TransactionState.Idle)
            assertThat(session.cart.value.lines).hasSize(1)
        }

    @Test
    fun `one valid scan starts one persisted sale and retains intent without the payment code`() =
        await {
            env.useSimulator { it.copy(payment = it.payment.copy(currencyCode = "AUD")) }
            env.container.walletDiscovery.refresh()
            val session = env.container.session(SaleKind.SALE)
            session.addCustom("Demo", 150, TaxRateEntity(1, "No tax", 0))
            val displayed = session.checkout(env.container.settingsState, flowOf(true), currency = env.container::currency).first()
            val checkout =
                displayed.copy(
                    wallets =
                        env.container.walletDiscovery.state.value
                            .offered(displayed.currency.code),
                )
            val wallets = env.container.walletPayments
            assertThat(wallets.begin(checkout)).isTrue()
            wallets.choose(ScanWallet.WECHAT_PAY)
            val epoch = wallets.state.value.epoch
            assertThat(wallets.scanned("133341022926803846", epoch)).isTrue()
            assertThat(wallets.scanned("133341022926803846", epoch)).isFalse()
            val finished =
                withTimeout(
                    15_000,
                ) {
                    env.container.payments.state
                        .first { it is TransactionState.Finished }
                } as TransactionState.Finished
            val sale = checkNotNull(env.container.sales.get(finished.id)).sale
            assertThat(sale.status).isEqualTo(SaleStatus.APPROVED)
            assertThat(sale.totalMinor).isEqualTo(150L)
            assertThat(sale.requestedWallet).isEqualTo("wechatpay_pos")
            assertThat(sale.context?.simulated).isTrue()
            assertThat(sale.toString()).doesNotContain("133341022926803846")
            assertThat(session.cart.value.lines).isEmpty()
        }
}
