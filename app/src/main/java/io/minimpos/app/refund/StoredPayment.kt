package io.minimpos.app.refund

import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleWithLines
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.data.settings.CaptureMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A stored payment together with how captures are made now: the one reading of what can be done with it ([actions]),
 * shared by the screens that show it ([StoredPayments.observe]) and by [io.minimpos.app.payment.Captures], which checks
 * a request against it before sending anything.
 *
 * @property record The sale with its lines.
 * @property captureMode How captures are made now, which decides some of the [actions].
 */
data class StoredPayment(
    val record: SaleWithLines,
    val captureMode: CaptureMode,
) {
    /** The sale row. */
    val sale: SaleEntity get() = record.sale

    /** Where the payment stands, see [PaymentStanding.of]. */
    val standing: PaymentStanding get() = sale.standing

    /** What can be done with the payment now, see [SaleWithLines.actions]. */
    val actions: Set<PaymentAction> = record.actions(captureMode)
}

/**
 * Stored payments as the screens follow them: each sale paired with how captures are made at the time, so a screen
 * never works out [StoredPayment.actions] itself.
 *
 * @param sales Where the sales are stored.
 * @param captureMode How captures are made, as the terminal setup changes; in the app it emits once the setup has been
 *   read, so no screen shows actions for a capture mode that was only a default.
 */
class StoredPayments(
    private val sales: SaleRepository,
    captureMode: Flow<CaptureMode>,
) {
    private val captureMode = captureMode.distinctUntilChanged()

    /** Follows sale [saleId]; emits null while there is none (for example after pruning). */
    fun observe(saleId: String): Flow<StoredPayment?> =
        combine(sales.observe(saleId), captureMode) { record, mode -> record?.let { StoredPayment(it, mode) } }
}
