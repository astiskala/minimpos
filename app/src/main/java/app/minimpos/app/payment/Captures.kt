package app.minimpos.app.payment

import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.repo.SaleEvent
import app.minimpos.app.data.repo.SaleRepository
import app.minimpos.app.refund.PaymentAction
import app.minimpos.app.refund.PaymentStanding
import app.minimpos.app.refund.StoredPayment
import app.minimpos.app.terminal.AdyenApi
import app.minimpos.app.terminal.ApiAccess
import app.minimpos.app.terminal.ApiTarget
import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What became of entering a tip, a capture or an authorisation adjustment, see [Captures]. */
sealed interface CaptureResult {
    /** Adyen received the capture; it confirms it in the Customer Area. */
    data object Requested : CaptureResult

    /** The adjustment went through: authorised, or received by Adyen when it is asynchronous. */
    data object Adjusted : CaptureResult

    /**
     * The card issuer refused the higher amount, so nothing was captured (and a tip was not saved).
     *
     * @property reason Adyen's refusal reason.
     */
    data class Refused(
        val reason: String,
    ) : CaptureResult

    /**
     * Adyen did not take the request, or its outcome is unknown (the capture can then be sent again safely).
     *
     * @property message Why, in English.
     */
    data class Failed(
        val message: String,
    ) : CaptureResult

    /**
     * Nothing was sent, because the Checkout API is not set up.
     *
     * @property problem What is missing.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : CaptureResult

    /** The payment no longer allows it (captured or cancelled meanwhile, or gone), or the amount is not valid. */
    data object NotAllowed : CaptureResult
}

/**
 * Finishes payments taken with manual capture: enters the tip of a sale taken for tipping on the receipt and captures
 * it, captures a pre-authorisation (adjusting it first when more is captured than it holds) and adjusts what a
 * pre-authorisation holds, through the Checkout API ([AdyenApi]); while it is not set up nothing is sent
 * ([CaptureResult.NotSetUp]). Which payments allow what is decided by their [StoredPayment.actions], the same reading
 * the screens use; anything else is [CaptureResult.NotAllowed].
 *
 * A tip above [PaymentStanding.TIP_ADJUSTMENT_PERCENT] of the bill first raises the authorisation to bill plus tip; if
 * the issuer refuses, the tip is not saved, so a smaller one can be entered. Otherwise the tip is saved and the total
 * captured (Adyen's overcapture). Every capture is stored as [CaptureStatus.PENDING] before it is sent and with an
 * idempotency key made from the sale and amount, so one whose outcome is unknown can be sent again
 * ([retryCapture]) without capturing twice. One operation runs at a time. A caller that must not lose an operation
 * when it is cancelled (a view model whose screen closes) runs it with `persisting`.
 *
 * @param sales Where the payments are stored, and their capture recorded.
 * @param target Where captures and adjustments go now: [AdyenApi.target] in the app, a fixed target in tests.
 * @param permits Whether the money-moving action is authorized now, checked under the operation lock.
 */
