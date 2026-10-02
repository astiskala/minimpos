package io.minimpos.app.payment

import io.minimpos.app.data.db.AdjustmentStatus
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.refund.PaymentAction
import io.minimpos.app.refund.PaymentStanding
import io.minimpos.app.refund.StoredPayment
import io.minimpos.app.terminal.AdyenApi
import io.minimpos.app.terminal.ApiSetup
import io.minimpos.app.terminal.ApiTarget
import io.minimpos.app.terminal.SetupProblem
import io.minimpos.terminal.checkout.ModificationAmount
import io.minimpos.terminal.checkout.ModificationResult
import io.minimpos.terminal.checkout.PaymentModifications
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What became of entering a tip, a capture or an authorisation adjustment, see [Captures]. */
sealed interface CaptureResult {
    /** Adyen received the capture; it confirms it in the Customer Area. */
    data object Requested : CaptureResult

    /** No Checkout API is set up: the amount was recorded, for staff to capture in the Customer Area. */
    data object Recorded : CaptureResult

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
     * Adyen did not take the request, its outcome is unknown (the capture can then be sent again safely), or the API is
     * not set up.
     *
     * @property message Why, in English.
     */
    data class Failed(
        val message: String,
    ) : CaptureResult

    /** The payment no longer allows it (captured or cancelled meanwhile, or gone), or the amount is not valid. */
    data object NotAllowed : CaptureResult
}

/**
 * Finishes payments taken with manual capture: enters the tip of a sale taken for tipping on the receipt and captures
 * it, captures a pre-authorisation (adjusting it first when more is captured than it holds) and adjusts what a
 * pre-authorisation holds. Through the Checkout API when it is set up ([AdyenApi]); otherwise the amount to capture is
 * only recorded ([CaptureStatus.MANUAL]) for staff to capture in the Customer Area. Which payments allow what is decided
 * by their [StoredPayment.actions] with the target's [ApiSetup.mode], the same reading the screens use; anything
 * else is [CaptureResult.NotAllowed].
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
 * @param describe Words what is missing when the API is only partly set up, for the message stored with the sale.
 */
