package io.minimpos.app.payment

import io.minimpos.app.data.db.AdjustmentStatus
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.repo.SaleRepository
import io.minimpos.app.refund.PaymentHold
import io.minimpos.app.terminal.AdyenApi
import io.minimpos.app.terminal.ApiTarget
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
 * by [PaymentHold].
 *
 * A tip above [PaymentHold.TIP_ADJUSTMENT_PERCENT] of the bill first raises the authorisation to bill plus tip; if the
 * issuer refuses, the tip is not saved, so a smaller one can be entered. Otherwise the tip is saved and the total
 * captured (Adyen's overcapture). Every capture is stored as [CaptureStatus.PENDING] before it is sent and with an
 * idempotency key made from the sale and amount, so one whose outcome is unknown can be sent again
 * ([retryCapture]) without capturing twice. One operation runs at a time. A caller that must not lose an operation
 * when it is cancelled (a view model whose screen closes) runs it with `persisting`.
 *
 * @param sales Where the payments are stored and updated.
 * @param api Where captures and adjustments go.
 */
class Captures(
    private val sales: SaleRepository,
    private val api: AdyenApi,
) {
    private val mutex = Mutex()

    /** Enters the tip of [saleId] ([tipMinor] in minor units, 0 for no tip) and captures the bill plus the tip. */
    suspend fun addTip(
        saleId: String,
        tipMinor: Long,
    ): CaptureResult =
        mutex.withLock {
            val sale = sales.get(saleId)?.sale?.takeIf { PaymentHold.canEnterTip(it) && tipMinor >= 0 }
            val tipped = sale?.copy(tipMinor = tipMinor)
            when {
                sale == null || tipped == null -> {
                    CaptureResult.NotAllowed
                }

                PaymentHold.needsAdjustment(sale.totalMinor, tipMinor) && tipped.amountMinor > sale.heldMinor -> {
                    // The tip is only saved once the higher amount is authorised.
                    withApi(tipped, tipped.amountMinor) { modifications ->
                        adjusted(
                            sale,
                            tipped.amountMinor,
                            modifications,
                        ) { capture(it.copy(tipMinor = tipMinor), tipped.amountMinor, modifications) }
                    }
                }

                else -> {
                    withApi(tipped, tipped.amountMinor) { capture(tipped, tipped.amountMinor, it) }
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
            val sale = sales.get(saleId)?.sale?.takeIf { PaymentHold.canCapture(it) && amountMinor > 0 }
            when {
                sale == null -> {
                    CaptureResult.NotAllowed
                }

                amountMinor > sale.heldMinor -> {
                    withApi(sale, amountMinor) { modifications ->
                        adjusted(sale, amountMinor, modifications) { capture(it, amountMinor, modifications) }
                    }
                }

                else -> {
                    withApi(sale, amountMinor) { capture(sale, amountMinor, it) }
                }
            }
        }

    /**
     * Changes what the pre-authorisation [saleId] holds to [amountMinor] (the same amount extends the authorisation),
     * without capturing. Needs the Checkout API: in the Customer Area mode it is [CaptureResult.NotAllowed].
     */
    suspend fun adjust(
        saleId: String,
        amountMinor: Long,
    ): CaptureResult =
        mutex.withLock {
            val sale = sales.get(saleId)?.sale?.takeIf { PaymentHold.canCapture(it) && amountMinor > 0 }
            when (val target = if (sale == null) null else api.target()) {
                null, ApiTarget.CustomerArea -> CaptureResult.NotAllowed
                is ApiTarget.NotSetUp -> fail(checkNotNull(sale), target.message)
                is ApiTarget.Ready -> adjust(checkNotNull(sale), amountMinor, target.modifications)
            }
        }

    /** Sends the capture of [saleId] again as it was, when [PaymentHold.canRetryCapture] allows it. */
    suspend fun retryCapture(saleId: String): CaptureResult =
        mutex.withLock {
            val sale = sales.get(saleId)?.sale?.takeIf(PaymentHold::canRetryCapture)
            val amount = sale?.capturedMinor
            if (sale == null || amount == null) CaptureResult.NotAllowed else withApi(sale, amount) { capture(sale, amount, it) }
        }

    /**
     * Runs [send] with the Checkout API when it is set up. Without one, [sale] is stored with the capture of [amount]
     * left to the Customer Area; when it is only partly set up, nothing is sent.
     */
    private suspend fun withApi(
        sale: SaleEntity,
        amount: Long,
        send: suspend (PaymentModifications) -> CaptureResult,
    ): CaptureResult =
        when (val target = api.target()) {
            is ApiTarget.Ready -> {
                send(target.modifications)
            }

            is ApiTarget.NotSetUp -> {
                fail(sale, target.message)
            }

            ApiTarget.CustomerArea -> {
                sales.update(sale.copy(capturedMinor = amount, captureStatus = CaptureStatus.MANUAL, modificationMessage = null))
                CaptureResult.Recorded
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
        val (updated, outcome) =
            when (result) {
                is ModificationResult.Authorised -> {
                    sale.copy(
                        authorisedMinor = amount,
                        adjustment = AdjustmentStatus.AUTHORISED,
                        adjustAuthorisationData = result.adjustAuthorisationData,
                        modificationMessage = null,
                    ) to CaptureResult.Adjusted
                }

                is ModificationResult.Received -> {
                    sale.copy(authorisedMinor = amount, adjustment = AdjustmentStatus.REQUESTED, modificationMessage = null) to
                        CaptureResult.Adjusted
                }

                is ModificationResult.Refused -> {
                    sale.copy(modificationMessage = result.reason) to CaptureResult.Refused(result.reason)
                }

                is ModificationResult.NotProcessed -> {
                    sale.copy(modificationMessage = result.message) to CaptureResult.Failed(result.message)
                }

                is ModificationResult.Unknown -> {
                    sale.copy(modificationMessage = result.message) to CaptureResult.Failed(result.message)
                }
            }
        sales.update(updated)
        return outcome
    }

    private suspend fun capture(
        sale: SaleEntity,
        amount: Long,
        modifications: PaymentModifications,
    ): CaptureResult {
        val psp = sale.pspReference ?: return CaptureResult.NotAllowed
        val pending = sale.copy(capturedMinor = amount, captureStatus = CaptureStatus.PENDING, modificationMessage = null)
        sales.update(pending)
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
        sales.update(pending.copy(captureStatus = status, modificationMessage = (outcome as? CaptureResult.Failed)?.message))
        return outcome
    }

    private suspend fun fail(
        sale: SaleEntity,
        message: String,
    ): CaptureResult {
        sales.get(sale.id)?.sale?.let { sales.update(it.copy(modificationMessage = message)) }
        return CaptureResult.Failed(message)
    }
}
