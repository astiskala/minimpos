package io.minimpos.app.refund

import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.RefundedLine
import io.minimpos.core.codec.RefundQrPayload
import io.minimpos.core.ids.Ids
import io.minimpos.core.money.CurrencySpec
import io.minimpos.core.refund.RefundCalculator
import io.minimpos.core.refund.RefundableLine
import io.minimpos.terminal.client.RefundParams
import io.minimpos.terminal.client.TerminalClient
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** Why a payment cannot be refunded. */
enum class RefundInvalidReason {
    /** The scanned code is not a Mini mPOS refund code, or the sale no longer exists. */
    NOT_A_RECEIPT,

    /**
     * The sale was not approved, or the terminal gave no transaction ID or no time stamp (an XML date-time with a time
     * zone) for it, so a reversal could not refer to it.
     */
    NOT_REFUNDABLE,

    /** Everything has already been refunded from this terminal. */
    FULLY_REFUNDED,

    /**
     * It is a pre-authorisation, which is captured in the Customer Area rather than charged; the app can only cancel it,
     * from history ([RefundablePayment.cancellation]).
     */
    PRE_AUTHORISATION,
}

/** What the operator chose to refund of a [RefundablePayment]. */
sealed interface RefundChoice {
    /** Everything that is left: a full reversal when nothing was refunded before, else the remaining amount. */
    data object Everything : RefundChoice

    /**
     * Items of a sale taken on this terminal; each line's share of its gross total is refunded.
     *
     * @property selection Units to refund per sale line ID; unknown lines are ignored and quantities are capped at what
     *   is left of each line.
     */
    data class Items(
        val selection: Map<Long, Int>,
    ) : RefundChoice

    /**
     * An amount typed in.
     *
     * @property minor The amount in minor units of the payment's currency.
     */
    data class Amount(
        val minor: Long,
    ) : RefundChoice
}

/** Whether a payment can be refunded. */
sealed interface Refundability {
    /**
     * It can.
     *
     * @property payment The payment, with what is left to refund.
     */
    data class Refundable(
        val payment: RefundablePayment,
    ) : Refundability

    /**
     * It cannot.
     *
     * @property reason Why.
     */
    data class NotRefundable(
        val reason: RefundInvalidReason,
    ) : Refundability
}

/**
 * A ready referenced refund: what the refund screen chose, checked and priced by [RefundablePayment.request].
 *
 * @property saleId The local sale being refunded, or null for a payment known only from its receipt QR code.
 * @property originalTransactionId POITransactionID of the payment to refund.
 * @property originalTimestamp Its POITransactionID time stamp, as the terminal expects it back.
 * @property originalReference Merchant reference of the payment, for the refund receipt; null if unknown.
 * @property currency ISO 4217 code of the payment.
 * @property amountMinor Amount to refund in minor units; positive, and the whole remaining amount for [full].
 * @property full A full reversal of the original payment (only when nothing was refunded before), sent without an
 *   amount.
 * @property merchantReference The refund's own merchant reference, such as `R-260930-145811-VQ45`.
 * @property lines The items refunded, for an item refund; empty for an amount or full refund.
 * @property cancellation Whether this full reversal cancels a pre-authorisation ([RefundablePayment.cancellation])
 *   rather than refunding a sale.
 * @throws IllegalArgumentException if [amountMinor] is not positive.
 */
data class RefundStart(
    val saleId: String?,
    val originalTransactionId: String,
    val originalTimestamp: String,
    val originalReference: String?,
    val currency: String,
    val amountMinor: Long,
    val full: Boolean,
    val merchantReference: String,
    val lines: List<RefundedLine> = emptyList(),
    val cancellation: Boolean = false,
) {
    init {
        require(amountMinor > 0) { "Refund amount must be positive" }
    }

    /** The Terminal API reversal: a full one is sent without amount and currency, a partial one in major units. */
    fun params(): RefundParams =
        RefundParams(
            originalTransactionId = originalTransactionId,
            originalTimestamp = originalTimestamp,
            merchantReference = merchantReference,
            amount = if (full) null else CurrencySpec.of(currency).toMajor(amountMinor),
            currency = if (full) null else currency,
        )
}

