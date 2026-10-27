package app.minimpos.app.payment

import app.minimpos.app.data.repo.GiftCardRepository
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.repo.TenderAllocation
import app.minimpos.app.data.repo.cashHandover
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.core.access.ManagerAction
import app.minimpos.core.split.CashPayment
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * Cash tenders of immediate sales, recorded locally once the operator confirmed the cash received and the change
 * handed back, and the cash handed over for approved gift-card cash-outs; nothing is sent to Adyen or a terminal, so no
 * setup is needed and terminal totals never include them. Each recording is one transaction that checks the reviewed
 * balance, allocation and unresolved tenders again, so a stale review, an excessive amount or a concurrent card attempt
 * is refused with nothing stored. Recordings are serialized; screens follow the stored sale.
 *
 * @param sales Where sales and their tenders are stored.
 * @param environment The current history environment (LIVE, TEST or SIMULATOR), kept with each cash tender so reports
 *   separate its money; null while a real destination has not established one.
 * @param onPaid Called with a new sale and its start once its first cash tender is stored, to arm its receipt and
 *   complete the session revision it was rung up in.
 * @param newId Generates sale IDs.
 * @param giftCards Where gift-card cash handovers are stored; null where none are recorded.
 * @param approval Manager approval of cash payments and card cashback handovers.
 */
class CashTenders(
    private val sales: SaleRepository,
    private val environment: () -> String?,
    private val onPaid: (id: String, start: PaymentStart) -> Unit = { _, _ -> },
    private val newId: () -> String = { Uuid.random().toString() },
    private val giftCards: GiftCardRepository? = null,
    private val approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
) {
    private val mutex = Mutex()

    /**
     * Stores a new sale paid first with [start]'s amount in cash, of which [receivedMinor] was handed over; returns its
     * ID, or null when [start] is not an acceptable new immediate-charge tender (too little cash, an existing sale).
     */
    suspend fun payNew(
        start: PaymentStart,
        receivedMinor: Long,
        createdAt: Long,
    ): String? {
        if (!approval.forActions(setOf(ManagerAction.SALE, ManagerAction.CASH_PAYMENT)).allowsNow()) return null
        if (start.existingSaleId != null || start.manualCapture || start.scannedPayment != null) return null
        val cash = payment(start.requestedAmountMinor, receivedMinor) ?: return null
        if (!SaleBook.splitBalance(start).accepts(cash.amountMinor, start.allocation)) return null
        val id = newId()
        mutex.withLock {
            sales.createCash(
                SaleBook.pendingSale(id, start, createdAt),
                SaleBook.lines(id, start.totals),
                SaleEvent.CashTendered(cash, 0),
                start.allocation,
                environment(),
            )
        }
        onPaid(id, start)
        return id
    }

    /**
     * Records [amountMinor] in cash chosen as [allocation] on partially paid sale [saleId], reviewed with
     * [reviewedPaidMinor] already paid, of which [receivedMinor] was handed over. False, with nothing stored, when the
     * sale changed since the review, a card attempt is unresolved, or the amount or allocation is not acceptable.
     */
    suspend fun payExisting(
        saleId: String,
        amountMinor: Long,
        allocation: TenderAllocation?,
        receivedMinor: Long,
        reviewedPaidMinor: Long,
    ): Boolean {
        if (!approval.forActions(setOf(ManagerAction.SALE, ManagerAction.CASH_PAYMENT)).allowsNow()) return false
        val cash = payment(amountMinor, receivedMinor) ?: return false
        return mutex.withLock { sales.recordCash(saleId, SaleEvent.CashTendered(cash, reviewedPaidMinor), allocation, environment()) }
    }

    /**
     * Records that staff handed over the cash of gift-card handover stage [stageId] (an approved cash-out); false,
     * storing nothing, when it is no longer awaiting. Approval is the caller's ([GiftCards]); serialized with tenders.
     */
    suspend fun handOver(stageId: String): Boolean = mutex.withLock { giftCards?.recordCashHandover(stageId) == true }

    /** Records a sale cashback/cash-out handover once, after approval checked by the caller. */
    suspend fun handOverCashback(saleId: String): Boolean {
        val grant = approval.grant()
        return mutex.withLock {
            val sale = sales.get(saleId)?.sale ?: return@withLock false
            if (sale.cashHandover?.canRecord != true) return@withLock false
            if (!approval.forAction(ManagerAction.CASHOUT).allows(grant)) return@withLock false
            sales.recordCashHandover(saleId)
        }
    }

    private fun payment(
        amountMinor: Long,
        receivedMinor: Long,
    ): CashPayment? = if (amountMinor > 0 && receivedMinor >= amountMinor) CashPayment(amountMinor, receivedMinor) else null
}
