package io.minimpos.terminal.simulator

import io.minimpos.terminal.checkout.ModificationAmount
import io.minimpos.terminal.checkout.ModificationResult
import io.minimpos.terminal.checkout.PaymentModifications
import java.util.Base64
import kotlin.random.Random

/**
 * Adyen's Checkout API as the [TerminalSimulator]'s payments need it, without a network or an API key: every capture
 * and adjustment is accepted. An adjustment sent with an `adjustAuthorisationData` blob (which the simulator returns for
 * approved pre-authorisations) is authorised synchronously with a new blob; one without is received asynchronously.
 */
class SimulatedModifications(
    /** Makes the PSP references and blobs. */
    private val random: Random = Random.Default,
) : PaymentModifications {
    override suspend fun capture(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        idempotencyKey: String,
    ): ModificationResult = ModificationResult.Received(pspReference())

    override suspend fun updateAmount(
        paymentPspReference: String,
        amount: ModificationAmount,
        reference: String,
        adjustAuthorisationData: String?,
        idempotencyKey: String,
    ): ModificationResult =
        if (adjustAuthorisationData != null) {
            ModificationResult.Authorised(pspReference(), blob(random))
        } else {
            ModificationResult.Received(pspReference())
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
