package app.minimpos.app.data.repo

import app.minimpos.app.data.db.AdjustmentStatus
import app.minimpos.app.data.db.CaptureStatus
import app.minimpos.app.data.db.Failure
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.data.db.SaleWithLines
import app.minimpos.app.data.db.SetupProblem
import app.minimpos.app.data.db.StoredReason
import app.minimpos.core.payment.PaymentContext
import app.minimpos.core.split.CashPayment
import app.minimpos.terminal.checkout.CardChargeResult
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentLink
import app.minimpos.terminal.checkout.PaymentLinkStatus
import app.minimpos.terminal.client.OfflineProcessingMode
import app.minimpos.terminal.client.TransactionDetails
import app.minimpos.terminal.transport.Fault
import java.time.Instant

/**
 * Something that happened to a stored sale after it was opened, which moves it on ([after]). It is the one place that
 * decides which [SaleStatus], [CaptureStatus] and fields each happening writes, and when each happened ([stamped]), so
 * callers name what happened rather than the fields or times; [SaleRepository.record] applies it in a transaction.
 * Pure: tested with plain JUnit, with [app.minimpos.app.history.PaymentStanding] as what can be observed. An accepted
 * refund or cancellation, which also changes the sale's lines, is [SaleRepository.applyRefund].
 *
 * Why something did not succeed is stored as the terminal or Adyen answered it (`message`, verbatim) or as a typed
 * [StoredReason] the screens word, which also keeps the words of a fault; each happening that writes one clears the
 * other.
 */
sealed interface SaleEvent {
    /** Local cash recordings, dispatched together without terminal outcomes. */
    sealed interface CashEvent : SaleEvent

    /** Explicit operator cash handover, stamped only at persistence. */
    data class CashHandedOver(
        /** Recording time in epoch milliseconds; null until persisted. */
        val at: Long? = null,
    ) : CashEvent {
        override fun stamped(now: Long): SaleEvent = copy(at = at ?: now)
    }

    /**
     * This event with the time it records stamped: the happening's own ([Settled.processedAt],
     * [CaptureSending.startedAt], [CaptureAnswered.processedAt]) taken from the terminal's own timestamp when it sent
     * one, else from [now] (epoch ms, see [SaleRepository.record]), unless already known. Pure, like the event itself;
     * the caller supplies the clock.
     */
    fun stamped(now: Long): SaleEvent

    /**
     * The payment is being sent to the terminal.
     *
     * @property poiId The POIID of the terminal it goes to.
     */
    data class Sending(
        val poiId: String,
    ) : SaleEvent {
        override fun stamped(now: Long) = this
    }

    /**
     * Opens the next card attempt of a partially paid sale before it can be sent; [SaleRepository.openTender] refuses a
     * stale or excessive one without replacing the stored identity.
     * @property serviceId Original ServiceID for this attempt and all its status checks.
     * @property amountMinor Frozen requested purchase amount in minor units, at most the unpaid balance.
     * @property paidMinor Purchase already paid when the attempt was reviewed; a different stored amount is stale.
     */
    data class TenderOpened(
        val serviceId: String,
        val amountMinor: Long,
        val paidMinor: Long,
    ) : SaleEvent {
        override fun stamped(now: Long) = this
    }

    /**
     * Cash was handed over for [cash]'s amount and recorded after explicit confirmation, without a Terminal API call.
     * @property cash Amount applied and cash received, in minor units.
     * @property paidMinor Purchase already paid when the handover was reviewed; a different stored amount is stale.
     * @property processedAt Recording time in epoch milliseconds; null until persistence stamps it.
     */
    data class CashTendered(
        val cash: CashPayment,
        val paidMinor: Long,
        val processedAt: Long? = null,
    ) : CashEvent {
        override fun stamped(now: Long): SaleEvent = copy(processedAt = processedAt ?: now)
    }

