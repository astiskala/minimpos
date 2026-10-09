package app.minimpos.app.feature.sale

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import app.minimpos.app.R
import app.minimpos.app.data.db.AdjustmentStatus
import app.minimpos.app.data.db.SaleEntity
import app.minimpos.app.data.db.SaleKind
import app.minimpos.app.data.db.SaleStatus
import app.minimpos.app.refund.PaymentStanding
import app.minimpos.app.refund.standing
import app.minimpos.app.ui.components.OutcomeNote
import app.minimpos.app.ui.components.SecondaryButton
import app.minimpos.app.ui.components.StatusKind
import app.minimpos.core.money.MoneyFormatter
import app.minimpos.terminal.client.RetryAdvice

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
 * How a sale in [status] is shown: approved is a success, declined and failed are errors, and cancelled, unsettled,
 * unpaid and expired payment links are warnings.
 */
fun statusKind(status: SaleStatus): StatusKind =
    when (status) {
        SaleStatus.APPROVED -> {
            StatusKind.SUCCESS
        }

        SaleStatus.CANCELLED, SaleStatus.UNKNOWN, SaleStatus.PENDING, SaleStatus.AWAITING_PAYMENT, SaleStatus.EXPIRED -> {
            StatusKind.WARNING
        }

        SaleStatus.DECLINED, SaleStatus.FAILED -> {
            StatusKind.ERROR
        }
    }

/**
 * How [sale] is shown: as its status says ([statusKind]), except that a hold whose cancellation was accepted, and a
 * capture that is being sent or whose outcome is unknown, are warnings, and a capture Adyen did not take is an error.
 */
fun statusKind(sale: SaleEntity): StatusKind =
    when (sale.standing) {
        PaymentStanding.NOT_APPROVED -> {
            statusKind(sale.status)
        }

        PaymentStanding.CHARGED, PaymentStanding.AWAITING_TIP, PaymentStanding.HELD, PaymentStanding.CAPTURE_REQUESTED -> {
            StatusKind.SUCCESS
        }

        PaymentStanding.CAPTURE_FAILED -> {
            StatusKind.ERROR
        }

        PaymentStanding.CAPTURE_SENDING,
        PaymentStanding.CAPTURE_UNKNOWN,
        PaymentStanding.HOLD_CANCELLED,
        -> {
            StatusKind.WARNING
        }
    }

/**
 * The heading for [sale]: its status ([statusTitle]), except for payments that only held their amount: "Awaiting tip"
 * or "Pre-authorized" while held, then where their capture stands, or "Cancellation requested" once their cancellation
 * was accepted.
 */
@Composable
@ReadOnlyComposable
fun statusTitle(sale: SaleEntity): String =
    when (val standing = sale.standing) {
        PaymentStanding.NOT_APPROVED, PaymentStanding.CHARGED -> {
            statusTitle(sale.status)
        }

        PaymentStanding.HOLD_CANCELLED -> {
            stringResource(R.string.status_cancellation_requested)
        }

        PaymentStanding.AWAITING_TIP -> {
            stringResource(R.string.status_awaiting_tip)
        }

        PaymentStanding.HELD -> {
            if (sale.kind == SaleKind.PRE_AUTHORISATION) stringResource(R.string.status_pre_authorised) else statusTitle(sale.status)
        }

        PaymentStanding.CAPTURE_REQUESTED,
        PaymentStanding.CAPTURE_FAILED,
        PaymentStanding.CAPTURE_SENDING,
        PaymentStanding.CAPTURE_UNKNOWN,
        -> {
            stringResource(checkNotNull(captureStrings(standing)).first)
        }
    }

/** The heading and the note (formatted with the amount) of where a capture stands; null when none was attempted. */
private fun captureStrings(standing: PaymentStanding): Pair<Int, Int>? =
    when (standing) {
        PaymentStanding.CAPTURE_REQUESTED -> {
            R.string.status_capture_requested to R.string.detail_capture_requested
        }

        PaymentStanding.CAPTURE_FAILED -> {
            R.string.status_capture_failed to R.string.detail_capture_failed
        }

        PaymentStanding.CAPTURE_SENDING, PaymentStanding.CAPTURE_UNKNOWN -> {
            R.string.status_capture_unknown to
                R.string.detail_capture_unknown
        }

        PaymentStanding.NOT_APPROVED,
        PaymentStanding.CHARGED,
        PaymentStanding.AWAITING_TIP,
        PaymentStanding.HELD,
        PaymentStanding.HOLD_CANCELLED,
        -> {
            null
        }
    }

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
    val standing = sale.standing
    if (standing == PaymentStanding.NOT_APPROVED || standing == PaymentStanding.HOLD_CANCELLED) return
    sale.tipMinor?.let { OutcomeNote(stringResource(R.string.detail_tip, money.format(sale.totalMinor), money.format(it))) }
    if (standing == PaymentStanding.AWAITING_TIP) {
        OutcomeNote(stringResource(if (afterPayment) R.string.result_tip_note else R.string.detail_tip_awaiting))
    }
    if (sale.kind == SaleKind.PRE_AUTHORISATION && sale.authorisedMinor != null && !standing.captured) {
        val held = money.format(sale.heldMinor)
        OutcomeNote(
            if (sale.adjustment == AdjustmentStatus.REQUESTED) {
                stringResource(R.string.detail_adjustment_requested, held)
            } else {
                stringResource(R.string.detail_held_now, held)
            },
        )
    }
    val note = captureStrings(standing)?.second ?: return
    OutcomeNote(stringResource(note, money.format(sale.capturedMinor ?: return)), Modifier.testTag("captureNote"))
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
            SaleStatus.AWAITING_PAYMENT -> R.string.status_awaiting_payment
            SaleStatus.EXPIRED -> R.string.status_link_expired
        },
    )
