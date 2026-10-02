package io.minimpos.app.payment

import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.terminal.checkout.ModificationAmount
import io.minimpos.terminal.checkout.PaymentLinkLineItem
import io.minimpos.terminal.checkout.PaymentLinkRequest
import java.time.Instant
import java.util.Locale

/**
 * A sale to be paid through an Adyen payment link instead of on the terminal, as [Checkout.linkStart] makes it.
 *
 * @property payment What checkout collected, as for the terminal (never taken for tipping on the receipt).
 * @property shopperReference The shopper reference to send with the link, whether or not the card is to be saved
 *   ([PaymentStart.tokenization] decides that); null when checkout has none.
 * @property expiresAt When the link stops working.
 */
data class PaymentLinkStart(
    val payment: PaymentStart,
    val shopperReference: String?,
    val expiresAt: Instant,
) {
    /** This link as the new PENDING sale [id], started at [createdAt] (epoch ms), before Adyen is called. */
    fun pendingSale(
        id: String,
        createdAt: Long,
    ): SaleEntity =
        SaleBook.pendingSale(id, payment, createdAt).copy(
            shopperReference = payment.tokenization?.shopperReference ?: shopperReference,
            paymentLink = true,
            paymentLinkExpiresAt = expiresAt.toEpochMilli(),
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

    private val COUNTRY = Regex("[A-Z]{2}")

    /**
     * The link request for [record], a sale opened for a payment link: its amount, reference, expiry and items, the
     * shopper's email and reference, saving the card for [recurringProcessingModel] when the shopper asked for it, the
     * customer reference as metadata, and the payment page's language and country from [locale].
     *
     * @throws IllegalArgumentException if [record] was not opened for a payment link.
     */
    fun request(
        record: SaleWithLines,
        recurringProcessingModel: String,
        locale: Locale,
    ): PaymentLinkRequest {
        val sale = record.sale
        val expiresAt = requireNotNull(sale.paymentLinkExpiresAt?.takeIf { sale.paymentLink }) { "Not a payment link sale" }
        return PaymentLinkRequest(
            reference = sale.merchantReference,
            amount = ModificationAmount(sale.currency, sale.totalMinor),
            expiresAt = Instant.ofEpochMilli(expiresAt),
            lineItems = lineItems(record.sortedLines),
            shopperEmail = sale.shopperEmail,
            shopperReference = sale.shopperReference,
            recurringProcessingModel = recurringProcessingModel.takeIf { sale.tokenizationRequested },
            shopperLocale = locale.toLanguageTag().takeIf { locale.language.isNotEmpty() },
            countryCode = locale.country.takeIf { COUNTRY.matches(it) },
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