    /**
     * The terminal's transaction ended (or the app gave up on it), as its lifecycle classified it.
     *
     * @property status How it ended.
     * @property message Why it did not succeed, as the terminal worded it; null when it succeeded or did not say.
     * @property details What the terminal answered (transaction, card, receipts, saved card and the blob for
     *   synchronous adjustments); null when it did not answer, which keeps what is stored.
     * @property reason Why it did not succeed, when the app says so; null otherwise.
     * @property processedAt Approval time in epoch milliseconds; null without a known approval date.
     * @property busyServiceId The ServiceID of the transaction a `Busy` refusal named, which can be aborted once; null
     *   otherwise. Only the sale's own event writes it, and [BusyAborted] clears it.
     * @property repeated Whether the same request already has a durable approved tender.
     */
    data class Settled(
        val status: SaleStatus,
        val message: String?,
        val details: TransactionDetails?,
        val reason: StoredReason? = null,
        /** Terminal approval time in epoch milliseconds; null when unavailable. */
        val processedAt: Long? = null,
        /** The ServiceID a `Busy` refusal named; see [busyServiceId]. */
        val busyServiceId: String? = null,
        /** This request already has a durable tender; refresh its answer without adding its amounts again. */
        val repeated: Boolean = false,
    ) : SaleEvent {
        override fun stamped(now: Long): SaleEvent =
            copy(
                processedAt =
                    processedAt
                        ?: details?.poiTimestamp?.let {
                            runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                        }
                        ?: now,
            )
    }

    /**
     * The terminal was asked to stop the transaction a `Busy` refusal named, so it cannot be asked again; whether it
     * actually stopped arrives with the refused transaction itself.
     */
    data object BusyAborted : SaleEvent {
        override fun stamped(now: Long) = this
    }

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
        override fun stamped(now: Long) = this

