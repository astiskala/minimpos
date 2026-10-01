package io.minimpos.app.payment

import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleLineEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.repo.ReceiptLinesJson
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.core.cart.CartTotals
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.receipt.CardReceiptLine
import io.minimpos.terminal.client.PaymentParams
import io.minimpos.terminal.client.RecurringModel
import io.minimpos.terminal.client.TransactionKind
import io.minimpos.terminal.parse.ReceiptField

/**
 * Everything checkout collected for one payment.
 *
 * @property totals The priced cart; its gross total is the amount charged.
 * @property currency The currency charged.
 * @property merchantReference Adyen's merchant reference for the payment, such as `260930-145811-VQ45`.
 * @property customerReference Sent as payment metadata when set; only when it is also the shopper reference.
 * @property shopperEmail The shopper's email, for the receipt; sent to Adyen only with [tokenization] that includes it.
 * @property tokenization Set only when the shopper opted in to saving their card.
 */
data class PaymentStart(
    val totals: CartTotals,
    val currency: CurrencySpec,
    val merchantReference: String,
    val customerReference: String?,
    val shopperEmail: String?,
    val tokenization: TokenizationRequest?,
)

/**
 * Saving the shopper's card with the payment (Adyen tokenization).
 *
 * @property shopperReference Adyen's `shopperReference`, which the saved card is filed under.
 * @property recurringProcessingModel How the saved card will be used later.
 * @property includeEmail Whether to send `shopperEmail` with the request.
 */
data class TokenizationRequest(
    val shopperReference: String,
    val recurringProcessingModel: RecurringModel,
    val includeEmail: Boolean,
)

/**
 * Card payments as stored sales ([SaleEntity] with its [SaleLineEntity] items), for the payments' [TransactionLifecycle].
 * The sale gets the cart as priced, the terminal's POIID once the request is sent, and the card, receipt and
 * tokenization details of the answer.
 *
 * @param sales Where sales are stored.
 */
class SaleBook(
    private val sales: SaleRepository,
) : TransactionBook<PaymentStart> {
    override val kind: TransactionKind = TransactionKind.PAYMENT

    override suspend fun open(
        id: String,
        request: PaymentStart,
        serviceId: String,
        createdAt: Long,
    ) {
        val totals = request.totals
        val sale =
            SaleEntity(
                id = id,
                createdAt = createdAt,
                currency = request.currency.code,
                taxMode = totals.mode.name,
                netMinor = totals.amounts.net,
                taxMinor = totals.amounts.tax,
                totalMinor = totals.amounts.gross,
                status = SaleStatus.PENDING,
                merchantReference = request.merchantReference,
                customerReference = request.customerReference,
                shopperReference = request.tokenization?.shopperReference,
                shopperEmail = request.shopperEmail,
                tokenizationRequested = request.tokenization != null,
                serviceId = serviceId,
            )
        sales.createPending(sale, lines(id, totals))
    }

    override fun operation(request: PaymentStart): TerminalOperation {
        val tokenization = request.tokenization
        return TerminalOperation.Pay(
            PaymentParams(
                amount = request.currency.toMajor(request.totals.amounts.gross),
                currency = request.currency.code,
                merchantReference = request.merchantReference,
                shopperReference = tokenization?.shopperReference,
                shopperEmail = request.shopperEmail?.takeIf { tokenization?.includeEmail == true },
                recurringProcessingModel = tokenization?.recurringProcessingModel,
                // The app prints its own combined receipt, including the card details.
                tenderOptions = listOf(RECEIPT_HANDLER),
                metadata = listOfNotNull(request.customerReference?.let { "customerReference" to it }).toMap(),
                requestCardAlias = tokenization != null,
            ),
        )
    }

    override suspend fun sending(
        id: String,
        poiId: String,
    ) {
        sales.get(id)?.let { sales.update(it.sale.copy(poiId = poiId)) }
    }

    override suspend fun settle(
        id: String,
        settlement: Settlement,
    ) {
        val sale = sales.get(id)?.sale ?: return
        val status =
            when (settlement.status) {
                SettlementStatus.SUCCEEDED -> SaleStatus.APPROVED
                SettlementStatus.CANCELLED -> SaleStatus.CANCELLED
                SettlementStatus.DECLINED -> SaleStatus.DECLINED
                SettlementStatus.FAILED -> SaleStatus.FAILED
                SettlementStatus.UNKNOWN -> SaleStatus.UNKNOWN
            }
        val details = settlement.details
        sales.update(
            if (details == null) {
                sale.copy(status = status, message = settlement.message)
            } else {
                sale.copy(
                    status = status,
                    poiTransactionId = details.poiTransactionId,
                    poiTimestamp = details.poiTimestamp,
                    pspReference = details.pspReference,
                    paymentBrand = details.paymentBrand,
                    maskedPan = details.maskedPan,
                    entryMode = details.entryMode,
                    authCode = details.approvalCode,
                    message = settlement.message,
                    errorCondition = details.errorCondition,
                    refusalReason = details.refusalReason,
                    storedPaymentMethodId = details.tokenization?.storedPaymentMethodId,
                    customerReceiptJson = ReceiptLinesJson.encode(details.customerReceipt.map(::toLine)),
                    cashierReceiptJson = ReceiptLinesJson.encode(details.cashierReceipt.map(::toLine)),
                    signatureRequired = details.signatureRequired,
                )
            },
        )
    }

    override suspend fun unsettledServiceId(id: String): String? =
        sales
            .get(id)
            ?.sale
            ?.takeIf { it.status == SaleStatus.UNKNOWN }
            ?.serviceId

    private fun lines(
        saleId: String,
        totals: CartTotals,
    ): List<SaleLineEntity> =
        totals.lines.mapIndexed { index, priced ->
            val line = priced.line
            SaleLineEntity(
                saleId = saleId,
                position = index,
                productId = line.productId,
                name = line.name,
                sku = line.sku,
                unitPriceMinor = line.unitPrice,
                quantity = line.quantity,
                taxName = line.tax.name,
                taxRateMilliPercent = line.tax.rateMilliPercent,
                netMinor = priced.amounts.net,
                taxMinor = priced.amounts.tax,
                grossMinor = priced.amounts.gross,
            )
        }

    /** Terminal API values and conversions shared with refunds. */
    companion object {
        /**
         * The `TenderOption` that makes the terminal leave receipt printing to the app, which prints one combined slip
         * with the card receipt lines instead.
         */
        const val RECEIPT_HANDLER = "ReceiptHandler"

        /** Converts a card receipt field from the terminal into the stored and printed form. */
        fun toLine(field: ReceiptField) = CardReceiptLine(field.key, field.name, field.value, field.bold)
    }
}
