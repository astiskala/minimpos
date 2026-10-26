package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundCancelStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.cancellable
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.transport.Fault
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Clock

/** What asking Adyen to cancel a delayed unreferenced refund found, see [RefundCancellations]. */
sealed interface RefundCancelResult {
    /** Adyen received the request; only its webhook, shown in the Customer Area, says whether the refund was cancelled. */
    data object Requested : RefundCancelResult

    /**
     * Nothing was received: not sent (setup, original context) or rejected, such as an expired refund delay.
     *
     * @property failure Why.
     */
    data class NotDone(
        val failure: Failure,
    ) : RefundCancelResult

    /**
     * Whether Adyen received it is not known; only the same request may be sent again.
     *
     * @property failure What prevented the confirmation.
     */
    data class Unconfirmed(
        val failure: Failure,
    ) : RefundCancelResult

    /** The refund cannot be cancelled from here (not an accepted unreferenced refund, already requested, or no approval). */
    data object NotAllowed : RefundCancelResult
}

/**
 * Cancels delayed unreferenced refunds through the Classic Payments API (`/voidPendingRefund`), which Adyen documents
 * only for unreferenced in-person refunds while a Support-configured refund delay lasts. Request-status recovery is kept
 * apart from refund settlement: a received cancellation means Adyen took the request, not that the refund was stopped,
 * which only its `VOID_PENDING_REFUND` webhook reports in the Customer Area.
 *
 * Each cancellation is stored as [RefundCancelStatus.SENDING] before it is sent, with the idempotency key `void-<refund
 * ID>`, so one whose answer went missing is sent again unchanged within Adyen's retention window and never as a new
 * request. It is sent only in the refund's own payment context, holding the current Manager approval. One runs at a time;
 * once committed, the exchange and its stored answer finish even if the caller is cancelled.
 *
 * @param refunds Where refunds and their cancellations are stored.
 * @param target Where it goes now: [app.minimpos.app.terminal.AdyenApi.target] in the app, a fixed target in tests.
 * @param delayed Whether the merchant confirmed Adyen Support configured a refund delay, read at each request.
 * @param approval Manager approval, checked as held now under the operation lock.
 * @param clock Stamps the first send for Adyen's idempotency retention.
 */
class RefundCancellations(
    private val refunds: RefundRepository,
    private val target: suspend () -> ApiTarget,
    private val delayed: () -> Boolean,
    private val approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    /** Whether a cancellation is being sent now, so the configuration it is sent with must not be replaced. */
    @Volatile
    var running: Boolean = false
        private set

    /** Asks Adyen to cancel refund [id], or sends an unresolved cancellation of it again with its original identity. */
    suspend fun cancel(id: String): RefundCancelResult =
        mutex.withLock {
            val refund =
                refunds.get(id)?.takeIf { approval.allowsNow() && it.cancellable(delayed()) }
                    ?: return@withLock RefundCancelResult.NotAllowed
            val unresolved = refund.cancelStatus?.unresolved == true
            when (val access = target().modifications(refund.context)) {
                is ApiAccess.Blocked -> {
                    val failure = Failure.NotSetUp(access.problem)
                    if (unresolved) RefundCancelResult.Unconfirmed(failure) else RefundCancelResult.NotDone(failure)
                }

                is ApiAccess.Ready -> {
                    if (unresolved) {
                        CheckoutOperations.retryProblem(refund.cancelStartedAt, clock.millis(), refund.cancelReason)?.let {
                            return@withLock RefundCancelResult.Unconfirmed(Failure.NotSetUp(it))
                        }
                    }
                    running = true
                    try {
                        withContext(NonCancellable) { send(refund, access) }
                    } finally {
                        running = false
                    }
                }
            }
        }

    private suspend fun send(
        refund: RefundEntity,
        access: ApiAccess.Ready<PaymentModifications>,
    ): RefundCancelResult {
        val sending = refunds.cancelSending(refund.id, clock.millis()) ?: return RefundCancelResult.NotAllowed
        val result = access.client.cancelPendingRefund(checkNotNull(sending.pspReference), "void-${refund.id}")
        val (status, outcome) =
            when (result) {
                is ModificationResult.Received -> {
                    RefundCancelStatus.REQUESTED to RefundCancelResult.Requested
                }

                is ModificationResult.Authorised -> {
                    RefundCancelStatus.REQUESTED to RefundCancelResult.Requested
                }

                is ModificationResult.Refused -> {
                    RefundCancelStatus.FAILED to
                        RefundCancelResult.NotDone(Failure.Remote(Fault.UnreadableReply()))
                }

                is ModificationResult.Failed -> {
                    val failure = Failure.Remote(result.fault)
                    // An earlier unresolved request stays unresolved: this answer cannot say the first one was not received.
                    if (result.fault.mayHaveTakenEffect || refund.cancelStatus?.unresolved == true) {
                        RefundCancelStatus.UNKNOWN to RefundCancelResult.Unconfirmed(failure)
                    } else {
                        RefundCancelStatus.FAILED to RefundCancelResult.NotDone(failure)
                    }
                }
            }
        val reason =
            when (outcome) {
                is RefundCancelResult.NotDone -> StoredReason.NotDone(outcome.failure)
                is RefundCancelResult.Unconfirmed -> StoredReason.Unconfirmed(outcome.failure)
                RefundCancelResult.Requested, RefundCancelResult.NotAllowed -> null
            }
        refunds.cancelAnswered(refund.id, status, reason, (result as? ModificationResult.Received)?.pspReference)
        return outcome
    }
}