class Captures(
    private val sales: SaleRepository,
    private val target: suspend () -> ApiTarget,
    private val describe: (SetupProblem) -> String = { it.name },
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
                allowing(saleId, PaymentAction.ENTER_TIP, target)?.takeIf { tipMinor >= 0 } ?: return@withLock CaptureResult.NotAllowed
            val total = sale.totalMinor + tipMinor
            if (PaymentStanding.tipNeedsAdjustment(sale.totalMinor, tipMinor) && total > sale.heldMinor) {
                // The tip is only saved once the higher amount is authorised.
                withApi(target, sale, total, tipMinor) { modifications ->
                    adjusted(sale, total, modifications) { capture(it, total, modifications, tipMinor) }
                }
            } else {
                withApi(target, sale, total, tipMinor) { capture(sale, total, it, tipMinor) }
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
                allowing(saleId, PaymentAction.CAPTURE, target)?.takeIf { amountMinor > 0 } ?: return@withLock CaptureResult.NotAllowed
            if (amountMinor > sale.heldMinor) {
                withApi(target, sale, amountMinor) { modifications ->
                    adjusted(sale, amountMinor, modifications) { capture(it, amountMinor, modifications) }
                }
            } else {
                withApi(target, sale, amountMinor) { capture(sale, amountMinor, it) }
            }
        }

    /**
     * Changes what the pre-authorisation [saleId] holds to [amountMinor] (the same amount extends the authorisation),
     * without capturing. Needs the Checkout API ([PaymentAction.ADJUST]): an adjustment cannot be left to the Customer
     * Area, so in that mode it is [CaptureResult.NotAllowed].
     */
    suspend fun adjust(
        saleId: String,
        amountMinor: Long,
    ): CaptureResult =
        mutex.withLock {
            val target = target()
            val sale =
                allowing(saleId, PaymentAction.ADJUST, target)?.takeIf { amountMinor > 0 } ?: return@withLock CaptureResult.NotAllowed
            withApi(target, sale, amountMinor) { adjust(sale, amountMinor, it) }
        }

    /** Sends the capture of [saleId] again as it was, when its [StoredPayment.actions] include [PaymentAction.RETRY_CAPTURE]. */
    suspend fun retryCapture(saleId: String): CaptureResult =
        mutex.withLock {
            val target = target()
            val sale = allowing(saleId, PaymentAction.RETRY_CAPTURE, target)
            val amount = sale?.capturedMinor
            if (sale == null || amount == null) CaptureResult.NotAllowed else withApi(target, sale, amount) { capture(sale, amount, it) }
        }

    /** The stored sale [saleId] when, with [target]'s capture mode, its [StoredPayment.actions] include [action]; else null. */
    private suspend fun allowing(
        saleId: String,
        action: PaymentAction,
        target: ApiTarget,
    ): SaleEntity? =
        sales
            .get(saleId)
            ?.let { StoredPayment(it, target.setup.mode) }
            ?.takeIf { action in it.actions }
            ?.sale

    /**
     * Runs [send] with the Checkout API when [target] is it. Without one, the capture of [amount] of [sale] (with
     * [tipMinor], when a tip is entered) is left to the Customer Area; when it is only partly set up, nothing is sent.
     */
    private suspend fun withApi(
        target: ApiTarget,
        sale: SaleEntity,
        amount: Long,
        tipMinor: Long? = null,
        send: suspend (PaymentModifications) -> CaptureResult,
    ): CaptureResult {
        val modifications = target.modifications
        val problem = target.setup.problem
        return when {
            modifications != null -> {
                send(modifications)
            }

            problem != null -> {
                fail(sale, describe(problem))
            }

            else -> {
                sales.recordCapture(sale.id, CaptureStatus.MANUAL, amount, tipMinor)
                CaptureResult.Recorded
            }
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
        // The amount held before is part of the key, so adjusting back to an earlier amount is a new request.
        val key = "adjust-${sale.id}-${sale.heldMinor}-$amount"
        val result =
            modifications.updateAmount(
                psp,
                ModificationAmount(sale.currency, amount),
                sale.merchantReference,
                sale.adjustAuthorisationData,
                key,
            )
        return when (result) {
            is ModificationResult.Authorised -> {
                sales.recordAdjustment(sale.id, amount, AdjustmentStatus.AUTHORISED, result.adjustAuthorisationData)
                CaptureResult.Adjusted
            }

            is ModificationResult.Received -> {
                sales.recordAdjustment(sale.id, amount, AdjustmentStatus.REQUESTED)
                CaptureResult.Adjusted
            }

            is ModificationResult.Refused -> {
                sales.modificationFailed(sale.id, result.reason)
                CaptureResult.Refused(result.reason)
            }

            is ModificationResult.NotProcessed -> {
                fail(sale, result.message)
            }

            is ModificationResult.Unknown -> {
                fail(sale, result.message)
            }
        }
    }

    private suspend fun capture(
        sale: SaleEntity,
        amount: Long,
        modifications: PaymentModifications,
        tipMinor: Long? = null,
    ): CaptureResult {
        val psp = sale.pspReference ?: return CaptureResult.NotAllowed
        sales.recordCapture(sale.id, CaptureStatus.PENDING, amount, tipMinor)
        val result =
            modifications.capture(
                psp,
                ModificationAmount(sale.currency, amount),
                sale.merchantReference,
                "capture-${sale.id}-$amount",
            )
        val (status, outcome) =
            when (result) {
                is ModificationResult.Received, is ModificationResult.Authorised -> CaptureStatus.REQUESTED to CaptureResult.Requested
                is ModificationResult.Refused -> CaptureStatus.FAILED to CaptureResult.Failed(result.reason)
                is ModificationResult.NotProcessed -> CaptureStatus.FAILED to CaptureResult.Failed(result.message)
                is ModificationResult.Unknown -> CaptureStatus.UNKNOWN to CaptureResult.Failed(result.message)
            }
        sales.recordCapture(sale.id, status, message = (outcome as? CaptureResult.Failed)?.message)
        return outcome
    }

    private suspend fun fail(
        sale: SaleEntity,
        message: String,
    ): CaptureResult {
        sales.modificationFailed(sale.id, message)
        return CaptureResult.Failed(message)
    }
}
