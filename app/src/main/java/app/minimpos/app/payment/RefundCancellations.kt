package app.minimpos.app.payment

import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundCancelStatus
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.cancellable
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.access.ManagerAction
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
    val running: Boolean get() = mutex.isLocked

    /** Refund cancellations as [FinancialOperations] holds them: busy while one is being sent. */
    internal val operationKind = OperationKind({ running })

    /** Asks Adyen to cancel refund [id], or sends an unresolved cancellation of it again with its original identity. */
    suspend fun cancel(id: String): RefundCancelResult =
        mutex.withLock {
            val approval = approval.forAction(ManagerAction.UNREFERENCED_REFUND)
            val refund =
                refunds.get(id)?.takeIf { approval.allowsNow() && it.cancellable(delayed()) }
                    ?: return@withLock RefundCancelResult.NotAllowed
            val target = target()
            val operation =
                object : IdentifiedSend<PaymentModifications, ModificationResult> {
                    override val unresolved = refund.cancelStatus?.unresolved == true
                    override val firstSentAt = refund.cancelStartedAt
                    override val reason = refund.cancelReason

                    override suspend fun approved() = approval.allowsNow()

                    override suspend fun access() = target.modifications(refund.context)

                    override suspend fun sending() = refunds.cancelSending(refund.id, clock.millis()) != null

                    override suspend fun exchange(client: PaymentModifications) =
                        client.cancelPendingRefund(checkNotNull(refund.pspReference), "void-${refund.id}")

                    override fun fault(answer: ModificationResult) = (answer as? ModificationResult.Failed)?.fault
                }
            withContext(NonCancellable) {
                when (val sent = OperationIdentity.send(operation, clock.millis())) {
                    Sent.Unapproved, Sent.Withdrawn -> {
                        RefundCancelResult.NotAllowed
                    }

                    is Sent.Blocked -> {
                        val failure = Failure.NotSetUp(sent.problem)
                        if (operation.unresolved) RefundCancelResult.Unconfirmed(failure) else RefundCancelResult.NotDone(failure)
                    }

                    is Sent.Expired -> {
                        RefundCancelResult.Unconfirmed(sent.failure)
                    }

                    // An earlier unresolved request stays unresolved: this answer cannot say the first one was not received.
                    is Sent.Unchanged -> {
                        answered(refund.id, null, RefundCancelStatus.UNKNOWN, RefundCancelResult.Unconfirmed(sent.failure))
                    }

                    is Sent.Answered -> {
                        answered(refund.id, sent.answer, sent.failed)
                    }
                }
            }
        }

    /** Stores Adyen's [result] to cancelling refund [id], which [failed] classifies when it failed, and reports it. */
    private suspend fun answered(
        id: String,
        result: ModificationResult,
        failed: FailedSend?,
    ): RefundCancelResult =
        when (result) {
            is ModificationResult.Received, is ModificationResult.Authorised -> {
                answered(id, result, RefundCancelStatus.REQUESTED, RefundCancelResult.Requested)
            }

            is ModificationResult.Refused -> {
                answered(id, result, RefundCancelStatus.FAILED, RefundCancelResult.NotDone(Failure.Remote(Fault.UnreadableReply())))
            }

            is ModificationResult.Failed if failed == FailedSend.NOT_DONE -> {
                answered(id, result, RefundCancelStatus.FAILED, RefundCancelResult.NotDone(Failure.Remote(result.fault)))
            }

            is ModificationResult.Failed -> {
                answered(id, result, RefundCancelStatus.UNKNOWN, RefundCancelResult.Unconfirmed(Failure.Remote(result.fault)))
            }
        }

    /** Stores [status] for cancelling refund [id], with [outcome]'s reason and the PSP reference of a received [result]. */
    private suspend fun answered(
        id: String,
        result: ModificationResult?,
        status: RefundCancelStatus,
        outcome: RefundCancelResult,
    ): RefundCancelResult {
        val reason =
            when (outcome) {
                is RefundCancelResult.NotDone -> StoredReason.NotDone(outcome.failure)
                is RefundCancelResult.Unconfirmed -> StoredReason.Unconfirmed(outcome.failure)
                RefundCancelResult.Requested, RefundCancelResult.NotAllowed -> null
            }
        refunds.cancelAnswered(id, status, reason, (result as? ModificationResult.Received)?.pspReference)
        return outcome
    }
}
