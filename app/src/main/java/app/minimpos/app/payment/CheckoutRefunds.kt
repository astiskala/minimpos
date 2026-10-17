package app.minimpos.app.payment

import android.database.SQLException
import app.minimpos.app.data.db.DeviceFault
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.app.data.repo.ReceiptLinesJson
import app.minimpos.app.data.repo.RefundRepository
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.history.RefundStart
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import kotlin.uuid.Uuid

/**
 * Refunds of saved-card charges ([RefundStart.checkout]) through Adyen's Checkout API, which has their PSP reference
 * but no terminal transaction to reverse. Each refund is stored as PENDING ([RefundEntity.checkoutRefund]) before Adyen
 * is called and sent from what was stored with the idempotency key [idempotencyKey], so asking again ([recheck]) cannot
 * refund twice. Adyen only receives refunds ([RefundStatus.REQUESTED]); the Customer Area confirms them. Manager
 * approval is checked again immediately before sending, and the original payment context must match the current
 * credentials. Refunds and checks run one at a time.
 *
 * @param scope Where refunds run, so one survives leaving the screen.
 * @param refunds Where refunds are stored; an accepted one is also applied to its sale.
 * @param sales Where the refunded saved-card sales are read.
 * @param target Where Checkout requests go now.
 * @param onAccepted Called once with the refund ID when Adyen received it in this process, for its automatic receipt.
 * @param clock Stamps the refunds and decides whether an unknown one may be sent again.
 * @param newId Generates refund IDs.
 * @param permits Whether Manager approval allows a refund now, checked under the operation lock.
 */
