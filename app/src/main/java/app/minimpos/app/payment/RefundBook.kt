package app.minimpos.app.payment

import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.repo.ReceiptLinesJson
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.history.RefundStart
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.client.TransactionKind

/**
 * Referenced refunds (Terminal API reversals) as stored refunds ([RefundEntity]), for the refunds'
 * [TransactionLifecycle]. Adyen confirms refunds asynchronously; the terminal only accepts them, which is stored as
 * [RefundStatus.REQUESTED]. Refunds that are cancelled, declined or never sent are [RefundStatus.FAILED]. Cancellations
 * of held payments are stored the same way, marked as [RefundEntity.cancellation].
 *
 * @param refunds Where refunds are stored; completing an accepted one also records it on its sale (what has been
 *   refunded, or that the hold was cancelled).
 */
class RefundBook(
    private val refunds: RefundRepository,
) : TransactionBook<RefundStart> {
    override val kind: TransactionKind = TransactionKind.REFUND

    override suspend fun open(
        id: String,
        request: RefundStart,
        serviceId: String,
        createdAt: Long,
    ) {
        refunds.create(request.pendingRefund(id, createdAt, serviceId = serviceId))
    }

    override suspend fun recordContext(
        id: String,
        context: PaymentContext,
    ) = refunds.recordContext(id, context)

    override suspend fun context(id: String) = refunds.get(id)?.context

    override fun expectedContext(request: RefundStart) = request.expectedContext

    override fun operation(request: RefundStart): TerminalOperation =
        TerminalOperation.Refund(request.params(), OperationAmount(request.amountMinor, request.currency))

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

    override suspend fun unsettledServiceId(id: String): String? = refunds.get(id)?.takeIf { it.status == RefundStatus.UNKNOWN }?.serviceId
}

/**
 * This refund as the new PENDING record [id], opened at [createdAt] (epoch ms) before anything is sent: one record shape
 * for both ways a refund is sent. A terminal refund carries its [serviceId]; a Checkout refund ([checkout]) keeps the
 * original payment's context it must be sent in.
 */
internal fun RefundStart.pendingRefund(
    id: String,
    createdAt: Long,
    serviceId: String? = null,
    checkout: Boolean = false,
): RefundEntity =
    RefundEntity(
        id = id,
        saleId = saleId,
        createdAt = createdAt,
        merchantReference = merchantReference,
        originalTransactionId = originalTransactionId,
        originalTimestamp = originalTimestamp,
        originalReference = originalReference,
        currency = currency,
        amountMinor = amountMinor,
        full = full,
        status = RefundStatus.PENDING,
        serviceId = serviceId,
        linesJson = ReceiptLinesJson.encodeRefunded(lines),
        cancellation = cancellation,
        context = expectedContext.takeIf { checkout },
        checkoutRefund = checkout,
    )