        /** Whether the link can still be paid, so the sale awaits its payment ([SaleStatus.AWAITING_PAYMENT]). */
        val stillOpen: Boolean get() = link.status == PaymentLinkStatus.ACTIVE || link.status == PaymentLinkStatus.PAYMENT_PENDING
    }

    /**
     * Adyen did not take what was sent (a payment link that was not created), so nothing was charged.
     *
     * @property fault Why; one that took no effect.
     */
    data class NotSent(
        val fault: Fault,
    ) : NotTaken {
        override fun stamped(now: Long) = this

        override val failure: Failure get() = Failure.Remote(fault)
    }

    /**
     * Nothing was sent, because something must be set up first ([Failure.NotSetUp]), so nothing was charged.
     *
     * @property problem What.
     */
    data class NotSetUp(
        val problem: SetupProblem,
    ) : NotTaken {
        override fun stamped(now: Long) = this

        override val failure: Failure get() = Failure.NotSetUp(problem)
    }

    /** Nothing was charged: what was sent was not taken ([NotSent]) or nothing could be sent ([NotSetUp]). */
    sealed interface NotTaken : SaleEvent {
        /** Why, as the sale stores it. */
        val failure: Failure
    }

    /**
     * It is not known whether what was sent took effect (a payment link that may or may not have been created):
     * [StoredReason.Unconfirmed].
     *
     * @property failure What prevented the confirmation; null when nothing more is known.
     */
    data class OutcomeUnknown(
        val failure: Failure? = null,
    ) : SaleEvent {
        override fun stamped(now: Long) = this
    }

    /**
     * The receipt was emailed.
     *
     * @property to The address it was sent to.
     */
    data class Emailed(
        val to: String,
    ) : SaleEvent {
        override fun stamped(now: Long) = this
    }

    /** A capture or adjustment happening, grouped for how it moves the sale on ([modified]). */
    sealed interface ModificationEvent : SaleEvent

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
    ) : SaleEvent,
        ModificationEvent {
        override fun stamped(now: Long) = this
    }

    /**
     * A capture of [amountMinor] is about to be sent to Adyen, with the tip entered for it.
     *
     * @property amountMinor The amount to capture.
     * @property tipMinor The tip written on the receipt; null keeps the stored one.
     * @property startedAt Send time in epoch milliseconds; null without a known time.
     */
    data class CaptureSending(
        val amountMinor: Long,
        val tipMinor: Long? = null,
        /** Persisted send time in epoch milliseconds; null only without a known time. */
        val startedAt: Long? = null,
    ) : SaleEvent,
        ModificationEvent {
        override fun stamped(now: Long): SaleEvent = copy(startedAt = startedAt ?: now)
    }

    /**
     * Adyen answered the capture being sent: received, not taken (it can be captured again) or unknown (it can be sent
     * again safely).
     *
     * @property result Adyen's answer.
     * @property processedAt Time acceptance was observed in epoch milliseconds; null when unavailable.
     */
    data class CaptureAnswered(
        val result: ModificationResult,
        /** Time acceptance was observed in epoch milliseconds; null when unavailable. */
        val processedAt: Long? = null,
    ) : SaleEvent,
        ModificationEvent {
        override fun stamped(now: Long): SaleEvent = copy(processedAt = processedAt ?: now)
    }

    /**
     * A capture or adjustment was not sent, because the Checkout API is only partly set up.
     *
     * @property problem What is missing.
     */
    data class ModificationNotSetUp(
        val problem: SetupProblem,
    ) : SaleEvent,
        ModificationEvent {
        override fun stamped(now: Long) = this
    }

    /**
     * The app stopped while the payment, or its capture, was being sent: a PENDING payment becomes unknown, and so does
     * a PENDING capture, which can then be sent again, both for [StoredReason.Interrupted]. Anything else is left alone.
     */
    data object Interrupted : SaleEvent {
        override fun stamped(now: Long) = this
    }

    /**
     * Records the non-secret connection before sending, then its certificate environment once answered.
     * @property context Actual original connection facts.
     */
    data class ContextRecorded(
        val context: PaymentContext,
    ) : SaleEvent {
        override fun stamped(now: Long) = this
    }

    /**
     * Adyen answered the charge of a saved card ([SaleEntity.savedCard]): charged ([SaleStatus.APPROVED], with its PSP
     * reference standing in as the transaction ID `.pspReference` that Adyen uses for payments not taken on a terminal),
     * refused ([SaleStatus.DECLINED]), not taken ([SaleStatus.FAILED]) or unknown ([SaleStatus.UNKNOWN]).
     *
     * @property result What Adyen answered.
     * @property processedAt When the answer arrived, in epoch milliseconds.
     */
    data class CardAnswered(
        val result: CardChargeResult,
        val processedAt: Long,
    ) : SaleEvent {
        override fun stamped(now: Long) = this
    }

    /**
     * Persists adjustment identity and amount before it can take effect.
     * @property key Logical operation identity reused until its outcome is known.
     * @property amountMinor Frozen adjustment amount, in minor units.
     * @property startedAt First-send time in epoch milliseconds; null until persistence stamps it.
     */
    data class AdjustmentSending(
        val key: String,
        val amountMinor: Long,
        val startedAt: Long? = null,
    ) : SaleEvent {
        override fun stamped(now: Long): SaleEvent = copy(startedAt = startedAt ?: now)
    }
}

/** Whether this record is settled enough to remove; unresolved transactions, links and held amounts remain actionable. */
val SaleEntity.retentionEligible: Boolean
    get() =
        when (status) {
            SaleStatus.PENDING, SaleStatus.UNKNOWN, SaleStatus.AWAITING_PAYMENT -> {
                false
            }

            SaleStatus.APPROVED -> {
                !cashAwaitingHandover && !adjustmentPending && !isPartiallyPaid &&
                    (!manualCapture || holdCancelled || captureStatus?.captured == true)
            }

            SaleStatus.DECLINED, SaleStatus.CANCELLED, SaleStatus.FAILED, SaleStatus.EXPIRED -> {
                true
            }
        }

