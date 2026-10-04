package io.github.astiskala.minimpos.terminal.simulator

import io.github.astiskala.minimpos.terminal.checkout.ModificationAmount
import io.github.astiskala.minimpos.terminal.checkout.ModificationResult
import io.github.astiskala.minimpos.terminal.checkout.PaymentModifications
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

    /** Why [psp] cannot be captured; null when it can (it is held, already captured, or unknown). */
    fun captureRefusal(psp: String): String? =
        when (payments[psp]) {
            State.CHARGED -> "The payment was captured when it was taken (simulated)"
            State.CANCELLED -> "The payment was cancelled (simulated)"
            State.HELD, State.CAPTURED, null -> null
        }

    /** Why the amount of [psp] cannot be adjusted; null when it can (it is held, or unknown). */
    fun adjustmentRefusal(psp: String): String? =
        when (payments[psp]) {
            State.CAPTURED -> "The payment was already captured (simulated)"
            State.CHARGED, State.CANCELLED -> captureRefusal(psp)
            State.HELD, null -> null
        }

    /** Records that [psp] was captured, when it was held. */
    fun captured(psp: String) {
        payments.replace(psp, State.HELD, State.CAPTURED)
    }

    private enum class State { CHARGED, HELD, CAPTURED, CANCELLED }
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
        ledger.captureRefusal(paymentPspReference)?.let { return ModificationResult.NotProcessed(it) }
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
            refusal != null -> ModificationResult.NotProcessed(refusal)
            adjustAuthorisationData != null -> ModificationResult.Authorised(pspReference(), blob(random))
            else -> ModificationResult.Received(pspReference())
        }
    }

    override suspend fun verify(): String? = null

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
