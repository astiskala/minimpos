package io.minimpos.app.feature.sale

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.minimpos.app.R
import io.minimpos.app.data.db.AdjustmentStatus
import io.minimpos.app.data.db.CaptureStatus
import io.minimpos.app.data.db.SaleEntity
import io.minimpos.app.data.db.SaleKind
import io.minimpos.app.data.db.SaleStatus
import io.minimpos.app.refund.PaymentHold
import io.minimpos.app.ui.components.OutcomeNote
import io.minimpos.app.ui.components.SecondaryButton
import io.minimpos.app.ui.components.StatusKind
import io.minimpos.core.money.MoneyFormatter
import io.minimpos.terminal.client.RetryAdvice

/** Opens the screen to enter the tip written on the receipt. */
@Composable
fun EnterTipButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = SecondaryButton(
    stringResource(R.string.result_enter_tip),
    onClick,
    icon = Icons.Default.EditNote,
    modifier = modifier.testTag("enterTip"),
)

/** The operator-facing explanation of [advice]. */
@Composable
@ReadOnlyComposable
fun adviceText(advice: RetryAdvice): String =
    stringResource(
        when (advice) {
            RetryAdvice.RETRY -> R.string.advice_retry
            RetryAdvice.WAIT_AND_RETRY -> R.string.advice_wait_and_retry
            RetryAdvice.DIFFERENT_PAYMENT_METHOD -> R.string.advice_different_payment_method
            RetryAdvice.TERMINAL_BUSY -> R.string.advice_terminal_busy
            RetryAdvice.CHECK_SETUP -> R.string.advice_check_setup
            RetryAdvice.DO_NOT_RETRY -> R.string.advice_do_not_retry
        },
    )

/**
 * How a sale in [status] is shown: approved is a success, declined and failed are errors, and cancelled or unsettled
 * sales are warnings.
 */
fun statusKind(status: SaleStatus): StatusKind =
    when (status) {
        SaleStatus.APPROVED -> StatusKind.SUCCESS
        SaleStatus.CANCELLED, SaleStatus.UNKNOWN, SaleStatus.PENDING -> StatusKind.WARNING
        SaleStatus.DECLINED, SaleStatus.FAILED -> StatusKind.ERROR
    }

/**
 * How [sale] is shown: as its status says ([statusKind]), except that a hold whose cancellation was accepted, and a
 * capture that is still to be made in the Customer Area or whose outcome is unknown, are warnings, and a capture Adyen
 * did not take is an error.
 */
fun statusKind(sale: SaleEntity): StatusKind =
    when {
        sale.cancelledHold -> StatusKind.WARNING
        sale.status != SaleStatus.APPROVED -> statusKind(sale.status)
        sale.captureStatus == CaptureStatus.FAILED -> StatusKind.ERROR
        sale.captureStatus == CaptureStatus.REQUESTED || sale.captureStatus == null -> StatusKind.SUCCESS
        else -> StatusKind.WARNING
    }

/**
 * The heading for [sale]: its status ([statusTitle]), except for payments that only held their amount: "Awaiting tip"
 * or "Pre-authorized" while held, then where their capture stands, or "Cancellation requested" once their cancellation
 * was accepted.
 */
@Composable
@ReadOnlyComposable
fun statusTitle(sale: SaleEntity): String =
    when {
        sale.cancelledHold -> stringResource(R.string.status_cancellation_requested)
        sale.status != SaleStatus.APPROVED -> statusTitle(sale.status)
        PaymentHold.awaitingTip(sale) -> stringResource(R.string.status_awaiting_tip)
        sale.captureStatus != null -> captureTitle(sale.captureStatus)
        sale.kind == SaleKind.PRE_AUTHORISATION -> stringResource(R.string.status_pre_authorised)
        else -> statusTitle(sale.status)
    }

@Composable
@ReadOnlyComposable
private fun captureTitle(status: CaptureStatus): String =
    stringResource(
        when (status) {
            CaptureStatus.REQUESTED -> R.string.status_capture_requested
            CaptureStatus.MANUAL -> R.string.status_capture_manual
            CaptureStatus.FAILED -> R.string.status_capture_failed
            CaptureStatus.PENDING, CaptureStatus.UNKNOWN -> R.string.status_capture_unknown
        },
    )

/** A payment that only held its amount (pre-authorisation or tip on the receipt) and whose cancellation was accepted. */
private val SaleEntity.cancelledHold: Boolean
    get() = (kind == SaleKind.PRE_AUTHORISATION || tipOnReceipt) && status == SaleStatus.APPROVED && refundedMinor > 0 && !captured

/**
 * Notes under the outcome of an approved [sale] that held its amount: the tip and bill, that the tip is awaited (on
 * the result just after the payment [afterPayment], with what to do), what an adjusted pre-authorisation holds now,
 * and where the capture stands.
 */
@Composable
internal fun ColumnScope.HoldNotes(
    sale: SaleEntity,
    money: MoneyFormatter,
    afterPayment: Boolean = false,
) {
    if (sale.status != SaleStatus.APPROVED || sale.cancelledHold) return
    sale.tipMinor?.let { OutcomeNote(stringResource(R.string.detail_tip, money.format(sale.totalMinor), money.format(it))) }
    if (PaymentHold.awaitingTip(sale)) {
        OutcomeNote(stringResource(if (afterPayment) R.string.result_tip_note else R.string.detail_tip_awaiting))
    }
    if (sale.kind == SaleKind.PRE_AUTHORISATION && sale.authorisedMinor != null && !sale.captured) {
        val held = money.format(sale.heldMinor)
        OutcomeNote(
            if (sale.adjustment == AdjustmentStatus.REQUESTED) {
                stringResource(R.string.detail_adjustment_requested, held)
            } else {
                stringResource(R.string.detail_held_now, held)
            },
        )
    }
    val captured = money.format(sale.capturedMinor ?: return)
    val note =
        when (sale.captureStatus ?: return) {
            CaptureStatus.REQUESTED -> R.string.detail_capture_requested
            CaptureStatus.MANUAL -> R.string.detail_capture_manual
            CaptureStatus.FAILED -> R.string.detail_capture_failed
            CaptureStatus.PENDING, CaptureStatus.UNKNOWN -> R.string.detail_capture_unknown
        }
    OutcomeNote(stringResource(note, captured), Modifier.testTag("captureNote"))
}

/** The heading for a sale in [status], such as "Approved". */
@Composable
@ReadOnlyComposable
fun statusTitle(status: SaleStatus): String =
    stringResource(
        when (status) {
            SaleStatus.APPROVED -> R.string.status_approved
            SaleStatus.DECLINED -> R.string.status_declined
            SaleStatus.CANCELLED -> R.string.status_cancelled
            SaleStatus.FAILED -> R.string.status_failed
            SaleStatus.UNKNOWN -> R.string.status_unknown
            SaleStatus.PENDING -> R.string.status_pending
        },
    )