/** This sale after [event] happened to it. */
fun SaleEntity.after(event: SaleEvent): SaleEntity =
    when (event) {
        is SaleEvent.CashEvent -> {
            cashEvent(event)
        }

        is SaleEvent.Sending -> {
            copy(poiId = event.poiId)
        }

        is SaleEvent.TenderOpened -> {
            openedTender(event)
        }

        is SaleEvent.Settled -> {
            settled(event)
        }

        is SaleEvent.LinkAnswered -> {
            linkAnswered(event)
        }

        is SaleEvent.NotTaken -> {
            ended(SaleStatus.FAILED, null, StoredReason.NotDone(event.failure))
        }

        is SaleEvent.OutcomeUnknown -> {
            ended(SaleStatus.UNKNOWN, null, StoredReason.Unconfirmed(event.failure))
        }

        is SaleEvent.Emailed -> {
            copy(emailedTo = event.to)
        }

        is SaleEvent.ModificationEvent -> {
            modified(event)
        }

        SaleEvent.BusyAborted -> {
            copy(busyServiceId = null)
        }

        SaleEvent.Interrupted -> {
            interrupted()
        }

        is SaleEvent.ContextRecorded, is SaleEvent.AdjustmentSending -> {
            prepared(event)
        }

        is SaleEvent.CardAnswered -> {
            cardAnswered(event)
        }
    }

/** Freezes the next attempt only while the reviewed purchase balance is still known and current. */
private fun SaleEntity.openedTender(event: SaleEvent.TenderOpened): SaleEntity {
    require(acceptsTender(event.amountMinor, event.paidMinor)) { "Payment balance changed" }
    return copy(status = SaleStatus.PENDING, serviceId = event.serviceId, tenderRequestedMinor = event.amountMinor, busyServiceId = null)
}

/** Applies confirmed cash to the purchase balance; cash adds no tip, surcharge or cashback. */
private fun SaleEntity.cashTendered(event: SaleEvent.CashTendered): SaleEntity {
    val amount = event.cash.amountMinor
    require(acceptsTender(amount, event.paidMinor)) { "Payment balance changed" }
    val collected = terminalPaidMinor ?: amountMinor
    return copy(
        paidMinor = Math.addExact(paidMinor, amount),
        terminalPaidMinor = Math.addExact(collected, amount),
        surchargeMinor = accumulatedComponent(surchargeMinor, 0),
        cashBackMinor = accumulatedComponent(cashBackMinor, 0),
        tenderRequestedMinor = amount,
        busyServiceId = null,
        message = null,
        reason = null,
    )
}

/**
 * Whether a further tender of [amountMinor] may be opened against this partially paid sale, as reviewed with
 * [reviewedPaidMinor] already paid: its purchase balance is known, unchanged since the review and covers the amount.
 */
fun SaleEntity.acceptsTender(
    amountMinor: Long,
    reviewedPaidMinor: Long,
): Boolean = remainingAmountKnown && isPartiallyPaid && paidMinor == reviewedPaidMinor && amountMinor in 1..remainingMinor

/** New partial responses with an unreported surcharge cannot establish the remaining purchase amount. */
val SaleEntity.remainingAmountKnown: Boolean get() = terminalPaidMinor == null || surchargeMinor != null

/** How a capture or adjustment happening moves the sale on: what was sent, what Adyen answered, or what was missing. */
private fun SaleEntity.modified(event: SaleEvent.ModificationEvent): SaleEntity =
    when (event) {
        is SaleEvent.AdjustmentAnswered -> {
            adjustmentAnswered(event.amountMinor, event.result).copy(adjustmentPending = event.result.unconfirmed)
        }

        is SaleEvent.CaptureSending -> {
            captureSending(event)
        }

        is SaleEvent.CaptureAnswered -> {
            captureAccepted(event)
        }

        is SaleEvent.ModificationNotSetUp -> {
            modificationFailed(null, StoredReason.NotDone(Failure.NotSetUp(event.problem)))
        }
    }

