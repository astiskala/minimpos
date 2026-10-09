package app.minimpos.app.feature.sale

import androidx.lifecycle.ViewModel
import app.minimpos.app.payment.WalletPayments
import app.minimpos.app.payment.WalletScanSource
import app.minimpos.core.payment.ScanWallet

/** Holds no codes or UI types; leaving this route abandons only transient scanning, never a financial operation. */
internal class WalletViewModel(
    private val payments: WalletPayments,
) : ViewModel() {
    val state = payments.state

    fun choose(wallet: ScanWallet?) = payments.choose(wallet)

    fun source(source: WalletScanSource) = payments.useSource(source)

    fun again() = payments.scanAgain()

    fun background() = payments.background()

    fun close() = payments.close()

    fun accepts(
        code: String,
        epoch: Long,
    ) = payments.accepts(code, epoch)

    fun scanned(
        code: String,
        epoch: Long,
    ) = payments.scanned(code, epoch)

    fun demo() = payments.demo()

    override fun onCleared() {
        payments.close()
    }
}
