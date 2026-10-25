package app.minimpos.core.receipt

import app.minimpos.core.money.CurrencySpec
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.core.tax.TaxAmounts
import app.minimpos.core.tax.TaxMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class TenderReceiptTest {
    private val builder =
        ReceiptBuilder(
            ReceiptBranding(),
            ReceiptOptions(),
            ReceiptLabels(),
            MoneyFormatter(CurrencySpec.of("AUD"), Locale.forLanguageTag("en-AU")),
        )
    private val sale =
        SaleReceipt(
            "SPLIT",
            null,
            "25/10/2026",
            TaxMode.INCLUSIVE,
            emptyList(),
            TaxAmounts(3000, 0, 3000),
            emptyList(),
            true,
            emptyList(),
            null,
            false,
        )

    private fun rows(receipt: SaleReceipt) = builder.sale(receipt).elements.filterIsInstance<ReceiptElement.Row>()

    @Test
    fun `a single card prints requested and returned plan facts separately from verbatim Adyen fields`() {
        val notes = listOf("Requested: 6 monthly payments", "Returned: Instalment · 6 · Issuer plan")
        val receipt =
            sale.copy(
                tenders = listOf(ReceiptTender(ReceiptTenderKind.CARD, "VISA", 3000)),
                cardReceipt = listOf(CardReceiptLine(name = "productType", value = "CREDIT 6 MESES")),
                cardPlans = notes,
            )
        val elements = builder.sale(receipt).elements
        assertThat(elements.filterIsInstance<ReceiptElement.Text>().map { it.text }).containsAtLeastElementsIn(notes).inOrder()
        assertThat(elements).contains(ReceiptElement.Row("productType", "CREDIT 6 MESES"))
        assertThat(elements.count { it == ReceiptElement.Text(notes.first()) }).isEqualTo(1)
        assertThat(builder.sale(receipt.copy(approved = false, tenders = emptyList())).elements)
            .containsAtLeastElementsIn(notes.map { ReceiptElement.Text(it) })
    }

    @Test
    fun `split receipt lists each tender with cash received and change`() {
        val receipt =
            sale.copy(
                tenders =
                    listOf(
                        ReceiptTender(ReceiptTenderKind.CARD, "VISA **** 1111", 1000),
                        ReceiptTender(ReceiptTenderKind.CARD, null, 500),
                        ReceiptTender(ReceiptTenderKind.CASH, null, 1500, receivedMinor = 2000, changeMinor = 500),
                    ),
            )
        assertThat(rows(receipt))
            .containsAtLeast(
                ReceiptElement.Row("Card VISA **** 1111", "$10.00"),
                ReceiptElement.Row("Card", "$5.00"),
                ReceiptElement.Row("Cash", "$15.00"),
                ReceiptElement.Row("Cash received", "$20.00"),
                ReceiptElement.Row("Change", "$5.00"),
            ).inOrder()
    }

    @Test
    fun `a single card has no breakdown while a balance due is shown`() {
        val single = sale.copy(tenders = listOf(ReceiptTender(ReceiptTenderKind.CARD, "MC", 1000)), balanceDueMinor = 2000)
        val shown = rows(single)
        assertThat(shown.map { it.left }).doesNotContain("Card MC")
        assertThat(shown).contains(ReceiptElement.Row("BALANCE DUE", "$20.00", TextStyle.BOLD))
        assertThat(rows(sale.copy(balanceDueMinor = 0)).map { it.left }).doesNotContain("BALANCE DUE")
        val cashOnly = rows(sale.copy(tenders = listOf(ReceiptTender(ReceiptTenderKind.CASH, null, 3000, 3000, 0))))
        assertThat(cashOnly).contains(ReceiptElement.Row("Cash", "$30.00"))
    }

    @Test
    fun `a gift card redemption is listed even alone, with its provider and digits`() {
        val gift = rows(sale.copy(tenders = listOf(ReceiptTender(ReceiptTenderKind.GIFT_CARD, "SVS **** 0042", 3000))))
        assertThat(gift).contains(ReceiptElement.Row("Gift card SVS **** 0042", "$30.00"))
        val bare = rows(sale.copy(tenders = listOf(ReceiptTender(ReceiptTenderKind.GIFT_CARD, null, 3000))))
        assertThat(bare).contains(ReceiptElement.Row("Gift card", "$30.00"))
    }

    @Test
    fun `a meal voucher is listed even alone, beside the card that paid the rest, with its voucher facts`() {
        val notes = listOf("Meal voucher: Titres-restaurant", "Not refundable")
        val receipt =
            sale.copy(
                tenders =
                    listOf(
                        ReceiptTender(ReceiptTenderKind.MEAL_VOUCHER, "MC **** 0009", 2500),
                        ReceiptTender(ReceiptTenderKind.CARD, "VISA", 500),
                    ),
                cardPlans = notes,
            )
        val elements = builder.sale(receipt).elements
        assertThat(elements).containsAtLeast(
            ReceiptElement.Row("Meal voucher MC **** 0009", "$25.00"),
            ReceiptElement.Row("Card VISA", "$5.00"),
        )
        assertThat(elements).containsAtLeastElementsIn(notes.map { ReceiptElement.Text(it) })
        val alone = rows(sale.copy(tenders = listOf(ReceiptTender(ReceiptTenderKind.MEAL_VOUCHER, null, 3000))))
        assertThat(alone).contains(ReceiptElement.Row("Meal voucher", "$30.00"))
    }

    @Test
    fun `a stored-value receipt lists its amounts, balance, outcome and every Adyen receipt`() {
        val receipt =
            GiftCardReceipt(
                title = "GIFT CARD · Load",
                reference = "G-1",
                dateTime = "25/10/2026",
                card = "SVS **** 0042",
                lines = listOf(GiftCardReceiptLine("Card payment", 1500), GiftCardReceiptLine("Load", 1500, bold = true)),
                balanceMinor = 2000,
                standingNote = "Needs attention",
                cardReceipts = listOf(listOf(CardReceiptLine(name = "APPROVED")), listOf(CardReceiptLine(name = "Tender", value = "T1"))),
            )
        val elements = builder.giftCard(receipt).elements
        assertThat(elements)
            .containsAtLeast(
                ReceiptElement.Text("GIFT CARD · Load", Align.CENTER, TextStyle.BOLD),
                ReceiptElement.Row("Reference", "G-1"),
                ReceiptElement.Row("Gift card", "SVS **** 0042"),
                ReceiptElement.Row("Card payment", "$15.00"),
                ReceiptElement.Row("Load", "$15.00", TextStyle.BOLD),
                ReceiptElement.Row("Balance", "$20.00"),
                ReceiptElement.Text("Needs attention", Align.CENTER, TextStyle.BOLD),
                ReceiptElement.Row("Tender", "T1"),
            ).inOrder()
        val plain =
            ReceiptBuilder(
                ReceiptBranding(),
                ReceiptOptions(showReferences = false),
                ReceiptLabels(),
                MoneyFormatter(CurrencySpec.of("AUD"), Locale.forLanguageTag("en-AU")),
            ).giftCard(receipt.copy(card = null, balanceMinor = null, standingNote = null, cardReceipts = emptyList()))
                .elements
        assertThat(plain.filterIsInstance<ReceiptElement.Row>().map { it.left }).containsExactly("Date", "Card payment", "Load").inOrder()
    }
}
