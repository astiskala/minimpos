package io.minimpos.app.payment

import io.minimpos.app.data.db.RefundEntity
import io.minimpos.app.data.db.RefundStatus
import io.minimpos.app.data.repo.ReceiptLinesJson
import io.minimpos.app.data.repo.RefundRepository
import io.minimpos.app.refund.RefundStart
import io.minimpos.terminal.client.TransactionKind

/**
 * Referenced refunds (Terminal API reversals) as stored refunds ([RefundEntity]), for the refunds'
 * [TransactionLifecycle]. Adyen confirms refunds asynchronously; the terminal only accepts them, which is stored as
 * [RefundStatus.REQUESTED]. Refunds that are cancelled, declined or never sent are [RefundStatus.FAILED].
 *
 * @param refunds Where refunds are stored; completing an accepted one also updates what has been refunded of the sale.
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
        refunds.create(
            RefundEntity(
                id = id,
                saleId = request.saleId,
                createdAt = createdAt,
                merchantReference = request.merchantReference,
                originalTransactionId = request.originalTransactionId,
                originalTimestamp = request.originalTimestamp,
                originalReference = request.originalReference,
                currency = request.currency,
                amountMinor = request.amountMinor,
                full = request.full,
                status = RefundStatus.PENDING,
                serviceId = serviceId,
                linesJson = ReceiptLinesJson.encodeRefunded(request.lines),
            ),
        )
    }

    override fun operation(request: RefundStart): TerminalOperation = TerminalOperation.Refund(request.params())

    override suspend fun settle(
        id: String,
        settlement: Settlement,
    ) {
        val refund = refunds.get(id) ?: return
        val details = settlement.details
        refunds.complete(
            refund.copy(
                status =
                    when (settlement.status) {
                        SettlementStatus.SUCCEEDED -> RefundStatus.REQUESTED
                        SettlementStatus.UNKNOWN -> RefundStatus.UNKNOWN
                        SettlementStatus.CANCELLED, SettlementStatus.DECLINED, SettlementStatus.FAILED -> RefundStatus.FAILED
                    },
                pspReference = details?.pspReference ?: refund.pspReference,
                message = settlement.message,
                customerReceiptJson =
                    details?.let { ReceiptLinesJson.encode(it.customerReceipt.map(SaleBook::toLine)) } ?: refund.customerReceiptJson,
            ),
        )
    }

    override suspend fun unsettledServiceId(id: String): String? = refunds.get(id)?.takeIf { it.status == RefundStatus.UNKNOWN }?.serviceId
}