class CheckoutRefunds(
    private val scope: CoroutineScope,
    private val refunds: RefundRepository,
    private val sales: SaleRepository,
    private val target: suspend () -> ApiTarget,
    private val onAccepted: (id: String) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> String = { Uuid.random().toString() },
    private val permits: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()

    /** The refund running now, which the refund screens follow to its result. */
    val state: StateFlow<TransactionState>
        field = MutableStateFlow<TransactionState>(TransactionState.Idle)

    /**
     * Sends [request] in the background and returns the new refund's ID straight away; null, with nothing stored or sent,
     * while another refund is running or [request] is not a Checkout refund of a stored sale.
     */
    @Synchronized
    fun start(request: RefundStart): String? {
        val saleId = request.saleId
        if (state.value is TransactionState.Processing || !request.checkout || saleId == null) return null
        val id = newId()
        state.value = TransactionState.Processing(id)
        scope.launch {
            try {
                refunds.create(opened(id, request, clock.millis()))
                mutex.withLock { refunds.get(id)?.let { send(it, saleId) } }
            } catch (e: CancellationException) {
                throw e
            } catch (ignored: SQLException) {
                interrupted(id)
            } catch (ignored: IllegalStateException) {
                interrupted(id)
            } finally {
                state.value = TransactionState.Finished(id)
            }
        }
        return id
    }

    /** Returns to [TransactionState.Idle] once the result has been seen, so the next refund can start. */
    fun acknowledge() {
        if (state.value is TransactionState.Finished) state.value = TransactionState.Idle
    }

    /**
     * Sends refund [id], whose outcome is unknown, again with its stored request and idempotency key. Anything else has
     * nothing left to check ([Recheck.Settled]); nothing changes while approval, setup or the payment context is missing,
     * or when it is older than [SavedCardCharges.RECHECK_WINDOW].
     */
    suspend fun recheck(id: String): Recheck =
        mutex.withLock {
            val refund =
                refunds.get(id)?.takeIf { it.checkoutRefund && it.status == RefundStatus.UNKNOWN } ?: return@withLock Recheck.Settled
            val saleId = refund.saleId ?: return@withLock Recheck.Settled
            if (Duration.ofMillis(clock.millis() - refund.createdAt) > SavedCardCharges.RECHECK_WINDOW) {
                return@withLock Recheck.StillUnknown(Failure.NotSetUp(SetupProblem.CHARGE_TOO_OLD))
            }
            send(refund, saleId)?.let { Recheck.StillUnknown(it) } ?: Recheck.Settled
        }

    /** Sends [refund] of sale [saleId] and stores the answer; returns why nothing (or nothing certain) is known yet. */
    private suspend fun send(
        refund: RefundEntity,
        saleId: String,
    ): Failure? {
        val pending = refund.status == RefundStatus.PENDING
        val sale = sales.get(saleId)?.sale
        val psp = sale?.pspReference
        val blocked =
            when {
                !permits() -> SetupProblem.MANAGER_APPROVAL
                psp == null -> SetupProblem.PAYMENT_CONTEXT
                else -> null
            }
        if (blocked != null) return notSent(refund, pending, blocked)
        val access = target().modifications(sale?.context)
        val client =
            when (access) {
                is ApiAccess.Ready -> access.client
                is ApiAccess.Blocked -> return notSent(refund, pending && access is ApiAccess.Unavailable, access.problem)
            }
        val result =
            client.refund(
                checkNotNull(psp),
                ModificationAmount(refund.currency, refund.amountMinor),
                refund.merchantReference,
                idempotencyKey(refund.id),
            )
        return when (result) {
            is ModificationResult.Received, is ModificationResult.Authorised -> {
                val reference =
                    (result as? ModificationResult.Received)?.pspReference ?: (result as? ModificationResult.Authorised)?.pspReference
                refunds.settle(refund.id, RefundStatus.REQUESTED, null, null, pspReference = reference)
                onAccepted(refund.id)
                null
            }

            is ModificationResult.Refused -> {
                refunds.settle(refund.id, RefundStatus.FAILED, result.said?.text, null)
                null
            }

            is ModificationResult.Failed if result.fault.mayHaveTakenEffect -> {
                val failure = Failure.Remote(result.fault)
                refunds.settle(refund.id, RefundStatus.UNKNOWN, null, null, StoredReason.Unconfirmed(failure))
                failure
            }

            is ModificationResult.Failed -> {
                refunds.settle(refund.id, RefundStatus.FAILED, null, null, StoredReason.NotDone(Failure.Remote(result.fault)))
                null
            }
        }
    }

    /** Nothing was sent: a refund never sent before is stored as failed; an unknown one stays unknown. */
    private suspend fun notSent(
        refund: RefundEntity,
        record: Boolean,
        problem: SetupProblem,
    ): Failure {
        val failure = Failure.NotSetUp(problem)
        if (record) refunds.settle(refund.id, RefundStatus.FAILED, null, null, StoredReason.NotDone(failure))
        return failure
    }

    private suspend fun interrupted(id: String) {
        try {
            refunds.settle(id, RefundStatus.UNKNOWN, null, null, StoredReason.Unconfirmed(Failure.Device(DeviceFault.DATABASE)))
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: SQLException) {
            // It stays PENDING, which the next start turns into UNKNOWN.
        } catch (ignored: IllegalStateException) {
            // The database is closed; it stays PENDING, which the next start turns into UNKNOWN.
        }
    }

    /** Values shared with tests. */
    companion object {
        /** The idempotency key of refund [refundId]: one refund, however often it is sent. */
        fun idempotencyKey(refundId: String): String = "refund-$refundId"

        /** [request] as the new PENDING Checkout refund [id], opened at [createdAt] (epoch ms). */
        internal fun opened(
            id: String,
            request: RefundStart,
            createdAt: Long,
        ): RefundEntity =
            RefundEntity(
                id = id,
                saleId = request.saleId,
                createdAt = createdAt,
                merchantReference = request.merchantReference,
                originalTransactionId = request.originalTransactionId,
                originalTimestamp = request.originalTimestamp,
                originalReference = request.originalReference,
                currency = request.currency,
                amountMinor = request.amountMinor,
                full = request.full,
                status = RefundStatus.PENDING,
                linesJson = ReceiptLinesJson.encodeRefunded(request.lines),
                context = request.expectedContext,
                checkoutRefund = true,
            )
    }
}
