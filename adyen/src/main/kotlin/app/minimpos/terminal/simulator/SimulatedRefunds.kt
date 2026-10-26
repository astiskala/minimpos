package app.minimpos.terminal.simulator

import app.minimpos.terminal.client.NexoVocabulary
import com.adyen.model.nexo.PaymentRequest
import com.adyen.model.nexo.PaymentType
import java.math.BigDecimal
import java.math.RoundingMode

/** Whether [this] is an unreferenced card refund (`PaymentType=Refund` without a gift card). */
internal val PaymentRequest.unreferencedRefund: Boolean get() = paymentData?.paymentType == PaymentType.REFUND

/**
 * What an approved simulated payment reports beside its base: a partial base, a tip and the surcharge. An unreferenced
 * refund returns exactly what it asks for, with no partial approval, tip or surcharge.
 */
internal fun simulatedAmounts(
    request: PaymentRequest,
    approved: Boolean,
    settings: SimulatorConfig,
): Triple<BigDecimal?, BigDecimal?, BigDecimal> {
    val requested = request.paymentTransaction.amountsReq.requestedAmount
    if (request.unreferencedRefund) return Triple(null, null, BigDecimal.ZERO)
    val partialBase = if (approved) simulatedPartialAmount(request.saleData, requested, settings) else null
    val tip = if (approved && partialBase == null) simulatedTipAmount(request.saleData, requested) else null
    return Triple(partialBase, tip, settings.surchargeAmount.max(BigDecimal.ZERO).setScale(requested.scale(), RoundingMode.HALF_UP))
}

/** What an approved unreferenced refund adds to its `AdditionalResponse`, as Adyen documents it; empty otherwise. */
internal fun refundAdditional(
    payment: SimulatedPayment,
    amount: BigDecimal,
    settings: SimulatorConfig,
): Map<String, String> =
    if (!payment.refund || !payment.approved) {
        emptyMap()
    } else {
        buildMap {
            put(NexoVocabulary.TRANSACTION_TYPE, "REFUND")
            put(NexoVocabulary.POS_AUTH_AMOUNT_VALUE, amount.movePointRight(amount.scale()).toBigInteger().toString())
            if (settings.refundsProcessedSynchronously) put(NexoVocabulary.ACQUIRER_RESPONSE_CODE, NexoVocabulary.APPROVED)
        }
    }