private fun SaleEntity.cardAnswered(event: SaleEvent.CardAnswered): SaleEntity =
    when (val result = event.result) {
        is CardChargeResult.Authorised -> {
            ended(SaleStatus.APPROVED, null, null).copy(
                pspReference = result.pspReference,
                poiTransactionId = ".${result.pspReference}",
                poiTimestamp = Instant.ofEpochMilli(event.processedAt).toString(),
                paymentBrand = result.brand ?: paymentBrand,
                maskedPan = result.lastFour?.let { "**** $it" } ?: maskedPan,
                authCode = result.authCode,
                processedAt = processedAt ?: event.processedAt,
            )
        }

        is CardChargeResult.Refused -> {
            ended(SaleStatus.DECLINED, result.said?.text, null).copy(pspReference = result.pspReference ?: pspReference)
        }

        is CardChargeResult.Failed -> {
            if (result.fault.mayHaveTakenEffect) {
                ended(SaleStatus.UNKNOWN, null, StoredReason.Unconfirmed(Failure.Remote(result.fault)))
            } else {
                ended(SaleStatus.FAILED, null, StoredReason.NotDone(Failure.Remote(result.fault)))
            }
        }
    }

private fun SaleEntity.captureSending(event: SaleEvent.CaptureSending): SaleEntity {
    if (captureStatus?.captured == true) return this
    val startedAt = if (capturedMinor == event.amountMinor && captureStartedAt != null) captureStartedAt else event.startedAt
    return captureRecorded(CaptureStatus.PENDING, event.amountMinor, event.tipMinor).copy(captureStartedAt = startedAt)
}

private fun SaleEntity.captureAccepted(event: SaleEvent.CaptureAnswered): SaleEntity {
    if (captureStatus?.captured == true) return this
    val answered = captureAnswered(event.result)
    return answered.copy(captureProcessedAt = if (answered.captureStatus?.captured == true) event.processedAt else null)
}

private fun SaleEntity.prepared(event: SaleEvent): SaleEntity =
    if (event is SaleEvent.ContextRecorded) {
        copy(context = event.context)
    } else {
        check(event is SaleEvent.AdjustmentSending)
        copy(
            adjustmentKey = event.key,
            adjustmentAmountMinor = event.amountMinor,
            adjustmentPending = true,
            adjustmentStartedAt = if (adjustmentKey == event.key) adjustmentStartedAt else event.startedAt,
        )
    }

private fun SaleEntity.ended(
    status: SaleStatus,
    message: String?,
    reason: StoredReason?,
): SaleEntity = copy(status = status, message = message, reason = reason)

private fun SaleEntity.settled(event: SaleEvent.Settled): SaleEntity {
    val effectiveStatus = if (paidMinor > 0 && event.status != SaleStatus.UNKNOWN) SaleStatus.APPROVED else event.status
    val ended =
        ended(effectiveStatus, event.message, event.reason).copy(
            processedAt = processedAt ?: event.processedAt.takeIf { effectiveStatus == SaleStatus.APPROVED && !paymentLink },
            busyServiceId = event.busyServiceId ?: busyServiceId,
        )
    return event.details?.let { answered(event, ended, it) } ?: ended
}

