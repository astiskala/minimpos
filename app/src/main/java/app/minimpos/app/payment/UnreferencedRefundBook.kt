package app.minimpos.app.payment

import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.UnreferencedRefundReason
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.settings.UnreferencedRefundSettings
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.client.PaymentParams
import app.minimpos.terminal.client.TransactionKind

/**
 * One unreferenced card refund as staff reviewed it: the amount returned to whichever card the shopper presents, with
 * no original payment. Frozen before anything is stored or sent; retries and status checks never rebuild it.
 *
 * @property amountMinor What is refunded, in minor units of [currency]; positive.
 * @property currency ISO 4217 code.
 * @property merchantReference The refund's own `SaleTransactionID`, `R-…` after the configured prefix.
 * @property reason Why staff made it, recorded with the refund.
 * @property confirmation The [UnreferencedRefundSettings.identity] it was reviewed under; the send is refused when the
 *   stored configuration differs.
 * @throws IllegalArgumentException if [amountMinor] is not positive.
 */
data class UnreferencedRefundStart(
    val amountMinor: Long,
    val currency: String,
    val merchantReference: String,
    val reason: UnreferencedRefundReason,
    val confirmation: String,
) {
    init {
        require(amountMinor > 0) { "Refund amount must be positive" }
    }

    /** This refund as the new PENDING record [id] with [serviceId], opened at [createdAt] before anything is sent. */
    internal fun pendingRefund(
        id: String,
        createdAt: Long,
        serviceId: String,
    ): RefundEntity =
        RefundEntity(
            id = id,
            saleId = null,
            createdAt = createdAt,
            merchantReference = merchantReference,
            originalTransactionId = null,
            originalTimestamp = null,
            originalReference = null,
            currency = currency,
            amountMinor = amountMinor,
            full = false,
            status = RefundStatus.PENDING,
            serviceId = serviceId,
            // No recorded payment's surcharge or tip is returned.
            surchargeMinor = 0,
            tipMinor = 0,
            unreferenced = true,
            unreferencedReason = reason,
        )
}

/**
 * Unreferenced card refunds as stored refunds ([RefundEntity.unreferenced]) for their own [TransactionLifecycle]. The
 * business record is a refund, with refund history, receipts and accounting; the wire message is a Terminal API
 * `Payment` with `PaymentType=Refund`, so its [kind] is [TransactionKind.PAYMENT] and a missing answer is checked as a
 * payment. Adyen confirms the refund only asynchronously: an accepted request is [RefundStatus.REQUESTED], keeping a
 * synchronous `acquirerResponseCode` verbatim, and cancelled, declined or unsent ones are [RefundStatus.FAILED].
 *
 * @param refunds Where refunds are stored.
 */
class UnreferencedRefundBook(
    private val refunds: RefundRepository,
) : TransactionBook<UnreferencedRefundStart> {
    override val kind: TransactionKind = TransactionKind.PAYMENT

    override suspend fun open(
        id: String,
        request: UnreferencedRefundStart,
        serviceId: String,
        createdAt: Long,
    ): Boolean = refunds.create(request.pendingRefund(id, createdAt, serviceId))

    override fun operation(request: UnreferencedRefundStart): TerminalOperation =
        TerminalOperation.Pay(
            PaymentParams(
                amountMinor = request.amountMinor,
                currency = request.currency,
                merchantReference = request.merchantReference,
                appPrintsReceipts = true,
                unreferencedRefund = true,
            ),
            unreferencedIdentity = request.confirmation,
        )

    override suspend fun recordContext(
        id: String,
        context: PaymentContext,
    ) = refunds.recordContext(id, context)

    override suspend fun context(id: String) = refunds.get(id)?.context

    override suspend fun settle(
        id: String,
        settlement: Settlement,
    ): Boolean {
        val status =
            when (settlement.status) {
                SettlementStatus.SUCCEEDED -> RefundStatus.REQUESTED
                SettlementStatus.UNKNOWN -> RefundStatus.UNKNOWN
                SettlementStatus.CANCELLED, SettlementStatus.DECLINED, SettlementStatus.FAILED -> RefundStatus.FAILED
            }
        return refunds.settle(id, status, settlement.message, settlement.details, settlement.reason)
    }

    override suspend fun unsettledServiceId(id: String): String? =
        refunds.get(id)?.takeIf { it.unreferenced && it.status == RefundStatus.UNKNOWN }?.serviceId
}
