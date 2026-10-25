package app.minimpos.app.data.repo

import androidx.room.withTransaction
import app.minimpos.app.data.db.AppDatabase
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.TenderKind
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.voucher.MealVoucherScheme
import app.minimpos.terminal.client.TransactionDetails
import kotlinx.coroutines.flow.Flow

/**
 * Stored card payments ([SaleEntity] with its [SaleLineEntity] items), and every change to them after they were opened.
 *
 * A sale is created as PENDING before the terminal is called ([createPending]); after that it only changes through
 * [record], which applies a [SaleEvent] (what happened to it, which decides the fields written), and [applyRefund] for
 * an accepted refund or cancellation, which also changes its lines. These are the only writes to one sale and its
 * lines. Each reads and writes the sale in one transaction and does nothing when the sale no longer exists (for
 * example after pruning).
 * Refunds are stored (and listed per sale) by [RefundRepository]; the history list and the housekeeping of whole tables
 * (settling interrupted transactions at startup, pruning and clearing) are in [HistoryRepository]. All functions are
 * main-safe (Room runs them on its own executor).
 */
class SaleRepository(
    private val db: AppDatabase,
    /** Epoch milliseconds stamped at persistence; tests can supply a fixed clock. */
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.saleDao()
    private val observations = RecordObservations(source = dao::observeSale)

    /**
     * Inserts a new [sale] (normally PENDING) with its [lines] in one transaction, and the [tender] row it is about to
     * send, so the attempt's identity, requested amount and allocation exist before anything is sent.
     */
    suspend fun createPending(
        sale: SaleEntity,
        lines: List<SaleLineEntity>,
        tender: SaleTenderEntity? = null,
    ) = db.withTransaction {
        dao.insert(sale)
        dao.insertLines(lines)
        if (tender != null) dao.upsertTender(tender)
    }

    /**
     * Opens the next card attempt [event] of partially paid sale [id] with its [allocation] (redeeming [giftCard], a
     * provider name, or paying from a [mealVoucher] of that market, when not null), storing the sale's new identity and
     * a PENDING tender row in one transaction. Returns false, storing nothing, when the sale is missing, its balance
     * changed since the review, a tender is unresolved, or the amount or allocation is not acceptable, including a
     * voucher amount beyond what its market may pay ([TenderBalance.acceptsVoucher]).
     */
    suspend fun openTender(
        id: String,
        event: SaleEvent.TenderOpened,
        allocation: TenderAllocation?,
        createdAt: Long,
        giftCard: String? = null,
        mealVoucher: MealVoucherScheme? = null,
    ): Boolean =
        db.withTransaction {
            val record = dao.sale(id) ?: return@withTransaction false
            val sale = record.sale
            val balance = TenderBalance.of(sale, record.lines, record.tenders)
            val acceptable =
                if (mealVoucher ==
                    null
                ) {
                    balance.accepts(event.amountMinor, allocation)
                } else {
                    balance.acceptsVoucher(event.amountMinor, mealVoucher)
                }
            if (!sale.acceptsTender(event.amountMinor, event.paidMinor) || !acceptable) {
                return@withTransaction false
            }
            dao.update(sale.after(event))
            dao.upsertTender(
                openedTender(sale.id, record.tenders.size + 1, event.amountMinor, allocation, createdAt)
                    .copy(serviceId = event.serviceId, context = sale.context, giftCard = giftCard, mealVoucher = mealVoucher?.name),
            )
            true
        }

    /**
     * Records confirmed [cash] on partially paid sale [id] with its [allocation] in [environment] (see
     * [SaleTenderEntity.environment]): an approved cash tender and the sale's new paid amount in one transaction.
     * Returns false, storing nothing, for a missing sale, a changed balance, an unresolved tender or an unacceptable
     * amount or allocation.
     */
    suspend fun recordCash(
        id: String,
        cash: SaleEvent.CashTendered,
        allocation: TenderAllocation?,
        environment: String?,
    ): Boolean =
        db.withTransaction {
            val record = dao.sale(id) ?: return@withTransaction false
            val sale = record.sale
            val amount = cash.cash.amountMinor
            val balance = TenderBalance.of(sale, record.lines, record.tenders)
            if (!sale.acceptsTender(amount, cash.paidMinor) || !balance.accepts(amount, allocation)) return@withTransaction false
            val stamped = cash.stamped(now()) as SaleEvent.CashTendered
            dao.update(sale.after(stamped))
            dao.upsertTender(cashTender(sale.id, record.tenders.size + 1, stamped, allocation, environment))
            true
        }

    /**
     * Stores the new [sale] paid first with confirmed [cash] (its [lines] as sold and an approved cash tender with its
     * [allocation]) in one transaction, recorded in [environment]. The sale is approved for what the cash covers,
     * and partially paid while a balance remains; no card is saved by cash.
     */
    suspend fun createCash(
        sale: SaleEntity,
        lines: List<SaleLineEntity>,
        cash: SaleEvent.CashTendered,
        allocation: TenderAllocation?,
        environment: String?,
    ) = db.withTransaction {
        val stamped = cash.stamped(now()) as SaleEvent.CashTendered
        val amount = stamped.cash.amountMinor
        require(amount in 1..sale.totalMinor) { "Cash exceeds the sale" }
        dao.insert(
            sale.copy(
                status = SaleStatus.APPROVED,
                tokenizationRequested = false,
                paidMinor = amount,
                terminalPaidMinor = amount,
                surchargeMinor = 0,
                cashBackMinor = 0,
                tenderRequestedMinor = amount,
                processedAt = stamped.processedAt,
            ),
        )
        dao.insertLines(lines)
        dao.upsertTender(cashTender(sale.id, 1, stamped, allocation, environment))
    }

    /** Returns the sale with ID [id] and its lines, or null if there is none (for example after pruning). */
    suspend fun get(id: String): SaleWithLines? = dao.sale(id)

    /** Observes the sale with ID [id] and its lines; emits null while there is none. */
    fun observe(id: String): Flow<SaleWithLines?> = observations.observe(id)

    /**
     * Returns the sale whose POITransactionID is [transactionId], or null. Used to link a scanned refund QR code to a
     * payment taken on this terminal.
     */
    suspend fun findByTransactionId(transactionId: String): SaleWithLines? = dao.saleByTransactionId(transactionId)

    /**
     * Records [event] for sale [id] without unchanged writes; returns false only when a healthy read proves it absent.
     * The tender row opened for the sale's current attempt follows its context and settlement in the same transaction.
     */
    suspend fun record(
        id: String,
        event: SaleEvent,
    ): Boolean =
        db.withTransaction {
            val sale = dao.header(id) ?: return@withTransaction false
            var stamped = event.stamped(now())
            if (stamped is SaleEvent.Settled && stamped.status == SaleStatus.APPROVED &&
                dao.tenderRecorded(id, sale.serviceId, stamped.details?.poiTransactionId)
            ) {
                if (!dao.tenderRecorded(id, sale.serviceId, null) && sale.tenderRequestedMinor != null) return@withTransaction true
                stamped = stamped.copy(repeated = true)
            }
            val updated = sale.after(stamped)
            if (updated != sale) dao.update(updated)
            val open = sale.serviceId?.let { dao.openTender(id, it) }
            when {
                stamped is SaleEvent.ContextRecorded && open != null -> dao.upsertTender(open.copy(context = stamped.context))
                stamped is SaleEvent.Settled && !stamped.repeated -> settleTender(sale, open, stamped)
            }
            true
        }

    /** Settles the attempt's [open] row in place, or appends an approved row for an attempt opened before rows existed. */
    private suspend fun settleTender(
        sale: SaleEntity,
        open: SaleTenderEntity?,
        settled: SaleEvent.Settled,
    ) {
        val details = settled.details
        if (open == null) {
            if (details != null) recordApprovedTender(sale, settled, details)
            return
        }
        dao.upsertTender(
            if (settled.status == SaleStatus.APPROVED && details != null) {
                answeredTender(
                    open,
                    details,
                    details.amountMinor ?: open.requestedMinor,
                    settled.processedAt ?: now(),
                    sale.context,
                )
            } else {
                open.copy(
                    status = settled.status,
                    returnedPaymentType = details?.returnedPaymentType ?: open.returnedPaymentType,
                    returnedInstallments = details?.returnedInstallments ?: open.returnedInstallments,
                    returnedPlanId = details?.returnedPlanId ?: open.returnedPlanId,
                    fundingSource = details?.fundingSource ?: open.fundingSource,
                    paymentMethodVariant = details?.paymentMethodVariant ?: open.paymentMethodVariant,
                )
            },
        )
    }

    private suspend fun recordApprovedTender(
        sale: SaleEntity,
        settled: SaleEvent.Settled,
        details: TransactionDetails,
    ) {
        val tender = settled.approvedTender(sale.totalMinor, sale.paidMinor) ?: return
        val seq = dao.tenderCount(sale.id) + 1
        dao.upsertTender(
            answeredTender(
                SaleTenderEntity(
                    id = "${sale.id}-$seq",
                    saleId = sale.id,
                    sequence = seq,
                    requestedMinor = tender.requestedMinor,
                    serviceId = sale.serviceId,
                ),
                details,
                tender.authorisedMinor,
                settled.processedAt ?: now(),
                sale.context,
            ),
        )
    }

    /**
     * Applies the accepted [refund] (one that is [RefundStatus.REQUESTED]) to its local sale. A cancellation of a held
     * payment marks the sale [SaleEntity.holdCancelled] and refunds nothing. A full refund marks every line and the
     * sale's whole amount (with any tip, see [SaleEntity.amountMinor]) as refunded; a partial one adds its
     * [RefundedLine] quantities and its amount. Neither can exceed what was sold. Nothing happens for a refund that was
     * not accepted, has no local sale, or whose sale no longer exists. Called by [RefundRepository.settle] inside its
     * transaction.
     */
    suspend fun applyRefund(refund: RefundEntity) {
        val saleId = refund.saleId?.takeIf { refund.status == RefundStatus.REQUESTED } ?: return
        db.withTransaction {
            val record = dao.sale(saleId) ?: return@withTransaction
            val refunded = record.refundedBy(refund)
            if (refunded.lines != record.lines) dao.updateLines(refunded.lines)
            dao.update(refunded.sale)
        }
    }

    /** Building tender rows, shared by card attempts and cash. */
    internal companion object {
        /** A PENDING card attempt row for sale [saleId] as tender [sequence], before it is sent. */
        fun openedTender(
            saleId: String,
            sequence: Int,
            amountMinor: Long,
            allocation: TenderAllocation?,
            createdAt: Long,
        ): SaleTenderEntity =
            SaleTenderEntity(
                id = "$saleId-$sequence",
                saleId = saleId,
                sequence = sequence,
                requestedMinor = amountMinor,
                status = SaleStatus.PENDING,
                createdAt = createdAt,
                allocationJson = TenderAllocationJson.encode(allocation),
            )

        fun cashTender(
            saleId: String,
            sequence: Int,
            cash: SaleEvent.CashTendered,
            allocation: TenderAllocation?,
            environment: String?,
        ): SaleTenderEntity =
            SaleTenderEntity(
                id = "$saleId-$sequence",
                saleId = saleId,
                sequence = sequence,
                requestedMinor = cash.cash.amountMinor,
                authorisedMinor = cash.cash.amountMinor,
                status = SaleStatus.APPROVED,
                createdAt = checkNotNull(cash.processedAt),
                tipMinor = 0,
                surchargeMinor = 0,
                cashBackMinor = 0,
                kind = TenderKind.CASH,
                allocationJson = TenderAllocationJson.encode(allocation),
                cashReceivedMinor = cash.cash.receivedMinor,
                changeMinor = cash.cash.changeMinor,
                environment = environment,
            )

        /** [tender] approved for [authorisedMinor] with the terminal's [details], answered at [processedAt], sent in [context]. */
        fun answeredTender(
            tender: SaleTenderEntity,
            details: TransactionDetails,
            authorisedMinor: Long,
            processedAt: Long,
            context: PaymentContext?,
        ): SaleTenderEntity =
            tender.copy(
                authorisedMinor = authorisedMinor,
                status = SaleStatus.APPROVED,
                poiTransactionId = details.poiTransactionId,
                poiTimestamp = details.poiTimestamp,
                pspReference = details.pspReference,
                paymentBrand = details.paymentBrand,
                maskedPan = details.maskedPan,
                createdAt = processedAt,
                context = context ?: tender.context,
                poiReconciliationId = details.poiReconciliationId,
                tipMinor = details.tipMinor,
                surchargeMinor = details.surchargeMinor,
                cashBackMinor = details.cashBackMinor,
                customerReceiptJson = ReceiptLinesJson.encodeFields(details.customerReceipt),
                cashierReceiptJson = ReceiptLinesJson.encodeFields(details.cashierReceipt),
                returnedPaymentType = details.returnedPaymentType,
                returnedInstallments = details.returnedInstallments,
                returnedPlanId = details.returnedPlanId,
                fundingSource = details.fundingSource,
                paymentMethodVariant = details.paymentMethodVariant,
            )
    }
}
