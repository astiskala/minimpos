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
}
