package app.minimpos.app.feature

import android.content.Context
import app.minimpos.app.R
import app.minimpos.app.data.db.RefundCancelStatus
import app.minimpos.app.data.db.RefundEntity
import app.minimpos.app.feature.refund.unreferencedReasonLabel
import app.minimpos.app.history.approvedSynchronously

/**
 * Localized lines of an unreferenced refund, also used at receipt delivery: that it names no original payment, why it
 * was made, a synchronous acquirer answer verbatim and what Adyen was last asked about cancelling it. Empty for
 * referenced refunds.
 */
internal fun Context.unreferencedRefundFacts(refund: RefundEntity): List<String> {
    if (!refund.unreferenced) return emptyList()
    return buildList {
        add(getString(R.string.unreferenced_receipt_note))
        refund.unreferencedReason?.let { add(getString(R.string.unreferenced_reason_line, getString(unreferencedReasonLabel(it)))) }
        refund.acquirerResponseCode?.let { add(getString(R.string.unreferenced_acquirer) + ": " + it) }
        if (refund.approvedSynchronously) add(getString(R.string.unreferenced_approved_note))
        when (refund.cancelStatus) {
            RefundCancelStatus.REQUESTED -> add(getString(R.string.refund_cancel_requested))
            RefundCancelStatus.SENDING, RefundCancelStatus.UNKNOWN -> add(getString(R.string.refund_cancel_unknown))
            RefundCancelStatus.FAILED, null -> Unit
        }
    }
}