/** [ended] with the transaction, card, receipt and tipping facts of the terminal's [details] answer. */
private fun SaleEntity.answered(
    event: SaleEvent.Settled,
    ended: SaleEntity,
    details: TransactionDetails,
): SaleEntity {
    val (terminalTipMinor, updatedPaidMinor) = settledAmounts(event, details)
    val donatedMinor = details.donationMinor?.takeIf { event.status == SaleStatus.APPROVED && it > 0 }
    return ended
        .copy(
            poiTransactionId = details.poiTransactionId,
            poiTimestamp = details.poiTimestamp,
            poiReconciliationId = details.poiReconciliationId ?: poiReconciliationId,
            shopperEmail = details.collectedEmail ?: shopperEmail,
            shopperRating = details.shopperRating ?: shopperRating,
            paidMinor = updatedPaidMinor,
            donationMinor = donatedMinor ?: donationMinor,
            offlineMode = details.offlineMode.name.takeIf { details.offlineMode != OfflineProcessingMode.ONLINE } ?: offlineMode,
            unconfirmedBatchCount = details.unconfirmedBatchCount ?: unconfirmedBatchCount,
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
            tipMinor = terminalTipMinor?.let { Math.addExact(tipMinor ?: 0, it) } ?: tipMinor,
        ).withTerminalComponents(this, event, details)
}

/** Terminal totals include the reported components; they are never added to AuthorizedAmount again. */
private fun SaleEntity.withTerminalComponents(
    previous: SaleEntity,
    event: SaleEvent.Settled,
    details: TransactionDetails,
): SaleEntity {
    if (event.status != SaleStatus.APPROVED || event.repeated) return this
    val collected = previous.terminalPaidMinor ?: if (previous.paidMinor > 0) previous.amountMinor else 0
    return copy(
        terminalPaidMinor = details.amountMinor?.let { Math.addExact(collected, it) },
        surchargeMinor = previous.accumulatedComponent(previous.surchargeMinor, details.surchargeMinor),
        cashBackMinor = previous.accumulatedComponent(previous.cashBackMinor, details.cashBackMinor),
    )
}

/** A missing component in any tender keeps the aggregate unknown, including after later reported zeroes. */
private fun SaleEntity.accumulatedComponent(
    previous: Long?,
    reported: Long?,
): Long? {
    val previousUnknown = (terminalPaidMinor != null || paidMinor > 0) && previous == null
    return if (reported == null || previousUnknown) null else Math.addExact(previous ?: 0, reported)
}

private fun SaleEntity.settledAmounts(
    event: SaleEvent.Settled,
    details: TransactionDetails,
): Pair<Long?, Long> {
    if (event.status != SaleStatus.APPROVED || manualCapture || event.repeated) return null to paidMinor
    if (requestedCashBackMinor != null) return null to totalMinor
    val terminalTipMinor = details.tipMinor?.takeIf { it > 0 }
    val reportedMinor = details.amountMinor
    val remaining = totalMinor - paidMinor
    val chosen = tenderRequestedMinor?.takeIf { it in 1..<remaining && !paymentLink && !savedCard }
    val baseTenderMinor =
        if (reportedMinor != null && (chosen != null || isPartialOrSplitTender(details.partial, reportedMinor, remaining, paidMinor))) {
            (reportedMinor - (terminalTipMinor ?: 0L) - (details.surchargeMinor ?: 0L) - (details.cashBackMinor ?: 0L))
                .coerceIn(0L, chosen ?: remaining)
        } else {
            remaining
        }
    return terminalTipMinor to Math.addExact(paidMinor, baseTenderMinor).coerceAtMost(totalMinor)
}

private fun isPartialOrSplitTender(
    partial: Boolean,
    reportedMinor: Long,
    remainingMinor: Long,
    paidMinor: Long,
): Boolean = partial || reportedMinor < remainingMinor || paidMinor > 0

/**
 * The approved terminal tender of a settled [SaleEvent], as the repository stores its row.
 *
 * @property requestedMinor What this tender was asked for: the balance still due in minor units.
 * @property authorisedMinor What the terminal approved for this tender in minor units; the requested amount when it
 *   reported none.
 */
data class ApprovedTender(
    val requestedMinor: Long,
    val authorisedMinor: Long,
)

/**
 * The approved terminal tender of a settled [SaleEvent], for the sale that totals [totalMinor] minor units and had
 * already collected [paidMinor]; null when the answer approves nothing.
 */
