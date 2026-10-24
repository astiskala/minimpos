package app.minimpos.app.data.repo

import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleLineEntity
import app.minimpos.app.data.db.SaleTenderEntity
import app.minimpos.core.split.AllocatableLine
import app.minimpos.core.split.TenderSplit

/**
 * The one reading of how a sale's frozen purchase total is divided among its tenders: what is unpaid, which sold units
 * are already allocated, which allocation modes remain open and whether a proposed tender is acceptable. Approved,
 * pending and unknown tenders keep their units and shares; declined and failed ones release them. Pure: the repository
 * checks [accepts] inside the transaction that stores a tender, and screens show the same reading.
 *
 * @property totalMinor The sale's frozen purchase total, in minor units.
 * @property paidMinor Purchase already paid by approved tenders, in minor units.
 * @param lines The sale's lines as sold; empty before a new sale is stored (positions then follow the cart).
 * @param tenders The sale's tender rows.
 * @param itemsAllowed Whether the sale is an immediate-charge sale whose items can be allocated.
 */
class TenderBalance(
    val totalMinor: Long,
    val paidMinor: Long,
    lines: List<AllocatableLine>,
    tenders: List<SaleTenderEntity>,
    itemsAllowed: Boolean = true,
) {
    private val active = tenders.filter { it.approved || it.unresolved }
    private val allocations = active.map { TenderAllocationJson.decode(it.allocationJson) }
    private val shares = allocations.filterIsInstance<TenderAllocation.Share>()

    /** Units allocated per line position by approved, pending and unknown item tenders. */
    private val allocated: Map<Int, Int> =
        allocations
            .filterIsInstance<TenderAllocation.Items>()
            .flatMap { it.lines }
            .groupBy { it.position }
            .mapValues { (_, units) -> units.sumOf { it.quantity } }

    /** Purchase still unpaid, in minor units; never negative. */
    val unpaidMinor: Long get() = (totalMinor - paidMinor).coerceAtLeast(0)

    /** Whether a tender might still collect money; nothing more is accepted until it is resolved. */
    val unresolved: Boolean = tenders.any { it.unresolved }

    /** The sold lines with what is already allocated, keyed by position in their [AllocatableLine.lineId]. */
    val lines: List<AllocatableLine> =
        lines.map { it.copy(allocatedQuantity = allocated[it.lineId.toInt()] ?: 0) }

    /** Whether units can be chosen: every collecting tender so far was an item allocation, so none is ambiguous. */
    val itemsOpen: Boolean = itemsAllowed && allocations.all { it is TenderAllocation.Items } && lines.any { it.remainingQuantity > 0 }

    /** The next share of an equal split already begun; null before one begins or once every share was collected. */
    val nextShare: TenderAllocation.Share? =
        shares.lastOrNull()?.let { last -> TenderAllocation.Share(shares.size, last.count, last.baseMinor).takeIf { it.index < it.count } }

    /** Whether equal shares can be chosen: none begun yet, or one is still left of the split already begun. */
    val sharesOpen: Boolean get() = shares.isEmpty() || nextShare != null

    /** The units of [selection] (position to quantity) as an allocation with their apportioned gross; null when none. */
    fun itemsFor(selection: Map<Int, Int>): TenderAllocation.Items? {
        val chosen =
            this.lines.mapNotNull { line ->
                val quantity = (selection[line.lineId.toInt()] ?: 0).coerceIn(0, line.remainingQuantity)
                if (quantity == 0) null else AllocatedLine(line.lineId.toInt(), quantity, TenderSplit.lineAmount(line, quantity))
            }
        return chosen.takeIf { it.isNotEmpty() }?.let(TenderAllocation::Items)
    }

    /** The amount [allocation] collects now, or [operatorMinor] for an amount; 0 when it collects nothing. */
    fun amountFor(
        allocation: TenderAllocation?,
        operatorMinor: Long,
    ): Long =
        when (allocation) {
            null -> {
                operatorMinor
            }

            is TenderAllocation.Share -> {
                TenderSplit.shareAmount(allocation.baseMinor, allocation.count, allocation.index, unpaidMinor)
            }

            is TenderAllocation.Items -> {
                TenderSplit.itemsAmount(lines, allocation.lines.associate { it.position.toLong() to it.quantity }, unpaidMinor)
            }
        }

    /**
     * Whether a tender of [amountMinor] chosen as [allocation] may be stored now: nothing is unresolved, the amount is
     * positive and within the unpaid balance, and it matches its share or item allocation exactly. A share continues
     * the split already begun (or starts one over the whole unpaid balance); items stay within unallocated units.
     */
    fun accepts(
        amountMinor: Long,
        allocation: TenderAllocation?,
    ): Boolean {
        if (unresolved || amountMinor !in 1..unpaidMinor) return false
        return when (allocation) {
            null -> {
                true
            }

            is TenderAllocation.Share -> {
                val expected = nextShare ?: TenderAllocation.Share(0, allocation.count, unpaidMinor).takeIf { shares.isEmpty() }
                allocation == expected && allocation.count in 2..TenderSplit.MAX_SHARES && amountFor(allocation, 0) == amountMinor
            }

            is TenderAllocation.Items -> {
                itemsOpen && allocation == itemsFor(allocation.lines.associate { it.position to it.quantity }) &&
                    amountFor(allocation, 0) == amountMinor
            }
        }
    }

    /** Building the reading of a stored sale. */
    companion object {
        /** [sale] with its [lines] and [tenders] as stored. */
        fun of(
            sale: SaleEntity,
            lines: List<SaleLineEntity>,
            tenders: List<SaleTenderEntity>,
        ): TenderBalance =
            TenderBalance(
                sale.totalMinor,
                sale.paidMinor,
                lines.sortedBy { it.position }.map { AllocatableLine(it.position.toLong(), it.quantity, 0, it.grossMinor) },
                tenders,
                itemsAllowed = sale.kind == SaleKind.SALE && !sale.manualCapture,
            )
    }
}
