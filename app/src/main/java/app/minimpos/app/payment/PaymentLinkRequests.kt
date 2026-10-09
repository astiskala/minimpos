package app.minimpos.app.payment

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.core.money.PaymentContext
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.PaymentLinkLineItem
import app.minimpos.terminal.checkout.PaymentLinkRequest
import java.time.Instant
import java.util.Locale

/**
 * A sale to be paid through an Adyen payment link instead of on the terminal, as [Checkout.linkStart] makes it.
 *
 * @property payment What checkout collected, as for the terminal (never taken for tipping on the receipt), shopper
 *   reference included.
 * @property expiresAt When the link stops working.
 */
data class PaymentLinkStart(
    val payment: PaymentStart,
    val expiresAt: Instant,
) {
    /** This link as the new PENDING sale [id], started at [createdAt] (epoch ms), before Adyen is called. */
    fun pendingSale(
        id: String,
        createdAt: Long,
        context: PaymentContext? = null,
        locale: Locale = Locale.ROOT,
    ): SaleEntity =
        SaleBook.pendingSale(id, payment, createdAt).copy(
            paymentLink = true,
            paymentLinkExpiresAt = expiresAt.toEpochMilli(),
            context = context,
            linkLocale = locale.toLanguageTag().takeIf { locale.language.isNotEmpty() },
            linkCountry = locale.country.takeIf { it.matches(Regex("[A-Z]{2}")) },
            linkRecurringModel = payment.tokenization?.recurringProcessingModel?.value,
        )
}

/**
 * What Adyen is sent to create the payment link of a stored sale: always made from the stored sale, so sending it again
 * after an unknown outcome (with the same idempotency key) sends the same request. Pure, so it is tested with plain
 * JUnit.
 */
object PaymentLinkRequests {
    /** Thousandths of a percent per basis point (hundredth of a percent), Adyen's unit for `taxPercentage`. */
    private const val MILLI_PER_BASIS_POINT = 10

    /**
     * The link request for [record], a sale opened for a payment link: its amount, reference, expiry and items, the
     * shopper's email and reference, the saved recurring model, the customer reference as metadata, and the frozen
     * payment-page language and country.
     *
     * @throws IllegalArgumentException if [record] was not opened for a payment link.
     */
    fun request(record: SaleWithLines): PaymentLinkRequest {
        val sale = record.sale
        val expiresAt = requireNotNull(sale.paymentLinkExpiresAt?.takeIf { sale.paymentLink }) { "Not a payment link sale" }
        return PaymentLinkRequest(
            reference = sale.merchantReference,
            amount = ModificationAmount(sale.currency, sale.totalMinor),
            expiresAt = Instant.ofEpochMilli(expiresAt),
            lineItems = lineItems(record.sortedLines),
            shopperEmail = sale.shopperEmail,
            shopperReference = sale.shopperReference,
            recurringProcessingModel = sale.linkRecurringModel.takeIf { sale.tokenizationRequested },
            shopperLocale = sale.linkLocale,
            countryCode = sale.linkCountry,
            metadata = listOfNotNull(sale.customerReference?.let { "customerReference" to it }).toMap(),
        )
    }

    /**
     * [lines] as link items, numbered from 1 in cart order. Adyen's amounts are for one unit, so a line whose totals do
     * not divide evenly by its quantity becomes one item named "quantity × name" with the line's totals; either way the
     * items add up to exactly what the sale charges.
     */
    fun lineItems(lines: List<SaleLineEntity>): List<PaymentLinkLineItem> =
        lines.mapIndexed { index, line ->
            val quantity = line.quantity.toLong()
            val perUnit = listOf(line.netMinor, line.taxMinor, line.grossMinor).all { it % quantity == 0L }
            val divisor = if (perUnit) quantity else 1L
            PaymentLinkLineItem(
                id = (index + 1).toString(),
                description = if (perUnit) line.name else "${line.quantity} × ${line.name}",
                quantity = divisor.toInt(),
                amountIncludingTax = line.grossMinor / divisor,
                amountExcludingTax = line.netMinor / divisor,
                taxAmount = line.taxMinor / divisor,
                taxPercentage = (line.taxRateMilliPercent + MILLI_PER_BASIS_POINT / 2) / MILLI_PER_BASIS_POINT.toLong(),
            )
        }
}
