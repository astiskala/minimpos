package app.minimpos.app.feature.home

import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.data.settings.GiftCardSetting
import app.minimpos.app.data.settings.ShopperReferenceSource
import app.minimpos.app.data.settings.TerminalMode
import app.minimpos.app.feature.settings.CapabilityCatalog
import app.minimpos.app.terminal.AdyenCapabilitiesState
import app.minimpos.app.terminal.DestinationCapabilities
import app.minimpos.app.terminal.TerminalState
import app.minimpos.app.terminal.WalletAvailability
import app.minimpos.core.giftcard.GiftCardProvider
import app.minimpos.core.payment.PaymentContext
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HomeTilesTest {
    private val settings = AppSettings()
    private val terminal = TerminalState(loaded = true, mode = TerminalMode.TERMINAL)
    private val context = PaymentContext("TERMINAL", "S1F2-000158000000001", "POS", "Merchant", "TEST", null)

    private fun tiles(
        settings: AppSettings = this.settings,
        terminal: TerminalState = this.terminal,
        wallets: WalletAvailability = WalletAvailability(),
        needsSetup: Boolean = false,
    ) = HomeTiles.of(settings, CapabilityCatalog(settings, terminal, AdyenCapabilitiesState(), wallets, "EUR"), needsSetup)

    private fun payment(change: (AppSettings) -> AppSettings) = tiles(change(settings)).payment

    @Test
    fun `a new installation shows what works without further setup`() {
        assertThat(tiles()).isEqualTo(
            HomeTiles(
                payment = listOf(HomeTile.PRE_AUTH, HomeTile.TOKENS),
                afterwards = listOf(HomeTile.REFUND, HomeTile.HISTORY, HomeTile.RECONCILIATION),
            ),
        )
    }

    @Test
    fun `tokens need card saving and a shopper reference`() {
        assertThat(payment { it.copy(payment = it.payment.copy(offerCardSaving = false)) }).containsExactly(HomeTile.PRE_AUTH)
        assertThat(payment { it.copy(payment = it.payment.copy(shopperReferenceSource = ShopperReferenceSource.NONE)) })
            .containsExactly(HomeTile.PRE_AUTH)
    }

    @Test
    fun `gift cards show once a provider is added, even an unfinished one, where the destination carries them`() {
        val unfinished = settings.copy(payment = settings.payment.copy(giftCards = listOf(GiftCardSetting(GiftCardProvider.INTERSOLVE))))
        assertThat(
            unfinished.payment.giftCards
                .single()
                .complete,
        ).isFalse()
        assertThat(tiles(unfinished).payment).contains(HomeTile.GIFT_CARDS)
        val tapToPay = terminal.copy(capabilities = DestinationCapabilities(giftCards = false))
        assertThat(tiles(unfinished, tapToPay).payment).doesNotContain(HomeTile.GIFT_CARDS)
    }

    @Test
    fun `cashout shows when switched on unless the destination is known not to carry it`() {
        val on = settings.copy(payment = settings.payment.copy(cashback = settings.payment.cashback.copy(standalone = true)))
        // Unverified (the store is not read yet) and Needs setup (a store without cashout) keep the tile.
        val carried = WalletAvailability(context = context, identity = "setup", cashbackDestination = true)
        assertThat(tiles(on).payment).contains(HomeTile.CASHOUT)
        assertThat(tiles(on, wallets = carried).payment).contains(HomeTile.CASHOUT)
        assertThat(tiles(on, wallets = carried.copy(checked = true, storeCountry = "NL")).payment).contains(HomeTile.CASHOUT)
        assertThat(tiles(on, wallets = WalletAvailability(context = context)).payment).doesNotContain(HomeTile.CASHOUT)
        assertThat(tiles(wallets = carried).payment).doesNotContain(HomeTile.CASHOUT)
    }

    @Test
    fun `reconciliation leaves where the destination reports no totals`() {
        val tapToPay = terminal.copy(capabilities = DestinationCapabilities(supportsTerminalFeatures = false))
        assertThat(tiles(terminal = tapToPay).afterwards).containsExactly(HomeTile.REFUND, HomeTile.HISTORY).inOrder()
    }

    @Test
    fun `switched-off tiles stay out before the terminal status and the store are read`() {
        assertThat(tiles(terminal = TerminalState())).isEqualTo(tiles())
    }

    @Test
    fun `new sale joins the payment tiles while the large tile finishes the setup`() {
        assertThat(tiles(needsSetup = true).payment).containsExactly(HomeTile.NEW_SALE, HomeTile.PRE_AUTH, HomeTile.TOKENS).inOrder()
    }
}
