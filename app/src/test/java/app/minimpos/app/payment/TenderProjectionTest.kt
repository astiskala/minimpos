package app.minimpos.app.payment

import app.minimpos.app.data.settings.PaymentSettings
import app.minimpos.app.data.settings.ReceiptTipping
import app.minimpos.core.cart.Cart
import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.tax.TaxMode
import app.minimpos.terminal.client.CardEntryOverride
import app.minimpos.terminal.client.TerminalTipOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** What each tender keeps of a checkout is one table: its start and its ceremony read the same [Tender.carries]. */
class TenderProjectionTest {
    private val start =
        PaymentStart(
            totals = Cart().totals(TaxMode.INCLUSIVE),
            currency = CurrencySpec.of("AUD"),
            merchantReference = "260930-145811-VQ45",
            customerReference = null,
            shopperEmail = null,
            tokenization = TokenizationRequest,
            shopperReference = "shopper",
            terminalTip = TerminalTipOptions(),
            allowPartialAuthorisation = true,
            askGiving = true,
            cardEntryOverride = CardEntryOverride.MOTO,
        )

    @Test
    fun `each tender start keeps exactly the card features its tender carries`() {
        assertThat(start.carrying(Tender.TERMINAL.carries)).isEqualTo(start)
        Tender.entries.forEach { tender ->
            assertThat(start.carrying(tender.carries).cardFeatures).isEqualTo(start.cardFeatures intersect tender.carries)
        }
        assertThat(start.forSavedCard().cardFeatures).isEmpty()
        assertThat(start.carrying(Checkout.SPLIT_CARRIES).cardFeatures).isEqualTo(start.cardFeatures intersect Checkout.SPLIT_CARRIES)
    }

    @Test
    fun `a tender's ceremony confirms exactly the tip and donation its start leaves out`() {
        val checkout =
            Checkout(
                form = CheckoutForm(askGratuity = true, askGiving = true),
                payment = PaymentSettings(terminalTipping = ReceiptTipping.DEFAULT_ON, askGiving = ReceiptTipping.DEFAULT_ON),
            )
        assertThat(checkout.ceremony(Tender.TERMINAL).drops).isEmpty()
        listOf(Tender.LINK, Tender.WALLET, Tender.TOKEN).forEach {
            assertThat(checkout.ceremony(it).drops).containsExactly(DroppedOption.TIP, DroppedOption.DONATION)
        }
    }
}
