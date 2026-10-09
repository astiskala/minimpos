package app.minimpos.app.payment

import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.refund.RefundChoice
import app.minimpos.app.refund.RefundStart
import app.minimpos.app.refund.RefundablePayment
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.ZoneId

/**
 * What financial screens can do to a stored sale besides delivering its receipt: starts refunds and cancellations,
 * and retries captures without exposing reference construction or financial implementations to those callers.
 * Pure eligibility stays in [RefundablePayment]; original-context and Manager approval rechecks stay in the
 * financial implementations. Starting a reversal does not establish its outcome; the lifecycle follows that separately.
 *
 * @param refunds The refunds' lifecycle, which also runs cancellations of payments that only hold their amount.
 * @param captures Sends a capture again.
 * @param settings Current saved settings, read at each initiation for the reference prefix.
 * @param clock Stamps the merchant reference of a refund or cancellation.
 * @param zone The time zone of that reference, read for each initiation.
 */
class StoredPaymentActions(
    private val refunds: TransactionLifecycle<RefundStart>,
    private val captures: Captures,
    private val settings: StateFlow<AppSettings>,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    /**
     * Starts [choice] of [payment] as a referenced refund; false for an invalid choice or a busy lifecycle.
     * A foreign receipt must have been independently reviewed by the caller, as on the refund screen.
     * Amounts use the payment's original currency minor units. True means started, not accepted or confirmed.
     */
    fun refund(
        payment: RefundablePayment,
        choice: RefundChoice,
    ): Boolean = start(payment.request(choice, settings.value.payment.referencePrefix, clock.instant(), zone())) != null

    /** Starts a full reversal of [record]'s held payment; null when not cancellable or a refund is already running. */
    fun cancel(record: SaleWithLines): String? =
        start(RefundablePayment.cancellation(record, settings.value.payment.referencePrefix, clock.instant(), zone()))

    /** Retries [saleId]'s stored capture amount and identity; callers use `persisting` to survive screen cancellation. */
    suspend fun retryCapture(saleId: String): CaptureResult = captures.retryCapture(saleId)

    private fun start(request: RefundStart?): String? = request?.let { runCatching { refunds.start(it) }.getOrNull() }
}
