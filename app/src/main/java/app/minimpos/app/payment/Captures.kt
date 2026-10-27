package app.minimpos.app.payment

import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.PaymentAction
import app.minimpos.app.history.PaymentStanding
import app.minimpos.app.history.StoredPayment
import app.minimpos.app.history.standing
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.access.ManagerAction
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock
import kotlin.uuid.Uuid

/** What became of entering a tip, a capture or an authorisation adjustment, see [Captures]. */
sealed interface CaptureResult {
    /** Adyen received the capture; it confirms it in the Customer Area. */
    data object Requested : CaptureResult

    /** The adjustment went through: authorised, or received by Adyen when it is asynchronous. */
    data object Adjusted : CaptureResult

    /**
     * The card issuer refused the higher amount, so nothing was captured (and a tip was not saved).
     *
     * @property said Adyen's refusal reason; null when it gave none.
     */
    data class Refused(
        val said: ExternalText?,
    ) : CaptureResult

    /**
     * Nothing was captured or adjusted: nothing was sent (the Checkout API is not set up) or Adyen did not take it, so
     * the amount may be changed and sent again.
     *
     * @property failure Why.
     */
    data class NotDone(
        val failure: Failure,
    ) : CaptureResult

    /**
     * It is not known whether Adyen took it; only the same request (same identity and amount) may be sent again, which
     * Adyen applies at most once.
     *
     * @property failure What prevented the confirmation.
     */
    data class Unconfirmed(
        val failure: Failure,
    ) : CaptureResult

    /** The payment no longer allows it (captured or cancelled meanwhile, or gone), or the amount is not valid. */
    data object NotAllowed : CaptureResult
}

/**
 * Finishes payments taken with manual capture: enters the tip of a sale taken for tipping on the receipt and captures
 * it, captures a pre-authorisation (adjusting it first when more is captured than it holds) and adjusts what a
 * pre-authorisation holds, through the Checkout API ([AdyenApi]); while it is not set up nothing is sent
 * ([CaptureResult.NotDone] with [Failure.NotSetUp]). Which payments allow what is decided by their
 * [StoredPayment.actions], the same reading the screens use; anything else is [CaptureResult.NotAllowed].
 *
 * A tip above [PaymentStanding.TIP_ADJUSTMENT_PERCENT] of the bill first raises the authorisation to bill plus tip; if
 * the issuer refuses, the tip is not saved, so a smaller one can be entered. Otherwise the tip is saved and the total
 * captured (Adyen's overcapture). Every capture is stored as [CaptureStatus.PENDING] before it is sent and with an
 * idempotency key made from the sale and amount, so one whose outcome is unknown can be sent again
 * ([retryCapture]) without capturing twice. One operation runs at a time. Waiting and eligibility checks are
 * cancellable; once committed, the stored request, exchange and stored answer finish even if the caller is cancelled.
 * Cancellation is then rethrown so a closed screen never continues with the outcome. Unresolved requests retain their
 * original identity and first-send time; retries stop at seven days or after an HTTP error without explicit retry permission.
 *
 * @param sales Where the payments are stored, and their capture recorded.
 * @param target Where captures and adjustments go now: [AdyenApi.target] in the app, a fixed target in tests.
 * @param approval Manager approval, which every capture and adjustment needs, checked as held now under the
 *   operation lock.
 * @param clock Checks persisted first-send times against Adyen’s guaranteed idempotency retention.
 */
