package io.minimpos.app.payment

import com.google.common.truth.Truth.assertThat
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.core.tax.TaxAmounts
import io.minimpos.terminal.checkout.ModificationAmount
import io.minimpos.terminal.checkout.PaymentLinkLineItem
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** What Adyen is sent for a payment link, made from the stored sale alone. */
class PaymentLinkRequestsTest {
    private val expiresAt = Instant.parse("2026-10-03T09:30:00Z")
    private val sale =
        SaleEntity(
            id = "s1",
            createdAt = 1,
            currency = "AUD",
            taxMode = "INCLUSIVE",
            netMinor = 1_118,
            taxMinor = 82,
            totalMinor = 1_200,
            status = SaleStatus.PENDING,
            merchantReference = "261002-093000-AB12",
            customerReference = "CUST-1",
            shopperReference = "CUST-1",
            shopperEmail = "sam@example.com",
            paymentLink = true,
            paymentLinkExpiresAt = expiresAt.toEpochMilli(),
        )

    private fun line(
        position: Int,
        name: String,
        quantity: Int,
        amounts: TaxAmounts,
        rate: Int = 10_000,
    ) = SaleLineEntity(
        position.toLong(),
        "s1",
        position,
        null,
        name,
        null,
        amounts.gross / quantity,
        quantity,
        "GST",
        rate,
        amounts.net,
        amounts.tax,
        amounts.gross,
    )

    private val lines = listOf(line(1, "Muffin", 1, TaxAmounts(300, 0, 300), rate = 0), line(0, "Flat white", 2, TaxAmounts(818, 82, 900)))

    @Test
    fun `the request carries the amount, expiry, items, shopper, metadata and the page's language`() {
        val request = PaymentLinkRequests.request(SaleWithLines(sale, lines), "CardOnFile", Locale.forLanguageTag("en-AU"))
        assertThat(request.reference).isEqualTo("261002-093000-AB12")
        assertThat(request.amount).isEqualTo(ModificationAmount("AUD", 1_200))
        assertThat(request.expiresAt).isEqualTo(expiresAt)
        assertThat(request.lineItems.map { it.description }).containsExactly("Flat white", "Muffin").inOrder()
        assertThat(request.shopperEmail).isEqualTo("sam@example.com")
        assertThat(request.shopperReference).isEqualTo("CUST-1")
        // Saving the card is only offered when the shopper asked for it at checkout.
        assertThat(request.recurringProcessingModel).isNull()
        assertThat(request.shopperLocale).isEqualTo("en-AU")
        assertThat(request.countryCode).isEqualTo("AU")
        assertThat(request.metadata).containsExactly("customerReference", "CUST-1")

        val saving =
            PaymentLinkRequests.request(
                SaleWithLines(sale.copy(tokenizationRequested = true), lines),
                "CardOnFile",
                Locale.JAPANESE,
            )
        assertThat(saving.recurringProcessingModel).isEqualTo("CardOnFile")
        assertThat(saving.shopperLocale).isEqualTo("ja")
        assertThat(saving.countryCode).isNull()
        val bare = PaymentLinkRequests.request(SaleWithLines(sale.copy(customerReference = null), emptyList()), "CardOnFile", Locale.ROOT)
        assertThat(bare.metadata).isEmpty()
        assertThat(bare.shopperLocale).isNull()
        assertThat(bare.lineItems).isEmpty()
    }

    @Test
    fun `items are per unit when the line divides evenly, else one item for the line, and always add up`() {
        val items =
            PaymentLinkRequests.lineItems(
                listOf(
                    line(0, "Flat white", 2, TaxAmounts(818, 82, 900)),
                    line(1, "Cookie", 3, TaxAmounts(909, 91, 1_000)),
                    line(2, "Tea", 1, TaxAmounts(364, 36, 400), 8_875),
                ),
            )
        assertThat(items)
            .containsExactly(
                PaymentLinkLineItem("1", "Flat white", 2, 450, 409, 41, 1_000),
                PaymentLinkLineItem("2", "3 × Cookie", 1, 1_000, 909, 91, 1_000),
                PaymentLinkLineItem("3", "Tea", 1, 400, 364, 36, 888),
            ).inOrder()
        assertThat(items.sumOf { it.amountIncludingTax * it.quantity }).isEqualTo(2_300)
    }

    @Test
    fun `a sale not opened for a payment link has no request`() {
        assertThrows(IllegalArgumentException::class.java) {
            PaymentLinkRequests.request(SaleWithLines(sale.copy(paymentLink = false), lines), "CardOnFile", Locale.ROOT)
        }
    }
}
