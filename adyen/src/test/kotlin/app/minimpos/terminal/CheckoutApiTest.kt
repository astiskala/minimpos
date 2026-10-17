package app.minimpos.terminal

import app.minimpos.terminal.checkout.AdyenCheckout
import app.minimpos.terminal.checkout.CardChargeRequest
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.CheckoutApi
import app.minimpos.terminal.checkout.CheckoutCredentials
import app.minimpos.terminal.checkout.CheckoutModifications
import app.minimpos.terminal.checkout.CheckoutPaymentLinks
import app.minimpos.terminal.checkout.CheckoutSavedCards
import app.minimpos.terminal.checkout.SavedCardApi
import app.minimpos.terminal.checkout.StoredCardListing
import app.minimpos.terminal.simulator.SimulatedModifications
import app.minimpos.terminal.simulator.SimulatedPaymentLinks
import app.minimpos.terminal.transport.Fault
import app.minimpos.terminal.transport.TerminalEnvironment
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CheckoutApiTest {
    @Test
    fun `one credential gets all three Checkout parts`() {
        val checkout = AdyenCheckout(CheckoutCredentials("key", "Merchant", TerminalEnvironment.TEST, ""))
        assertThat(checkout.modifications).isInstanceOf(CheckoutModifications::class.java)
        assertThat(checkout.links).isInstanceOf(CheckoutPaymentLinks::class.java)
        assertThat(checkout.savedCards).isInstanceOf(CheckoutSavedCards::class.java)
    }

    @Test
    fun `parts assemble into one Checkout API as given`() {
        val modifications = SimulatedModifications()
        val links = SimulatedPaymentLinks()
        val cards =
            object : SavedCardApi {
                override suspend fun list(shopperReference: String) = StoredCardListing.Listed(emptyList())

                override suspend fun charge(
                    request: CardChargeRequest,
                    idempotencyKey: String,
                ) = CardChargeResult.Failed(Fault.Unsupported)
            }
        val checkout = CheckoutApi.of(modifications, links, cards)
        assertThat(checkout.modifications).isSameInstanceAs(modifications)
        assertThat(checkout.links).isSameInstanceAs(links)
        assertThat(checkout.savedCards).isSameInstanceAs(cards)
    }
}