/**
 * A payment that can be refunded, from the local history or only from its receipt QR code, and the one set of rules
 * for refunding it: whether it can be refunded at all ([check], [find]), what is left, what a [RefundChoice] refunds,
 * the refund request itself ([request]) and the refund QR code printed on the sale's receipt ([qrCode]). It also holds
 * the rule for pre-authorisations, which are never refunded but can be cancelled ([cancellation], [canCancel]).
 *
 * A payment can be refunded when it was approved and the terminal gave a transaction ID and a time stamp that a
 * reversal can send back, and something is left to refund. Pure: callers look the sale up themselves.
 *
 * @property transactionId Its POITransactionID.
 * @property timestamp Its POITransactionID time stamp, as sent back in the reversal request.
 * @property createdAt When it was paid.
 * @property amountMinor The amount paid, in minor units of [currency].
 * @property currency ISO 4217 code of the payment.
 * @property reference Its merchant reference, if known.
 * @property local The sale on this terminal, or null for a payment known only from its QR code (its items and earlier
 *   refunds are then unknown, so it cannot be refunded by item).
 */
class RefundablePayment private constructor(
    val transactionId: String,
    val timestamp: String,
    val createdAt: Instant,
    val amountMinor: Long,
    val currency: String,
    val reference: String?,
    val local: SaleWithLines?,
) {
    /** Already refunded from this terminal; 0 when the sale is not [local]. */
    val refundedMinor: Long get() = local?.sale?.refundedMinor ?: 0

    /** What can still be refunded, never negative. */
    val remainingMinor: Long get() = (amountMinor - refundedMinor).coerceAtLeast(0)

    /** The sale's lines with what is left of each, in cart order; empty when the sale is not [local]. */
    val lines: List<RefundableLine>
        get() = local?.sortedLines.orEmpty().map { RefundableLine(it.id, it.quantity, it.refundedQuantity, it.grossMinor) }

    /** Whether items can be chosen, which needs the [local] sale. */
    val itemsKnown: Boolean get() = local != null

    /** How much [choice] refunds, in minor units; items are apportioned by [RefundCalculator.amountFor]. */
    fun amountFor(choice: RefundChoice): Long =
        when (choice) {
            RefundChoice.Everything -> remainingMinor
            is RefundChoice.Items -> RefundCalculator.amountFor(lines, choice.selection, remainingMinor)
            is RefundChoice.Amount -> choice.minor
        }

    /** Whether [choice] refunds more than 0 and no more than what is left. */
    fun isValid(choice: RefundChoice): Boolean = amountFor(choice) in 1..remainingMinor

    /** [quantity] units of line [lineId] capped at what is left of it; null for a line the sale does not have. */
    fun cappedQuantity(
        lineId: Long,
        quantity: Int,
    ): Int? = lines.firstOrNull { it.lineId == lineId }?.let { quantity.coerceIn(0, it.remainingQuantity) }

    /**
     * The refund of [choice], or null when it is not [isValid]. Its merchant reference is generated from [now] in
     * [zone] with "R" after [referencePrefix] (just "R" without one). Choosing [RefundChoice.Everything] before anything
     * was refunded is a full reversal; refunded items carry their apportioned share of the line.
     */
    fun request(
        choice: RefundChoice,
        referencePrefix: String,
        now: Instant,
        zone: ZoneId,
    ): RefundStart? {
        if (!isValid(choice)) return null
        val prefix = referencePrefix.trim().let { if (it.isEmpty()) "R" else "$it-R" }
        return RefundStart(
            saleId = local?.sale?.id,
            originalTransactionId = transactionId,
            originalTimestamp = timestamp,
            originalReference = reference,
            currency = currency,
            amountMinor = amountFor(choice),
            full = choice == RefundChoice.Everything && refundedMinor == 0L,
            merchantReference = Ids.transactionReference(prefix, now, zone),
            lines = (choice as? RefundChoice.Items)?.let(::refundedLines).orEmpty(),
        )
    }

    private fun refundedLines(choice: RefundChoice.Items): List<RefundedLine> =
        local?.sortedLines.orEmpty().mapNotNull { line ->
            val quantity = choice.selection[line.id]?.takeIf { it > 0 } ?: return@mapNotNull null
            val share =
                RefundCalculator.lineAmount(
                    RefundableLine(line.id, line.quantity, line.refundedQuantity, line.grossMinor),
                    quantity,
                )
            RefundedLine(line.id, line.name, quantity, line.unitPriceMinor, share)
        }

    /** Deciding whether payments can be refunded, and pre-authorisations cancelled. */
    companion object {
        /** Whether the stored sale [record] can be refunded; a pre-authorisation never can. */
        fun check(record: SaleWithLines): Refundability =
            when {
                record.sale.kind == SaleKind.PRE_AUTHORISATION -> Refundability.NotRefundable(RefundInvalidReason.PRE_AUTHORISATION)
                else -> eligible(record)?.let(::refundable) ?: Refundability.NotRefundable(RefundInvalidReason.NOT_REFUNDABLE)
            }

        /**
         * The cancellation of the pre-authorisation [record], or null when it cannot be cancelled: it is not an approved
         * pre-authorisation with the terminal's transaction details, or a cancellation was already accepted. It is a
         * full reversal (no amount, so Adyen releases the whole hold, or refunds it in full if it was captured in the
         * meantime), with a merchant reference generated from [now] in [zone] with "C" after [referencePrefix].
         */
        fun cancellation(
            record: SaleWithLines,
            referencePrefix: String,
            now: Instant,
            zone: ZoneId,
        ): RefundStart? {
            if (record.sale.kind != SaleKind.PRE_AUTHORISATION) return null
            val payment = eligible(record)?.takeIf { it.refundedMinor == 0L } ?: return null
            val prefix = referencePrefix.trim().let { if (it.isEmpty()) "C" else "$it-C" }
            return RefundStart(
                saleId = record.sale.id,
                originalTransactionId = payment.transactionId,
                originalTimestamp = payment.timestamp,
                originalReference = payment.reference,
                currency = payment.currency,
                amountMinor = payment.amountMinor,
                full = true,
                merchantReference = Ids.transactionReference(prefix, now, zone),
                cancellation = true,
            )
        }

        /** Whether the pre-authorisation [record] can be cancelled, as [cancellation] decides. */
        fun canCancel(record: SaleWithLines): Boolean = cancellation(record, "", Instant.EPOCH, ZoneOffset.UTC) != null

        /**
         * Whether the payment to refund can be refunded: the stored sale [record] when there is one (a scanned code of a
         * sale taken on this terminal should be looked up first, so its items and earlier refunds are known), else the
         * payment described by the scanned [qr] code.
         */
        fun find(
            record: SaleWithLines?,
            qr: RefundQrPayload?,
        ): Refundability =
            when {
                record != null -> {
                    check(record)
                }

                qr != null -> {
                    refundable(
                        RefundablePayment(
                            qr.transactionId,
                            TerminalClient.formatTimestamp(qr.timestamp),
                            qr.timestamp,
                            qr.amountMinor,
                            qr.currency,
                            qr.reference,
                            null,
                        ),
                    )
                }

                else -> {
                    Refundability.NotRefundable(RefundInvalidReason.NOT_A_RECEIPT)
                }
            }

        /**
         * The refund QR code content for [record]'s receipt, or null when the sale could never be refunded (a
         * pre-authorisation, or its transaction ID cannot be carried in the code). Printed whether or not something is
         * left to refund.
         */
        fun qrCode(record: SaleWithLines): String? {
            if (record.sale.kind == SaleKind.PRE_AUTHORISATION) return null
            val payment = eligible(record) ?: return null
            // Eligibility already requires a time stamp with a time zone, so it is one instant.
            val instant = checkNotNull(TerminalClient.instantOf(payment.timestamp))
            return runCatching {
                RefundQrPayload(payment.transactionId, instant, payment.amountMinor, payment.currency, payment.reference).encode()
            }.getOrNull()
        }

        private fun eligible(record: SaleWithLines): RefundablePayment? {
            val sale = record.sale
            val transactionId = sale.poiTransactionId
            val timestamp = sale.poiTimestamp?.takeIf { TerminalClient.instantOf(it) != null }
            if (sale.status != SaleStatus.APPROVED || transactionId == null || timestamp == null) return null
            return RefundablePayment(
                transactionId,
                timestamp,
                Instant.ofEpochMilli(sale.createdAt),
                sale.totalMinor,
                sale.currency,
                sale.merchantReference,
                record,
            )
        }

        private fun refundable(payment: RefundablePayment): Refundability =
            if (payment.remainingMinor > 0) {
                Refundability.Refundable(payment)
            } else {
                Refundability.NotRefundable(RefundInvalidReason.FULLY_REFUNDED)
            }
    }
}