class Captures(
    private val sales: SaleRepository,
    private val target: suspend () -> ApiTarget,
    private val permits: suspend () -> Boolean = { true },
) {
    private val mutex = Mutex()

    /** Enters the tip of [saleId] ([tipMinor] in minor units, 0 for no tip) and captures the bill plus the tip. */
    suspend fun addTip(
        saleId: String,
        tipMinor: Long,
    ): CaptureResult =
        mutex.withLock {
            val target = target()
            val sale =
                allowing(saleId, PaymentAction.ENTER_TIP)?.takeIf { tipMinor >= 0 } ?: return@withLock CaptureResult.NotAllowed
            val total = sale.totalMinor + tipMinor
            if (PaymentStanding.tipNeedsAdjustment(sale.totalMinor, tipMinor) && total > sale.heldMinor) {
                // The tip is only saved once the higher amount is authorised.
                withApi(target, sale) { modifications ->
                    adjusted(sale, total, modifications) { capture(it, total, modifications, tipMinor) }
                }
            } else {
                withApi(target, sale) { capture(sale, total, it, tipMinor) }
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
            if (amountMinor > sale.heldMinor) {
                withApi(target, sale) { modifications ->
                    adjusted(sale, amountMinor, modifications) { capture(it, amountMinor, modifications) }
                }
            } else {
                withApi(target, sale) { capture(sale, amountMinor, it) }
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
            withApi(target, sale) { adjust(sale, amountMinor, it) }
        }

    /** Sends the capture of [saleId] again as it was, when its [StoredPayment.actions] include [PaymentAction.RETRY_CAPTURE]. */
    suspend fun retryCapture(saleId: String): CaptureResult =
        mutex.withLock {
            val target = target()
            val sale = allowing(saleId, PaymentAction.RETRY_CAPTURE)
            val amount = sale?.capturedMinor
            if (sale == null || amount == null) CaptureResult.NotAllowed else withApi(target, sale) { capture(sale, amount, it) }
        }

    /** The stored sale [saleId] when its [StoredPayment.actions] include [action]; else null. */
    private suspend fun allowing(
        saleId: String,
        action: PaymentAction,
    ): SaleEntity? =
        if (!permits()) {
            null
        } else {
            sales
                .get(saleId)
                ?.let(::StoredPayment)
                ?.takeIf { action in it.actions }
                ?.sale
        }

    /** Runs [send] with the Checkout API when [target] has it; else nothing is sent, and [sale] records why. */
    private suspend fun withApi(
        target: ApiTarget,
        sale: SaleEntity,
        send: suspend (PaymentModifications) -> CaptureResult,
    ): CaptureResult =
        when (val access = target.modifications(sale.context)) {
            is ApiAccess.Ready -> {
                send(access.client)
            }

            is ApiAccess.Blocked -> {
                if (access is ApiAccess.Unavailable) sales.record(sale.id, SaleEvent.ModificationNotSetUp(access.problem))
                CaptureResult.NotSetUp(access.problem)
            }
        }

    /** Adjusts [sale] to [amount] and, once that went through, runs [then] with the adjusted sale. */
    private suspend fun adjusted(
        sale: SaleEntity,
        amount: Long,
        modifications: PaymentModifications,
        then: suspend (SaleEntity) -> CaptureResult,
    ): CaptureResult {
        val result = adjust(sale, amount, modifications)
        val adjusted = sales.get(sale.id)?.sale
        return if (result == CaptureResult.Adjusted && adjusted != null) then(adjusted) else result
    }

    private suspend fun adjust(
        sale: SaleEntity,
        amount: Long,
        modifications: PaymentModifications,
    ): CaptureResult {
        val psp = sale.pspReference ?: return CaptureResult.NotAllowed
        // An unresolved request keeps its identity; a separate renewal or amount change gets a new identity.
        if (sale.adjustmentPending && sale.adjustmentAmountMinor != amount) return CaptureResult.NotAllowed
        val key = if (sale.adjustmentPending) checkNotNull(sale.adjustmentKey) else "adjust-${java.util.UUID.randomUUID()}"
        sales.record(sale.id, SaleEvent.AdjustmentSending(key, amount))
        val result =
            modifications.updateAmount(
                psp,
                ModificationAmount(sale.currency, amount),
                sale.merchantReference,
                sale.adjustAuthorisationData,
                key,
            )
        sales.record(sale.id, SaleEvent.AdjustmentAnswered(amount, result))
        return when (result) {
            is ModificationResult.Authorised, is ModificationResult.Received -> CaptureResult.Adjusted
            is ModificationResult.Refused -> CaptureResult.Refused(result.reason)
            is ModificationResult.NotProcessed -> CaptureResult.Failed(result.message)
            is ModificationResult.Unknown -> CaptureResult.Failed(result.message)
        }
    }

    private suspend fun capture(
        sale: SaleEntity,
        amount: Long,
        modifications: PaymentModifications,
        tipMinor: Long? = null,
    ): CaptureResult {
        val psp = sale.pspReference ?: return CaptureResult.NotAllowed
        sales.record(sale.id, SaleEvent.CaptureSending(amount, tipMinor))
        val result =
            modifications.capture(
                psp,
                ModificationAmount(sale.currency, amount),
                sale.merchantReference,
                "capture-${sale.id}-$amount",
            )
        sales.record(sale.id, SaleEvent.CaptureAnswered(result))
        return when (result) {
            is ModificationResult.Received, is ModificationResult.Authorised -> CaptureResult.Requested
            is ModificationResult.Refused -> CaptureResult.Failed(result.reason)
            is ModificationResult.NotProcessed -> CaptureResult.Failed(result.message)
            is ModificationResult.Unknown -> CaptureResult.Failed(result.message)
        }
    }
}
