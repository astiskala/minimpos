package app.minimpos.app.payment

import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.data.security.ManagerApproval
import app.minimpos.app.history.RefundStart
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.core.payment.PaymentContext
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.time.Clock
import kotlin.uuid.Uuid

/**
 * Refunds of saved-card charges ([RefundStart.checkout]) through Adyen's Checkout API, which has their PSP reference
 * but no terminal transaction to reverse. Each refund is stored as PENDING ([RefundEntity.checkoutRefund]) before Adyen
 * is called and sent from what was stored with the idempotency key [idempotencyKey], so asking again ([recheck]) cannot
 * refund twice. Adyen only receives refunds ([RefundStatus.REQUESTED]); the Customer Area confirms them.
 * [CheckoutOperations] runs them: Manager approval is checked again immediately before sending, the original payment
 * context must match the current credentials, and refunds and checks run one at a time. Callers start and check them
 * through [FinancialOperations].
 *
 * @param scope Where refunds run, so one survives leaving the screen.
 * @param refunds Where refunds are stored; an accepted one is also applied to its sale.
 * @param sales Where the refunded saved-card sales are read.
 * @param target Where Checkout requests go now.
 * @param onAccepted Called once with the refund ID when Adyen received a refund started in this process, for its
 *   automatic receipt.
 * @param clock Stamps the refunds and decides whether an unknown one may be sent again.
 * @param newId Generates refund IDs.
 * @param approval Manager approval, which every refund needs: taken when it starts, checked again before sending.
 */
class CheckoutRefunds(
    scope: CoroutineScope,
    refunds: RefundRepository,
    sales: SaleRepository,
    target: suspend () -> ApiTarget,
    onAccepted: (id: String) -> Unit = {},
    clock: Clock = Clock.systemUTC(),
    newId: () -> String = { Uuid.random().toString() },
    approval: ManagerApproval = ManagerApproval.NOT_REQUIRED,
) {
    private val operations =
        CheckoutOperations(scope, Book(refunds, sales), target, { id, _ -> onAccepted(id) }, clock, newId, approval)

    /** The refund running now, which the refund screens follow to its result. */
    val state: StateFlow<TransactionState> = operations.state

    /**
     * Sends [request] in the background and returns the new refund's ID straight away; null, with nothing stored or sent,
     * while another refund is running or [request] is not a Checkout refund of a stored sale.
     */
    fun start(request: RefundStart): String? = if (request.checkout && request.saleId != null) operations.start(request) else null

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next refund can start. */
    fun acknowledge() = operations.acknowledge()

    /**
     * Sends refund [id], whose outcome is unknown, again with its stored request and idempotency key, as
     * [CheckoutOperations.recheck] does; anything but an unknown Checkout refund has nothing left to check
     * ([Recheck.Settled]).
     */
    suspend fun recheck(id: String): Recheck = operations.recheck(id)

    /** Checkout refunds as stored refunds, sent with [PaymentModifications.refund] against the sale's PSP reference. */
    private class Book(
        private val refunds: RefundRepository,
        private val sales: SaleRepository,
    ) : CheckoutBook<RefundStart, PaymentModifications> {
        override val needsApproval: Boolean get() = true

        override fun amount(request: RefundStart): OperationAmount = OperationAmount(request.amountMinor, request.currency)

        override suspend fun open(
            id: String,
            request: RefundStart,
            createdAt: Long,
            current: PaymentContext?,
        ) = refunds.create(request.pendingRefund(id, createdAt, checkout = true))

        override suspend fun unsent(id: String): CheckoutRecord? {
            val refund =
                refunds.get(id)?.takeIf {
                    it.checkoutRefund && (it.status == RefundStatus.PENDING || it.status == RefundStatus.UNKNOWN)
                } ?: return null
            val saleId = refund.saleId ?: return null
            return CheckoutRecord(refund.status == RefundStatus.PENDING, refund.createdAt, sales.get(saleId)?.sale?.context)
        }

        override suspend fun missing(id: String): SetupProblem? = SetupProblem.PAYMENT_CONTEXT.takeIf { pspReference(id) == null }

        override fun access(
            target: ApiTarget,
            context: PaymentContext?,
        ): ApiAccess<PaymentModifications> = target.modifications(context)

        override suspend fun send(
            id: String,
            client: PaymentModifications,
            now: Long,
        ): CheckoutAnswer {
            val refund = refunds.get(id) ?: return CheckoutAnswer.NotTaken
            val psp = pspReference(id) ?: return CheckoutAnswer.NotTaken
            val amount = ModificationAmount(refund.currency, refund.amountMinor)
            return when (val result = client.refund(psp, amount, refund.merchantReference, idempotencyKey(id))) {
                is ModificationResult.Received -> {
                    refunds.settle(id, RefundStatus.REQUESTED, null, null, pspReference = result.pspReference)
                    CheckoutAnswer.Taken
                }

                is ModificationResult.Authorised -> {
                    refunds.settle(id, RefundStatus.REQUESTED, null, null, pspReference = result.pspReference)
                    CheckoutAnswer.Taken
                }

                is ModificationResult.Refused -> {
                    refunds.settle(id, RefundStatus.FAILED, result.said?.text, null)
                    CheckoutAnswer.NotTaken
                }

                is ModificationResult.Failed if result.fault.mayHaveTakenEffect -> {
                    val failure = Failure.Remote(result.fault)
                    refunds.settle(id, RefundStatus.UNKNOWN, null, null, StoredReason.Unconfirmed(failure))
                    CheckoutAnswer.Unconfirmed(failure)
                }

                is ModificationResult.Failed -> {
                    refunds.settle(id, RefundStatus.FAILED, null, null, StoredReason.NotDone(Failure.Remote(result.fault)))
                    CheckoutAnswer.NotTaken
                }
            }
        }

        override suspend fun notSent(
            id: String,
            problem: SetupProblem,
        ) = refunds.settle(id, RefundStatus.FAILED, null, null, StoredReason.NotDone(Failure.NotSetUp(problem)))

        override suspend fun interrupted(
            id: String,
            fault: DeviceFault,
        ) = refunds.settle(id, RefundStatus.UNKNOWN, null, null, StoredReason.Unconfirmed(Failure.Device(fault)))

        /** The PSP reference of the saved-card sale refund [id] refunds; null when it is gone or has none. */
        private suspend fun pspReference(id: String): String? =
            refunds
                .get(id)
                ?.saleId
                ?.let { sales.get(it) }
                ?.sale
                ?.pspReference
    }

    /** Values shared with tests. */
    companion object {
        /** The idempotency key of refund [refundId]: one refund, however often it is sent. */
        fun idempotencyKey(refundId: String): String = "refund-$refundId"
    }
}
