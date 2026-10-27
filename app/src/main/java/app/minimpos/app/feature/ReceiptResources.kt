package app.minimpos.app.feature

import android.content.Context
import app.minimpos.app.R
import app.minimpos.app.data.db.RefundStatus
import app.minimpos.app.feature.giftcard.giftCardStageLabel
import app.minimpos.app.history.PaymentStanding
import app.minimpos.app.locale
import app.minimpos.app.receipt.ReceiptSampleTexts
import app.minimpos.app.receipt.ReceiptTexts
import app.minimpos.app.receipt.ReportText
import app.minimpos.core.receipt.ReceiptLabels

/**
 * Receipt, daily-report and stored-value texts in [localized]'s language, with [sample] for the settings preview: what
 * receipts read at delivery, so saved merchant text and Adyen receipt fields never change with it.
 */
internal fun localizedReceiptTexts(
    localized: Context,
    sample: ReceiptSampleTexts,
): ReceiptTexts =
    ReceiptTexts(
        labels = receiptLabels(localized),
        locale = localized.locale,
        sample = sample,
        simulationNote = localized.getString(R.string.link_simulation_note),
        demoLink = localized.getString(R.string.link_demo_label),
        standing = { standing -> localized.standingNote(standing) },
        refund = { status -> localized.getString(refundNote(status)) },
        report = { label -> localized.getString(reportLabel(label)) },
        giftCardStage = { localized.getString(giftCardStageLabel(it)) },
        giftCardTitle = localized.getString(R.string.receipt_gift_card_title),
        giftCardAttention = localized.getString(R.string.gift_card_needs_attention),
        cashoutTitle = localized.getString(R.string.cashout_title),
        cashback = { localized.cashbackFacts(it) },
        mexicanPlan = { localized.mexicanPlan(it) },
        mealVoucher = { localized.mealVoucherFacts(it) },
        unreferenced = { localized.unreferencedRefundFacts(it) },
    )

private fun receiptLabels(localizedContext: Context) =
    ReceiptLabels(
        date = localizedContext.getString(R.string.receipt_date),
        reference = localizedContext.getString(R.string.receipt_reference),
        customer = localizedContext.getString(R.string.receipt_customer),
        subtotal = localizedContext.getString(R.string.receipt_subtotal),
        tax = localizedContext.getString(R.string.receipt_tax),
        total = localizedContext.getString(R.string.receipt_total),
        includesTaxFormat = localizedContext.getString(R.string.receipt_includes_tax),
        quantityFormat = localizedContext.getString(R.string.receipt_quantity),
        refundQrCaption = localizedContext.getString(R.string.receipt_refund_qr_caption),
        merchantCopy = localizedContext.getString(R.string.receipt_merchant_copy),
        refundTitle = localizedContext.getString(R.string.receipt_refund_title),
        originalReference = localizedContext.getString(R.string.receipt_original_sale),
        refundTotal = localizedContext.getString(R.string.receipt_refund_total),
        partialRefund = localizedContext.getString(R.string.receipt_partial_refund),
        fullRefund = localizedContext.getString(R.string.receipt_full_refund),
        cardSaved = localizedContext.getString(R.string.receipt_card_saved),
        notCompleted = localizedContext.getString(R.string.receipt_not_completed),
        preAuthTitle = localizedContext.getString(R.string.receipt_pre_auth_title),
        amountHeld = localizedContext.getString(R.string.receipt_amount_held),
        preAuthNote = localizedContext.getString(R.string.receipt_pre_auth_note),
        cancellationTitle = localizedContext.getString(R.string.receipt_cancellation_title),
        cancelledReference = localizedContext.getString(R.string.receipt_cancelled_reference),
        cancellationNote = localizedContext.getString(R.string.receipt_cancellation_note),
        released = localizedContext.getString(R.string.receipt_released),
        amount = localizedContext.getString(R.string.receipt_amount),
        tip = localizedContext.getString(R.string.receipt_tip),
        surcharge = localizedContext.getString(R.string.surcharge_label),
        unreported = localizedContext.getString(R.string.surcharge_unknown),
        charged = localizedContext.getString(R.string.surcharge_charged),
        purchase = localizedContext.getString(R.string.surcharge_purchase),
        tenderCard = localizedContext.getString(R.string.split_tender_card),
        tenderCash = localizedContext.getString(R.string.split_tender_cash),
        cashReceived = localizedContext.getString(R.string.split_cash_received),
        change = localizedContext.getString(R.string.split_change),
        balanceDue = localizedContext.getString(R.string.receipt_balance_due),
        cashReturned = localizedContext.getString(R.string.split_cash_returned),
        tenderGiftCard = localizedContext.getString(R.string.split_tender_gift_card),
        tenderMealVoucher = localizedContext.getString(R.string.split_tender_meal_voucher),
        giftCard = localizedContext.getString(R.string.receipt_gift_card),
        balance = localizedContext.getString(R.string.receipt_gift_card_balance),
        tipTotal = localizedContext.getString(R.string.receipt_tip_total),
        signature = localizedContext.getString(R.string.receipt_signature),
        heldNow = localizedContext.getString(R.string.receipt_held_now),
        captured = localizedContext.getString(R.string.receipt_captured),
        taxableGrossFormat = localizedContext.getString(R.string.receipt_taxable_gross_format),
        taxableNetFormat = localizedContext.getString(R.string.receipt_taxable_net_format),
        amountDue = localizedContext.getString(R.string.receipt_amount_due),
        unpaid = localizedContext.getString(R.string.receipt_unpaid),
        payLinkCaption = localizedContext.getString(R.string.receipt_pay_link_caption),
        payLinkIntro = localizedContext.getString(R.string.receipt_pay_link_intro),
        payNow = localizedContext.getString(R.string.receipt_pay_now),
        linkValidFormat = localizedContext.getString(R.string.receipt_link_valid),
        paidOnline = localizedContext.getString(R.string.receipt_paid_online),
    )

