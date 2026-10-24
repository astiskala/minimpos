package app.minimpos.app.payment

import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.settings.AppSettings
import app.minimpos.app.history.RefundChoice
import app.minimpos.app.history.RefundStart
import app.minimpos.app.history.RefundablePayment
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import java.time.ZoneId

/**
 * What financial screens can do to a stored sale besides delivering its receipt: starts refunds and cancellations,
 * and retries captures without exposing reference construction or financial implementations to those callers.
 * Pure eligibility stays in [RefundablePayment]; original-context and Manager approval rechecks stay in the
 * financial implementations. Starting a reversal does not establish its outcome; [FinancialOperations.refundProgress]
 * follows that separately.
 *
 * @param operations Sends refunds and cancellations the way their payment was taken (see [FinancialOperations.refund]).
 * @param captures Sends a capture again.
 * @param settings Current saved settings, read at each initiation for the reference prefix.
 * @param clock Stamps the merchant reference of a refund or cancellation.
 * @param zone The time zone of that reference, read for each initiation.
 * @param legs Runs returns split across several tenders or including cash; null offers none.
 */
class StoredPaymentActions(
    private val operations: FinancialOperations,
    private val captures: Captures,
    private val settings: StateFlow<AppSettings>,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val legs: RefundLegs? = null,
) {
    /**
     * Starts [choice] of [payment] returned to its original tenders as [allocation] (the proposal, possibly adjusted
     * by staff within each tender's balance): one card leg is followed like any refund, several legs or any cash run as
     * one multi-leg return through [legs]. [RefundStarted.NONE] for an invalid allocation or while a refund runs.
     * A foreign receipt must have been independently reviewed by the caller, as on the refund screen. Amounts use the
     * payment's original currency minor units; starting means neither accepted nor confirmed.
     */
    fun refund(
        payment: RefundablePayment,
        choice: RefundChoice,
        allocation: Map<String, Long>,
    ): RefundStarted {
        val requests =
            payment.requests(choice, allocation, settings.value.payment.referencePrefix, clock.instant(), zone())
                ?: return RefundStarted.NONE
        val single = requests.singleOrNull()?.takeUnless { it.cash }
        return when {
            single != null -> if (start(single) != null) RefundStarted.SINGLE else RefundStarted.NONE
            legs?.start(requests) == true -> RefundStarted.LEGS
            else -> RefundStarted.NONE
        }
    }

    /** Starts a full reversal of [record]'s held payment; null when not cancellable or a refund is already running. */
    fun cancel(record: SaleWithLines): String? =
        start(RefundablePayment.cancellation(record, settings.value.payment.referencePrefix, clock.instant(), zone()))

    /** Retries [saleId]'s stored capture amount and identity; the capture module owns execution through its stored answer. */
    suspend fun retryCapture(saleId: String): CaptureResult = captures.retryCapture(saleId)

    private fun start(request: RefundStart?): String? = request?.let(operations::refund)
}

/** How a reviewed return was started, so its screen follows the right progress. */
enum class RefundStarted {
    /** One card reversal, followed by [FinancialOperations.refundProgress]. */
    SINGLE,

    /** Several legs or cash, followed by [RefundLegs.state]. */
    LEGS,

    /** Nothing was started: the allocation is invalid or a refund is already running. */
    NONE,
}
