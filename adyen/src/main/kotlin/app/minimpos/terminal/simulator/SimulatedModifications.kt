package app.minimpos.terminal.simulator

import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.encoding.Base64
import kotlin.random.Random

/**
 * What the simulated terminal and the simulated Checkout API both know about the payments the simulator took, by PSP
 * reference, so the two answer consistently: a capture of a payment that was captured straight away is refused, a
 * cancelled pre-authorisation can no longer be captured, and a full reversal of an uncaptured one cancels it. Payments
 * it never saw (taken before the app restarted) are not judged. Thread-safe.
 */
internal class SimulatedLedger {
    private val payments = ConcurrentHashMap<String, State>()

    /** Releases all modification state after confirmed complete history deletion. */
    fun clear() = payments.clear()

    /** Releases modification state only for payments whose durable history has been deleted. */
    fun forget(psps: Set<String>) {
        psps.forEach(payments::remove)
    }

    /** Records the approved payment [psp]; [manualCapture] for a pre-authorisation, which waits for its capture. */
    fun approved(
        psp: String,
        manualCapture: Boolean,
    ) {
        payments[psp] = if (manualCapture) State.HELD else State.CHARGED
    }

    /** Releases the hold of [psp] when it is still uncaptured; returns whether it did (a cancellation, not a refund). */
    fun cancel(psp: String): Boolean = payments.replace(psp, State.HELD, State.CANCELLED)

    /** How Adyen would reject a capture of [psp]; null when it can be captured (it is held, already captured, or unknown). */
    fun captureRefusal(psp: String): Fault? =
        when (payments[psp]) {
            State.CHARGED -> rejected("The payment was captured when it was taken (simulated)")
            State.CANCELLED -> rejected("The payment was cancelled (simulated)")
            State.HELD, State.CAPTURED, null -> null
        }

    /** How Adyen would reject an adjustment of [psp]; null when it can be adjusted (it is held, or unknown). */
    fun adjustmentRefusal(psp: String): Fault? =
        when (payments[psp]) {
            State.CAPTURED -> rejected("The payment was already captured (simulated)")
            State.CHARGED, State.CANCELLED -> captureRefusal(psp)
            State.HELD, null -> null
        }

    /** How Adyen would reject a refund of [psp]; null when it can be refunded (it charged, was captured, or is unknown). */
    fun refundRefusal(psp: String): Fault? =
        when (payments[psp]) {
            State.HELD -> rejected("The payment was not captured (simulated)")
            State.CANCELLED -> rejected("The payment was cancelled (simulated)")
            State.CHARGED, State.CAPTURED, null -> null
        }

    /** Adyen's rejection of a modification the payment no longer allows, with its simulated words. */
    private fun rejected(said: String) = Fault.AdyenRejected(HTTP_UNPROCESSABLE, null, ExternalText(said))

    /** Records that [psp] was captured, when it was held. */
    fun captured(psp: String) {
        payments.replace(psp, State.HELD, State.CAPTURED)
    }

    private enum class State { CHARGED, HELD, CAPTURED, CANCELLED }

    private companion object {
        /** How Adyen rejects a modification it cannot apply to the payment. */
        const val HTTP_UNPROCESSABLE = 422
    }
}

/**
 * Adyen's Checkout API as the [TerminalSimulator]'s payments need it, without a network or an API key. Captures and
 * adjustments of payments the simulator took follow what happened to them (see [TerminalSimulator.modifications]);
 * others are accepted. An adjustment sent with an `adjustAuthorisationData` blob (which the simulator returns for
 * approved pre-authorisations) is authorised synchronously with a new blob; one without is received asynchronously.
 */
class SimulatedModifications internal constructor(
    private val random: Random,
    private val ledger: SimulatedLedger,
) : PaymentModifications {
    override suspend fun capture(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult {
        ledger.captureRefusal(paymentPspReference)?.let { return ModificationResult.Failed(it) }
        ledger.captured(paymentPspReference)
        return ModificationResult.Received(pspReference(random))
    }

    override suspend fun updateAmount(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        adjustAuthorisationData: String?,
        idempotencyKey: String,
    ): ModificationResult {
        val refusal = ledger.adjustmentRefusal(paymentPspReference)
        return when {
            refusal != null -> ModificationResult.Failed(refusal)
            adjustAuthorisationData != null -> ModificationResult.Authorised(pspReference(random), blob(random))
            else -> ModificationResult.Received(pspReference(random))
        }
    }

    /** Accepts the refund of a payment that charged; one that only held its amount is refused, as Adyen refuses it. */
    override suspend fun refund(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult =
        ledger.refundRefusal(paymentPspReference)?.let { ModificationResult.Failed(it) }
            ?: ModificationResult.Received(pspReference(random))

    override suspend fun verify(): Fault? = null

    /** Values shared with the simulated terminal and saved cards. */
    companion object {
        private const val BLOB_BYTES = 24
        private const val PSP_LENGTH = 16
        private const val PSP_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** A random stand-in for Adyen's `adjustAuthorisationData` blob. */
        internal fun blob(random: Random): String = "BQABAQ" + Base64.encode(random.nextBytes(BLOB_BYTES))

        /** A random stand-in for an Adyen PSP reference: 16 upper-case letters and digits. */
        internal fun pspReference(random: Random): String =
            (1..PSP_LENGTH).map { PSP_ALPHABET[random.nextInt(PSP_ALPHABET.length)] }.joinToString("")
    }
}