/** The payment-standing note a receipt prints; null when none, including for a payment that was not approved. */
private fun Context.standingNote(standing: PaymentStanding): String? =
    when (standing) {
        // The receipt builder already warns about payments that were not approved.
        PaymentStanding.CHARGED, PaymentStanding.NOT_APPROVED -> null

        PaymentStanding.AWAITING_TIP -> getString(R.string.detail_tip_awaiting)

        PaymentStanding.HELD -> getString(R.string.receipt_pre_auth_note)

        PaymentStanding.CAPTURE_REQUESTED -> getString(R.string.status_capture_requested)

        PaymentStanding.CAPTURE_FAILED -> getString(R.string.status_capture_failed)

        PaymentStanding.CAPTURE_SENDING, PaymentStanding.CAPTURE_UNKNOWN -> getString(R.string.status_capture_unknown)

        PaymentStanding.HOLD_CANCELLED -> getString(R.string.status_cancellation_requested)
    }

/** The refund-request outcome a refund receipt prints. */
private fun refundNote(status: RefundStatus): Int =
    when (status) {
        RefundStatus.REQUESTED -> R.string.refund_status_requested
        RefundStatus.PENDING -> R.string.status_pending
        RefundStatus.UNKNOWN -> R.string.status_unknown
        RefundStatus.FAILED -> R.string.status_failed
    }

/** The daily-report label of [label]. */
private fun reportLabel(label: ReportText): Int =
    when (label) {
        ReportText.TITLE -> R.string.report_title
        ReportText.GENERATED -> R.string.report_generated
        ReportText.SALES -> R.string.report_sales
        ReportText.TIPS -> R.string.report_tips
        ReportText.SURCHARGES -> R.string.surcharge_label
        ReportText.PURCHASE -> R.string.surcharge_purchase_known
        ReportText.UNKNOWN_SURCHARGES -> R.string.surcharge_unknown_count
        ReportText.REFUNDS -> R.string.report_refunds
        ReportText.CASHBACK -> R.string.cashback_approved
        ReportText.CASH_HANDED_OVER -> R.string.cashback_handed_over
        ReportText.CASH_SALES -> R.string.report_cash_sales
        ReportText.CASH_REFUNDS -> R.string.report_cash_refunds
        ReportText.UNREFERENCED_REFUNDS -> R.string.report_unreferenced_refunds
        ReportText.REFUND_SURCHARGES -> R.string.surcharge_return_fee
        ReportText.UNKNOWN_REFUND_SURCHARGES -> R.string.surcharge_unknown_refund_count
        ReportText.NET -> R.string.report_net
        ReportText.HOLDS -> R.string.report_holds
        ReportText.UNRESOLVED -> R.string.report_unresolved
        ReportText.ONLINE -> R.string.report_online
        ReportText.UNDATED -> R.string.report_undated
        ReportText.UNDATED_NOTE -> R.string.report_undated_note
        ReportText.NOTE -> R.string.report_note
        ReportText.ISSUE_NOTE -> R.string.report_issue_note
        ReportText.DEMO -> R.string.report_demo
        ReportText.UNKNOWN -> R.string.report_unknown
        ReportText.GIFT_VALUE_ADDED -> R.string.report_gift_value_added
        ReportText.GIFT_VALUE_REMOVED -> R.string.report_gift_value_removed
        ReportText.GIFT_FUNDING_CARD -> R.string.report_gift_funding_card
        ReportText.GIFT_FUNDING_EXTERNAL -> R.string.report_gift_funding_external
        ReportText.GIFT_FUNDING_RETURNED -> R.string.report_gift_funding_returned
        ReportText.GIFT_CASH_HANDED_OVER -> R.string.report_gift_cash_handed_over
    }
