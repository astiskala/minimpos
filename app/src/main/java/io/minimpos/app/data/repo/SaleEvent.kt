package io.minimpos.app.data.repo

import io.minimpos.app.data.db.AdjustmentStatus
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.data.db.SetupProblem
import io.minimpos.app.data.db.StoredReason
import io.minimpos.terminal.checkout.ModificationResult
import io.minimpos.terminal.checkout.PaymentLink
import io.minimpos.terminal.checkout.PaymentLinkStatus
import io.minimpos.terminal.client.TransactionDetails

/**
 * Something that happened to a stored sale after it was opened, which moves it on ([after]). It is the one place that
 * decides which [SaleStatus], [CaptureStatus] and fields each happening writes, so callers name what happened rather
 * than the fields; [SaleRepository.record] applies it in a transaction. Pure: tested with plain JUnit, with
 * [io.minimpos.app.refund.PaymentStanding] as what can be observed. An accepted refund or cancellation, which also
 * changes the sale's lines, is [SaleRepository.applyRefund].
 *
 * Why something did not succeed is stored as Adyen or the terminal worded it (`message`) or, when the app itself says
 * so, as a [StoredReason] the screens word; each happening that writes one clears the other.
 */
sealed interface SaleEvent {
    /**
     * The payment is being sent to the terminal.
     *
     * @property poiId The POIID of the terminal it goes to.
     */
    data class Sending(
        val poiId: String,
    ) : SaleEvent

    /**
     * The terminal's transaction ended (or the app gave up on it), as its lifecycle classified it.
     *
     * @property status How it ended.
     * @property message Why it did not succeed, as the terminal worded it; null when it succeeded or did not say.
     * @property details What the terminal answered (transaction, card, receipts, saved card and the blob for
     *   synchronous adjustments); null when it did not answer, which keeps what is stored.
     * @property reason Why it did not succeed, when the app says so; null otherwise.
     */
    data class Settled(
        val status: SaleStatus,
        val message: String?,
        val details: TransactionDetails?,
        val reason: StoredReason? = null,
    ) : SaleEvent

    /**
     * Adyen answered about the sale's payment link: where it stands, with its ID, address and expiry (an expiry Adyen did
     * not send keeps the one chosen when the sale was opened).
     *
     * @property link What Adyen said.
     * @property cancelling Whether the link was being expired at the app's request, so a link that ended was cancelled
     *   rather than [SaleStatus.EXPIRED].
     */
    data class LinkAnswered(
        val link: PaymentLink,
        val cancelling: Boolean = false,
    ) : SaleEvent {
        /** Whether the link can still be paid, so the sale awaits its payment ([SaleStatus.AWAITING_PAYMENT]). */
        val stillOpen: Boolean get() = link.status == PaymentLinkStatus.ACTIVE || link.status == PaymentLinkStatus.PAYMENT_PENDING
    }

    /**
     * Adyen did not take what was sent (a payment link that was not created), so nothing was charged.
     *
     * @property message Why, as Adyen worded it.
     */
    data class NotSent(
        val message: String,
    ) : SaleEvent

    /**
     * Nothing was sent, because something must be set up first ([StoredReason.NotSetUp]), so nothing was charged.
     *
     * @property problem What.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : SaleEvent

    /**
     * It is not known whether what was sent took effect (a payment link that may or may not have been created):
     * [StoredReason.OutcomeUnknown].
     *
     * @property message What went wrong, as it was worded; null when nothing more is known.
     */
    data class OutcomeUnknown(
        val message: String? = null,
    ) : SaleEvent

    /**
     * The receipt was emailed.
     *
     * @property to The address it was sent to.
     */
    data class Emailed(
        val to: String,
    ) : SaleEvent

    /**
     * Adyen answered an adjustment of what the payment holds to [amountMinor]: it went through (synchronously, with a
     * new blob, or asynchronously, keeping the stored one), or it did not, which is only recorded as the reason.
     *
     * @property amountMinor The amount asked for.
     * @property result Adyen's answer.
     */
    data class AdjustmentAnswered(
        val amountMinor: Long,
        val result: ModificationResult,
    ) : SaleEvent

    /**
     * A capture of [amountMinor] is about to be sent to Adyen, with the tip entered for it.
     *
     * @property amountMinor The amount to capture.
     * @property tipMinor The tip written on the receipt; null keeps the stored one.
     */
    data class CaptureSending(
        val amountMinor: Long,
        val tipMinor: Long? = null,
    ) : SaleEvent

    /**
     * Adyen answered the capture being sent: received, not taken (it can be captured again) or unknown (it can be sent
     * again safely).
     *
     * @property result Adyen's answer.
     */
    data class CaptureAnswered(
        val result: ModificationResult,
    ) : SaleEvent

    /**
     * A capture or adjustment was not sent, because the Checkout API is only partly set up.
     *
     * @property problem What is missing.
     */
    data class ModificationNotSetUp(
        val problem: SetupProblem,
    ) : SaleEvent

    /**
     * The app stopped while the payment, or its capture, was being sent: a PENDING payment becomes unknown, and so does
     * a PENDING capture, which can then be sent again, both for [StoredReason.Interrupted]. Anything else is left alone.
     */
    data object Interrupted : SaleEvent
}

