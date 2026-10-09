package app.minimpos.terminal.simulator

import app.minimpos.terminal.checkout.ModificationAmount
import app.minimpos.terminal.checkout.ModificationResult
import app.minimpos.terminal.checkout.PaymentModifications
import app.minimpos.terminal.transport.ExternalText
import app.minimpos.terminal.transport.Fault
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * What the simulated terminal and the simulated Checkout API both know about the payments the simulator took, by PSP
 * reference, so the two answer consistently: a capture of a payment that was captured straight away is refused, a
 * cancelled pre-authorisation can no longer be captured, and a full reversal of an uncaptured one cancels it. Payments
 * it never saw (taken before the app restarted) are not judged. Thread-safe.
 */
internal class SimulatedLedger {
    private val payments = ConcurrentHashMap<String, State>()

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
    /** One that knows no payments, so it accepts every capture and adjustment; [random] makes PSP references and blobs. */
    constructor(random: Random = Random.Default) : this(random, SimulatedLedger())

    override suspend fun capture(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult {
        ledger.captureRefusal(paymentPspReference)?.let { return ModificationResult.Failed(it) }
        ledger.captured(paymentPspReference)
        return ModificationResult.Received(pspReference())
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
            adjustAuthorisationData != null -> ModificationResult.Authorised(pspReference(), blob(random))
            else -> ModificationResult.Received(pspReference())
        }
    }

    override suspend fun verify(): Fault? = null

    private fun pspReference() = (1..PSP_LENGTH).map { UPPER[random.nextInt(UPPER.length)] }.joinToString("")

    /** Values shared with the simulated terminal. */
    companion object {
        private const val PSP_LENGTH = 16
        private const val BLOB_BYTES = 24
        private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** A random stand-in for Adyen's `adjustAuthorisationData` blob. */
        internal fun blob(random: Random): String = "BQABAQ" + Base64.getEncoder().encodeToString(random.nextBytes(BLOB_BYTES))
    }
}