fun SaleEvent.Settled.approvedTender(
    totalMinor: Long,
    paidMinor: Long,
): ApprovedTender? {
    if (status != SaleStatus.APPROVED) return null
    val details = details ?: return null
    val requested = (totalMinor - paidMinor).coerceAtLeast(0)
    val authorised = details.amountMinor ?: requested
    return ApprovedTender(requested, authorised)
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
        is ModificationResult.Refused -> modificationFailed(result.said?.text, null)
        is ModificationResult.Failed -> modificationFailed(null, result.fault.storedReason())
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
        is ModificationResult.Received, is ModificationResult.Authorised -> {
            copy(
                captureStatus = CaptureStatus.REQUESTED,
            ).modificationFailed(null, null)
        }

        is ModificationResult.Refused -> {
            copy(captureStatus = CaptureStatus.FAILED).modificationFailed(result.said?.text, null)
        }

        is ModificationResult.Failed -> {
            captureFailed(result.fault)
        }
    }

private fun SaleEntity.captureFailed(fault: Fault): SaleEntity =
    copy(captureStatus = if (fault.mayHaveTakenEffect) CaptureStatus.UNKNOWN else CaptureStatus.FAILED)
        .modificationFailed(null, fault.storedReason())

/** Whether this answer leaves the modification's outcome unknown, so it is sent again with the same identity. */
private val ModificationResult.unconfirmed: Boolean get() = this is ModificationResult.Failed && fault.mayHaveTakenEffect

/** This fault as the stored reason: unconfirmed when the request may have taken effect, else not done. */
private fun Fault.storedReason(): StoredReason =
    if (mayHaveTakenEffect) StoredReason.Unconfirmed(Failure.Remote(this)) else StoredReason.NotDone(Failure.Remote(this))

private fun SaleEntity.interrupted(): SaleEntity {
    val payment = if (status == SaleStatus.PENDING) ended(SaleStatus.UNKNOWN, null, StoredReason.Interrupted) else this
    return if (captureStatus == CaptureStatus.PENDING) {
        payment.copy(captureStatus = CaptureStatus.UNKNOWN).modificationFailed(null, StoredReason.Interrupted)
    } else {
        payment
    }
}

/**
 * This sale once [refund], accepted by Adyen, is applied: a cancellation releases the hold; a full refund marks every
 * line refunded and the whole amount; an item or amount refund adds its quantities and amount, never beyond what was
 * sold or charged. Pure, so [SaleRepository.applyRefund] only stores it.
 */
internal fun SaleWithLines.refundedBy(refund: RefundEntity): SaleWithLines {
    if (refund.cancellation) return copy(sale = sale.copy(holdCancelled = true))
    val added = ReceiptLinesJson.decodeRefunded(refund.linesJson).groupBy { it.lineId }
    val refundedLines =
        lines.map { line ->
            val quantity = added[line.id].orEmpty().sumOf { it.quantity }
            line.copy(
                refundedQuantity =
                    if (refund.full &&
                        refund.tenderId == null
                    ) {
                        line.quantity
                    } else {
                        (line.refundedQuantity + quantity).coerceAtMost(line.quantity)
                    },
            )
        }
    val total = if (refund.full && refund.tenderId == null) sale.amountMinor else Math.addExact(sale.refundedMinor, refund.amountMinor)
    return copy(sale = sale.copy(refundedMinor = total.coerceAtMost(sale.amountMinor)), lines = refundedLines)
}

/** Cash approved but not handed over, or an approved cash request whose returned cash amount is unreported. */
val SaleEntity.cashAwaitingHandover: Boolean get() = cashHandover?.needsAttention == true

private fun SaleEntity.cashEvent(event: SaleEvent.CashEvent): SaleEntity =
    when (event) {
        is SaleEvent.CashTendered -> cashTendered(event)
        is SaleEvent.CashHandedOver -> if (cashHandover?.canRecord == true) copy(cashHandedOverAt = event.at) else this
    }