class Captures(
    private val sales: SaleRepository,
    private val target: suspend () -> ApiTarget,
    private val approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    /**
     * Captures as [FinancialOperations] holds them: busy while a capture or adjustment, chained ones included, uses the
     * current API target.
     */
    internal val operationKind = OperationKind({ mutex.isLocked })

    /** Enters the tip of [saleId] ([tipMinor] in minor units, 0 for no tip) and captures the bill plus the tip. */
    suspend fun addTip(
        saleId: String,
        tipMinor: Long,
    ): CaptureResult =
        mutex.withLock {
            val target = target()
            val sale =
                allowing(saleId, PaymentAction.ENTER_TIP)?.takeIf { tipMinor >= 0 } ?: return@withLock CaptureResult.NotAllowed
            val total = Math.addExact(Math.addExact(sale.totalMinor, sale.surchargeMinor ?: 0), tipMinor)
            if (!PaymentStanding.captureAmountAllowed(sale, total)) return@withLock CaptureResult.NotAllowed
            committed {
                if (PaymentStanding.captureNeedsAdjustment(sale, total, tipMinor)) {
                    // The tip is only saved once the higher amount is authorised.
                    adjusted(sale, total, target) { capture(it, total, target, tipMinor) }
                } else {
                    capture(sale, total, target, tipMinor)
                }
            }
        }

    /**
     * Captures [amountMinor] of the pre-authorisation [saleId]: up to what it holds directly (Adyen releases the rest),
     * more after adjusting the authorisation to [amountMinor].
     */
    suspend fun capture(
        saleId: String,
        amountMinor: Long,
    ): CaptureResult =
        mutex.withLock {
            val target = target()
            val sale =
                allowing(saleId, PaymentAction.CAPTURE)?.takeIf { amountMinor > 0 } ?: return@withLock CaptureResult.NotAllowed
            if (!PaymentStanding.captureAmountAllowed(sale, amountMinor)) return@withLock CaptureResult.NotAllowed
            committed {
                if (PaymentStanding.captureNeedsAdjustment(sale, amountMinor)) {
                    adjusted(sale, amountMinor, target) { capture(it, amountMinor, target) }
                } else {
                    capture(sale, amountMinor, target)
                }
            }
        }

    /**
     * Changes what the pre-authorisation [saleId] holds to [amountMinor] (the same amount extends the authorisation),
     * without capturing ([PaymentAction.ADJUST]).
     */
    suspend fun adjust(
        saleId: String,
        amountMinor: Long,
    ): CaptureResult =
        mutex.withLock {
            val target = target()
            val sale =
                allowing(saleId, PaymentAction.ADJUST)?.takeIf { amountMinor > 0 } ?: return@withLock CaptureResult.NotAllowed
            committed { adjust(sale, amountMinor, target) }
        }

    /** Sends the capture of [saleId] again as it was, when its [StoredPayment.actions] include [PaymentAction.RETRY_CAPTURE]. */
    suspend fun retryCapture(saleId: String): CaptureResult =
        mutex.withLock {
            val target = target()
            val sale = allowing(saleId, PaymentAction.RETRY_CAPTURE)
            val amount = sale?.capturedMinor
            if (sale == null || amount == null) CaptureResult.NotAllowed else committed { capture(sale, amount, target) }
        }

    /** The stored sale [saleId] when its [StoredPayment.actions] include [action]; else null. */
    private suspend fun allowing(
        saleId: String,
        action: PaymentAction,
    ): SaleEntity? {
        val permission = if (action == PaymentAction.ADJUST) ManagerAction.ADJUST_AUTHORISATION else ManagerAction.CAPTURE
        if (!approval.forAction(permission).allowsNow()) return null
        return sales
            .get(saleId)
            ?.let(::StoredPayment)
            ?.takeIf { action in it.actions }
            ?.sale
    }

    /**
     * Runs [send] once the caller still wants it; once committed, its requests and stored answers finish even if the
     * caller is cancelled, which is then rethrown so a closed screen never continues with the outcome.
     */
    private suspend fun committed(send: suspend () -> CaptureResult): CaptureResult {
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) { send() }.also { currentCoroutineContext().ensureActive() }
    }

    /** Adjusts [sale] to [amount] at [target] and, once that went through, runs [then] with the adjusted sale. */
    private suspend fun adjusted(
        sale: SaleEntity,
        amount: Long,
        target: ApiTarget,
        then: suspend (SaleEntity) -> CaptureResult,
    ): CaptureResult {
        val result = adjust(sale, amount, target)
        val adjusted = sales.get(sale.id)?.sale
        return if (result == CaptureResult.Adjusted && adjusted != null) then(adjusted) else result
    }

    private suspend fun adjust(
        sale: SaleEntity,
        amount: Long,
        target: ApiTarget,
    ): CaptureResult {
        val psp = sale.pspReference ?: return CaptureResult.NotAllowed
        // An unresolved request keeps its identity; a separate renewal or amount change gets a new identity.
        if (sale.adjustmentPending && sale.adjustmentAmountMinor != amount) return CaptureResult.NotAllowed
        val key = if (sale.adjustmentPending) checkNotNull(sale.adjustmentKey) else "adjust-${Uuid.random()}"
        val operation =
            object : Modification(sale, target, ManagerAction.ADJUST_AUTHORISATION) {
                override val unresolved = sale.adjustmentPending
                override val firstSentAt = sale.adjustmentStartedAt

                override suspend fun sending(): Boolean {
                    if (!unresolved) sales.record(sale.id, SaleEvent.AdjustmentSending(key, amount, clock.millis()))
                    return true
                }

                override suspend fun exchange(client: PaymentModifications) =
                    client.updateAmount(
                        psp,
                        ModificationAmount(sale.currency, amount),
                        sale.merchantReference,
                        sale.adjustAuthorisationData,
                        key,
                    )
            }
        return operation.outcome(OperationIdentity.send(operation, clock.millis())) { answer ->
            sales.record(sale.id, SaleEvent.AdjustmentAnswered(amount, answer))
            CaptureResult.Adjusted
        }
    }

    private suspend fun capture(
        sale: SaleEntity,
        amount: Long,
        target: ApiTarget,
        tipMinor: Long? = null,
    ): CaptureResult {
        val psp = sale.pspReference ?: return CaptureResult.NotAllowed
        val operation =
            object : Modification(sale, target, ManagerAction.CAPTURE) {
                override val unresolved = sale.standing in setOf(PaymentStanding.CAPTURE_SENDING, PaymentStanding.CAPTURE_UNKNOWN)

                // The capture's identity is the sale and amount, so the same amount is a resend even after a known failure.
                override val resend = sale.capturedMinor == amount
                override val firstSentAt = sale.captureStartedAt

                override suspend fun sending(): Boolean {
                    if (!unresolved) sales.record(sale.id, SaleEvent.CaptureSending(amount, tipMinor, clock.millis()))
                    return true
                }

                override suspend fun exchange(client: PaymentModifications) =
                    client.capture(psp, ModificationAmount(sale.currency, amount), sale.merchantReference, "capture-${sale.id}-$amount")
            }
        return operation.outcome(OperationIdentity.send(operation, clock.millis())) { answer ->
            sales.record(sale.id, SaleEvent.CaptureAnswered(answer))
            CaptureResult.Requested
        }
    }

    /**
     * A capture or adjustment of [sale] at [target] as [OperationIdentity] sends it, under the [permission] checked as
     * held now.
     */
    private abstract inner class Modification(
        private val sale: SaleEntity,
        private val target: ApiTarget,
        private val permission: ManagerAction,
    ) : IdentifiedSend<PaymentModifications, ModificationResult> {
        override val reason get() = sale.modificationReason

        override suspend fun approved() = approval.forAction(permission).allowsNow()

        override suspend fun access() = target.modifications(sale.context)

        override fun fault(answer: ModificationResult) = (answer as? ModificationResult.Failed)?.fault

        /** What [sent] means for the caller, with [stored] recording an answer and naming the result it went through as. */
        suspend fun outcome(
            sent: Sent<ModificationResult>,
            stored: suspend (ModificationResult) -> CaptureResult,
        ): CaptureResult =
            when (sent) {
                Sent.Unapproved, Sent.Withdrawn -> {
                    CaptureResult.NotAllowed
                }

                is Sent.Blocked -> {
                    val unresolved =
                        sale.adjustmentPending || sale.standing in setOf(PaymentStanding.CAPTURE_SENDING, PaymentStanding.CAPTURE_UNKNOWN)
                    if (sent.access is ApiAccess.Unavailable && !unresolved) {
                        sales.record(sale.id, SaleEvent.ModificationNotSetUp(sent.problem))
                    }
                    val failure = Failure.NotSetUp(sent.problem)
                    if (unresolved) CaptureResult.Unconfirmed(failure) else CaptureResult.NotDone(failure)
                }

                is Sent.Expired -> {
                    CaptureResult.Unconfirmed(sent.failure)
                }

                is Sent.Unchanged -> {
                    CaptureResult.Unconfirmed(sent.failure)
                }

                is Sent.Answered -> {
                    val went = stored(sent.answer)
                    when (val answer = sent.answer) {
                        is ModificationResult.Received, is ModificationResult.Authorised -> went
                        is ModificationResult.Refused -> CaptureResult.Refused(answer.said)
                        is ModificationResult.Failed -> answer.fault.captureResult(sent.failed)
                    }
                }
            }

        /** This fault as the result of a capture or adjustment, as [failed] classifies it: not done only when it proved so. */
        private fun Fault.captureResult(failed: FailedSend?): CaptureResult {
            val failure = Failure.Remote(this)
            return if (failed == FailedSend.NOT_DONE) CaptureResult.NotDone(failure) else CaptureResult.Unconfirmed(failure)
        }
    }
}
