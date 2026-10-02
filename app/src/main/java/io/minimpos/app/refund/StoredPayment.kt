package io.minimpos.app.refund

import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.SaleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * A stored payment: the one reading of what can be done with it ([actions]), shared by the screens that show it
 * ([StoredPayments.observe]) and by [io.minimpos.app.payment.Captures], which checks a request against it before
 * sending anything.
 *
 * @property record The sale with its lines.
 */
data class StoredPayment(
    val record: SaleWithLines,
) {
    /** The sale row. */
    val sale: SaleEntity get() = record.sale

    /** Where the payment stands, see [PaymentStanding.of]. */
    val standing: PaymentStanding get() = sale.standing

    /** What can be done with the payment now, see [SaleWithLines.actions]. */
    val actions: Set<PaymentAction> = record.actions
}

/**
 * Stored payments as the screens follow them, so a screen never works out [StoredPayment.actions] itself.
 *
 * @param sales Where the sales are stored.
 */
class StoredPayments(
    private val sales: SaleRepository,
) {
    /** Follows sale [saleId]; emits null while there is none (for example after pruning). */
    fun observe(saleId: String): Flow<StoredPayment?> = sales.observe(saleId).map { record -> record?.let(::StoredPayment) }
}