/** This sale after [event] happened to it. */
fun SaleEntity.after(event: SaleEvent): SaleEntity =
    when (event) {
        is SaleEvent.Sending -> copy(poiId = event.poiId)
        is SaleEvent.Settled -> settled(event)
        is SaleEvent.LinkAnswered -> linkAnswered(event)
        is SaleEvent.NotSent -> ended(SaleStatus.FAILED, event.message, null)
        is SaleEvent.NotSetUp -> ended(SaleStatus.FAILED, null, StoredReason.NotSetUp(event.problem))
        is SaleEvent.OutcomeUnknown -> ended(SaleStatus.UNKNOWN, event.message, StoredReason.OutcomeUnknown)
        is SaleEvent.Emailed -> copy(emailedTo = event.to)
        is SaleEvent.AdjustmentAnswered -> adjustmentAnswered(event.amountMinor, event.result)
        is SaleEvent.CaptureSending -> captureRecorded(CaptureStatus.PENDING, event.amountMinor, event.tipMinor)
        is SaleEvent.CaptureAnswered -> captureAnswered(event.result)
        is SaleEvent.ModificationNotSetUp -> modificationFailed(null, StoredReason.NotSetUp(event.problem))
        SaleEvent.Interrupted -> interrupted()
    }

private fun SaleEntity.ended(
    status: SaleStatus,
    message: String?,
    reason: StoredReason?,
): SaleEntity = copy(status = status, message = message, reason = reason)

private fun SaleEntity.settled(event: SaleEvent.Settled): SaleEntity {
    val ended = ended(event.status, event.message, event.reason)
    val details = event.details ?: return ended
    return ended.copy(
        poiTransactionId = details.poiTransactionId,
        poiTimestamp = details.poiTimestamp,
        pspReference = details.pspReference,
        paymentBrand = details.paymentBrand,
        paymentMethodVariant = details.paymentMethodVariant,
        maskedPan = details.maskedPan,
        entryMode = details.entryMode,
        authCode = details.approvalCode,
        errorCondition = details.errorCondition,
        refusalReason = details.refusalReason,
        storedPaymentMethodId = details.tokenization?.storedPaymentMethodId,
        customerReceiptJson = ReceiptLinesJson.encodeFields(details.customerReceipt),
        cashierReceiptJson = ReceiptLinesJson.encodeFields(details.cashierReceipt),
        signatureRequired = details.signatureRequired,
        adjustAuthorisationData = details.adjustAuthorisationData,
    )
}

private fun SaleEntity.linkAnswered(event: SaleEvent.LinkAnswered): SaleEntity {
    val link = event.link
    val status =
        when {
            event.stillOpen -> SaleStatus.AWAITING_PAYMENT
            link.status == PaymentLinkStatus.COMPLETED -> SaleStatus.APPROVED
            event.cancelling -> SaleStatus.CANCELLED
            else -> SaleStatus.EXPIRED
        }
    return ended(status, null, null).copy(
        paymentLinkId = link.id,
        paymentLinkUrl = link.url,
        paymentLinkExpiresAt = link.expiresAt?.toEpochMilli() ?: paymentLinkExpiresAt,
    )
}

private fun SaleEntity.modificationFailed(
    message: String?,
    reason: StoredReason?,
): SaleEntity = copy(modificationMessage = message, modificationReason = reason)

private fun SaleEntity.adjustmentAnswered(
    amountMinor: Long,
    result: ModificationResult,
): SaleEntity =
    when (result) {
        is ModificationResult.Authorised -> adjusted(amountMinor, AdjustmentStatus.AUTHORISED, result.adjustAuthorisationData)
        is ModificationResult.Received -> adjusted(amountMinor, AdjustmentStatus.REQUESTED, adjustAuthorisationData)
        is ModificationResult.Refused -> modificationFailed(result.reason, null)
        is ModificationResult.NotProcessed -> modificationFailed(result.message, null)
        is ModificationResult.Unknown -> modificationFailed(result.message, null)
    }

private fun SaleEntity.adjusted(
    amountMinor: Long,
    status: AdjustmentStatus,
    blob: String?,
): SaleEntity = copy(authorisedMinor = amountMinor, adjustment = status, adjustAuthorisationData = blob).modificationFailed(null, null)

private fun SaleEntity.captureRecorded(
    status: CaptureStatus,
    amountMinor: Long,
    tipMinor: Long?,
): SaleEntity =
    copy(captureStatus = status, capturedMinor = amountMinor, tipMinor = tipMinor ?: this.tipMinor).modificationFailed(null, null)

private fun SaleEntity.captureAnswered(result: ModificationResult): SaleEntity =
    when (result) {
        is ModificationResult.Received, is ModificationResult.Authorised -> captureAnswered(CaptureStatus.REQUESTED, null)
        is ModificationResult.Refused -> captureAnswered(CaptureStatus.FAILED, result.reason)
        is ModificationResult.NotProcessed -> captureAnswered(CaptureStatus.FAILED, result.message)
        is ModificationResult.Unknown -> captureAnswered(CaptureStatus.UNKNOWN, result.message)
    }

private fun SaleEntity.captureAnswered(
    status: CaptureStatus,
    message: String?,
): SaleEntity = copy(captureStatus = status).modificationFailed(message, null)

private fun SaleEntity.interrupted(): SaleEntity {
    val payment = if (status == SaleStatus.PENDING) ended(SaleStatus.UNKNOWN, null, StoredReason.Interrupted) else this
    return if (captureStatus == CaptureStatus.PENDING) {
        payment.copy(captureStatus = CaptureStatus.UNKNOWN).modificationFailed(null, StoredReason.Interrupted)
    } else {
        payment
    }
}
